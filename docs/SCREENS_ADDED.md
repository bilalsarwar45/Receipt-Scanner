# Screens added beyond the design handover

The handover (`docs/design/receipt-scan-handover.md`) covers capture, receipts, Library, Insights, tools, packs and settings. The brief requires more (voice and manual expenses, bills, budgets, reminders). These screens were designed during development from the same tokens and components (`core:design-system`) and follow the handover's rules: pill buttons, 16 dp cards, status always shown as text plus icon, amounts in tabular figures, validation on blur, undo instead of confirmation where the action can be undone.

| Screen | Where | Why it was needed |
|---|---|---|
| Command sheet (voice + typed) | Home pill and mic → `feature:commands` `CommandSheet` | Brief §8.1. Example chips, push-to-talk with live level bars, editable transcript, one clarification question at a time, editable add preview, real query results with date range and source filter, Undo. |
| Voice intro and mic states | Inside the command sheet | First-use explanation of where speech is processed; denied/unavailable states with typing fallback. |
| Library → Expenses tab | Library | Shows every counted expense from receipts, voice, manual entry and bill payments in one list, with its source. |
| Library → Bills tab | Library | Bills are separate from expenses; status badges Not paid / Due soon / Overdue / Part paid / Paid. |
| Library → Other scans tab | Library | Saved results from the 12 non-receipt tools. |
| Expense detail (E-Detail) | Tap an expense | Amount, source, linked receipt or bill payment, change history, refund, remove with undo, restore. |
| Add / edit expense (E-Form) | Home "Add manually", Library → Expenses | Manual entry with amount validation on blur, category chips, date, note. Counted with source "Entered by you". |
| Bill detail (B-Detail) | Library → Bills | Amount due, paid, left to pay, payments list, record payment dialog, remove payment, remind before due date. |
| Record payment dialog | Bill detail | Partial payments; over-payment blocked. |
| Refund dialog | Expense detail | Refunds count negative and cannot exceed the original. |
| Edit receipt (confirmed) | Receipt detail → Edit | The handover's R-Review is for new scans; this reuses it for saved receipts and updates the linked expense instead of adding another. |
| Saved scan detail | Library → Other scans | Readable label/value view of any module's stored payload; copy, share, scan again, delete with undo. |
| Document detail | Library → Documents | Open, share, show recognised text, delete with undo. |
| Search results | Top bar search | Grouped results across receipts, expenses, bills, PDFs (including their text) and saved scans. |
| Reminders list | Tools → More, Settings | Open reminders with due time and channel, mark done with undo, permission banner. |
| Budgets | Insights → Manage, Settings | Monthly limit per category or overall, progress, "spent · left" message, remove with undo. |
| Shopping list | Receipt detail → Shopping list, Tools → More | Pick receipt items to buy again, add typed items, tick, clear, share. |
| Food log | Tools → More, after the Calories tool | Per-day ≈ kcal and macros from logged estimates, with day navigation. |
| Settings pages: Language & currency, Receipt defaults, Voice & commands, Tips, Notifications, About | Settings | X-Settings lists them but only Appearance, Packs and Data were designed. About shows the APK signing certificate SHA-256 and flags the test key. |
| Delete everything (type DELETE) | Settings → Data | Irreversible, so it needs typed confirmation. |
| Onboarding (3 pages + 2 choices) | First run, Settings → Show the welcome again | Explains review-before-count and privacy; sets currency and date order. |
| Possible duplicate dialog with "Attach to it" | Receipt confirm | R-Duplicate covered receipt-vs-receipt; attaching to an existing voice/manual expense was added so the purchase counts once. |
