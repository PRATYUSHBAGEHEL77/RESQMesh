package com.resqmesh.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
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
import java.util.concurrent.ConcurrentHashMap

private const val SERVICE_ID = "com.resqmesh.app.SOS"
private const val MAX_HOPS = 5

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
    private val eventState = mutableStateListOf<UiEvent>()
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
            // Prototype accepts discovered peers automatically. Production needs explicit trust/authentication.
            client.acceptConnection(endpointId, payloadCallback)
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
            // Small BYTES payloads complete quickly; events are logged on receipt instead.
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
            gpsStatus = "Using recent location from ${recent.provider} (±${recent.accuracy.toInt()} m)."
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
                            gpsStatus = "Location fix from ${location.provider}: ±${location.accuracy.toInt()} m."
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
                        gpsStatus = "Location fix from ${location.provider}: ±${location.accuracy.toInt()} m."
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
        // Opens an installed maps app if available. Offline maps must be downloaded in that app beforehand.
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(RESQMesh%20SOS)")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        runCatching { startActivity(intent) }
            .onFailure { addEvent("SYSTEM", "Could not open map", "Coordinates: $latitude, $longitude. Open them in a maps app manually.", latitude, longitude) }
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
            "GPS ${location.latitude}, ${location.longitude} · ±${location.accuracy.toInt()} m · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(location.time))}"
        } else {
            "No GPS coordinates attached (user-selected fallback)."
        }
        addEvent("SOS", "SOS created", "$id · $locationDetail · waiting for peer receipt", location?.latitude, location?.longitude)
        if (connected.isEmpty()) {
            addEvent("SYSTEM", "SOS NOT SENT", "No connected peers. Keep the app open and retry when a peer connects.", location?.latitude, location?.longitude)
        } else {
            broadcast(envelope, excludeEndpoint = null)
        }
    }

    private fun handleIncoming(fromEndpoint: String, envelope: JSONObject) {
        val kind = envelope.optString("kind", "")
        val id = envelope.optString("id", "")
        if (id.isBlank() || kind !in setOf("SOS", "ACK")) return
        if (!seenIds.add(id)) {
            addEvent("SYSTEM", "Duplicate ignored", id)
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
                // This ACK means this node received the SOS; it is not a rescue-service acknowledgement.
                val ack = JSONObject()
                    .put("kind", "ACK")
                    .put("id", UUID.randomUUID().toString())
                    .put("originalId", id)
                    .put("ackBy", localName)
                    .put("createdAt", System.currentTimeMillis())
                    .put("ttl", MAX_HOPS)
                    .put("hops", 0)
                seenIds.add(ack.getString("id"))
                // ACK must be sent BACK to the sender. Excluding fromEndpoint here
                // accidentally prevented the original phone from ever seeing confirmation.
                sendToEndpoint(fromEndpoint, ack)
                if (ttl > 0) {
                    val forwarded = JSONObject(envelope.toString())
                        .put("ttl", ttl - 1)
                        .put("hops", hops + 1)
                    val oldPath = forwarded.optJSONArray("path") ?: org.json.JSONArray()
                    oldPath.put(localName)
                    forwarded.put("path", oldPath)
                    broadcast(forwarded, excludeEndpoint = fromEndpoint)
                }
            }
            "ACK" -> {
                addEvent("ACK", "SOS RECEIPT CONFIRMED", "SOS=${envelope.optString("originalId")} · received by ${envelope.optString("ackBy")}. This confirms app-to-app receipt only.")
                if (ttl > 0) {
                    val forwarded = JSONObject(envelope.toString())
                        .put("ttl", ttl - 1)
                        .put("hops", hops + 1)
                    broadcast(forwarded, excludeEndpoint = fromEndpoint)
                }
            }
        }
    }

    private fun sendToEndpoint(endpointId: String, envelope: JSONObject) {
        if (!connected.containsKey(endpointId)) {
            addEvent("SYSTEM", "ACK NOT SENT", "The sender disconnected before the receipt acknowledgement could be delivered.")
            return
        }
        val payload = Payload.fromBytes(envelope.toString().toByteArray(Charsets.UTF_8))
        client.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                addEvent("SYSTEM", "Receipt ACK queued", "Sent to the originating connected peer; this confirms app-to-app receipt only.")
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
        val payload = Payload.fromBytes(envelope.toString().toByteArray(Charsets.UTF_8))
        client.sendPayload(recipients, payload)
            .addOnSuccessListener {
                addEvent("SYSTEM", "Payload handed to nearby transport", "Target peers: ${recipients.size}. This is not proof of receipt; wait for SOS RECEIPT CONFIRMED.")
            }
            .addOnFailureListener { e ->
                addEvent("SYSTEM", "SEND FAILED", "to ${recipients.size} peer(s): ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
    }

    private fun addEvent(kind: String, text: String, detail: String, latitude: Double? = null, longitude: Double? = null) {
        runOnUiThread {
            eventState.add(0, UiEvent(UUID.randomUUID().toString(), kind, text, detail, latitude, longitude))
            while (eventState.size > 150) eventState.removeAt(eventState.lastIndex)
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
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onSendWithoutGps: (String) -> Unit,
    onOpenMap: (Double, Double) -> Unit
) {
    var sosText by remember { mutableStateOf("Need assistance. Please check this location.") }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("RESQMesh", style = MaterialTheme.typography.headlineMedium)
            Text("Offline SOS mesh · field-test build", style = MaterialTheme.typography.bodyMedium)
            Text("Keep this screen open during testing. Delivery is not guaranteed; this is not a certified emergency service.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Device name (unique per phone)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(gpsStatus, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !running, modifier = Modifier.weight(1f)) {
                    Text("Start mesh")
                }
                OutlinedButton(onClick = onStop, enabled = running, modifier = Modifier.weight(1f)) {
                    Text("Stop")
                }
            }
            Text("Connected peer IDs: ${peers.size}")
            Text("A connected peer is not proof an SOS was received. Check the receipt acknowledgement in the event log.", style = MaterialTheme.typography.bodySmall)
            peers.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }

            Divider()
            Text("Send an SOS", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = sosText,
                onValueChange = { sosText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("SOS message") }
            )
            Button(onClick = { onSend(sosText) }, modifier = Modifier.fillMaxWidth()) {
                Text("Broadcast SOS with GPS")
            }
            OutlinedButton(onClick = { onSendWithoutGps(sosText) }, modifier = Modifier.fillMaxWidth()) {
                Text("Send without GPS (fallback)")
            }
            Text(
                "Prototype only: GPS is attached only when a real fix is obtained. An ACK means another app node received the message, not that emergency services were notified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Divider()
            Text("Event log", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events, key = { it.id }) { event ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${event.kind} · ${event.text}", style = MaterialTheme.typography.titleSmall)
                            Text(event.detail, style = MaterialTheme.typography.bodySmall)
                            if (event.latitude != null && event.longitude != null) {
                                Text("Coordinates: ${event.latitude}, ${event.longitude}", style = MaterialTheme.typography.bodySmall)
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
