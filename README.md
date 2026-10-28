# RESQMesh v0.4.0 — delivery observability candidate

RESQMesh is an experimental Android peer-to-peer SOS prototype. It is **not certified or validated for real emergencies**.

## Changes in this candidate

- Outgoing SOS is synchronously saved to the local outbox before a transmission attempt. The queue is retried when a peer connects while the mesh is running; it is not a guaranteed Android background worker.
- Event history and queued SOS envelopes persist across ordinary app restarts.
- Per-recipient Nearby transfer tracking no longer drops a broadcast's tracking record after the first recipient succeeds.
- Delivery events distinguish `QUEUED`, `SENDING`, `SENT` (transport only), `SOS RECEIVED` (recipient app processed it), `SOS ACKNOWLEDGED` (correlated app ACK), and transport failure/cancellation.
- Duplicate SOS messages are re-acknowledged to help recover from a lost ACK.
- GPS status labels a last-known location as `CACHED` and shows its age, accuracy, provider, and timestamp. A new callback fix is labelled `CURRENT`. If no fix is available, the SOS is still sent without coordinates.
- Outgoing payloads carry a SHA-256 integrity checksum. Receivers reject missing/mismatched checksums. This detects accidental corruption only; it is **not a cryptographic signature or protection against a malicious peer**.
- Connection requests still require users to compare Nearby's authentication code on both devices and explicitly accept.
- Added an in-app OpenStreetMap view that can use cached tiles. Users must browse the relevant area and zoom levels online beforehand. No region-download manager or bundled offline map data is included.

## Not yet fixed / not verified — do not claim complete

1. **Cryptographic identity and location privacy:** not implemented. Pairing-code confirmation is not durable identity authentication; SHA-256 checksums are not keyed authentication. Do not use with untrusted peers or sensitive data.
2. **End-to-end delivery semantics:** the current ACK protocol needs three-device testing and further protocol work before it can prove that a specific final recipient received and displayed a message. An ACK means an app node processed an SOS, not that emergency services were notified.
3. **Multi-hop reliability:** forwarding, ACK routing, loops, TTL handling, duplicate suppression, and recovery need physical A→B→C testing. No result is claimed in advance.
4. **Background reliability:** no foreground service/WorkManager-based always-on mesh has been validated. Screen-off, battery saver, process death, and reboot recovery are not guaranteed.
5. **Offline maps:** only viewed/cached OpenStreetMap tiles are available offline. Uncached areas/zoom levels will be blank. Do not rely on this as a complete offline navigation map.
6. **Build/runtime:** this source archive has not been compiled in this environment and has not been tested on phones. A successful GitHub Actions build validates compilation only, not delivery or safety.

## Required test sequence

Record a video or screenshots and timestamped event logs for each run. Do not test only the green happy path.

### Test 1 — two phones, local link
1. Install the same build on A and B. Disable mobile data and Wi-Fi internet; leave Bluetooth/Wi-Fi radios enabled as required by Nearby Connections.
2. Start mesh on both. Compare the authentication digits on both devices; accept only when identical.
3. Send unique text from A. Verify B shows `SOS RECEIVED`, exact text, message ID, location metadata (or explicit no-location state), and A later shows the matching original SOS ID as acknowledged.
4. Repeat while B is disconnected and while turning the receiver off immediately after sending. Confirm the sender never labels `SENT` alone as acknowledged.

### Test 2 — queue and restart
1. On A with no connected peers, send an SOS. Verify it says queued/not delivered.
2. Force-stop and reopen A. Connect to B and start mesh. Verify the same message ID retries and is not silently lost.
3. Repeat with location permission denied, location disabled, and stale last-known location.

### Test 3 — three-phone relay
1. Connect A↔B and B↔C; keep A and C out of direct radio range if practical.
2. Send A→B→C. Verify C displays the exact message once and records the path/hop count.
3. Verify acknowledgements return to A for the correct message ID. Disconnect B during forwarding and repeat. Capture failures; do not mark pass if C never receives the message.

### Test 4 — integrity and peer verification
1. Confirm mismatched Nearby authentication digits are rejected.
2. Use a development-only test payload with a deliberately altered checksum; verify receiver rejects it and logs `MESSAGE INTEGRITY CHECK FAILED`.
3. This does not test malicious forgery; cryptographic identity/signatures remain unimplemented.

### Test 5 — background and maps
1. Repeat transmission with screen off, battery saver on, app backgrounded, process killed, and after reboot. Record what actually works; expect possible failures.
2. While online, view the SOS area at multiple zoom levels. Disable internet and reopen it. Confirm only cached tiles render and note blank areas.

## Delivery status meanings

- `QUEUED`: saved locally; not yet proof of delivery.
- `SENDING`: Nearby reports transfer in progress.
- `SENT`: Nearby transport completed to a peer; recipient app processing is not proven.
- `SOS RECEIVED`: recipient app parsed and displayed/recorded the SOS.
- `SOS ACKNOWLEDGED`: an application ACK correlated to the SOS ID was processed. This is app-to-app only and must be verified in multi-hop tests.
- `SEND FAILED` / `SEND CANCELED`: transport did not complete to that peer. Pending SOS remains queued where applicable for retry after reconnection.

**Never rely on this prototype as the only way to request help. It does not contact emergency services.**

## Build through GitHub Actions

Commit the project to GitHub, open **Actions → Build RESQMesh APK → Run workflow** (or push to `main`), and download the `RESQMesh-debug-APK` artifact only after the workflow succeeds. A green build is not a field-test pass.
