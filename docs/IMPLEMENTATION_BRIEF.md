# Claude Code Implementation Prompt — Receipt Scanner, Modular Scanner Suite and Voice Expenses

Read this entire brief, inspect the repository and available design handoff, then implement the Android application. This is an implementation assignment, not a request for another plan or a static prototype.

## 1. Mission, authority and source of truth

Build the receipt-focused Android application described in the accompanying **Claude_Design_Receipt_Scanner_Prompt.md**, using the approved Claude Design screens/assets when supplied in the repository or handoff.

The full scope includes **14 scanner capabilities**, excluding business cards, plus the voice and typed-command feature specified here.

Priority:
1. This implementation brief, including the explicitly added voice/manual-expense requirements.
2. The approved product decisions and design handoff.
3. The earlier design brief for remaining behavior and scope.
4. Repository conventions for implementation details.

The earlier instruction to stop after Home concepts applied to Claude Design. It does not require you to stop implementation at Home or ask for a second Home approval.

Preserve the approved Home hierarchy: receipt scanning remains primary. Implement all workflows with persistence, real processing/providers, recovery states and meaningful validation. Static screens, hardcoded AI answers and buttons that do nothing do not count as implemented features.

### Missing design is explicitly authorized

Voice input, typed commands, manual expenses and their impact on Insights are new requirements and may be absent from the supplied design.

**Design and implement these missing surfaces yourself during development.** Reuse the approved colors, typography, spacing, components and navigation. Add the minimum coherent UI needed, without sending the project back to Claude Design or waiting for another design round.

Use the same approach for routine missing error, permission, loading and correction states. Keep the design consistent and record additions in the handoff.

If no approved screen assets are available, use the design brief as the starting point and document that visual fidelity to an unseen final design cannot be verified. Continue with the functional implementation.

### Working style

- Inspect existing code, instructions, build tooling and assets before choosing an approach.
- Preserve working code and user changes. Do not overwrite the repository or perform destructive cleanup.
- Reuse existing infrastructure where sensible; do not rewrite a mature project just to match preferred libraries.
- Make reasonable implementation decisions and document them. Do not stop after planning or ask repeated questions about routine choices.
- Continue through the complete scope in working vertical slices.
- If an external model asset, device, credential or license is genuinely unavailable, finish independent work, provide the real integration/training path, and report the precise blocker honestly.
- Do not claim an unavailable asset is implemented through a fake result, random prediction or demo-only provider.
- Build locally and prepare reviewable artifacts. Store distribution/signing/publishing decisions for the product owner; no production release is requested.

## 2. Complete feature scope and proposed solutions

These are concrete starting approaches, not claims that every model is already available or validated. Verify current official SDK documentation, compatibility and the license of the exact code/weights/datasets you use.

| Capability | Proposed implementation | Important boundary |
|---|---|---|
| Receipt — primary | CameraX capture, image normalization, ML Kit Text Recognition v2, geometry-aware receipt parser, exact money arithmetic, local categorization, editable review and Room persistence. | OCR alone is not receipt line-item extraction. |
| Bills/invoices | Shared OCR infrastructure plus invoice/date/amount parser, explicit payment records and reminder scheduling. | Capturing an unpaid bill does not create a paid expense automatically. |
| PDF/documents | CameraX with edge detection/perspective correction and page tools; Android PDF generation/rendering APIs. A provider-owned ML Kit Document Scanner flow is an alternative where it matches the approved UI. | Provider-owned capture screens have customization constraints; no silent UI promises. |
| QR/barcode | ML Kit Barcode Scanning with supported format selection and typed result handlers. | Decoding a product code does not supply a product database. |
| Text/OCR | Shared ML Kit OCR engine with selection, correction, actionable entities, local indexing and export. | Validate supported scripts; do not advertise Urdu OCR through a Latin-only model. |
| Product/warranty | Barcode and label OCR, serial/model extraction, user-confirmed product record and receipt linking. | Product catalog lookup is optional and explicit; warranties are not invented. |
| Translation | ML Kit on-device translation with managed language packs and source-text review. | OCR language coverage and translation language coverage differ. |
| Nutrition/ingredients | Label OCR and structured nutrition-table/ingredient parsing; deterministic serving calculations. | Label facts, recognized text and AI explanation remain distinct. |
| Expiry dates | OCR plus date/type detection, locale-aware parsing and user confirmation. | Manufacturing, expiry and best-before dates have different meanings. |
| Math | OCR plus editable expression; a strict AST parser and deterministic supported solvers. Add a validated formula-recognition pack for richer layouts. | A general text recognizer is not a universal formula recognizer. |
| Object counter | MediaPipe/LiteRT object detector, initially benchmark EfficientDet-Lite0 or an appropriate validated detector; count filtered detections inside the selected region, then allow marker correction. | Supported classes and crowded-scene performance are limited by the selected model. |
| Plant identification | A specialist, commercially usable species classifier with matching label/taxonomy data, exported to a compatible mobile runtime; top-k matches and manual confirmation. | A generic ImageNet classifier is not a reliable species identifier. |
| Food/calories | A specialist food classifier plus user-confirmed portions and an offline nutrition database derived from a suitable USDA FoodData Central subset. | Food recognition, portion estimation and nutrient calculation are separate tasks. |
| Wi-Fi | Android Wi-Fi APIs for permitted network data; QR decoding for Wi-Fi credentials and supported system connection controls. | Follow OS permission/throttling restrictions; show stale results honestly. |
| Voice and typed commands — new | On-device speech provider, editable transcript, deterministic intent/slot parser, validated command objects and application use cases; optional local language-model assistance. | A language model never writes SQL, invents totals or commits arbitrary actions. |

