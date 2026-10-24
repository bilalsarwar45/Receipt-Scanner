# Voice and typed commands

The command sheet lets people add and ask about expenses by speaking or typing. It opens from the "Ask or add an expense…" pill and the mic button on Home.

Modules:
- `feature:commands-engine` (pure Kotlin): `CommandParser`, `NumberWords`, `Lexicon`, `QuickAddPolicy`, `CommandExecutor`. Unit tested in `CommandParserTest` (20 tests).
- `feature:voice-input` (Android): `SpeechToTextProvider` and the mic UI.
- `feature:commands` (Compose): `CommandSheet` / `CommandPanel`.

## Speech recognition

`SpeechProviders.choose(context, language, allowSystem)` picks a provider at runtime:

| Provider | When | Where audio goes |
|---|---|---|
| `OnDeviceSpeechProvider` | Android 12+ (API 31) when `SpeechRecognizer.isOnDeviceRecognitionAvailable` is true. Checks language support with `checkRecognitionSupport` and offers `triggerModelDownload` when the language model is missing. | Stays on the phone (`createOnDeviceSpeechRecognizer`). |
| `SystemSpeechProvider` | Only when the person turns on **Settings → Voice → Allow the system speech service**, and on-device recognition is unavailable. The setting explains that the phone's speech service may send audio to its provider. | Depends on the phone's speech service. Never used silently. |
| None | Otherwise. | The sheet says voice isn't available on this phone and keeps typing available. |

The recognizer is created and destroyed on the main thread and cancelled when the sheet closes. Partial results are shown live; the final transcript is editable before anything runs.

**Not implemented:** the whisper.cpp local fallback described in the brief (section 8.2). It needs a native JNI build and a multilingual model download with on-device benchmarks; this was not done in this pass. Phones without on-device recognition use typed commands. Amount/date/category recognition accuracy has not been benchmarked on real devices.

## What the parser understands

Languages: English, Roman Urdu and Urdu script. Number words include "three hundred", "one thousand five hundred", "two lakh", "teen sau", "dedh hazaar", "do lakh", "تین سو". Urdu compound numbers 21–99 other than round tens are not yet in the lexicon; unknown words are reported, never guessed.

| Intent | Examples | Result |
|---|---|---|
| Add expense | "Add 300 rupees fuel expense for today", "Aaj fuel ka teen sau rupay expense add kar do", "آج ایندھن کا تین سو روپے کا خرچ شامل کر دو" | Editable preview (amount, category, date, merchant, note). Saves through `LedgerService.addExpense` with source VOICE and a unique execution key. |
| Query expenses | "Bring me my last 30 days expenses", "How much did I spend on groceries last month?", "Pichlay tees din ke kharchay dikhao" | Real totals from the ledger, per currency, with category and source breakdown and the exact date range shown (e.g. "25 Sep – 24 Oct"). |
| Find receipts | "Find the charger receipt from last week" | Receipt list from the receipt search. |
| Edit | "Change it to 350" | Only edits the visible draft or the add made a moment ago. |
| Undo | "Undo that expense" | Voids the most recent add from this sheet (`LedgerService.undo`). |
| Remind | "Remind me about this" (with a selected record) | Opens the reminder sheet. |

Rules that protect totals:
- "Last 30 days" means today and the 29 days before; "last month" means the previous calendar month. Both are tested.
- "kal" (yesterday or tomorrow in Urdu) is treated as yesterday only with past-tense context ("kal fuel par 500 rupay kharch kiye"); otherwise the sheet asks.
- When two amounts are possible (e.g. "add 300 and 3000 fuel today"), the sheet asks one question with options instead of saving.
- If the currency isn't said, the default currency from Settings is used and flagged in the preview.
- Unsupported requests get a plain "I can't do that yet" with examples, never a guessed action.

## Quick add

Off by default (**Settings → Voice → Quick add**). When on, an add is saved immediately with an Undo snackbar only if `QuickAddPolicy` finds: an explicit add word, exactly one amount, the currency and date said, a known category and no unknown words. Example chips always open the preview. Otherwise the preview card is shown.

## Double-count protection

Each execution uses a key kept in `rememberSaveable`, so rotation or a double tap returns the same expense (`AddResult.AlreadyExecuted`). If a receipt for a voice expense is scanned later, the duplicate check offers **Attach to it**, so the purchase is still counted once.

## Permissions

`RECORD_AUDIO` is requested only when the person taps the mic. A permanent denial shows a link to system settings, and typing stays available.
