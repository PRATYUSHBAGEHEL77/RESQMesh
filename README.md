# RESQMesh — Android offline SOS mesh prototype

RESQMesh is an early Android prototype for exchanging SOS messages between nearby phones using Google Play services Nearby Connections. The current source includes GPS-coordinate capture for SOS messages and Wi-Fi state permissions for Nearby Connections.

## Build without Android Studio

This repository includes `.github/workflows/build-apk.yml`. Push to `main` or run the workflow manually from the GitHub **Actions** tab. A successful run uploads `RESQMesh-debug-APK` as an artifact.

## GPS behavior

- Press **Broadcast SOS with GPS** and grant precise location permission.
- The app requests a fresh fix from Android's GPS provider. If it succeeds, latitude, longitude, reported accuracy, timestamp, and provider are included in the SOS payload and shown in the event log.
- GPS may take time or fail indoors, with GPS disabled, or without a clear sky view. If no real fix is available, the app does not invent coordinates; use **Send without GPS (fallback)** only if you deliberately want to send a message without coordinates.
- Coordinates are shared with connected RESQMesh nodes when you send an SOS.

## Permissions

The manifest declares `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` without the old `maxSdkVersion=31` restriction, plus Bluetooth/Nearby permissions and fine/coarse location permissions.

## Important limitations

- This source has **not been compiled or physically tested after the latest GPS/permission changes**. Use GitHub Actions to compile it and inspect the build result before installing.
- Nearby Connections behavior depends on the device, Google Play services, OS version, radio state, and permissions. An app-node ACK only means another participating app received the message; it does not notify emergency services.
- The prototype auto-accepts nearby connections, keeps event/duplicate state in memory, and does not have production security, durable queuing, authentication, or emergency-service integration.
- Do not rely on this prototype for real emergencies. Delivery is not guaranteed, and GPS coordinates alone do not provide an offline map.