All 14 scanner capabilities remain in scope. Organize related entries in Tools without merging their engineering boundaries.

## 3. Technology and project setup

For a new native Android project, prefer:
- Kotlin, Jetpack Compose and Material 3.
- Unidirectional UI state with ViewModels, Coroutines and Flow.
- Room for structured local data and appropriate FTS indexing.
- DataStore for ordinary preferences.
- Hilt or an equivalent already-established DI approach.
- CameraX for custom camera flows.
- WorkManager for deferrable jobs and persistent work.
- Android Photo Picker / Storage Access Framework / FileProvider as appropriate.
- Kotlin serialization or an existing equivalent for typed contracts.
- A version catalog and compatible, pinned dependency versions.

Respect the existing project if it uses a different proven native Android structure.

For a new app, start by evaluating minSdk 26; confirm the actual minimum supported by selected dependencies. Use the current stable compile toolchain and target required for distribution at implementation time. Do not casually raise the whole app's minimum SDK to enable one optional advanced provider.

Base features must remain usable on devices without Gemini Nano/AICore or a suitable platform speech model.

Use one preferred mobile vision runtime where possible. Do not bundle LiteRT, ONNX Runtime, multiple duplicate vision stacks and a local LLM just because they are candidates. Additional runtimes belong behind an actual measured feature requirement.

For native libraries and downloaded providers:
- Check supported ABIs.
- Verify Android 16 KB page-size compatibility.
- Test native loading on supported real/device-emulator architectures.
- Provide a safe unavailable-provider state rather than an UnsatisfiedLinkError crash.
- Avoid downloading executable code; use versioned model/data assets with vetted runtime code included through the normal build.

Keep dependency, model and data notices with the project. Code license, model-weight license and training-data rights must be checked separately.

## 4. Module architecture and reuse

Make the scanners reusable Android library modules, not merely different packages inside one app.

A possible layout:
- :app — navigation, app-specific integration and composition root.
- :core:scanner-contracts — neutral inputs, outcomes and host capabilities.
- :core:design-system
- :core:capture
- :core:ocr
- :core:barcode
- :core:ml-runtime
- :core:model-packs
- :core:money-time
- :core:testing — meaningful shared test utilities.
- :data:local — host persistence.
- :domain:expenses — canonical expense/query use cases.
- :domain:receipt-actions — host-specific purchase actions.
- :scanner:receipt
- :scanner:invoice
- :scanner:qr
- :scanner:text
- :scanner:product
- :scanner:translation
- :scanner:nutrition
- :scanner:expiry
- :scanner:math
- :scanner:counter
- :scanner:plant
- :scanner:food
- :scanner:wifi
- :feature:documents — integrated PDF/document feature; external SDK packaging is not required.
- :feature:voice-input — reusable recording/transcript UI and speech-provider adapter.
- :feature:commands — intent/slot handling with host action registration.
- :feature:library
- :feature:insights
- :samples:scanner-host — a minimal second app proving module reuse.

Adjust module granularity to avoid empty scaffolding. Retain the independent scanner boundaries.

### Dependency rules

- A scanner must not depend on :app, another unrelated scanner, this app's receipt database, monetization or global bottom navigation.
- Shared OCR/barcode/capture engines are dependencies, not copied implementations.
- The host injects theme, capture/result sinks, save behavior, provider capabilities and navigation callbacks.
- Modules return their own typed payloads through a neutral contract.
- Do not put every feature-specific type into one central sealed hierarchy that forces every host to depend on every scanner.
- Prefer generic contracts or neutral envelopes with feature-owned, versioned payloads.
- Use URI/stream/file-handle boundaries rather than passing huge bitmaps through navigation or Parcelable bundles.
- The host controls app-level persistence. A module can request temporary storage through a narrow interface.
- A host can disable optional actions/providers without breaking the scan flow.

Example conceptual contract:
~~~kotlin
interface ScannerEntry<I, O> {
    val capabilityId: String
    // Actual Compose/activity integration must support host theming,
    // cancel/back, validated inputs and typed completion.
}

data class ScanEvidence(
    val sourceId: String,
    val pageIndex: Int?,
    val region: EvidenceRegion?
)
~~~

Keep actual APIs practical and documented. The excerpt is illustrative, not a mandate for empty interfaces.

Prove reuse in the sample host:
- Launch QR, Text and one model-backed scanner through only their public APIs.
- Build the remaining modules independently and exercise their contracts.
- The sample must not import the main app or expense ledger.
- Document installation/dependency setup, inputs, outcomes, temporary-file ownership and versioning.

## 5. Canonical local data model — prevent double counting

Voice/manual entry adds a capability beyond scanned receipts. Therefore, **expense queries must use one canonical expense ledger**, rather than adding independent receipt totals to independent voice totals.

Keep evidence, extracted records and financial entries separate.

Suggested entities:
- SourceDocument / SourceImage / ScanSession.
- Receipt and ReceiptItem, including raw OCR evidence and normalized fields.
- Invoice and PaymentRecord.
- ExpenseEntry, ExpenseAllocation and ExpenseSourceLink.
- ProductRecord and WarrantyRecord.
- Reminder and SuggestedAction.
- NutritionLabelRecord, FoodLog and ExpiryRecord.
- TranslationRecord, MathRecord, CountRecord, PlantRecord and other saved scan results.
- ModelPackRecord and capability state.
- CommandExecution with idempotency key and outcome.
- CategoryRule / MerchantAlias / ItemAlias / user correction history.
- SearchIndexMetadata, including provider/model version.

