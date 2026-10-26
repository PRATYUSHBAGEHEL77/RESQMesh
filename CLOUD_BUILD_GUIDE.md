# RESQMesh — cloud APK build kit

This kit is intended for a PC that cannot run Android Studio. The included GitHub Actions workflow builds the Android project on a hosted GitHub runner and publishes the APK as a downloadable workflow artifact.

## Build without installing Android Studio

1. Sign in to GitHub in your browser and create a **new repository** named `RESQMesh`. For the simplest setup, make it private if you do not want the source code public.
2. Extract this ZIP on your PC.
3. Open the new repository on GitHub. Use **Add file → Upload files** and upload the *contents* of the extracted `RESQMesh` folder (including the hidden `.github` folder). If the browser does not show hidden files, create `.github/workflows/build-apk.yml` directly on GitHub and paste in the workflow from this ZIP.
4. Commit the uploaded files to the `main` branch.
5. Open the repository's **Actions** tab. Choose **Build RESQMesh APK**, then **Run workflow** if it did not start automatically after your commit.
6. Wait for the workflow to finish. Open its successful run and download the **RESQMesh-debug-APK** artifact.
7. Extract the downloaded artifact ZIP on your PC, transfer the APK to your Android phone, and install it. Android may ask you to allow installation from that file app.

## Important limitations

- This workflow is a cloud build setup, **not proof that the app compiles successfully**. Run it and inspect the result; if it fails, share the error log so the build can be fixed.
- The existing source is an early prototype and has not been physically tested. GPS is **not implemented yet** in this source archive; nearby SOS delivery is also not yet verified on real phones.
- Do not rely on this prototype for real emergencies. An offline message can only reach another device if the radio connection/path and compatible app are available.
- Do not upload passwords, signing keys, private information, or location data to the repository.
- GitHub's free usage and availability can vary by account and repository settings.
