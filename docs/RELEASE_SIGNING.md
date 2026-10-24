# Release signing

## What signs the APKs CI produces today

The release APKs from CI (`app-release.apk` and the sample host's `scanner-host-release.apk`) are signed with a **test key committed to this repository**:

| | |
|---|---|
| File | `keystore/receiptscan-test-release.jks` |
| Alias | `receiptscan-test` |
| Store and key password | `receiptscan-test` |
| Subject | `CN=Receipt Scan TEST release key (sideload only), O=Receipt Scan, C=PK` |
| SHA-256 | `5C:B1:6D:D7:B7:02:90:18:C1:53:27:2F:35:35:DA:F0:F8:17:6B:26:39:8A:79:AD:03:2F:01:77:46:7C:2C:B8` |

CI prints the certificate with `apksigner verify --print-certs` in the "Check 16 KB page alignment" step, and the app shows the same SHA-256 in **Settings → About → Signing key**, labelled "Test release key (sideload only)".

Because the key and its password are public, **anyone can sign an APK that Android treats as an update of this one**. Use these builds for testing and sideloading only. Do not publish them to Google Play or distribute them as the official app.

## Using your own upload key

The app module reads an upload key from environment variables and prefers it over the test key. No file changes are needed:

```
export RS_UPLOAD_STORE_FILE=/path/to/upload.jks
export RS_UPLOAD_STORE_PASSWORD=...
export RS_UPLOAD_KEY_ALIAS=...
export RS_UPLOAD_KEY_PASSWORD=...
./gradlew :app:assembleRelease      # or :app:bundleRelease for Play
```

In GitHub Actions, store the keystore as a base64 secret, decode it in a step before the build, and pass the four values from secrets. Never commit a real keystore or its passwords.

To create an upload key:

```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

## Before publishing on Google Play

1. Create the upload key above and enrol in Play App Signing.
2. Build an App Bundle (`:app:bundleRelease`) with the upload key.
3. Remove or rotate the committed test key if test builds should no longer install over each other.
4. Phones that installed a test-key build must uninstall it before installing the Play version (different signature).