### Expense representation

At minimum include:
- Stable ID.
- Exact amount and currency.
- Effective local date and relevant timestamp/timezone information.
- Category and optional merchant/note.
- Source: scanned receipt, voice or manual.
- State: draft / confirmed / voided, with clear edit history or equivalent.
- Links to supporting receipt/payment evidence.
- Kind, such as purchase expense or refund, with explicit sign semantics.
- Idempotency/duplicate information.

Use integer minor units for stored money and BigDecimal/rational arithmetic for parsing, quantities, rates and intermediate calculations. Never use Float/Double as the financial source of truth. Respect each currency's fraction digits, rounding rules, overflow and invalid values.

PKR 300.00 is 30,000 minor units. Do not treat every currency as having two decimal places.

### Reconciliation rules

- A confirmed purchase receipt can create one linked confirmed expense.
- An imported/extracted receipt still needing review is excluded from confirmed expense totals.
- A voice/manual expense can exist without a receipt.
- Attaching a later receipt to that expense enriches the same purchase instead of creating a second expense.
- Editing/deleting/voiding either side must update links and derived totals transactionally.
- Matching amount/date/merchant suggests a duplicate; it does not silently merge real purchases.
- A captured unpaid invoice creates a bill record, not a paid expense.
- Recording a bill payment creates or links the corresponding expense once. Partial payments remain separate linked records with a remaining balance.
- Attaching a payment receipt to an existing bill payment must not count it again.
- Refunds affect the refund's effective date and link to the original purchase when known.
- Document-only scans and estimated food calories never become expenses automatically.

Use real Room transactions and database constraints, not only UI flags. Derived summaries must update after edits, merges, refunds and undo.

### Date and currency semantics

- Use an injectable Clock and a user-visible reporting timezone; default to the device timezone, with Asia/Karachi used in examples/tests.
- Keep purchase date, scan date, invoice due date and payment date separate.
- Query expenses by their effective expense date, not the scan/import date.
- “Last 30 days” means today plus the previous 29 local calendar dates. Show the resolved date range.
- “Last month” means the previous calendar month.
- “This month” begins on the first local day of the current month.
- Use proper local-date-to-instant boundaries where timestamps are queried; do not subtract fixed milliseconds across DST changes.
- Never sum different currencies into one number without an explicit supported conversion. Default to separate currency groups.

## 6. Receipt recognition and purchase intelligence

### Capture and preprocessing

Implement:
- Camera and image/PDF import.
- EXIF orientation correction.
- Blur, glare and crop guidance.
- Edge/perspective correction where appropriate.
- Downsampled processing copies with source-quality originals retained according to policy.
- Several receipts in a session as separate records.
- Several images of one long receipt as one record.
- Cancellation, retry, process recreation and cleanup of temporary files.

Use bounded image sizes and page-at-a-time PDF processing. A 30-page import must not decode every page at full resolution into memory.

Do not promise seamless long-receipt stitching through naive image concatenation. Preserve segments, overlap evidence and a recoverable review flow.

### Receipt parser

Build a geometry-aware pipeline:
1. Read recognized text, bounding boxes, page/segment, script/language and source evidence.
2. Normalize Unicode, whitespace, decimal separators and currency symbols without discarding the raw text.
3. Group lines and detect price/quantity columns.
4. Identify merchant/date/reference candidates.
5. Detect item rows, wrapped descriptions and quantity/unit-price/line-total patterns.
6. Distinguish subtotal, total, discount, tax, change, tendered cash and payment metadata.
7. Reconcile candidate line sums and receipt total with explicit tax-included/discount semantics.
8. Return partial structured results and review reasons.
9. Let user corrections override extraction, preserving provenance.
10. Save only confirmed values into canonical expense records.

Cash tendered and change are not extra expenses. Repeated total text is not another purchase line. A partial parse is acceptable; an invented item is not.

Use locale-specific keyword dictionaries, amount/date parsers and correction-learned aliases. Make behavior testable against real, licensed or synthetic receipt fixtures representing local and international formats.

For unsupported scripts, add a separately evaluated OCR provider only after validating its actual Android inference path and commercial suitability. Do not assume a framework's general multilingual claim covers Urdu receipts accurately.

### Categories and useful actions

Start with deterministic local categories:
- Merchant/category rules.
- Normalized item keywords.
- User corrections and scoped aliases.
- Suggested categories with a fallback “Uncategorized.”

Do not retroactively recategorize all history without an explicit action.

Actions must be real end-to-end features:
- Category editing.
- Item-based bill splitting.
- Shopping list creation.
- Own-history item price comparisons.
- Product/warranty linking.
- Confirmed date reminders.
- Expense collection and CSV/PDF report export.
- Search/questions about saved receipts.

For bill splitting, allocate shared items, taxes, discounts, service charges and tips explicitly. Use an exact remainder-allocation algorithm so participants' totals equal the final bill to the minor unit.

For price comparisons, use confirmed item matches and normalize quantity/pack size. Report the source records; do not present a pack-size difference as a price increase.

## 7. Implement each scanner completely

