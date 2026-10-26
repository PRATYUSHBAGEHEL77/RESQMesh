package com.resqmesh.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    val detail: String
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
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ResqScreen(
                        name = localName,
                        onNameChange = { localName = it.take(24) },
                        status = status,
                        running = running,
                        peers = peerState.toList(),
                        events = eventState.toList(),
                        onStart = { requestPermissionsAndStart() },
                        onStop = { stopMesh() },
                        onSend = { message -> sendSos(message) }
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
            val bytes = payload.asBytes() ?: return
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
            handleIncoming(endpointId, json)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Small BYTES payloads complete quickly; events are logged on receipt instead.
        }
    }

    private fun sendSos(message: String) {
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
            .put("ttl", MAX_HOPS)
            .put("hops", 0)
            .put("path", org.json.JSONArray().put(localName))
        addEvent("SOS", "SOS created", "$id · not yet acknowledged by a recipient")
        broadcast(envelope, excludeEndpoint = null)
        if (connected.isEmpty()) {
            addEvent("SYSTEM", "No connected peers", "SOS is not transmitted yet; this prototype does not persist it.")
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
                addEvent("SOS", "SOS received from $origin", "$text · id=$id · hops=$hops")
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
                broadcast(ack, excludeEndpoint = fromEndpoint)
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
                addEvent("ACK", "Peer receipt acknowledgement", "SOS=${envelope.optString("originalId")} · received by ${envelope.optString("ackBy")}")
                if (ttl > 0) {
                    val forwarded = JSONObject(envelope.toString())
                        .put("ttl", ttl - 1)
                        .put("hops", hops + 1)
                    broadcast(forwarded, excludeEndpoint = fromEndpoint)
                }
            }
        }
    }

    private fun broadcast(envelope: JSONObject, excludeEndpoint: String?) {
        val recipients = connected.keys.filter { it != excludeEndpoint }
        if (recipients.isEmpty()) return
        val payload = Payload.fromBytes(envelope.toString().toByteArray(Charsets.UTF_8))
        client.sendPayload(recipients, payload)
            .addOnFailureListener { e ->
                addEvent("SYSTEM", "Send failed", "to ${recipients.size} peer(s): ${e.localizedMessage}")
            }
    }

    private fun addEvent(kind: String, text: String, detail: String) {
        runOnUiThread {
            eventState.add(0, UiEvent(UUID.randomUUID().toString(), kind, text, detail))
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
    running: Boolean,
    peers: List<String>,
    events: List<UiEvent>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit
) {
    var sosText by remember { mutableStateOf("Need assistance. Please check this location.") }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("RESQMesh", style = MaterialTheme.typography.headlineMedium)
            Text("Offline SOS mesh prototype", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Device name (unique per phone)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !running, modifier = Modifier.weight(1f)) {
                    Text("Start mesh")
                }
                OutlinedButton(onClick = onStop, enabled = running, modifier = Modifier.weight(1f)) {
                    Text("Stop")
                }
            }
            Text("Connected peer IDs: ${peers.size}")
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
                Text("Broadcast SOS")
            }
            Text(
                "Prototype only: no GPS location is attached. An ACK means another app node received the message, not that emergency services were notified.",
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
                        }
                    }
                }
            }
        }
    }
}
