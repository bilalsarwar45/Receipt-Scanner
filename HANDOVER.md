# Receipt & Scan: handover to Claude Code

## Where things are

- Repo: https://github.com/bilalsarwar45/Receipt-Scanner
- `main`: the full app (PR #1, merged).
- Branch `claude/project-thread-d9p915` (open PR #2), on top of `main`:
  1. `02bc9e3`: per-CPU release APKs (ABI splits in `app/build.gradle.kts`). The arm64 APK is 82 MB, the universal one 185 MB. CI publishes them to the `test-build` prerelease.
  2. `fe7871b`: `tools/smoke_test.py` now runs real scans: a camera receipt, a gallery import of `tools/fixtures/receipt.png` (total should read 1404), and a plant photo. It prints the app's warnings and errors at the end. **CI for this commit was not finished when work stopped.** Its "Emulator smoke test" log is the first thing to read.
  3. This handover file (`HANDOVER.md`).

## Open bug (reported by Bilal on the installed release APK)

1. The gallery picker shows an error.
2. Scanning fails in every tool (receipt, plant, food, and so on).

What is known:
- On the emulator, with the release APK: the app opens, manual and command expenses work, the camera opens, and a photo reaches the crop screen. Earlier smoke tests never pressed "Read receipt" or ran a model, so they could not catch this.
- Everything is on-device (ML Kit text/barcode, AIY plant/food TFLite via MediaPipe). There are no API keys, so a missing key or internet is not the cause.
- Main suspect: **R8 shrinking in the release build** (`isMinifyEnabled = true`, `isShrinkResources = true` in `app/build.gradle.kts`). `app/proguard-rules.pro` keeps MediaPipe and protobuf but has no rules for ML Kit / `com.google.android.gms.internal.mlkit_*`, TFLite or CameraX. A failure common to all tools fits a stripped class or resource in the shared pipeline (`core/ocr/TextRecognition.kt`, `core/ml-runtime/Vision.kt`, `core/capture/Images.kt`, `core/scanner-ui`).
- Checked and correct: the FileProvider (`core/capture` manifest, authority `${applicationId}.files`), the photo picker contract (`core/capture/Permissions.kt`, PickVisualMedia), and the INTERNET permission.
- The failure screen shows the real error text (for example "Text recognition failed: <message>"). A screenshot of it, or logcat, gives the cause directly.

Fastest way to confirm:
1. `./gradlew :app:installDebug` on the phone and try a scan. If debug works and release fails, it is R8.
2. Quick release check: set `isMinifyEnabled = false` and `isShrinkResources = false`, rebuild and retest. Then add proper keep rules and turn shrinking back on.
3. `adb logcat | grep -iE "receiptscan|mlkit|mediapipe|tflite|AndroidRuntime"` while scanning.

## Build the release APK locally

Requirements: JDK 17+, Android SDK with platform 36 (Android Studio provides both), and internet for Gradle.

```
git clone https://github.com/bilalsarwar45/Receipt-Scanner.git
cd Receipt-Scanner
git checkout claude/project-thread-d9p915
tools/fetch_assets.sh                    # plant/food models + USDA food pack (bash, curl, python3)
./gradlew :app:assembleRelease           # signed with the committed TEST key
# APKs: app/build/outputs/apk/release/app-arm64-v8a-release.apk (phones), app-universal-release.apk
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
./gradlew test                           # unit tests
python3 tools/smoke_test.py app/build/outputs/apk/release/app-universal-release.apk   # with an x86_64 emulator running
```

On Windows, run `tools/fetch_assets.sh` in Git Bash or WSL, and use `gradlew.bat`.

## Key docs in the repo

`IMPLEMENTATION_STATUS.md`, `docs/VALIDATION_REPORT.md`, `docs/FEATURE_MATRIX.md`, `docs/MODEL_AND_DATA_REGISTER.md`, `docs/RELEASE_SIGNING.md`, `docs/ARCHITECTURE.md`.