### 7.1 Bills/invoices
Use shared OCR plus a dedicated parser for supplier, invoice number, issue/due dates, items, taxes and payable amount. Persist review states, payment status and payment history. Implement duplicate review and date reminders. Capture a paid status only when evidenced or confirmed.

### 7.2 PDF/documents
Provide document, whiteboard and book modes; multipage crop/rotate/enhance, page reorder/add/remove, naming, folders and export. Use Android-supported PDF rendering/generation with bounded page sizes. Searchable PDF requires a real OCR text layer; an image-only PDF must not be labeled searchable. Choose a compatible PDF library only if platform APIs are insufficient and its exact license is suitable.

Handle encrypted/unreadable PDFs, import cancellation, low storage and export failure. Do not request broad storage access for ordinary imports/exports.

### 7.3 QR/barcode
Share one decoding engine across the QR and Wi-Fi/product entry paths. Parse typed results, preview links and require a deliberate Open action. Show the raw product code when no product record is available. Prevent repeated frame detections from opening the same result repeatedly. Keep Wi-Fi passwords masked.

### 7.4 Text/OCR
Provide editable text, source-image selection, find/copy/share/export and actionable URL/phone/email recognition. Hand selected text to Translation through its public contract. Validate script support. Do not confuse touchscreen digital-ink recognition with handwriting OCR from a photograph.

### 7.5 Product/warranty
Extract barcode/model/serial text; let users confirm product identity and link purchase evidence. Persist warranty/return terms only from evidence or user entry. Support missing catalog data with editable fields. Optional online catalog lookups must be explicit and isolated from local scanning; no receipt upload by implication.

### 7.6 Translation
Use ML Kit's local translation provider with explicit language-pack management. Preserve recognized source text and language overrides. Handle unavailable recognition versus unavailable translation separately. If read-aloud is offered, verify local TTS/voice availability and disclose any online-only voice rather than silently using it.

### 7.7 Nutrition/ingredients
Parse nutrition-table columns and units, including per-100 g/ml, per-serving and per-pack presentations. Calculate from label values and a confirmed quantity using exact decimal arithmetic. Preserve the ingredient and allergen statements and OCR evidence. Highlight requested ingredients only from recognized text; “not found” does not establish “allergen-free.” Add on-demand plain-language explanation without changing label facts.

### 7.8 Expiry dates
Recognize date type and candidate values. Prompt for ambiguity such as 04/05, incomplete years and multiple printed dates. Do not infer expiry from a manufacturing date without confirmed shelf life. Persist product/date/reminder records and allow corrections/cancellation.

### 7.9 Math
Implement a strict tokenizer/parser and typed expression AST. Never evaluate recognized text as code or use unrestricted eval.

Baseline supported operations should include:
- Arithmetic with parentheses and decimal/fraction inputs.
- Percentages, powers and square roots within a documented numeric domain.
- Single-variable linear equations.
- Quadratic equations where the implementation can verify results.
- Clear division-by-zero, invalid-domain and unsupported-expression states.

Use rule-generated explanation steps for supported solvers and verify results by substitution/numeric checks where appropriate.

Printed text OCR may cover simple linear expressions. Rich formulas, stacked fractions and handwriting require a specialist recognition pack. Evaluate a candidate such as PP-FormulaNet only after checking exact artifact size, runtime conversion, tokenizer, license and Android benchmarks. Its desktop availability is not proof of mobile readiness.

Provide an expression editor so users can correct recognition. Deliver supported math honestly; list richer recognition blockers rather than substituting unverified LLM solutions.

### 7.10 Object counter
Benchmark a real detector such as EfficientDet-Lite0 with its actual class list. Filter detections by class and selected region, apply the correct postprocessing/NMS, and render aligned markers.

Allow manual add/remove/undo. Store detected count, user corrections and confirmed count. Test overlaps, cropped objects, empty scenes and rotated images. General “count anything” and dense identical-object counting require a different validated model; do not claim them from a generic detector.

### 7.11 Plants
Integrate a specialist plant model with a matching species-label map and taxonomy. Prefer a compact mobile classifier such as an appropriately trained MobileNetV3/EfficientNet-Lite architecture; architecture choice is not a substitute for trained weights.

If no suitable redistributable pretrained asset is available:
- Search authoritative sources/model cards for a legitimate alternative.
- Provide a reproducible training/fine-tuning/export/quantization pipeline and document the dataset requirements.
- Use properly permitted training/validation data.
- Keep the real mobile inference adapter and corrected UI operational.
- Explicitly mark the missing species model as a blocker; do not claim an ImageNet label, embedding guess or random output is a validated plant identity.

Return top-k candidates and support another photo/manual confirmation. Care data must come from an attributed local dataset or clearly sourced provider, not fabricated facts. Avoid edibility/disease-diagnosis guarantees.

### 7.12 Food/calories
Implement:
1. Food image classification or supported component detection.
2. User correction/selection of food components.
3. User-confirmed portion, weight, preparation and additions.
4. Mapping to local nutrition records.
5. Deterministic calorie/macronutrient calculation.
6. Optional user-confirmed consumption log.

Use a curated, versioned offline subset of USDA FoodData Central or another appropriately permitted nutrition dataset. Preserve food IDs, units, preparation variants and data provenance. Local dishes such as biryani or roti may need reviewed recipe records; do not assume a generic class establishes a fixed nutritional value.

