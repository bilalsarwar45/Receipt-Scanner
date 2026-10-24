# Model and data register

Every model and dataset the app uses: what it is, where it comes from, how it gets into the app, its licence, and its hash. Hashes come from the CI log of the run that built the release APK ("==== assets manifest" in the "Check 16 KB page alignment" step), produced by `tools/fetch_assets.sh`.

Check the licence terms yourself before a commercial release. The licences below are as published by each provider when this was written.

## Bundled in the APK (fetched by CI at build time, not committed to git)

| Asset | Used by | Source | Version | Licence | Size (bytes) | SHA-256 |
|---|---|---|---|---|---|---|
| `aiy_plants_v1.tflite` | `scanner:plant` | Google AIY Vision Classifier Plants V1, https://tfhub.dev/google/lite-model/aiy/vision/classifier/plants_V1/3 | 3 | Apache-2.0 | 5,056,146 | `9ff2cc02d066fc266045ce299f04fb76907d9313d576881b61c255aa19433521` |
| `aiy_food_v1.tflite` | `scanner:food` | Google AIY Vision Classifier Food V1, https://tfhub.dev/google/lite-model/aiy/vision/classifier/food_V1/1 | 1 | Apache-2.0 | 21,151,551 | `03dd6d9129501f97be00775d9e17b5d9ad13be730149352adb8f4ca953b7a650` |
| `food_usda_sr.json` | `scanner:food` (calories and nutrients per 100 g, portions) | Built by `tools/build_food_pack.py` from USDA FoodData Central SR Legacy CSV (April 2018), https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip | SR Legacy 2018-04 | Public domain (CC0 1.0, U.S. Government work) | 3,395,008 | `59a1d7f65f8f61216de81e9f8c0b83a157b5451ed9a4c67ed80d3e2b4c5fc137` |
| (source zip of the above) | build input only | as above | | | 6,074,592 | `b80817294b8850530aaedf2e515c02593b1824f763a0ff356e5c2081643e6fd0` |

The plant and food models label their outputs with embedded metadata (2,102 plant species and 2,024 dishes). If CI cannot download a file, the build still succeeds: the plant tool shows "model not installed", and the food tool falls back to searching foods by name.

## Downloaded inside the app as a feature pack

| Pack | Used by | Source | Version | Licence | Size | SHA-256 (checked before use) |
|---|---|---|---|---|---|---|
| `detector-efficientdet-lite0` | `scanner:counter` | MediaPipe EfficientDet-Lite0 int8, https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite | int8 / 1 | Apache-2.0 | 4,602,795 | `0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb` |

The catalog is in `core/model-packs/src/main/assets/packs/catalog.json`. CI downloads the file on every build and logs "(catalog hash matches)". The app deletes any download whose hash does not match.

## Platform-managed models (no file is shipped with the app)

| Component | Used by | Library version | Licence / terms | Notes |
|---|---|---|---|---|
| ML Kit Text Recognition v2 (Latin) | receipts, bills, PDF, text, product, nutrition, expiry, math | `com.google.mlkit:text-recognition` 16.0.1 (model bundled in the library) | ML Kit Terms of Service | Latin script only |
| ML Kit Barcode Scanning | QR, product, Wi-Fi | `com.google.mlkit:barcode-scanning` 17.3.0 (bundled) | ML Kit Terms | |
| ML Kit Translation | translate | `com.google.mlkit:translate` 17.0.3 | ML Kit Terms | Language packs (~30 MB each) downloaded by ML Kit on request |
| ML Kit Language ID | translate | `com.google.mlkit:language-id` 17.0.6 (bundled) | ML Kit Terms | |
| MediaPipe Tasks Vision runtime | counter, plants, food | `com.google.mediapipe:tasks-vision` 0.10.26.1 | Apache-2.0 | Runs the TFLite files above |
| Android SpeechRecognizer (on-device) | voice commands | Android platform, API 31+ | Platform | `createOnDeviceSpeechRecognizer`; no audio leaves the phone |

## Not used

- whisper.cpp, multilingual-e5-small, a local LLM and PP-FormulaNet are named in the brief but not integrated. See `FEATURE_MATRIX.md`.
- The app calls no cloud recognition API. The only network use is downloading the feature pack above and ML Kit language packs, both started by the person.
