# Receipt & Scan — design handover for Claude Code

Version 1 · 24 Oct 2026 · Android, Jetpack Compose + Material 3
Design canvas: https://claude.ai/artifact/15aSvkANuNDNARYrSDzsrB
Reference stylesheet (the source of every token below): `dls-reference.css` in this folder.

---

## 1. What to build

Receipt & Scan is an on-device scanner app. Receipt scanning comes first. Thirteen other scanners live in a Tools tab. Every scanner except PDF is a self-contained module that can be embedded on its own.

Product rules that apply everywhere:

1. **On-device only.** Photos, text and results never leave the phone. No account, no cloud, no ads.
2. **AI runs only when the person acts.** Nothing is analysed in the background.
3. **Never invent numbers.** No fake confidence scores and no "you saved X" figures. Show only:
   - values read from the photo,
   - values the person confirmed or edited,
   - values calculated from those (with the formula shown when it matters),
   - AI suggestions, always labelled "Suggested".
4. **Keep value sources visible.** Use the source badges in section 4.4.
5. **Status never relies on colour alone.** Always pair it with an icon and text.
6. **Touch targets are at least 48 dp.** Text contrast meets WCAG AA.
7. **Education is short, contextual and dismissible.** It appears on first use of a module and never as a multi-page onboarding.

### Sample data (use in previews and tests)

| Receipt | Date | Status | Amount | Notes |
|---|---|---|---|---|
| FreshMart | 23 Oct 2026 18:42 | Confirmed | PKR 3,800.00 | Groceries, 6 lines (below) |
| Corner Café | 23 Oct 2026 | Confirmed | PKR 1,680.00 | Dining. Split 3 ways = 560.00 each |
| TechHub | 22 Oct 2026 | Needs review | PKR 6,500.00 detected | Not counted in totals. Wireless headphones, 14-day return (5 Nov), 12-month warranty (22 Oct 2027), serial TH-8842-11A |
| City Pharmacy | 28 Sep 2026 | Confirmed | PKR 920.00 | Health |
| Daily Bake | 21 Sep 2026 | Confirmed | PKR 450.00 | Groceries. Used for the "couldn't read total" state |
| FreshMart (older) | 14 Jul 2026 | Confirmed | — | Rice 5kg at PKR 1,150.00, used for price comparison |

FreshMart lines:

| Item | Qty × unit price | Line total (PKR) |
|---|---|---|
| Milk 1L | 2 × 320 | 640 |
| Bread | 1 × 240 | 240 |
| Rice 5kg | 1 × 1,200 | 1,200 |
| Eggs 12 | 1 × 550 | 550 |
| Cooking oil | 1 × 750 | 750 |
| Apples | 1 × 460 | 460 |

Subtotal 3,840 + Tax 160 − Discount 200 = **Total 3,800**.

October confirmed total = **PKR 5,480.00** from 2 receipts (FreshMart + Corner Café). TechHub is excluded until it's reviewed.

---

## 2. Navigation and information architecture

- **Bottom nav (floating pill, 68 dp):** Home, Library, Tools, Insights.
  - The active item expands into a filled pill showing its icon and label.
  - Inactive items show the icon with a small label.
- **App bar:** Settings opens from the app bar icon on every tab root. It is not a tab.
- **Home:**
  - Scan-receipt hero.
  - Quick tools row: 4 recent tools plus "All tools".
  - Recent receipts.
  - Needs-review card.
- **Library:**
  - Tabs: Receipts / Documents / Other scans.
  - Filter chips: All, Needs review · n, Confirmed, Date.
  - Sort control; list grouped by date.
- **Tools:**
  - Search ("Search 14 tools") and recent chips.
  - Three groups:
    - Receipts & documents: Receipts, Bills, Document, Text.
    - Products & food: Warranty, Nutrition, Expiry, Calories.
    - Everyday tools: QR code, Translate, Math, Counter, Plants, Wi-Fi.
- **Insights:**
  - Period control: Week / Month / Year.
  - Confirmed spending, category bar, top merchants, review prompt.
  - Trends unlock after 2 months of confirmed receipts.

Back behaviour:

- System back closes a sheet or dialog first, then goes to the previous screen.
- Leaving capture with unsaved parts shows "Leave scanning?" (Save draft / Discard).

---

## 3. Tokens

### 3.1 Colour — Simple (default skin)

| Token | Light | Dark |
|---|---|---|
| bg | #F5F5FB | #0D0E1A |
| card / card-solid | #FFFFFF | #181A2A |
| surface2 (fields, chips) | #F0F1F8 | #222438 |
| border | #E7E8F2 | #272A3F |
| text | #15172B | #F2F2FA |
| muted | #5E6278 | #A9ABC6 |
| primary / primary-fill | #4A56E8 | #4A56E8 |
| on-primary | #FFFFFF | #FFFFFF |
| tonal / on-tonal | #ECEEFF / #3E47C9 | rgba(180,185,255,.14) / #B4B9FF |
| ok / ok-bg | #1F7A55 / #E3F6EC | #86D9AE / rgba(134,217,174,.14) |
| warn / warn-bg / warn-line | #8A4B00 / #FFF1DF / #E0A458 | #F2B96B / rgba(242,185,107,.14) / #B98236 |
| err / err-bg | #B42318 / #FDECEA | #FF8A80 / rgba(255,138,128,.14) |
| nav | #FFFFFF | #1C1E31 |
| scrim | rgba(13,14,26,.48) | rgba(0,0,0,.6) |
| snack / snack-text / snack-action | #1E2033 / #F2F2FA / #B4B9FF | #E9E9F5 / #15172B / #3E47C9 |
| wash1 / wash2 (top radial glow) | rgba(170,156,255,.20) / rgba(140,186,255,.14) | rgba(110,92,255,.16) / rgba(56,118,255,.10) |

Checked contrast ratios:

| Pair | Ratio |
|---|---|
| primary #4A56E8 with white text | 5.54:1 |
| muted #5E6278 on white | 4.95:1 |
| muted #5E6278 on bg | 4.59:1 |
| ok #1F7A55 on ok-bg | 4.7:1 |
| warn #8A4B00 on warn-bg | above 5.2:1 |

### 3.2 Skins (Settings → Appearance)

Skins override **only** primary-fill, card, border, bg, wash and tile rendering. Spacing, type, radius, layout and status colours never change. Each skin has light and dark variants. Theme choice is Light / Dark / System (default System).

| Skin | primary-fill | card | wash | Tiles |
|---|---|---|---|---|
| Simple (default) | #4A56E8 flat | #FFFFFF solid | 20% / 14% | Tinted flat: 14% module colour on card, icon at 82% |
| Lavender Mist | linear 135° #4A56E8 → #6A4FE8 | rgba(255,255,255,.74) with a #FFF 95% border | 60% / 60% | 3D: 150° gradient from 50% white mix to the module colour, inner highlight and soft drop shadow |
| Aqua Pearl | linear 120° #0F7C86 → #2C6BC9; primary #0F7C86 | #FFFFFF | teal-tinted | 3D as Mist |

Aqua Pearl also sets bg #F2F7F7, tonal rgba(15,124,134,.10) and on-tonal #0E6E78.

Implement skins as a `SkinTokens` data class provided through a `CompositionLocal`. They sit on top of an M3 `ColorScheme`. Persist the choice in DataStore.

### 3.3 Module colours

Used for tiles, icons and module headers. Never use them for status.

| Module | Colour |
|---|---|
| Receipt | #4A56E8 |
| Bills | #12998C |
| PDF / Document | #3B6CF5 |
| Text | #64748B |
| Product & warranty | #8B5CF6 |
| Nutrition | #16A34A |
| Expiry | #E11D48 |
| Food calories | #F97316 |
| QR & barcode | #F59E0B |
| Translate | #0891B2 |
| Math | #DB2777 |
| Counter | #A16207 |
| Plant | #65A30D |
| Wi-Fi | #0284C7 |

### 3.4 Type

The UI font is Plus Jakarta Sans. Instrument Serif Italic is used only for brand accents. Use tabular numerals for all amounts.