A specialist food classifier also needs legitimate weights and validation. Supply training/export tooling if necessary. Food classification alone cannot estimate exact portion mass, hidden oil or recipe composition.

Keep Nutrition Label and Food Photo modules independent but share nutrition calculations/data contracts. Purchased food must not automatically become consumed food.

### 7.13 Wi-Fi
Use API-specific OS permission handling and supported Android connection flows. Distinguish Nearby networks from Scan Wi-Fi QR. Show actual available SSID/signal/security/band details, last-refresh status and stale results. Handle disabled settings, denial, API limitations, no results and scan throttling. Never claim unknown-password recovery or guaranteed network safety.

## 8. Voice and typed input — mandatory new feature

The same command engine must support:
- Spoken input.
- Typed natural-language commands.
- Accessible manual forms as a fallback.

Examples required by the product owner:
- “Bring me my last 30 days expenses.”
- “Add 300 rupees fuel expense for today.”

Also support practical variations:
- “Show my fuel expenses this month.”
- “How much did I spend on groceries last month?”
- “Find the charger receipt from last week.”
- “Add 1,500 rupees groceries for yesterday.”
- “Aaj fuel ka teen sau rupay expense add kar do.”
- “Pichlay tees din ke kharchay dikhao.”
- “آج ایندھن کا تین سو روپے کا خرچ شامل کر دو”
- “Undo that expense.” within an explicit recent-command context.
- “Change it to 350.” within the currently visible draft/recent-add context.

Implement English first as a complete path. Build Urdu-script and Roman Urdu text normalization/intent aliases, then validate actual Urdu/code-switching audio with the selected provider. Do not advertise fluent multilingual speech merely because a model is labeled multilingual.

### 8.1 Voice UI you must design during development

Add a compact microphone entry near the Home/search command surface and relevant Library/Insights search surfaces. Receipt Scan remains the primary Home action.

Use a bottom sheet or another consistent, accessible compact surface with:
- Short first-use benefit: “Ask about expenses or add one by voice.”
- Example chips for the two required commands.
- Push-to-talk control.
- Listening, stopped, transcribing, interpreting and result states.
- Editable transcript.
- Cancel and retry.
- Typed input alternative.
- Language/provider setup when needed.
- One concise clarification when a required field is ambiguous.
- An editable expense preview with category, amount, currency, date and optional note.
- Query results as real records and totals, not a decorative chatbot answer.

Use real audio level feedback if showing a waveform. Do not animate “listening” after the microphone has stopped.

Request RECORD_AUDIO only when the user invokes voice. Stop recording on cancellation/navigation and manage audio focus/interruption. No background hotword/continuous listening is required. Discard raw audio after processing by default.

### 8.2 Proposed speech-provider strategy

Use a narrow SpeechToTextProvider abstraction with runtime capability discovery, supported languages, model readiness, cancellation and structured errors.

Primary path:
- Use Android's explicitly on-device SpeechRecognizer when the API and device support it.
- Check availability before createOnDeviceSpeechRecognizer.
- Query language/model support and use supported model-download APIs where available.
- Respect main-thread/lifecycle requirements and destroy the recognizer correctly.
- A generic recognizer plus EXTRA_PREFER_OFFLINE is not proof of local-only processing.

Fallback path:
- Integrate a genuine local engine such as whisper.cpp through a well-maintained Android/JNI adapter.
- Evaluate multilingual tiny/base models with appropriate quantization.
- Choose the model through measured accuracy, latency and memory on target phones.
- Multilingual Urdu requires multilingual weights, not an English-only .en model.
- Download the selected model only when needed; show actual size and local storage requirements.
- Keep processing local and support cancellation.

The newer ML Kit on-device Speech Recognition API is an alternative to evaluate behind the same interface. Check its current stability, device/language coverage and release status. Do not make an alpha/provider-specific capability the only path to a core feature.

If local speech is unavailable, typed commands/manual entry remain operational. Do not silently invoke cloud transcription.

Benchmark recognition of **amounts, dates and category words**, not only general transcription quality. “300” versus “3,000” is a significant command error.

### 8.3 Intent and slot parsing

Do not require a large language model for the common commands.

Use a layered local parser:
1. Normalize transcript/text and Unicode numerals.
2. Recognize command intent with deterministic patterns/grammar and aliases.
3. Extract and validate slots.
4. Resolve dates/categories/currencies through domain code.
5. Clarify missing/ambiguous fields in the UI.
6. Create a typed command.
7. Execute through application use cases with an idempotency key.

Minimum intents:
- QueryExpenses.
- SummarizeExpenses.
- FindReceipts or FindPurchasedItems.
- AddExpense.
- EditPendingExpense or an explicitly identified recent expense.
- UndoRecentCommand.
- CreateReminder from a selected/evidenced record, with date confirmation.

Support:
- Amounts as digits and common spoken number forms.
- Rupees/rupee/PKR/rs and user-default currency, visibly confirmed in the result.
- English, Urdu and Roman Urdu category aliases such as fuel/petrol.
- Today, yesterday, last N days, this month, last month and explicit dates.
- Common local number words and lakh/crore forms only where actually implemented and tested.
- Merchant/category/date/source filters.
- Follow-ups bound to the active visible context.
- A response indicating an unsupported command rather than guessing.

Words such as “kal” and “parson” may be date-ambiguous. Resolve through explicit context when reliable; otherwise ask a short question with concrete date choices.

Do not use speech confidence as if it were a calibrated financial correctness guarantee. Validate extracted amounts independently and display them clearly.

