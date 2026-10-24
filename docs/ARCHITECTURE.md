# Architecture

Receipt & Scan is a single-activity Jetpack Compose app built from small Gradle modules. Every scanner is a library module that can be embedded in another app. The expense ledger is the only place money is counted.

## Layers

| Layer | Modules | Rules |
|---|---|---|
| Pure Kotlin (JVM, tested without Android) | `core:money-time`, `core:scanner-contracts`, `core:ocr-model`, `core:model-packs-engine`, `domain:expenses`, `domain:receipt-actions`, `domain:insights`, `feature:commands-engine`, `scanner:*-engine` | No Android imports. Parsers, rules and calculations live here so they are unit tested on any JVM (`./gradlew -PjvmOnly=true test`). |
| Platform adapters | `core:ocr` (ML Kit Text v2), `core:barcode` (ML Kit Barcode), `core:ml-runtime` (MediaPipe Tasks Vision), `core:capture` (CameraX, image store, permissions, pickers), `core:model-packs` (WorkManager downloads with SHA-256 checks), `data:local` (Room + DataStore) | Wrap one provider each, behind interfaces from the pure layer. |
| UI kit | `core:design-system` (tokens and components from `docs/design/`), `core:scanner-ui` (scanner contract, capture screen, processing/failure/result scaffolds, money and date fields) | Shared by every scanner and both hosts. |
| Scanners | `scanner:receipt`, `invoice`, `qr`, `text`, `translation`, `product`, `nutrition`, `expiry`, `math`, `counter`, `plant`, `food`, `wifi` | Depend only on `core:scanner-ui`, their own engine and platform adapters. Never on `data:local`, `domain:expenses` or `:app`. |
| Features | `feature:documents` (PDF), `feature:voice-input` (speech providers, mic UI), `feature:commands` (command sheet) | Reusable UI features. |
| Hosts | `:app`, `:samples:scanner-host` | `:app` is the composition root. The sample host proves reuse and is checked in CI to have no path to the app, ledger or storage. |

```
            :app ───────────────┬──────────────┬───────────────┐
             │                  │              │               │
   feature:commands     feature:documents   scanner:* (13)   data:local
             │                  │              │               │
 feature:commands-engine        └──── core:scanner-ui ────┐     │
             │                         │       │          │     │
       domain:expenses ◄───────────────┼───────┼──────────┼─────┘
             │                   core:capture core:ocr  core:design-system
       core:money-time                         │
                                         core:ocr-model ─ core:scanner-contracts

 :samples:scanner-host ── scanner:qr, scanner:text, scanner:plant, core:scanner-ui   (no ledger)
```

## The scanner contract

Defined in `core/scanner-ui/.../Contract.kt`:

- `ScannerModule<R>` exposes `info` (title, colour, icon, tips, required packs), `Capture(input, host, onResult, onCancel)` and `Result(result, input, host, onAction, onBack)`. `R` is owned by the module.
- `ScannerHost` gives a module what it may use: `capabilities` (can save, share, set reminders, open links, allow online lookups), session `temp` storage, an OCR engine, `keepImage`, education dismissal, camera-asked state, and a `PackGate` composable for feature packs.
- Results never write to storage. They emit `ResultAction`s: `Save(ScanEnvelope)`, `Copy`, `Share`, `OpenLink`, `Remind`, `HandOff`, `ScanAgain`, `Done`, or `Custom(id, json)` for module-specific payloads such as `receipt.confirm`, `invoice.save`, `product.warranty`, `food.log`.
- `ScannerFlow(module, input, host, onAction, onClose)` runs capture → result, handles Scan again and wraps capture in the host's `PackGate`.
- `BasicScannerHost` is a no-persistence host for samples and tests.

The app's `AppScannerHost` (`app/.../AppHost.kt`) adds image keeping, pack download screens (M-Intro / M-Download) and preference wiring. `ScannerScreen` turns actions into ledger and Library writes.

## Canonical ledger (no double counting)

`domain:expenses` holds the only money model. Every amount is `Money(minor: Long, currency)`; different currencies are never summed.

- `ExpenseEntry` is the single row that counts. Its `source` is RECEIPT, VOICE, MANUAL or BILL_PAYMENT. Refunds are entries with kind REFUND and count negative.
- A `Receipt` starts as NEEDS_REVIEW and does not count. `confirmReceipt` creates one expense, or attaches the receipt to an existing voice/manual expense (`attachToExpenseId`), or re-applies edits to its already-linked expense. A `ExpenseSourceLink(RECEIPT, receiptId)` is unique, so one receipt can never back two expenses.
- `possibleDuplicates` finds same-amount, same-day expenses from other sources before confirming; the UI offers Attach, Keep both or Discard.
- An `Invoice` (bill) never counts. Only `recordPayment` creates a BILL_PAYMENT expense; partial payments and over-payment checks are enforced.
- Commands carry an execution key; replaying the same key returns the existing expense (`AlreadyExecuted`), so double taps and recompositions cannot add twice. `undo(key)` voids the expense.
- Deletes are soft (VOIDED / DELETED) with undo tokens; edits bump a version and write an `ExpenseRevision`.
- Item-level categories are allocated with largest-remainder `Allocation`, so category totals always add up to the expense amount.

`data:local` implements `LedgerStore` with Room (`RoomLedgerStore`), plus `RecordsRepository` for non-money records (saved scans with FTS search, documents, reminders, warranties, budgets, learned merchant rules, shopping list, food log).

## App composition

- `AppGraph` is a manual DI root created by `ReceiptScanApp`. It owns the database, preferences (`StateFlow<Settings>`), ledger, records, image store, pack manager, reminder scheduler and the command parser/executor. It pushes preferences into modules (date order, enhance, auto-capture, translation target, learned merchant names and categories).
- `AppState` (a ViewModel) holds the back stack (`Screen` sealed list), current tab, snackbar host and overlay requests (command sheet, reminder sheet, export sheet).
- `MainActivity.AppRoot` routes screens; notification taps carry a record id and open the matching receipt, bill, expense, scan or warranty.
- Reminders use WorkManager one-time work per reminder (`ReminderScheduler`), five notification channels, and the POST_NOTIFICATIONS permission is requested inside the reminder sheet only when needed.

## Feature packs

`core:model-packs-engine` parses `packs/catalog.json` (id, purpose, version, provider, HTTPS URL, SHA-256, size, licence, provenance). `core:model-packs` downloads with WorkManager (Wi‑Fi only by default), checks free space, verifies SHA-256 before use, and exposes `PackState`. Platform-managed packs (ML Kit translation models) are listed but managed by ML Kit.

Bundled assets that are not committed (AIY plants/food classifiers, USDA food pack) are fetched in CI by `tools/fetch_assets.sh`, which logs their hashes. See `MODEL_AND_DATA_REGISTER.md`.

## Building

- JVM modules only: `./gradlew -PjvmOnly=true test`
- Full build: `./gradlew assembleDebug assembleRelease testDebugUnitTest` (Android SDK 36, JDK 21).
- CI (`.github/workflows/ci.yml`) runs both, fetches assets, checks sample-host isolation, runs lint, checks 16 KB alignment and prints the APK signing certificate.
