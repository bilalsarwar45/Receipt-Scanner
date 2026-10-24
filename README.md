# Receipt & Scan

On-device Android receipt scanner with a modular scanner suite (14 tools), voice and typed expense commands, and one canonical expense ledger that never counts a purchase twice.

## Build

Requirements: JDK 17, Android SDK 36. Internet access is needed once for Gradle and for the model/data assets.

```
tools/fetch_assets.sh                 # plant + food models and the USDA food pack (optional; tools say "not installed" without them)
./gradlew :app:assembleRelease        # app, signed with the committed TEST key (see docs/RELEASE_SIGNING.md)
./gradlew :samples:scanner-host:assembleRelease   # sample app that reuses the scanner modules
./gradlew test                        # unit tests
./gradlew -PjvmOnly=true test         # pure-Kotlin modules only, no Android SDK needed
```

CI (`.github/workflows/ci.yml`) runs all of this, checks 16 KB page alignment and the sample's module isolation, runs an emulator smoke test, and uploads the APKs as the `apks` artifact.

## Docs

- `IMPLEMENTATION_STATUS.md`: what is done and what is not
- `docs/ARCHITECTURE.md`: modules and the scanner contract
- `docs/FEATURE_MATRIX.md`: every capability, its provider and status
- `docs/VALIDATION_REPORT.md`: what was actually tested
- `docs/MODEL_AND_DATA_REGISTER.md`: models and data, sources, licences, hashes
- `docs/VOICE_AND_COMMANDS.md`, `docs/SCREENS_ADDED.md`, `docs/RELEASE_SIGNING.md`
- `docs/IMPLEMENTATION_BRIEF.md` and `docs/design/`: the original brief and design handover