### 8.4 Typed command examples

These JSON objects illustrate the domain boundary. Dates shown are from a frozen Asia/Karachi test clock on 24 October 2026; production dates must be resolved at runtime.

~~~json
{
  "type": "AddExpense",
  "amountMinor": 30000,
  "currency": "PKR",
  "categoryId": "fuel",
  "effectiveDate": "2026-10-24",
  "source": "voice",
  "note": null,
  "executionKey": "unique-per-confirmed-command"
}
~~~

~~~json
{
  "type": "QueryExpenses",
  "startDateInclusive": "2026-09-25",
  "endDateExclusive": "2026-10-25",
  "currencyGrouping": "separate",
  "states": ["confirmed"],
  "sources": ["receipt", "manual", "voice"]
}
~~~

Use actual sealed/domain types and validated enums internally. These examples must not be routed as unrestricted JSON into the database.

### 8.5 Add/edit/undo behavior

Default:
- Parse the command.
- Show an editable compact confirmation card.
- The user taps **Add expense**.
- Commit one transaction.
- Show “Added PKR 300 · Fuel · Today” with Undo.
- Refresh Home/Insights/query results from the same canonical ledger.

Also implement an optional **Quick add** setting:
- Explain once that fully specified explicit add commands can save immediately.
- Only save when intent/amount/currency/date/category resolution meets documented deterministic rules.
- Show the saved amount/date and immediate Undo.
- Ambiguous recognition or missing fields still opens the review card.
- A vague utterance or an example suggestion is never an automatic write.

Never save partial transcripts. Duplicate final callbacks, screen rotation, retries and restored UI state must not create duplicate expenses. Enforce an execution key transactionally.

Editing “it” applies only to an identified current draft or visibly scoped recent command. Undo targets its recorded command effect and must update totals exactly once. Broad deletion is not inferred from ambiguous speech.

Manual entry must use the same validation and expense use case, support receipt attachment later, and mark its source accurately.

### 8.6 Expense query behavior

“Bring me my last 30 days expenses” must:
1. Resolve the local date window.
2. Query confirmed canonical expenses.
3. Include receipt, manual and voice entries once each.
4. Exclude drafts, unpaid bills and voided entries.
5. Group currencies separately.
6. Show the resolved range, record count, total and actual matching records.
7. Provide category/source filters and drill-down.
8. Explain the scope as recorded expenses, not complete bank spending.

All totals come from deterministic database/domain calculations. If nothing matches, say so. Do not fabricate an answer from general knowledge or ask a language model to estimate the user's spending.

### 8.7 Optional local language-model assistance

The parser can later use an optional compact local model for paraphrase-to-intent mapping or grounded explanations.

Candidate: Qwen3-0.6B, after checking the exact artifact/license and a verified Android runtime/quantization path. It is a candidate, not a promise of reliable tool use or Urdu accuracy.

Other supported devices may use an appropriate AICore/ML Kit Prompt provider through the same capability boundary.

Rules:
- Optional, on demand and not needed for the two required voice commands.
- Prefer constrained schema/grammar output where supported.
- Revalidate every generated field.
- Reject unsupported actions, extra fields, invented IDs and out-of-range values.
- Never execute model-produced SQL/code.
- Treat receipt text as data, never as instructions.
- Numeric summaries are calculated first; any explanation is grounded in those values and source IDs.
- Preserve typed/deterministic behavior when the model is unavailable, quota-limited or fails.

## 9. Search, retrieval and insights

Implement structured/FTS search first:
- Merchant, item, note, receipt text, date, amount, category and source.
- Locale normalization and user-managed item/merchant aliases.
- Incremental updates on save/edit/delete.
- Source links and highlighted evidence.

Optional semantic retrieval:
- Evaluate an appropriate compact embedding model, such as multilingual-e5-small, with a verified export/tokenizer/runtime.
- Follow the chosen model's documented preprocessing, pooling and query/document prefixes.
- Measure quantization effects and actual target-language retrieval quality.
- Preserve model version, index version and rebuild/migration strategy.
- For a modest local collection, a bounded similarity scan may be simpler than adding a native vector database.
- Never use embedding similarity to calculate financial totals or identify duplicates without review.

Insights must include confirmed scanned plus manual/voice expense entries, labeled accordingly. Support category totals, recorded spending over time, own-history item prices and a useful no-data state.

Implement user-set budget thresholds and local notifications based on recorded expenses. Forecasting should use a transparent baseline such as recent daily averages or same-period trends, require sufficient history and disclose incomplete records. Do not present a generic generated forecast as fact.

## 10. Model/data pack lifecycle

Implement a central pack manager with:
- Pack ID, purpose, version and provider.
- Immutable HTTPS asset location and expected digest.
- Exact size, required free space and supported runtime/ABI.
- License/notice and model/data provenance.
- Tokenizer, label map, normalization and matching compatibility metadata.
- Available / not downloaded / downloading / verifying / ready / failed / unavailable states.
- Cancellation/retry and real progress.
- Atomic installation and validation.
- Protection against corrupt archives/path traversal.
- Compatibility checks and safe rollback after failed upgrades.
- User-controlled deletion and storage reporting.
- Reference-counted/owned runtime sessions and memory release.

Distinguish app-supplied downloadable packs from platform-managed models. Do not pretend to delete or relocate a model that the platform controls.

Pack setup must not block the launch splash or receipt core unnecessarily. Avoid automatically downloading every model. Prefer the smallest model meeting measured task needs.