| Style | Size / weight / tracking | Use |
|---|---|---|
| d1 | 30 / 700 / −0.9 | Hero amounts |
| h1 | 24 / 700 / −0.5 | Screen and dialog titles |
| big app bar | 24 / 700 / −0.6 | Tab roots |
| h2 | 18 / 700 / −0.3 | Section titles |
| t1 | 16 / 600 | List titles |
| b1 | 15 / 400, line height 1.5 | Body |
| b2 | 14 / 400, line height 1.45 | Secondary body |
| lab | 13 / 600 | Field labels |
| cap | 12 / 400 | Meta |
| ov | 12 / 700, +0.8, uppercase | Group headers |
| num | 700 | Amounts |
| mono | System mono | Raw receipt text, serials, expressions |

Support font scale up to 200%. Rows must grow in height rather than truncate amounts.

### 3.5 Shape, space, elevation

- **Radius:**

  | Element | Radius (dp) |
  |---|---|
  | Small tile | 12 |
  | Tile | 15 |
  | Large tile | 18 |
  | Extra-large tile | 24 |
  | Field | 16 |
  | Card | 24 |
  | Dialog / sheet | 28 |
  | Chips, buttons, nav | Pill |

- **Spacing scale:** 2, 4, 6, 8, 10, 12, 16, 20, 24, 32. The screen gutter is 20.
- **Sizes:**

  | Element | Size (dp) |
  |---|---|
  | Button | 56 (60 large, 44 small) |
  | Icon button | 48 |
  | Chip | 40 |
  | List row | ≥ 64 (compact 56) |
  | Field | 52 |
  | Search | 54 |
  | Nav | 68, inset 20 from the sides and 26 from the bottom |

- **Elevation:**
  - Cards use a 1 dp border plus a very soft shadow (0 1 2 at 4%, 0 6 18 at 5%).
  - Dark mode uses the border only.

### 3.6 Icons

Use 2 dp stroke line icons on a 24 grid, at 14 / 18 / 22 / 26 / 32 / 44 dp. Recommended mapping: Material Symbols Rounded, weight 400. Icon names used in the designs:

> receipt, bill, doc, board, book, text, product, nutrition, expiry, food, qr, translate, math, counter, plant, wifi, signal, camera, image, import, scan, crop, rotate, enhance, flash, help, search, settings, home, library, tools, insights, alert, check, close, back, chev, more, edit, trash, share, copy, download, external, link, shield, lock, bell, clock, calendar, users, user, trend, tag, grid, sparkle, info, eye, offline, storage, palette, sun, moon, device, pause, play, plus, minus, undo, refresh, sort, filter, globe, phone, laptop, basket, cup.

---

## 4. Components

