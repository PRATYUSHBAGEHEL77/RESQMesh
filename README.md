# RESQMesh — Offline SOS field-test candidate

This Android prototype exchanges SOS envelopes between nearby Android devices using Google Play services Nearby Connections. It is not certified or validated for real emergency response. Never rely on it as the only way to summon help.

## Changes in this candidate (0.2.0)
- Wi-Fi state permissions are declared without old SDK restrictions.
- Bluetooth / Nearby runtime permissions are requested at startup.
- Each fresh installation receives a distinct default device name to avoid the same-name discovery guard blocking untouched phones.
- SOS envelope includes a unique ID, sender name, message, timestamp, GPS latitude/longitude, reported accuracy, location timestamp/provider, hop count and TTL when a GPS fix is available.
- Received SOS events display the original sender's coordinates and an **OPEN LOCATION IN MAPS** action. This opens an installed mapping app; for offline map display, download the relevant map area in that mapping app before losing connectivity.
- Event log explicitly distinguishes transport submission from app-to-app receipt acknowledgement. An ACK is not an emergency-services acknowledgement.
- Invalid/empty payloads and send failures are logged to help diagnose why a peer did not display a message.

## Build without Android Studio
Use the included GitHub Actions workflow. Upload the repository files to GitHub and wait for the workflow to complete; download the `RESQMesh-debug-APK` artifact.

## Two-phone test procedure
1. Install the same build on both phones. Keep the app visible and the phones awake during this prototype test.
2. Grant Nearby/Bluetooth permissions. Turn on Bluetooth and Wi-Fi on both phones. Turn on device Location for GPS tests.
3. Use distinct names (the app generates a distinct default name on a fresh install).
4. Tap **Start mesh** on both. Confirm each side lists a connected peer.
5. On phone A tap **Broadcast SOS with GPS** outdoors or near a window and wait for a real GPS fix.
6. On phone B verify an `SOS RECEIVED` event, coordinates and `OPEN LOCATION IN MAPS`. On phone A verify `SOS RECEIPT CONFIRMED`.
7. Repeat with internet/mobile data disabled. GPS coordinates can be obtained offline, but mapping apps only show maps offline if map data is already downloaded.
8. Test with GPS disabled, permission denied, a disconnected peer, duplicate delivery, app restart and a third phone acting as a relay. Record failures rather than assuming success.

## Known limitations — not production-ready
- Not tested or compiled on physical devices by this package creator. A green cloud build only proves compilation.
- Event log and messages are in-memory; they are lost if the app process is killed or the phone restarts. There is no durable SOS queue/retry guarantee.
- No end-to-end encryption, identity verification, abuse protection, authenticated contacts, background service guarantees, battery optimization handling, or audited security design.
- Nearby Connections support and radio behavior vary by Android version, vendor and Google Play services availability.
- Multi-hop forwarding is best-effort and must be physically verified; it is not a guaranteed mesh network.
- A peer ACK means the RESQMesh app received the SOS, not that responders or emergency services received it.
- GPS can be unavailable or inaccurate indoors; never treat coordinates as verified if they are old or inaccurate.

Do not advertise or deploy this build as emergency-ready. Real deployment requires independent engineering/security review, privacy review, usability testing, and field reliability testing with documented pass criteria.
