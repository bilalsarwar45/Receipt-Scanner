# Validation report

What was actually run, where, and with what result. Anything not listed here was **not** tested.

All checks run in GitHub Actions (`.github/workflows/ci.yml`) on every push to `claude/project-thread-d9p915`. The development sandbox could not run Gradle or reach Google Maven, so nothing below was run locally. CI is the only source of these results.

## 1. Build

| Check | Result |
|---|---|
| `:app:assembleDebug`, `:app:assembleRelease` (R8 on) | Pass |
| `:samples:scanner-host:assembleDebug`, `assembleRelease` | Pass |
| Release APK signature (`apksigner verify --print-certs`) | Pass. Signed with the test key, SHA-256 `5c:b1:6d:d7:…:7c:2c:b8` (see `RELEASE_SIGNING.md`) |
| 16 KB page alignment of every native library (`zipalign -c -P 16`) on both release APKs | Pass ("Verification successful") |
| Sample host isolation: its dependency tree must not contain `:app`, `:data:local`, `:domain:*` or `:feature:commands` | Pass |
| Android lint (app and sample) | Runs and uploads a report; it does **not** fail the build. Lint warnings have not been triaged. |
| Model and data fetch with SHA-256 logged | Pass; all 4 files downloaded, and the EfficientDet pack matches its catalog hash (`MODEL_AND_DATA_REGISTER.md`) |

## 2. Unit tests (139 tests, all passing)

| Module | Tests | What they cover |
|---|---|---|
| `core:money-time` | 29 | Money parsing and arithmetic in minor units, rounding, currency formats, date parsing and ranges |
| `feature:commands-engine` | 20 | Command parser: add, query, find receipts, edit, undo, remind; English, Roman Urdu, Urdu script; clarifications |
| `domain:expenses` | 17 | Ledger: confirm, re-confirm, attach receipt to an existing expense, duplicates, refunds, bill payments, void/restore, undo, idempotent keys, no double counting |
| `scanner:receipt-engine` | 13 | Receipt parsing: totals, tax, discounts, dates, item lines, reconciliation |
| `data:local` | 8 | Room ledger store on a real SQLite database: exact round trips, idempotent voice adds, totals exclude drafts/unpaid/voided, no double counting when attaching, one expense per receipt, rollback on failure, delete/undo/purge, full-text search |
| `core:model-packs-engine` | 6 | Pack SHA-256 verification, storage accounting, catalog parsing |
| `domain:receipt-actions` | 6 | Split bill (shares always add to the total), price compare, CSV export |
| `scanner:qr-engine` | 5 | URL, Wi-Fi, contact, payment and plain payloads; links never auto-open |
| `scanner:math-engine` | 5 | Arithmetic, linear and quadratic solving with steps |
| `scanner:counter-engine` | 4 | Tiling and de-duplication of detections |
| `scanner:expiry-engine` | 4 | Expiry date formats and ambiguous dates |
| `core:ocr-model`, `domain:insights`, `scanner:invoice-engine`, `scanner:nutrition-engine`, `scanner:text-engine` | 3 each | OCR line model; insights and forecast; invoice due dates; nutrition columns; smart chips |
| `scanner:food-engine`, `scanner:plant-engine`, `scanner:product-engine` | 2 each | Food pack lookups and portions; plant result wording; product label fields |
| `scanner:wifi-engine` | 1 | Wi-Fi staleness |

The tests check logic with fixed inputs. They do not measure recognition accuracy on real photos or voices.

## 3. Emulator smoke test

`tools/smoke_test.py` runs on an Android 14 (API 34, Google APIs, x86_64) emulator. It installs the **release** APKs (the same signed, R8-shrunk files that are delivered) and drives the UI with adb. The job fails on any crash, on an ANR in the app, or when an expected text is missing. The CI log lists what was on screen at each step, and screenshots are uploaded as the `smoke-screenshots` artifact.

Steps and what is checked:

1. Launch → onboarding shows → Skip.
2. Home shows "Scan receipt" and "Add manually".
3. Add manually → type 300 → pick Fuel → Add expense. The home total shows **PKR 300.00 · 1 expense**.
4. Library → Expenses lists "Fuel · Today · Entered by you · PKR 300".
5. Tools lists the 14 tools. Insights shows PKR 300.00 under Fuel and "Entered by you · 1".
6. Command sheet → example "Add 300 rupees fuel expense for today" → the preview shows the amount → Add expense → Undo is offered. The home total shows **PKR 600.00 · 2 expenses**.
7. Settings → About shows the signing key SHA-256 and the models list.
8. Scan receipt opens the camera with its tips, and the emulator's virtual camera reaches the crop screen.
9. Sample host app: lists QR code, Text and Plants, and opens the Text scanner sheet.

Result: see the latest CI run. The release handover message gives the run that produced the delivered APK.

## 4. Not tested

- Any physical phone.
- OCR, receipt parsing, plant, food and speech **accuracy** on real inputs; no benchmark set exists.
- Voice input end to end (the emulator has no on-device speech model). Only the parser behind it is tested.
- Camera capture of real paper, long-receipt stitching, PDF text layer in a PDF reader.
- Reminders firing at the right time and surviving a reboot.
- Feature pack download inside the app (CI checks the file and hash, not the in-app download).
- Translation language pack download.
- Wi-Fi connect suggestion on a real network.