Document what works:
- On a fresh install with no optional packs.
- After OCR/provider setup.
- After each specific pack is installed.
- In airplane mode.
- On a device missing optional advanced capabilities.

Do not promise a fixed app/model size before measuring it.

## 11. Education, permissions and accessibility

Follow the approved design and add concise contextual education:
- First receipt scan: flatten receipt, show total, avoid glare.
- Review: explain highlighted fields.
- Long receipt: explain the real multi-image capture method.
- Voice: example commands, transcript editing and saving behavior.
- Model setup: benefit, actual size and offline readiness.
- Nutrition: serving versus whole pack.
- Calories: portion confirmation and estimate status.
- Math: confirm the equation.
- Counter: correct markers.
- Plant: take a useful second image when needed.
- Wi-Fi: explain network versus QR modes and OS requirements.

No mandatory long tutorial before scanning. Remember dismissed tips and make help accessible again.

Implement:
- Contextual permissions and permanent-denial recovery.
- At least 48 dp touch targets.
- Font scaling, contrast, TalkBack labels and logical focus.
- Labels plus icons for unfamiliar tools.
- State text/icons in addition to colors.
- Dark theme, reduced motion and realistic Android back/keyboard behavior.
- Responsive phone layouts and a practical larger-screen adaptation.
- Accessible manual alternatives to camera/voice where possible.

No pricing, advertising placements or subscription limits have been specified. Do not invent product pricing or gate essential workflows behind an unrequested paywall.

## 12. Privacy, reliability and performance

- Local processing and app-private storage by default.
- Keep sensitive financial data, receipt images, audio and transcripts out of logs/crash breadcrumbs.
- Do not retain raw voice audio by default.
- Do not include shared production secrets/API keys in the client.
- External lookup/share actions are explicit and use the smallest necessary data.
- Use content URIs and time-limited grants appropriately.
- Respect Android backup behavior for sensitive data and keys.
- If file/database encryption is implemented, use an appropriate maintained solution with Android Keystore key management and a tested recovery/migration policy. Never store a local encryption key beside its ciphertext or invent cryptography.
- Clean up failed/cancelled temporary files.
- Keep database/file operations transactional or recoverable.

Performance:
- No OCR/model loading/database work on the main thread.
- Close CameraX frames promptly; use appropriate backpressure.
- Allow only bounded concurrent heavy inference.
- Release camera, microphone and native sessions with lifecycle/cancellation.
- Avoid holding full-size image/PDF batches in RAM.
- Use CPU fallback when an accelerator is unavailable.
- Benchmark cold start, scan latency, voice latency, peak memory, ANRs and native loads on representative phones.
- Test graceful behavior under low memory/storage and interrupted work.

Reminders should use the appropriate OS-supported scheduling mechanism. Distinguish a date reminder from a guaranteed exact-time alarm and do not claim exact delivery where the OS does not provide it.

## 13. Meaningful validation and required scenarios

Use tests to verify financial correctness, parser behavior, migrations, provider contracts and user workflows. Avoid large numbers of tests that merely repeat implementation details.

### Financial/domain tests
- Exact money parsing across separators and currency fraction digits.
- Quantity/tax/discount reconciliation.
- Split-bill totals including remainders.
- Receipt/voice/manual linkage without duplicate expenses.
- Partial bill payments and attached payment receipts.
- Refund and undo semantics.
- Mixed currencies kept separate.
- Editing a linked receipt updates the canonical expense exactly once.
- Drafts/voided entries excluded from totals.
- Database upgrades preserve records and constraints.

### Receipt/scanner fixtures
- Clear, blurry, cropped and long receipts.
- Wrapped item descriptions and multiple taxes.
- Cash tender/change versus purchase total.
- Partial OCR and missing totals.
- Barcode decoding versus absent product metadata.
- Nutrition per-serving/per-100 g/unit conversion.
- Expiry date ambiguity.
- Supported/unsupported math and checked solutions.
- Counter markers aligned after crop/rotation.
- Plant/food unknown classes and low-quality input.
- Offline provider/data-pack behavior.

### Voice/command tests

Freeze the Clock in Asia/Karachi on 24 October 2026 for these examples:
1. Required 30-day query resolves 25 September–24 October inclusive.
2. “Add 300 rupees fuel expense for today” → PKR 300.00, Fuel, 24 October.
3. “Add 1,500 rupees groceries for yesterday” → PKR 1,500.00, 23 October.
4. Roman Urdu and Urdu-script versions map to the same validated command where supported.
5. Ambiguous amount/date produces a clarification, not a save.
6. Duplicate final ASR callbacks create at most one expense.
7. Cancelling/retrying recording does not persist a partial result.
8. “Change it to 350” edits only the active scoped item.
9. Undo excludes the correct expense and refreshes every total.
10. Missing microphone permission and absent speech pack preserve typed input.
11. A later receipt attachment does not double count a voice expense.
12. No-network tests prove the claimed local speech/command path.
13. Read-only queries never modify data.
14. Recognition of 300 versus 3,000 is evaluated with real audio, not just parser strings.
15. “Last month” and “last 30 days” produce different correct calendar ranges.
16. DST tests in another timezone verify local date boundaries.

Keep recognition-quality evaluation separate from intent-parser tests. A mocked transcript does not establish speech accuracy.

