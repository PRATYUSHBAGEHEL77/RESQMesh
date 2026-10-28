package com.resqmesh.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val SERVICE_ID = "com.resqmesh.app.SOS"
private const val MAX_HOPS = 5

data class PendingPeer(val endpointId: String, val name: String, val digits: String)

data class TransferTrack(val messageId: String, val kind: String, val remainingEndpoints: MutableSet<String>)

data class UiEvent(
    val id: String,
    val kind: String,
    val text: String,
    val detail: String,
    val latitude: Double? = null,
    val longitude: Double? = null
)

class MainActivity : ComponentActivity() {
    private val client by lazy { Nearby.getConnectionsClient(this) }

    private val connected = ConcurrentHashMap<String, String>() // endpointId -> endpointName
    private val seenIds = ConcurrentHashMap.newKeySet<String>()
    // Nearby transport payload ID -> (application message ID, kind). Transport completion
    // is distinct from recipient processing and app-level acknowledgement.
    private val outgoingPayloads = ConcurrentHashMap<Long, TransferTrack>()
    private val eventState = mutableStateListOf<UiEvent>()
    private val pendingPeerState = mutableStateListOf<PendingPeer>()
    private val prefs: SharedPreferences by lazy { getSharedPreferences("resqmesh_durable", MODE_PRIVATE) }
    private val peerState = mutableStateListOf<String>()
    private var localName by mutableStateOf("RESQ-Phone")
    private var status by mutableStateOf("Offline. Start mesh on each test phone.")
    private var running by mutableStateOf(false)
    private var gpsStatus by mutableStateOf("GPS not requested yet.")
    private var pendingSosText by mutableStateOf("")

