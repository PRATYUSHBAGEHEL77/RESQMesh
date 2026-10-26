# RESQMesh — Android real-radio prototype

This is an early physical-device prototype using Google Play services Nearby Connections.
It is not an emergency-ready system.

## Requirements
- Android Studio with Android SDK 35 and JDK 17
- Three or more Android phones with compatible Google Play services
- Nearby devices/Bluetooth permissions granted
- Bluetooth and Wi-Fi radios enabled as requested by the operating system

Internet is not required for the intended nearby exchange after the app and dependencies are installed. Google Play services is required by this transport implementation. Keep Bluetooth and Wi-Fi enabled as requested by the OS; current Nearby Connections behavior does not silently turn radios on for the app.

## Open and run
1. Extract this folder.
2. Open the `RESQMesh` folder in Android Studio.
3. Let Gradle sync and install dependencies.
4. Connect three physical phones and enable developer USB debugging.
5. Install and launch the app on all phones.
6. Give each phone a distinct name, e.g. `A`, `B`, `C`.
7. Keep the app open, press **Start mesh** on all phones, and grant requested permissions.
8. Wait for peer connections and send a test SOS.

## What this code attempts to implement
- Nearby Connections P2P_CLUSTER advertising/discovery
- Peer connection acceptance
- SOS message envelopes with unique IDs, hop count and TTL
- App-level flooding to connected peers
- Duplicate suppression during the current process
- Acknowledgement messages that mean only "a participating app node received it"

## Important limitations
- This is an MVP code drop and has not been built or tested on physical phones in this environment.
- The current message/event history is in memory and is lost if the process closes.
- The code does not attach GPS coordinates.
- It automatically accepts connections. Do not use it with unknown/untrusted devices as a real emergency tool.
- The code does not implement cryptographic identity verification, end-to-end application encryption, durable store-and-forward, delivery guarantees, or emergency-service integration.
- P2P_CLUSTER permits multiple peer connections, but that alone does not prove end-to-end multi-hop delivery. Validate the three-device test and capture logs.
- For actual multi-hop test, ensure A and C have no direct connection, while B can connect to both. Physical radio range is environment-dependent; use a sufficiently separated layout and confirm the peer list.
- This prototype is not safety-certified and must not replace emergency services.

## Objective test plan
1. **Direct delivery:** A and B connect; A sends a unique SOS; B logs the same ID.
2. **Duplicate suppression:** resend the same envelope ID from a test harness or modify a test to replay it; recipient should log duplicate ignored and not forward again.
3. **TTL:** set TTL to 0 and confirm the receiver does not forward.
4. **Three-device relay:** A connects to B; B connects to C; A and C are not connected. Send from A and verify C logs the same SOS ID with a higher hop count. Repeat at least 20 times and report successful deliveries / attempts.
5. **Offline condition:** disable mobile data and ensure no Wi-Fi internet connection is available while leaving the radios needed by Nearby Connections enabled. Verify direct and relay tests still work.
6. **Failure reporting:** stop B after it receives an SOS; confirm no false claim of delivery to C.
7. **App restart:** current implementation loses seen IDs and logs; document this as a known failure until persistence is implemented.

## Suggested next engineering steps
1. Compile and fix any dependency/API version issues in Android Studio.
2. Replace the `ConcurrentHashMap`/in-memory event list with Room persistence.
3. Add durable message queue, acknowledgements with explicit semantics, retries and expiry.
4. Add user-approved location and attach coordinates only when permission is granted.
5. Add peer authentication and a threat model before considering real emergency use.
