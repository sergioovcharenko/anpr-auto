# Android APK

The GitHub Actions workflow compiles an experimental offline OCR APK with CameraX and bundled Google ML Kit text recognition.

Open Actions > Build Android APK > latest successful run > Artifacts > ANPR-AUTO-offline-APK. Install app-debug.apk. This is a debug build, not a Play Store release.

Limitations: dedicated YOLO plate detection, background capture on locked screen, persistent annotated-photo archive, and multi-frame confidence checks are not yet implemented. The current preview recognizes full Ukrainian-format plates and saves high-quality photos while the app is open. Red detections are never saved.