    private val gpsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fineGranted || coarseGranted) {
                acquireGpsAndSend(pendingSosText)
            } else {
                gpsStatus = "Location permission denied. Sending SOS without coordinates so the message is not blocked."
                addEvent("SYSTEM", "Location permission denied", "SOS will be sent without coordinates.")
                sendSos(pendingSosText, null)
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                status = "Required permissions denied: ${denied.joinToString()}"
            } else {
                startMeshInternal()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Give each installation a distinct default name so two untouched phones can discover one another.
        if (localName == "RESQ-Phone") {
            val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString()
            val model = Build.MODEL.orEmpty().replace(Regex("[^A-Za-z0-9]"), "").take(8).ifBlank { "Phone" }
            localName = "RESQ-$model-${androidId.takeLast(4)}"
        }
        loadSavedEvents()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ResqScreen(
                        name = localName,
                        onNameChange = { localName = it.take(24) },
                        status = status,
                        gpsStatus = gpsStatus,
                        running = running,
                        peers = peerState.toList(),
                        events = eventState.toList(),
                        pendingPeers = pendingPeerState.toList(),
                        onAcceptPeer = { acceptPeer(it) },
                        onRejectPeer = { rejectPeer(it) },
                        onStart = { requestPermissionsAndStart() },
                        onStop = { stopMesh() },
                        onSend = { message -> requestGpsAndSend(message) },
                        onSendWithoutGps = { message -> sendSos(message, null) },
                        onOpenMap = { lat, lon -> openMap(lat, lon) }
                    )
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.distinct().toTypedArray()
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMeshInternal()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMeshInternal() {
        if (running) return
        running = true
        status = "Starting advertising and discovery. Keep this screen open."
        val strategy = Strategy.P2P_CLUSTER

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        client.startAdvertising(
            localName.ifBlank { "RESQ-Phone" },
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            status = "Advertising. Searching for nearby RESQMesh devices…"
        }.addOnFailureListener { e ->
            status = "Advertising failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }

        client.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnFailureListener { e ->
            status = "Discovery failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    private fun stopMesh() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected.clear()
        peerState.clear()
        running = false
        status = "Stopped. No messages can be relayed while stopped."
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (connected.containsKey(endpointId)) return
            val remoteName = info.endpointName
            if (remoteName == localName) return

            // Deterministic initiator rule reduces simultaneous crossed requests:
            // the lexicographically smaller advertised name initiates.
            if (localName.lowercase() < remoteName.lowercase()) {
                client.requestConnection(localName, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { e ->
                        addEvent("SYSTEM", "Connection request failed", "$remoteName: ${e.localizedMessage}")
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // A discovery loss is not proof that an established connection is gone.
            addEvent("SYSTEM", "Peer no longer visible", connected[endpointId] ?: endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Require explicit human confirmation of the same short authentication code on both phones.
            val pending = PendingPeer(endpointId, info.endpointName, info.authenticationDigits)
            runOnUiThread {
                pendingPeerState.removeAll { it.endpointId == endpointId }
                pendingPeerState.add(pending)
                addEvent("SECURITY", "Peer verification required", "Compare code ${info.authenticationDigits} on both phones before accepting ${info.endpointName}.")
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connected[endpointId] = result.status.statusMessage ?: endpointId
                    // The endpoint name is reliably available in ConnectionInfo during initiation;
                    // use the endpoint ID as a stable display fallback.
                    if (!peerState.contains(endpointId)) peerState.add(endpointId)
                    status = "Connected to ${connected.size} peer(s). Internet is not required for this link."
                    addEvent("SYSTEM", "Connected", "endpoint=$endpointId")
                    retryPendingOutbox()
                }
                else -> addEvent("SYSTEM", "Connection failed", "endpoint=$endpointId status=${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            peerState.remove(endpointId)
            status = "Disconnected endpoint $endpointId. Active peers: ${connected.size}"
            addEvent("SYSTEM", "Disconnected", endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes()
            if (bytes == null) {
                addEvent("SYSTEM", "Received empty payload", "Endpoint=$endpointId")
                return
            }
            val raw = String(bytes, Charsets.UTF_8)
            val json = runCatching { JSONObject(raw) }.getOrElse {
                addEvent("SYSTEM", "Received unreadable message", "Endpoint=$endpointId · ${it.localizedMessage ?: "invalid JSON"}")
                return
            }
            addEvent("SYSTEM", "Payload received from peer", "Endpoint=$endpointId · ${bytes.size} bytes")
            handleIncoming(endpointId, json)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val tracked = outgoingPayloads[update.payloadId] ?: return
            val messageId = tracked.messageId
            val kind = tracked.kind
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    addEvent("SYSTEM", "$kind SENDING", "$messageId · peer=$endpointId · ${update.bytesTransferred}/${update.totalBytes} bytes")
                PayloadTransferUpdate.Status.SUCCESS -> {
                    addEvent("SYSTEM", "$kind SENT", "$messageId · transport completed to peer=$endpointId. This is not proof the receiver processed it; wait for an app ACK.")
                    tracked.remainingEndpoints.remove(endpointId)
                    if (tracked.remainingEndpoints.isEmpty()) outgoingPayloads.remove(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    addEvent("SYSTEM", "$kind SEND FAILED", "$messageId · peer=$endpointId · transport failed. Pending SOS remains queued for retry.")
                    tracked.remainingEndpoints.remove(endpointId)
                    if (tracked.remainingEndpoints.isEmpty()) outgoingPayloads.remove(update.payloadId)
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    addEvent("SYSTEM", "$kind SEND CANCELED", "$messageId · peer=$endpointId")
                    tracked.remainingEndpoints.remove(endpointId)
                    if (tracked.remainingEndpoints.isEmpty()) outgoingPayloads.remove(update.payloadId)
                }
            }
        }
    }

    private fun requestGpsAndSend(message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) {
            addEvent("SYSTEM", "SOS not sent", "Enter a short message first.")
            return
        }
        pendingSosText = clean
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            gpsPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        acquireGpsAndSend(clean)
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun acquireGpsAndSend(message: String) {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val hasFinePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = if (hasFinePermission) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        val enabledProviders = providers.filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        if (enabledProviders.isEmpty()) {
            gpsStatus = "Device Location is off. Sending SOS without coordinates; enable Location and retry for GPS."
            addEvent("SYSTEM", "Location unavailable", "All location providers are disabled; SOS is sent without coordinates.")
            sendSos(message, null)
            return
        }

        // Prefer a recent real fix so the SOS is not blocked waiting for a cold GPS start.
        val now = System.currentTimeMillis()
        val recent = enabledProviders.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { now - it.time in 0..300_000L }
            .maxByOrNull { it.time }
        if (recent != null) {
            gpsStatus = "CACHED location · provider=${recent.provider} · accuracy ±${recent.accuracy.toInt()} m · age=${((now - recent.time).coerceAtLeast(0L) / 1000)}s · captured=${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(recent.time))}. Not a fresh fix."
            sendSos(message, recent)
            return
        }

        gpsStatus = "Getting location… SOS will still be sent if a fix is unavailable."
        addEvent("SYSTEM", "Location requested", "Trying enabled providers; SOS will not be blocked indefinitely.")
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val cancellation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) CancellationSignal() else null
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                cancellation?.cancel()
                gpsStatus = "Location timed out. SOS sent without coordinates; move outdoors and try again for a fix."
                addEvent("SYSTEM", "Location timeout", "SOS sent without coordinates rather than being silently blocked.")
                sendSos(message, null)
            }
        }
        handler.postDelayed(timeout, 15_000L)

        val provider = when {
            hasFinePermission && enabledProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            enabledProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> enabledProviders.first()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                locationManager.getCurrentLocation(provider, cancellation, mainExecutor) { location ->
                    if (completed.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeout)
                        if (location != null) {
                            gpsStatus = "CURRENT fix · provider=${location.provider} · accuracy ±${location.accuracy.toInt()} m · captured=${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(location.time))}."
                            sendSos(message, location)
                        } else {
                            gpsStatus = "No location fix. SOS sent without coordinates."
                            addEvent("SYSTEM", "Location fix unavailable", "SOS sent without coordinates; no location was invented.")
                            sendSos(message, null)
                        }
                    }
                }
            } catch (e: SecurityException) {
                if (completed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout)
                    gpsStatus = "Location permission error. SOS sent without coordinates."
                    addEvent("SYSTEM", "Location permission error", e.localizedMessage ?: "Permission unavailable")
                    sendSos(message, null)
                }
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (completed.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeout)
                        runCatching { locationManager.removeUpdates(this) }
                        gpsStatus = "CURRENT fix · provider=${location.provider} · accuracy ±${location.accuracy.toInt()} m · captured=${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(location.time))}."
                        sendSos(message, location)
                    }
                }
                @Deprecated("Deprecated by Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            try {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                if (completed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout)
                    gpsStatus = "Location permission error. SOS sent without coordinates."
                    addEvent("SYSTEM", "Location permission error", e.localizedMessage ?: "Permission unavailable")
                    sendSos(message, null)
                }
            }
            handler.postDelayed({ if (completed.get()) runCatching { locationManager.removeUpdates(listener) } }, 16_000L)
        }
    }

    private fun openMap(latitude: Double, longitude: Double) {
        // In-app map uses cached OSM tiles. The area and zoom levels must be viewed online first.
        val intent = Intent(this, OfflineMapActivity::class.java)
            .putExtra("latitude", latitude)
            .putExtra("longitude", longitude)
        runCatching { startActivity(intent) }
            .onFailure {
                addEvent("SYSTEM", "Could not open in-app map", "Coordinates: $latitude, $longitude. Check map screen installation.", latitude, longitude)
            }
    }

    private fun sendSos(message: String, location: Location?) {
        val clean = message.trim()
        if (clean.isEmpty()) {
            addEvent("SYSTEM", "SOS not sent", "Enter a short message first.")
            return
        }
        val id = UUID.randomUUID().toString()
        seenIds.add(id)
        val envelope = JSONObject()
            .put("kind", "SOS")
            .put("id", id)
            .put("origin", localName.ifBlank { "RESQ-Phone" })
            .put("text", clean.take(500))
            .put("createdAt", System.currentTimeMillis())
            .put("latitude", if (location != null) location.latitude else JSONObject.NULL)
            .put("longitude", if (location != null) location.longitude else JSONObject.NULL)
            .put("accuracyMeters", if (location != null) location.accuracy else JSONObject.NULL)
            .put("locationTimestamp", if (location != null) location.time else JSONObject.NULL)
            .put("locationProvider", if (location != null) location.provider else JSONObject.NULL)
            .put("ttl", MAX_HOPS)
            .put("hops", 0)
            .put("path", org.json.JSONArray().put(localName))
        val locationDetail = if (location != null) {
            "GPS ${location.latitude}, ${location.longitude} · ±${location.accuracy.toInt()} m · age=${((System.currentTimeMillis() - location.time).coerceAtLeast(0L) / 1000)}s · provider=${location.provider}"
        } else {
            "No GPS coordinates attached (user-selected fallback)."
        }
        saveOutbox(id, envelope)
        addEvent("SOS", "SOS QUEUED", "$id · $locationDetail · stored locally until an app peer acknowledges receipt", location?.latitude, location?.longitude)
        if (connected.isEmpty()) {
            addEvent("SYSTEM", "SOS QUEUED — NOT DELIVERED", "No connected peers. The message is saved on this phone and will retry when a peer connects and the mesh is started.", location?.latitude, location?.longitude)
        } else {
            transmitOutbox(id, envelope)
        }
    }

    private fun handleIncoming(fromEndpoint: String, envelope: JSONObject) {
        if (!verifyEnvelopeIntegrity(envelope)) {
            addEvent("SECURITY", "MESSAGE INTEGRITY CHECK FAILED", "Rejected a payload with a missing or mismatched SHA-256 checksum from endpoint=$fromEndpoint. This detects accidental changes; it is not a cryptographic sender identity proof.")
            return
        }
        val kind = envelope.optString("kind", "")
        val id = envelope.optString("id", "")
        if (id.isBlank() || kind !in setOf("SOS", "ACK")) {
            addEvent("SYSTEM", "INVALID MESSAGE REJECTED", "Missing ID or unsupported kind from endpoint=$fromEndpoint")
            return
        }
        if (!seenIds.add(id)) {
            addEvent("SYSTEM", "Duplicate received", "$id · already processed; re-acknowledging if this is an SOS")
            // A retransmitted SOS must receive another ACK. Otherwise a lost ACK
            // makes a correctly received SOS look permanently undelivered.
            if (kind == "SOS") sendAck(fromEndpoint, id)
            return
        }

        val ttl = envelope.optInt("ttl", 0)
        val hops = envelope.optInt("hops", 0)

        when (kind) {
            "SOS" -> {
                val origin = envelope.optString("origin", "Unknown")
                val text = envelope.optString("text", "(empty SOS)")
                val latitude = envelope.opt("latitude")
                val longitude = envelope.opt("longitude")
                val locationDetail = if (latitude != null && latitude != JSONObject.NULL && longitude != null && longitude != JSONObject.NULL) {
                    val accuracy = envelope.opt("accuracyMeters").let { if (it != null && it != JSONObject.NULL) " · ±${(it as Number).toFloat().toInt()} m" else "" }
                    " · GPS=$latitude,$longitude$accuracy"
                } else " · no GPS coordinates"
                val latitudeValue = envelope.opt("latitude")
                val longitudeValue = envelope.opt("longitude")
                val receivedLat = if (latitudeValue is Number) latitudeValue.toDouble() else null
                val receivedLon = if (longitudeValue is Number) longitudeValue.toDouble() else null
                addEvent("SOS", "SOS RECEIVED from $origin", "$text · id=$id · hops=$hops$locationDetail", receivedLat, receivedLon)
                sendAck(fromEndpoint, id)
                if (ttl > 0) {
                    val forwarded = JSONObject(envelope.toString())
                        .put("ttl", ttl - 1)
                        .put("hops", hops + 1)
                    val oldPath = forwarded.optJSONArray("path") ?: org.json.JSONArray()
                    oldPath.put(localName)
                    forwarded.put("path", oldPath)
                    // Persist the relay copy before forwarding so a later connection can retry it.
                    saveOutbox(id, forwarded)
                    broadcast(forwarded, excludeEndpoint = fromEndpoint)
                }
            }
            "ACK" -> {
                val originalId = envelope.optString("originalId")
                removeOutbox(originalId)
                addEvent("ACK", "SOS ACKNOWLEDGED", "SOS=$originalId · processed by ${envelope.optString("ackBy")}. This confirms app-to-app receipt only, not emergency-services notification.")
                if (ttl > 0) {
                    val forwarded = JSONObject(envelope.toString())
                        .put("ttl", ttl - 1)
                        .put("hops", hops + 1)
                    broadcast(forwarded, excludeEndpoint = fromEndpoint)
                }
            }
        }
    }

    private fun sendAck(endpointId: String, originalId: String) {
        val ack = JSONObject()
            .put("kind", "ACK")
            .put("id", UUID.randomUUID().toString())
            .put("originalId", originalId)
            .put("ackBy", localName)
            .put("createdAt", System.currentTimeMillis())
            .put("ttl", MAX_HOPS)
            .put("hops", 0)
        seenIds.add(ack.getString("id"))
        sendToEndpoint(endpointId, ack)
    }

    private fun saveOutbox(id: String, envelope: JSONObject) {
        // SharedPreferences commit is synchronous: the SOS is persisted before transmission is attempted.
        prefs.edit().putString("outbox_$id", envelope.toString()).commit()
    }

    private fun removeOutbox(id: String) {
        if (id.isNotBlank()) prefs.edit().remove("outbox_$id").commit()
    }

    private fun transmitOutbox(id: String, envelope: JSONObject) {
        if (connected.isEmpty()) return
        addEvent("SYSTEM", "SOS TRANSMISSION ATTEMPT", "$id · peers=${connected.size}; awaiting recipient ACK")
        broadcast(envelope, excludeEndpoint = null)
    }

    private fun retryPendingOutbox() {
        val pending = prefs.all.entries.filter { it.key.startsWith("outbox_") && it.value is String }
        if (pending.isEmpty()) return
        addEvent("SYSTEM", "Retrying saved SOS messages", "${pending.size} pending message(s) will be retransmitted to connected peers.")
        pending.forEach { entry ->
            val raw = entry.value as? String ?: return@forEach
            val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            transmitOutbox(entry.key.removePrefix("outbox_"), envelope)
        }
    }

    private fun acceptPeer(peer: PendingPeer) {
        pendingPeerState.removeAll { it.endpointId == peer.endpointId }
        client.acceptConnection(peer.endpointId, payloadCallback)
            .addOnFailureListener { e -> addEvent("SECURITY", "Connection acceptance failed", e.localizedMessage ?: e.javaClass.simpleName) }
    }

    private fun rejectPeer(peer: PendingPeer) {
        pendingPeerState.removeAll { it.endpointId == peer.endpointId }
        client.rejectConnection(peer.endpointId)
        addEvent("SECURITY", "Peer rejected", "Rejected ${peer.name} after verification prompt.")
    }

    private fun loadSavedEvents() {
        val raw = prefs.getString("events_json", "[]") ?: "[]"
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                eventState.add(UiEvent(o.optString("id"), o.optString("kind"), o.optString("text"), o.optString("detail"),
                    if (o.isNull("lat")) null else o.optDouble("lat"), if (o.isNull("lon")) null else o.optDouble("lon")))
            }
        }
    }

    private fun persistEvents() {
        val array = org.json.JSONArray()
        eventState.take(150).forEach { e ->
            array.put(JSONObject().put("id", e.id).put("kind", e.kind).put("text", e.text).put("detail", e.detail)
                .put("lat", e.latitude ?: JSONObject.NULL).put("lon", e.longitude ?: JSONObject.NULL))
        }
        prefs.edit().putString("events_json", array.toString()).apply()
    }

    private fun sendToEndpoint(endpointId: String, envelope: JSONObject) {
        if (!connected.containsKey(endpointId)) {
            addEvent("SYSTEM", "ACK NOT SENT", "The sender disconnected before the receipt acknowledgement could be delivered.")
            return
        }
        sealEnvelopeIntegrity(envelope)
        val payload = Payload.fromBytes(envelope.toString().toByteArray(Charsets.UTF_8))
        outgoingPayloads[payload.id] = TransferTrack(envelope.optString("originalId", envelope.optString("id")), envelope.optString("kind", "MESSAGE"), mutableSetOf(endpointId))
        client.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                addEvent("SYSTEM", "ACK SEND REQUEST ACCEPTED", "ACK payload=${payload.id} · endpoint=$endpointId. Waiting for transport result.")
            }
            .addOnFailureListener { e ->
                addEvent("SYSTEM", "ACK SEND FAILED", e.localizedMessage ?: e.javaClass.simpleName)
            }
    }

    private fun broadcast(envelope: JSONObject, excludeEndpoint: String?) {
        val recipients = connected.keys.filter { it != excludeEndpoint }
        if (recipients.isEmpty()) {
            addEvent("SYSTEM", "No relay recipients", "No eligible connected peers for this message.")
            return
        }
        sealEnvelopeIntegrity(envelope)
        val payload = Payload.fromBytes(envelope.toString().toByteArray(Charsets.UTF_8))
        outgoingPayloads[payload.id] = TransferTrack(envelope.optString("id"), envelope.optString("kind", "MESSAGE"), recipients.toMutableSet())
        client.sendPayload(recipients, payload)
            .addOnSuccessListener {
                addEvent("SYSTEM", "${envelope.optString("kind", "MESSAGE")} SEND REQUEST ACCEPTED", "Message=${envelope.optString("id")} · target peers=${recipients.size}. Waiting for transport completion and then a recipient ACK.")
            }
            .addOnFailureListener { e ->
                addEvent("SYSTEM", "SEND FAILED", "to ${recipients.size} peer(s): ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
    }

    // SHA-256 checksum detects accidental payload corruption. It is deliberately NOT
    // described as authentication: an attacker who can rewrite a message can recompute it.
    private fun canonicalEnvelope(envelope: JSONObject): String {
        val copy = JSONObject(envelope.toString())
        copy.remove("integritySha256")
        val keys = mutableListOf<String>()
        val iterator = copy.keys()
        while (iterator.hasNext()) keys += iterator.next()
        keys.sort()
        val canonical = JSONObject()
        keys.forEach { key -> canonical.put(key, copy.get(key)) }
        return canonical.toString()
    }

    private fun sealEnvelopeIntegrity(envelope: JSONObject) {
        envelope.remove("integritySha256")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalEnvelope(envelope).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        envelope.put("integritySha256", digest)
    }

    private fun verifyEnvelopeIntegrity(envelope: JSONObject): Boolean {
        val supplied = envelope.optString("integritySha256", "")
        if (supplied.isBlank()) return false
        val copy = JSONObject(envelope.toString())
        copy.remove("integritySha256")
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(canonicalEnvelope(copy).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun addEvent(kind: String, text: String, detail: String, latitude: Double? = null, longitude: Double? = null) {
        runOnUiThread {
            eventState.add(0, UiEvent(UUID.randomUUID().toString(), kind, text, detail, latitude, longitude))
            while (eventState.size > 150) eventState.removeAt(eventState.lastIndex)
            persistEvents()
        }
    }

    override fun onDestroy() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        super.onDestroy()
    }
}

@Composable
private fun ResqScreen(
    name: String,
    onNameChange: (String) -> Unit,
    status: String,
    gpsStatus: String,
    running: Boolean,
    peers: List<String>,
    events: List<UiEvent>,
    pendingPeers: List<PendingPeer>,
    onAcceptPeer: (PendingPeer) -> Unit,
    onRejectPeer: (PendingPeer) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onSendWithoutGps: (String) -> Unit,
    onOpenMap: (Double, Double) -> Unit
) {
    var sosText by remember { mutableStateOf("Need assistance. Please check this location.") }

    pendingPeers.lastOrNull()?.let { peer ->
        AlertDialog(
            onDismissRequest = { onRejectPeer(peer) },
            title = { Text("Verify nearby device") },
            text = { Text("Device: ${peer.name}\nVerification code: ${peer.digits}\n\nOnly accept if the other phone shows the same code and you intend to connect.") },
            confirmButton = { TextButton(onClick = { onAcceptPeer(peer) }) { Text("Code matches — connect") } },
            dismissButton = { TextButton(onClick = { onRejectPeer(peer) }) { Text("Reject") } }
        )
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("RESQMesh", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "OFFLINE SOS  •  FIELD TEST",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) { padding ->
        // One scrolling container for the entire screen. Previously the outer Column
        // did not scroll, so the event log could be pushed below the viewport.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Prototype only — not a certified emergency service. Keep normal emergency options available.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Device", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = name,
                            onValueChange = onNameChange,
                            label = { Text("Unique device name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (status.contains("failed", true) || status.contains("denied", true))
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Text("Location: $gpsStatus", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStart, enabled = !running, modifier = Modifier.weight(1f)) {
                                Text("Start mesh")
                            }
                            OutlinedButton(onClick = onStop, enabled = running, modifier = Modifier.weight(1f)) {
                                Text("Stop")
                            }
                        }
                        Text(
                            "Connected peers: ${peers.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (peers.isNotEmpty()) {
                            peers.forEach { peer ->
                                Text("• $peer", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text("No peers connected yet.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Send an SOS", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = sosText,
                            onValueChange = { sosText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            label = { Text("Emergency message") },
                            placeholder = { Text("Describe what help is needed") }
                        )
                        Button(
                            onClick = { onSend(sosText) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Broadcast SOS with GPS")
                        }
                        OutlinedButton(
                            onClick = { onSendWithoutGps(sosText) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Send without GPS")
                        }
                        Text(
                            "A peer receipt confirms app-to-app delivery only. It does not contact emergency services.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Event log", style = MaterialTheme.typography.titleLarge)
                    Text("${events.size} events", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (events.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("No events yet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Connection changes, SOS sends, received messages, GPS status and receipt acknowledgements will appear here.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                items(events, key = { it.id }) { event ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when (event.kind) {
                                "SOS" -> MaterialTheme.colorScheme.primaryContainer
                                "ACK" -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text("${event.kind}  •  ${event.text}", style = MaterialTheme.typography.titleSmall)
                            Text(event.detail, style = MaterialTheme.typography.bodyMedium)
                            if (event.latitude != null && event.longitude != null) {
                                Text(
                                    "Coordinates: ${event.latitude}, ${event.longitude}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { onOpenMap(event.latitude, event.longitude) }) {
                                    Text("OPEN LOCATION IN MAPS")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
