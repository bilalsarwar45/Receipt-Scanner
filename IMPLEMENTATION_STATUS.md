# Implementation status

Branch `claude/project-thread-d9p915`, draft PR #1. Not merged to `main`; merging needs Bilal's approval.

## Summary

The full app is built from `docs/IMPLEMENTATION_BRIEF.md` and the design in `docs/design/`. Where the two differ, the brief wins. Screens the design does not cover were designed from the same tokens (`docs/SCREENS_ADDED.md`).

- 46 Gradle modules: 20 pure-Kotlin modules (domain rules, parsers, engines) and 26 Android modules (UI, platform adapters, storage, the app and a sample host). About 19,000 lines of Kotlin.
- 14 scanners, plus voice, typed and manual expenses, all writing to one canonical ledger.
- 139 unit tests in 22 files, all passing in CI.
- Release APK built in CI and signed with the committed **test key** (`docs/RELEASE_SIGNING.md`).
- A sample host app (`samples/scanner-host`) reuses the QR, Text and Plant scanners without the app, ledger or storage. CI fails if it depends on them.
- An emulator smoke test (Android 14) installs both **release** APKs on every push. It adds an expense by hand and another through a command, then checks the totals in Home, Library and Insights. Last result: all checks passed (CI run 39).

## Done

| Area | State |
|---|---|
| Canonical ledger (receipts, voice, typed, manual, bill payments, refunds; no double counting; undo; revisions) | Done, 17 ledger + 8 Room tests |
| Receipt capture, long receipts, import, review, confirm, duplicates, attach to existing expense | Done |
| Bills and invoices with partial payments and reminders | Done |
| PDF documents (Document, Whiteboard, Book) with text layer | Done |
| QR/barcode, Text, Translate, Product/warranty, Nutrition, Expiry, Math, Counter, Plants, Food, Wi-Fi | Done; see `docs/FEATURE_MATRIX.md` for each one's limits |
| Voice and typed commands (English, Roman Urdu, Urdu script) | Done, 20 parser tests; `docs/VOICE_AND_COMMANDS.md` |
| Library, search, Insights, budgets, forecast, split, compare, shopping list, food log, reminders, CSV export | Done |
| Settings (appearance, language and currency, packs, receipt defaults, voice, tips, notifications, data, about), onboarding | Done |
| Feature packs with SHA-256 verification (EfficientDet-Lite0) | Done |
| Models and data fetched in CI with logged hashes | Done; `docs/MODEL_AND_DATA_REGISTER.md` |
| 16 KB page alignment of native libraries | Checked in CI on both release APKs |
| Docs: architecture, voice, screens added, feature matrix, model register, validation report, release signing | Done |

## Not done or limited

- **whisper.cpp speech fallback:** not implemented. Voice needs Android 12+ with on-device recognition; on older phones the sheet offers typing.
- **Semantic search (multilingual-e5-small) and local LLM help:** not implemented. Search uses Room FTS and substring matching.
- **PP-FormulaNet / handwriting math:** not integrated; the math tool reads printed or typed expressions.
- **Urdu/Arabic OCR:** not offered; ML Kit Text v2 Latin only.
- **Accuracy:** OCR, parsing, plant, food and speech accuracy on real photos and voices has not been measured.
- **Device checks:** checked on an emulator only. Camera capture on real paper, the PDF text layer in a reader, reminders across a reboot and speech recognition have not been checked on a physical phone.
- **Release key:** the APK is signed with a public test key. A Play release needs Bilal's upload key.

See `docs/VALIDATION_REPORT.md` for exactly what ran.