### Integration and device checks
- Complete receipt capture/import → correction → save → expense query.
- Every scanner's primary flow and meaningful recovery path.
- Voice add → query → attach receipt → undo/edit.
- Navigation/process recreation and unsaved edits.
- Model download interruption/corruption/version mismatch.
- Native library compatibility, including 16 KB page-size checks.
- Minimal second-host integration without app/ledger dependencies.
- Font scaling, dark mode and TalkBack on critical flows.

Run appropriate Gradle compilation, unit tests, lint and available instrumentation checks. Build an installable debug APK. If a physical device/emulator/provider is unavailable, report that limitation accurately and supply the relevant repeatable verification steps.

Never report a test as passed if it was not run.

## 14. Execution sequence — keep implementing

Start by creating a concise feature-to-design-to-provider checklist and verifying the repository builds. Then work through:

1. **Foundation:** app shell, approved design tokens, navigation, contracts, canonical ledger, storage and migrations.
2. **Receipt vertical slice:** capture/import, real OCR, parser, correction, persistence, expense creation and Library.
3. **Voice/manual vertical slice:** design missing UI, implement typed commands, on-device STT adapter, local fallback, add/query/edit/undo and unified Insights.
4. **Deterministic utility scanners:** invoices, PDF, QR, Text, Product, Translation, Nutrition, Expiry and Wi-Fi.
5. **Specialist model scanners:** Math recognition extensions, Counter, Plant and Food, including real assets, conversion/training tooling where needed and benchmarks.
6. **Purchase intelligence:** categorization, shopping lists, splitting, own-history comparisons, warranty/reminders, semantic search and budget/forecast logic.
7. **Hardening:** education, permissions, accessibility, memory/performance, cross-host reuse, migrations and full validation.

Build and verify each slice before expanding. Continue through all stages; do not end after the checklist or after a visually convincing Home.

Maintain a resumable **IMPLEMENTATION_STATUS.md** with:
- Completed functionality.
- Exact active work.
- Actual test/build commands and outcomes.
- Remaining feature/provider work.
- Model/data provenance and blockers.
- The next concrete steps.

If context is exhausted, resume from this state rather than restarting or reducing scope.

## 15. Definition of done and deliverables

Deliver:
- Working Android source.
- Installable debug APK and reproducible build instructions.
- All scanner modules and second-host reuse example.
- The added voice/typed/manual-expense UI integrated with the approved design.
- Real provider integrations and versioned model/data pack manifests.
- Training/export/quantization scripts when they are necessary for specialist assets.
- Database schema/migrations and tested financial invariants.
- Source-attributed, deterministic expense queries and calculations.
- Relevant tests and an honest validation report.
- Architecture/module-integration documentation.
- MODEL_AND_DATA_REGISTER.md listing exact sources, revisions, licenses, formats, inputs/outputs and benchmarks.
- A concise list of screens added because they were missing from design.
- A feature completion matrix distinguishing working verified capability, working limited scope, and genuinely blocked external assets.

A manual correction flow is valuable, but it does not by itself establish that an AI scanner is complete. An inference wrapper without real usable weights is not complete plant/food recognition. A model file downloaded successfully is not evidence of accuracy.

Finish all work that can be completed, identify exact remaining external requirements if any, and make the result concrete and reviewable.

**Begin by inspecting the repository and design handoff, then implement. Do not stop at a proposal.**

---

## Official technical starting references

Check current documentation and exact artifact versions during implementation. Candidate models require separate mobile benchmarking and validation.

### Android and scanning
- [Android architecture](https://developer.android.com/topic/architecture)
- [CameraX](https://developer.android.com/media/camera/camerax)
- [Room](https://developer.android.com/training/data-storage/room)
- [ML Kit text recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2)
- [ML Kit document scanner](https://developers.google.com/ml-kit/vision/doc-scanner)
- [ML Kit barcode scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)
- [ML Kit translation](https://developers.google.com/ml-kit/language/translation)
- [Android Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan)
- [Android Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
- [Android 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)

### Voice and local language capabilities
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [RecognitionSupport](https://developer.android.com/reference/android/speech/RecognitionSupport)
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
- [whisper.cpp model documentation](https://github.com/ggml-org/whisper.cpp/tree/master/models)
- [ML Kit on-device speech recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android)
- [ML Kit GenAI support](https://developers.google.com/ml-kit/genai)
- [ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- [Qwen3-0.6B — candidate model card](https://huggingface.co/Qwen/Qwen3-0.6B)

### Vision, retrieval and nutrition
- [MediaPipe Object Detector](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector)
- [Object detection for Android](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector/android)
- [LiteRT](https://developers.google.com/edge/litert)
- [PaddleOCR — evaluate specific formula/OCR artifacts](https://github.com/PaddlePaddle/PaddleOCR)
- [PP-FormulaNet documentation — mobile suitability still requires validation](https://paddlepaddle.github.io/PaddleX/3.1/en/module_usage/tutorials/ocr_modules/formula_recognition.html)
- [multilingual-e5-small — candidate model card](https://huggingface.co/intfloat/multilingual-e5-small)
- [Pl@ntNet offline documentation — feasibility reference, not a model reuse grant](https://docs.plantnet.org/en/tutorials/install-the-offline-embedded-mode/)
- [USDA FoodData Central](https://fdc.nal.usda.gov/)
- [USDA downloadable datasets](https://fdc.nal.usda.gov/download-datasets)
- [Nutrition5k research](https://github.com/google-research-datasets/Nutrition5k)
