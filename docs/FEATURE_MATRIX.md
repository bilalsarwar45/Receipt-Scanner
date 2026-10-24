# Feature completion matrix

Status key:
- **Working, verified**: implemented with a real provider, and the logic is covered by passing unit tests in CI.
- **Working, limited**: implemented and builds, but with a stated limit (scope, language, data, or not yet checked on a phone).
- **Blocked**: needs an external asset or decision that is not available.

"On a phone" means checked on a physical device or emulator. See `VALIDATION_REPORT.md` for exactly what ran.

| Capability | Provider | Status | Notes |
|---|---|---|---|
| Receipt scan: single and long (multi-part) capture, crop, rotate, enhance, import image/PDF | CameraX, ML Kit Text v2, PdfRenderer | Working, limited | Parser covered by 13 tests (totals, tax, discounts, dates, item lines, reconciliation). Real-photo accuracy has not been measured. |
| Receipt review and confirm, duplicate check, attach to existing expense | `domain:expenses` | Working, verified | Ledger tests cover confirm, re-confirm, attach, duplicates, delete/undo. |
| Canonical ledger with no double counting | `domain:expenses`, Room | Working, verified | 17 ledger tests + 8 Room store tests. |
| Manual expenses, edit, refund, remove/restore | `domain:expenses` | Working, verified | |
| Voice commands (add, query, find receipts, edit, undo, remind) | Android on-device SpeechRecognizer (API 31+) + `CommandParser` | Working, limited | Parser: 20 tests, English / Roman Urdu / Urdu script. Speech needs Android 12+ with on-device recognition; whisper.cpp fallback not implemented. Recognition accuracy not benchmarked. |
| Typed commands | `CommandParser` | Working, verified | Same parser, works on every phone. |
| Bills / invoices: scan, due date, partial payments, reminders | ML Kit Text v2, `InvoiceParser`, ledger | Working, verified (logic) | Bill never counts until a payment is recorded (tested). |
| PDF documents: Document / Whiteboard / Book modes, reorder, rotate, searchable text layer | CameraX, ML Kit Text v2, `PdfDocument` | Working, limited | The invisible text layer has not been checked in a PDF reader on a phone. |
| QR and barcodes | ML Kit Barcode | Working, verified (payload parsing) | Links never auto-open. 5 tests. |
| Text / OCR with smart chips | ML Kit Text v2 (Latin) | Working, limited | Latin script only; Urdu/Arabic OCR is not offered and the UI says so. |
| Translation | ML Kit Translation + Language ID | Working, limited | Language packs downloaded by ML Kit; OCR input is Latin script. |
| Product label and warranty | ML Kit Text v2 + Barcode | Working, limited | Warranty dates only from the label or the person; never guessed. |
| Nutrition label and ingredients | ML Kit Text v2 + `NutritionParser` | Working, limited | Per-100 g / per-serving columns kept separate. 3 tests. |
| Expiry dates | ML Kit Text v2 + `ExpiryParser` | Working, limited | Ambiguous dates offered as a choice. 4 tests. |
| Math | ML Kit Text v2 + strict parser and solvers | Working, limited | Printed or typed arithmetic, linear equations and quadratics with real roots, solved exactly with steps. General OCR is not a handwriting/formula recognizer; PP-FormulaNet was not integrated. 5 tests. |
| Object counter | MediaPipe EfficientDet-Lite0 int8 (downloaded pack, SHA-256 checked) | Working, limited | COCO's 80 classes only; tap to add / long-press to remove; the confirmed count is saved. 4 tests for tiling and de-duplication. |
| Plants | Google AIY Vision Classifier Plants V1 (bundled, fetched in CI) | Working, limited | "Likely matches", no percentages, safety note. Accuracy not benchmarked. If the CI fetch fails the tool says the model isn't installed. |
| Food and calories | Google AIY Vision Classifier Food V1 + USDA SR Legacy pack (fetched in CI) | Working, limited | Model suggests dish names; the person picks foods and portions; calories shown as ≈ estimates. Portion size is never inferred from the photo. |
| Wi-Fi | ML Kit Barcode (Wi-Fi QR) + `WifiNetworkSuggestion`; Wi-Fi scan list | Working, limited | Android may limit scan frequency; staleness is shown. Password not stored. |
| Library, search, Insights, budgets, forecast | Room FTS, `domain:insights` | Working, verified (logic) | Insights 3 tests; forecast needs 21 days of history and shows its formula. |
| Split bill, price compare, shopping list, CSV export | `domain:receipt-actions` | Working, verified | 6 tests; split shares always add to the total. |
| Reminders and notifications | WorkManager + 5 channels | Working, limited | Not yet checked on a phone across reboots. |
| Feature packs (download, pause, verify, delete) | WorkManager, SHA-256 | Working, verified (logic) | 6 tests for verification and storage. |
| Sample host reusing scanners | `:samples:scanner-host` (QR, Text, Plants) | Working, verified (build) | CI fails if it depends on the app, ledger or storage modules. |
| Semantic search (multilingual-e5-small) | — | Not implemented | Search uses Room FTS and substring matching. |
| Local LLM assistance (brief §8.7) | — | Not implemented | Optional in the brief; the rule-based parser covers the required commands. |
| whisper.cpp speech fallback | — | Not implemented | Needs a native build and model benchmarking. |