| Component | Spec | Compose |
|---|---|---|
| Button | Variants: primary (fill, can be a gradient in skins), tonal, outline, ghost, danger, danger-tonal. Height 56/60/44. Disabled is 38% opacity, keeps its label, and the reason is shown nearby | `Button`, `FilledTonalButton`, `OutlinedButton`, `TextButton` with custom shapes |
| Icon button | 48 dp. `.s` variant has a card background and border on tab roots | `IconButton` |
| Chip | 40 dp. States: selected (inverse text/bg), default (surface2), tonal action, outline | `FilterChip` / `AssistChip` |
| Badge | 22 dp. Variants: ok-dot, warn+icon, err+icon, info, neutral | Custom `StatusBadge(kind, text, icon)` |
| Tile | Module icon container, 36/44/56/72 dp. Skin decides flat or 3D | `ModuleTile(module, size)` |
| List row | Leading tile, title + meta, trailing amount + badge. Compact variant | `ListItem` with slots |
| Card | Radius 24, border, soft shadow | `Card` / `ElevatedCard` |
| Field | 52 dp, label above, helper below. States: default, focus (primary border + 4 dp halo), error, warn, ok, disabled | `OutlinedTextField` restyled, `supportingText` |
| Search | 54 dp pill | `SearchBar` (docked) |
| Segmented | 48 dp, selected segment raised | `SingleChoiceSegmentedButtonRow` |
| Tabs | 48 dp, 3 dp primary underline | `PrimaryTabRow` |
| Switch / radio / checkbox | M3 sizes, primary colour | Standard |
| Banner | Radius 20. Variants: info, warn, err, ok. Icon + title + body + optional action | Custom `InlineBanner` |
| Bottom sheet | Radius 28, grab handle, scrim 48% | `ModalBottomSheet` |
| Dialog | Radius 28, 24 padding, actions right-aligned, destructive on the right | `AlertDialog` |
| Snackbar | 52 dp, sits 112 dp above the bottom (clears the nav), one action | `SnackbarHost` |
| Toast | 40 dp pill at the top, no actions, status only | Custom overlay |
| Progress | Linear 8 dp bar, circular 22 dp spinner, ring for downloads | M3 progress indicators |
| Skeleton | Shimmer blocks matching the final layout | Custom `Modifier.shimmer()` |
| Floating nav | See section 2 | Custom `NavigationBar` |
| Camera chrome | Dark (#07080D) screen, viewfinder radius 28, edge outline (lavender #8F98FF searching, green #7BE0A8 locked), guidance pill, mode chips, 80 dp shutter, 52 dp side buttons | CameraX `PreviewView` + overlays |

### 4.4 Value source badges (required)

| Badge | Meaning |
|---|---|
| **From photo** (info, scan icon) | Read by OCR or a detector, not changed |
| **Edited** / **Entered by you** (neutral, user icon) | Typed or changed by the person |
| **Calculated** (neutral) | Derived from the values above. Show the formula on tap |
| **Suggested** (info, sparkle icon) | AI guess. The person must accept it before it counts |
| **Estimate** (neutral) | Approximate values such as food calories. Always shown with "≈" |
| **Confirmed** (ok, dot) | The person confirmed the receipt; it now counts in totals |
| **Needs review** (warn, alert icon) | Excluded from totals until confirmed |

---

## 5. Screen inventory

The IDs below are stable. Use them as Compose destination names and as preview names. Each `.dc.html` file on the canvas matches one ID.

### Home & tabs

| ID | Screen | Key states |
|---|---|---|
| S-Home | Home, returning user | Hero scan, quick tools, recent receipts, review card |
| S-Empty | Home, first use | Welcome, 3-step explainer, scan CTA, import |
| S-Dark | Home in dark theme | — |
| H-Loading | Home skeleton | Shown only if loading takes more than 300 ms |
| T-Tools | Tools tab | Search, recent chips, 3 groups / 14 tools |
| L-Library | Library, receipts | Filters, sort, date groups |
| L-Search | Library search | Receipts / Items / Text scopes, highlights, "Ask" entry |
| L-Select | Multi-select + delete dialog | Long-press enters selection, shows count, share/delete |
| L-Undo | After delete | Snackbar with Undo, 6 s window |
| L-EmptyDocs | Documents tab, empty | Mode chips, scan / import |
| I-Insights | Insights | Confirmed spend, categories, top merchants, review prompt, trends locked |
| I-Limited | Insights, not enough data | Progress "0 of 2 confirmed", preview of what unlocks |

### Receipt flow

| ID | Screen | Notes |
|---|---|---|
| R-Permission | Camera pre-prompt sheet | Shown before the system dialog. "Import instead" is the alternative |
| R-Capture | Capture | Single / Long receipt modes, auto-capture, guidance pill, edge lock |
| R-Long | Long receipt | Part counter, thumbnails, "keep the last line in view", Done |
| R-Crop | Adjust | Crop, Rotate, Enhance; "Add another image to this receipt" vs "Add another receipt"; Read receipt |
| R-Processing | Reading | 4 determinate steps, linear progress, Cancel |
| R-Review | Review | Summary banner, fields, items, uncertain line highlighted, total reconciliation, Save draft / Confirm & save |
| R-EditLine | Edit item sheet | Source crop, validation errors, Confirm disabled with a reason |
| R-Duplicate | Possible duplicate dialog | View existing / Keep both / Discard this scan |
| R-Saved | Success | Check ring pop, summary, next actions, Scan another |
| R-Detail | Receipt detail, items | Action chips (Split, Compare, Remind, Export), Items/Text/Image tabs, exact arithmetic |
| R-Text | Receipt detail, text | Raw OCR in mono, find in text, copy |
| R-Unreadable | Couldn't read total | Blurred source, error banner, manual total, "Use item sum", retake |

### Receipt actions

| ID | Screen | Notes |
|---|---|---|
| A-Split | Split bill | Equally / By item / Custom, people, "Adds up exactly", tip toggle, share summary |
| A-Compare | Compare prices | Only from the person's own receipts; shows the % formula; fix wrong matches |
| A-NoHistory | Compare, first purchase | Empty state, "Tell me when I can compare" |
| A-Warranty | Warranty & return | Return-by and warranty-until dates, terms marked "Entered by you", reminder |
| A-Reminder | Reminder sheet | Notification permission asked inline at the point of need |
| A-Export | Export sheet | PDF / CSV / Image, range, attach photo, Save to files / Share |
| A-Ask | Ask about receipts | On-device Q&A; answers carry Calculated/Suggested badges and source chips |

### Scanners

| ID | Screen | Notes |
|---|---|---|
| M-Intro | Module first use | Tile, 3 tips, feature pack size, Download and start / Not now |
| M-Download | Pack downloading | Progress ring, MB counter, Pause / Cancel, can leave the screen |
| S-Bills | Bill result | Amount due, due-in badge, fields with sources, reminder, Mark as paid |
| S-PDF | Document pages | Document / Whiteboard / Book, reorder grid, searchable toggle, Save PDF |
| S-QR | QR result over camera | Link preview, safety banner, never auto-open, Copy / Open |
| S-Text | Text result | Detected language, editable text, smart chips (date, phone), copy/save |
| S-Product | Product label | Model/serial/barcode from photo, link to a receipt, Add warranty |
| S-Translate | Live translate | Overlay on the viewfinder, language pair, Offline indicator, Show original |
| S-Nutrition | Nutrition | Serving size from the label (editable), per serving (calculated) vs per 100 g (read) |
| S-Expiry | Expiry date | Ambiguous date offered as a choice of two, item/type, reminder |
| S-Math | Math | "We read" editable expression, answer, steps, check line |
| S-Counter | Counter | Numbered markers, unclear spot, ± correction, Undo, Save count |
| S-Plant | Plants | "Likely matches" (no %), safety disclaimer, add another photo |
| S-Food | Food calories | Confirm the food via chips, portion control, "≈ kcal" Estimate |
| S-WifiChoice | Wi-Fi mode sheet | Scan Wi-Fi QR / Nearby networks |
| S-WifiList | Nearby networks | Connected, secured/open, strength as text, open-network warning |

### Settings & skins

| ID | Screen |
|---|---|
| X-Settings | Settings root: Appearance, Language & currency, Feature packs, Receipt defaults, Tips, Notifications, Data & privacy, About |
| X-Appearance | Skin picker (Simple / Lavender Mist / Aqua Pearl), theme, Reduce motion, Bigger amounts |
| X-Packs | Feature packs in all states: Ready, Get, Downloading, Paused, No space; Wi-Fi only |
| X-Data | Storage, export everything, keep photos, auto-delete, Delete all (type DELETE) |
| K-Mist / K-Aqua / K-Dark | The same screens rendered in each skin, for QA |

### System boards

| ID | Board |
|---|---|
| Y-Notifications | Notifications |
| Y-Dialogs | Dialogs, sheets, snackbars, toasts |
| Y-Validation | Validation |
| Y-States | Empty, error and recovery states |
| Y-Motion | Motion spec |
| DLS | Tokens and components |
| IA | Where the 14 scanners live |

---

## 6. Flows

### 6.1 Receipt (primary)

1. Entry points: Home hero, Tools → Receipts, or the Library empty state.
2. If camera permission hasn't been asked yet, show **R-Permission**. Continue opens the system dialog; Import instead opens the picker.
3. If permission is permanently denied, show the "Camera is off" dialog with Open settings / Import.
4. **R-Capture.** The guidance pill cycles through:
   - "Find the receipt"
   - "Move closer"
   - "Hold steady · edges found"
   - auto-capture (on by default; a toggle sits next to the shutter).
5. Long receipt (**R-Long**):
   - Each capture adds a part. A thumbnail strip shows the parts.
   - The guidance says to overlap the last line.
   - Parts are stitched on Done.
6. **R-Crop:**
   - Corner handles, Rotate, Enhance (on by default).
   - "Add another image to this receipt" appends a page to the same receipt.
   - "Add another receipt" queues a new receipt.
   - "Read receipt" starts processing.
7. **R-Processing:**
   - Steps: Image cleaned up → Text found (n lines) → Matching items and totals → Checking the arithmetic.
   - Cancel returns to Adjust with the photo kept.
8. **R-Review:**
   - Every low-certainty field is highlighted with warn styling and "Hard to read · check price".
   - The reconciliation line shows the actual formula. When the totals don't match, it shows a warn helper with the difference.
   - Save draft stores the receipt as Needs review, excluded from totals.
   - Confirm & save requires a merchant, a date and a total.
9. After saving, run the duplicate check (same merchant + date + total within 0.01). On a match, show **R-Duplicate**.
10. **R-Saved** shows next actions. Scan another goes back to Capture.

### 6.2 Other modules (shared shape)

`Tools tile → (first use) M-Intro → (pack missing) M-Download → Capture (module camera chrome) → Result screen (S-*) → Save / Copy / Share / Remind`

- Every result screen shows its source photo, or a link to it.
- Every result screen labels each value with its source badge.
- Wi-Fi starts with the S-WifiChoice sheet.
- PDF uses its own multi-page flow, which is not embeddable.

### 6.3 Reminders

1. Set from Bills, Warranty, Expiry or Receipt detail.
2. The first time, the Allow notifications banner appears inside the sheet, not on app launch.
3. "Not now" saves the reminder anyway and shows a "Notifications off" badge with a link to Settings.

### 6.4 Delete and undo

1. Single delete uses a dialog. Multi-select delete uses a dialog that names the receipts.
2. After delete, a snackbar with Undo stays for 6 s. The hard delete runs after the snackbar is dismissed.
3. "Delete all data" requires typing DELETE and cannot be undone.

---

## 7. Validation rules

Validate on blur and on save, never during the first characters typed. Errors block Confirm; warnings never block Save draft.

| Field | Rule | Message |
|---|---|---|
| Amount | Digits, one decimal point, ≤ 2 decimals, ≤ 99,999,999.99 | "Enter an amount like 550.00" |
| Quantity | > 0, decimals allowed (0.5 kg) | "Must be more than 0" / "Must be at least 1" for count units |
| Date | ≤ today + 1 day. Ambiguous dd/mm vs mm/dd asks the person | "This date is in the future" |
| Merchant | Required to confirm, 1–60 characters | "Add a merchant name" |
| Totals | items + tax − discount = total, within 0.01 | "Items add up to PKR X, total says PKR Y" (warn) |
| Split custom | Shares sum to the total exactly | "PKR 40.00 left to assign" |
| Reminder | Must be in the future | "Pick a time after now" |
| Delete all | Input equals DELETE | Button disabled until it matches |

---

## 8. Copy

### 8.1 Notifications (Android channels)

| Channel | Importance | Trigger | Example |
|---|---|---|---|
| Reminders · bills | High | Chosen date, 10:00 | "City Power bill due in 2 days" — "PKR 8,240.00 due on 30 Oct." [Mark as paid] [Open] |
| Reminders · warranty | Default | 3 days before the end | "Return window ends 5 Nov" — "Wireless headphones from TechHub. 3 days left." [Open] [Snooze 1 day] |
| Reminders · expiry | Default | 1 day before | "Yogurt 500 g is best before tomorrow" [Done] [Open] |
| Background tasks | Low, silent | Export or pack ready | "Export ready" — "FreshMart receipt · PDF · 1.2 MB" [Share] [Open] |
| Draft nudge | Low | Once, 24 h after a draft | "Finish reviewing TechHub" — "It's saved as a draft and not counted yet." [Review] |

Amounts appear only when they are confirmed or printed on a bill. Never show estimates in notifications.

### 8.2 Dialogs

| Dialog | Body | Actions |
|---|---|---|
| Discard changes? | "Your edits to {merchant} won't be saved." | Keep editing / Discard |
| Delete receipt? | "{merchant} and its photo will be removed. You can undo for a few seconds." | Cancel / Delete |
| Delete n receipts? | Names the receipts | Optional "Also delete exported PDFs" checkbox |
| Camera is off | "Turn on camera access in Android settings, or import a photo instead." | Import / Open settings |
| Open this link? | Shown for http or lookalike domains, with "Not secure and looks unusual." | Cancel / Open anyway |
| Leave scanning? | "You have {n} parts of a long receipt." | Save draft / Discard |
| This may already be saved | Shows the matching receipt | View existing / Keep both / Discard this scan |

### 8.3 Education (first-use tips per module, max 3 lines each)

| Module | Tips |
|---|---|
| Receipt | Lay it flat with all four corners in view · For long receipts use Long receipt mode · Check highlighted lines before confirming |
| Bills | Include the amount due and due date · We'll offer a reminder |
| Document | Pick Document, Whiteboard or Book · Add pages, then drag to reorder |
| Text | Hold parallel to the page · Tap text to edit before copying |
| Product | Scan the sticker with model and serial · Link it to a receipt for warranty |
| Translate | Download the language pair once to work offline · Tap the eye to see the original |
| Nutrition | Fill the frame with the table only · Avoid glare · Check the serving size we read |
| Expiry | Get close to the printed date · If it can be read two ways, we'll ask |
| Math | One problem at a time · Check "We read" before trusting the answer |
| Counter | Spread items so they don't overlap · Tap to add or remove a marker |
| Plants | One leaf or flower, in focus · Matches can be wrong; never use them to decide edibility |
| Food | Whole plate from above · Confirm the food and portion; calories are an estimate |
| QR | We never open links automatically · Check the address first |
| Wi-Fi | Passwords go to Android's Wi-Fi settings, never stored · Avoid open networks for banking |

Dismissal is stored per module. Settings → Tips and guidance → "Show first-use tips" resets them.

### 8.4 Empty, error and recovery states

See the **Y-States** board. Every state has a title, a one-sentence body saying what still works, and one primary action.

Covered states: no matches, offline, storage full, no text found, language not supported, camera off, couldn't read, pack needed, notifications off, too many to count (cap 500), no plant match, no Wi-Fi networks. Library empty states also appear in L-EmptyDocs, and Insights empty states in I-Limited.

---

## 9. Motion

| Pattern | Duration | Easing | Use |
|---|---|---|---|
| Press | 90 ms | standard | Scale 0.97 |
| Toggle / select | 150 ms | standard | Switch, radio, nav pill width |
| Enter (rise) | 240 ms | emphasized-decelerate | Move up 16 dp + fade. Cards, sheets, snackbars |
| Exit | 180 ms | emphasized-accelerate | Down + fade |
| Screen change | 300 ms | emphasized | Shared axis X for push/pop; fade-through between tabs |
| Success | 500 ms | overshoot | Ring scales 0.6 → 1; content rises with a 60 ms stagger |
| Edge lock | 200 ms | standard | Outline lavender → green, light haptic |
| Scan line | 1600 ms loop | ease-in-out | Processing only |
| Skeleton | 1400 ms loop | linear | Only when loading exceeds 300 ms |

Easing curves:

| Name | Curve |
|---|---|
| standard | (.2, 0, 0, 1) |
| emphasized-decelerate | (.05, .7, .1, 1) |
| emphasized-accelerate | (.3, 0, .8, .15) |
| overshoot | (.2, .9, .3, 1.25), or `spring(dampingRatio = 0.6)` |

Reduce motion is on when either the in-app switch or the system animator scale is 0. When it's on:

- all movement becomes a 150 ms fade,
- loops stop,
- the scan line becomes a static linear progress bar.

Haptics:

- Edge lock and capture: light tick.
- Save: confirm.
- Error: reject, once.

---

## 10. Module contract (embeddable scanners)

Each non-PDF scanner is a Gradle module, for example `:scanner:nutrition`. It exposes one entry point and no dependency on app navigation.

```kotlin
interface ScannerModule<R : ScanResult> {
    val id: ModuleId                 // RECEIPT, BILL, TEXT, PRODUCT, TRANSLATE, NUTRITION, EXPIRY, MATH, COUNTER, PLANT, FOOD, QR, WIFI
    val requiredPacks: List<PackId>  // on-device models; empty if none
    @Composable fun Capture(input: ScanInput, onResult: (ScanOutcome<R>) -> Unit)
    @Composable fun Result(result: R, onAction: (ResultAction) -> Unit)
}

data class ScanInput(
    val source: Source,              // Camera | Image(uri) | Pdf(uri)
    val locale: Locale,
    val currency: Currency = Currency.getInstance("PKR"),
    val skin: SkinTokens,            // host passes theme, module never hardcodes colours
    val showEducation: Boolean,
)

sealed interface ScanOutcome<out R> {
    data class Success<R>(val result: R) : ScanOutcome<R>
    data class NeedsReview<R>(val result: R, val issues: List<Issue>) : ScanOutcome<R>
    data class Failed(val reason: FailReason) : ScanOutcome<Nothing> // NoText, Blurry, Unsupported, PermissionDenied, PackMissing, StorageFull, Cancelled
}

data class Value<T>(val value: T, val source: ValueSource, val region: RectF? = null)
enum class ValueSource { FROM_PHOTO, EDITED, CALCULATED, SUGGESTED, ESTIMATE }
```

Rules:

- Every field in a result is a `Value<T>` carrying its source, so the UI can render the right badge.
- `region` links a value back to its place in the photo (used by "View photo" and the edit-sheet crop).
- Modules never write to the Library directly. They return the result, and the host decides how to save it.
- Modules must render correctly with any `SkinTokens` and in both themes.
- Feature packs are installed through Play Feature Delivery or on-device model download. Each module shows M-Intro / M-Download via a host-provided `PackGate` composable.

---

## 11. Accessibility checklist

- 48 dp minimum touch targets, including chips (40 dp visual, 48 dp hit area).
- Every icon-only button has a content description, as in the designs (`aria-label` → `contentDescription`).
- Status badges include text; the warn row in Review includes the text "Hard to read".
- Charts have text equivalents (category list below the bar; price list below the line).
- Camera guidance is announced through a live region (`LiveRegionMode.Polite`) whenever it changes.
- Dynamic type up to 200% without clipping amounts. Wrap or stack the amount under the title instead.
- Focus order follows reading order. Dialogs trap focus. The sheet grab handle is decorative.
- Dark theme and all skins meet AA for text. Skin gradients sit only behind white bold text ≥ 14 sp.

---

## 12. Implementation notes for Compose

- **Theme:** `ReceiptScanTheme(skin: Skin, dark: Boolean)` provides an M3 `ColorScheme` plus `LocalSkinTokens` (primaryFill as a `Brush`, cardAlpha, washColors, tileStyle).
- **Gradients:** buttons and the active nav pill use a `Brush` background when the skin has a gradient.
- **Wash:** a top 440 dp `Box` with two radial gradients, drawn behind the content with `drawBehind`.
- **Money:** use `BigDecimal` and never floating point. Format with `NumberFormat.getCurrencyInstance` and the PKR symbol shown as "PKR". Always show 2 decimals.
- **Storage and search:** Room for receipts, lines and reminders. FTS4 table for OCR text search. Semantic "Ask" uses on-device embeddings over receipt lines and is computed on demand.
- **ML:**
  - ML Kit Text Recognition v2 and Barcode Scanning (bundled).
  - ML Kit Translation for on-device language packs.
  - Custom TFLite models for plant and food, downloaded as packs.
- **Camera:** CameraX with an ImageAnalysis use case for edge detection and guidance at 10 fps. Capture at full resolution.
- **Notifications:** five channels as in section 8.1. Use WorkManager for reminders. Request `POST_NOTIFICATIONS` only from the reminder sheet.
- **Previews:** one `@Preview` per screen ID × {Simple light, Simple dark, Mist, Aqua}. The canvas K-boards show the expected result.

---

## 13. Out of scope

- Business-card scanning.
- Any cloud sync or account.
- Marketing notifications.
- Savings or confidence percentages.
