# Darcha — Full Build Playbook (M1 → M4, T0 → T26)

Every task below is a ready-to-paste Claude Code prompt. **One task = one session.** This file replaces `M1_PLAYBOOK.md`.

## The ritual (never skip)

1. Claude Code in **Plan mode** → paste ONE task prompt.
2. Review the plan: inside scope? no extra deps? Approve.
3. Execute → tests green.
4. Ask: *"Explain the key decisions in this diff in 5 bullets."*
5. Review the diff yourself → commit → `/clear` → next task.

Prerequisites in repo: `CLAUDE.md` (root), `docs/TECH_SPEC.md`, this file.

## Progress checklist

**M1 — Parser core (no UI)**
- [x] T0 Skeleton · [x] T1 Fixtures infra · [x] T2 Container+errors · [x] T3 Workbook · [x] T4 SharedStrings · [x] T5 Styles · [x] T6 Sheet data · [x] T7 Layout extras · [x] T8 Facade · 🧑 OWNER: real fixtures · [ ] T9 CI

**M2 — Raw grid on screen**
- [ ] T10 MVI foundation · [ ] T11 File loading · [ ] T12 Geometry engine · [ ] T13 Canvas render · [ ] T14 Scroll+fling · [ ] T15 Sheet tabs + perf gate

**M3 — Fidelity**
- [ ] T16 Format engine · [ ] T17 Style render · [ ] T18 Merged cells · [ ] T19 Frozen panes · [ ] T20 Pinch zoom

**M4 — Product polish**
- [ ] T21 ACTION_VIEW · [ ] T22 Recent files · [ ] T23 Error UI · [ ] T24 Icon+theme+UZ · 🧑 OWNER: GIFs · [ ] T25 README+metrics · 🧑 OWNER: keystore · [ ] T26 Release v1.0

---

# M1 — Parser core

## T0 — Project skeleton

```text
Create the Gradle skeleton for the "Darcha" project per docs/TECH_SPEC.md §6.

Modules:
- :app — minimal Compose activity showing the text "Darcha" (placeholder only)
- :feature:viewer — empty Android library module (Compose enabled)
- :core:model — pure Kotlin JVM module (kotlin("jvm") plugin)
- :core:parser — pure Kotlin JVM module, depends only on :core:model

Requirements:
- Gradle Kotlin DSL + version catalog (gradle/libs.versions.toml)
- Kotlin 2.x, JVM target 17, minSdk 26
- JUnit in both :core modules; kxml2 as testImplementation in :core:parser only
- .gitignore for Android/Gradle/IDE; README.md stub linking docs/TECH_SPEC.md

Acceptance:
- ./gradlew build is green
- :core:* modules have no Android Gradle plugin and no android/androidx dependencies
```

## T1 — Fixture infrastructure + synthetic fixtures

```text
Set up the parser test fixture infrastructure per CLAUDE.md "Testing".

1. Create core/parser/src/test/resources/fixtures/{excel,libreoffice,gsheets,wps,synthetic}/ (keep empty dirs with .gitkeep where needed).
2. Write tools/gen_fixtures.py using openpyxl that generates into fixtures/synthetic/:
   - values-basic.xlsx (numbers, text, booleans)
   - strings-shared.xlsx (repeated strings → shared strings)
   - styles-basic.xlsx (bold, italic, font color, fill color, alignments)
   - merged.xlsx (several merged ranges)
   - frozen.xlsx (frozen first row and first column)
   - dates.xlsx (date, time, datetime cells with builtin formats)
   - multisheet.xlsx (3 sheets, distinct names incl. non-ASCII "Jadval 1")
   - sparse-gaps.xlsx (data at A1, C5, AA100 — gaps everywhere)
3. Create fixtures/FIXTURES.md: a table (file, producer, what it covers, golden highlights).

Acceptance:
- python3 tools/gen_fixtures.py runs (document `pip install openpyxl` in the script header)
- All 8 files exist in fixtures/synthetic/ and are committed
- FIXTURES.md lists them
```

## T2 — Container detection + error taxonomy

```text
In :core:parser implement container detection per TECH_SPEC §7 step 1, and the error taxonomy per §10.

1. ErrorKind sealed hierarchy in :core:model: Corrupted, Encrypted, Unsupported, TooLarge (+ message payloads where useful).
2. ContainerDetector: reads the first 8 bytes of a stream/file.
   - ZIP magic 50 4B 03 04 → ZIP
   - OLE/CFB magic D0 CF 11 E0 A1 B1 1A E1 → treat as Encrypted (password-protected xlsx or legacy .xls)
   - anything else → Corrupted
3. Parser-facing result type so raw exceptions never cross the module boundary.

Tests: pure byte-array based: zip bytes, ole bytes, garbage, empty, 3-byte file.

Acceptance: ./gradlew :core:parser:test green.
```

## T3 — workbook.xml + rels

```text
Implement streaming parsing of xl/workbook.xml and xl/_rels/workbook.xml.rels with XmlPullParser per TECH_SPEC §7 step 2.

Output model in :core:model:
- WorkbookMeta(date1904: Boolean, sheets: List<SheetRef>)
- SheetRef(name, sheetId, relId, partPath) — order preserved as in workbook.xml

Details:
- Resolve relId → target path via the rels part; normalize relative paths ("worksheets/sheet1.xml" → "xl/worksheets/sheet1.xml").
- Read date1904 from workbookPr if present, default false.
- Use ZipFile for part access.

Tests: golden tests against all synthetic fixtures (multisheet.xlsx must yield 3 sheets in order, correct names incl. non-ASCII).

Acceptance: tests green; streaming only, no DOM loading.
```

## T4 — Shared strings

```text
Implement streaming parsing of xl/sharedStrings.xml → StringTable per TECH_SPEC §7 step 3.

Handle:
- plain <si><t> entries
- rich text runs <si><r><t>…</t></r>… → flatten by concatenating run texts (run formatting is out of scope for v1)
- xml:space="preserve" whitespace
- missing sharedStrings.xml entirely (valid workbook with no shared strings)

Tests: golden tests using strings-shared.xlsx and values-basic.xlsx; add a unit test with an inline XML string covering rich-text runs.

Acceptance: tests green.
```

## T5 — Styles + number formats + date detection

```text
Implement streaming parsing of xl/styles.xml per TECH_SPEC §7 step 4.

1. Resolve cellXfs → CellStyle table: font (bold, italic, color), fill foreground color, horizontal/vertical alignment, numFmtId.
2. Hardcode the builtin number format table (ids 0–163: implement the ids the OOXML spec actually defines; leave gaps undefined).
3. isDateFormat(numFmtId, formatCode): builtin ids 14–22 and 45–47 are dates/times; custom formats are dates if the code contains y/m/d/h/s tokens outside quoted sections and color/condition brackets.
4. Colors: support rgb="FFRRGGBB" and indexed colors (ship the standard indexed palette); theme colors resolve to a documented fallback for v1.

Tests: golden tests via styles-basic.xlsx and dates.xlsx; direct unit tests for isDateFormat with at least 10 format codes (positive and negative).

Acceptance: tests green.
```

## T6 — Sheet data → sparse model

```text
Implement streaming parsing of xl/worksheets/sheetN.xml into the sparse model per TECH_SPEC §7 step 5 and §8.

Model (:core:model):
- SheetData with sparse rows; each row: sorted IntArray of column indices + parallel arrays for values and styleIds
- CellValue: Number(Double) | SharedText(index) | InlineText(String) | Bool | Error(code)

Parsing:
- A1-style ref → (row, col) conversion utility (with tests, incl. AA, AAA columns)
- Cell types: n (default), s, inlineStr, b, e, str — skip <f>, read cached <v>
- Rows/cells may be missing or out of dense order — never allocate for empty cells
- Ignore the dimension element's claims; trust actual cells

Tests: golden tests on values-basic, sparse-gaps (exact coordinates), strings-shared; assert sparse-gaps produces exactly 3 stored cells.

Acceptance: tests green.
```

## T7 — Layout: widths, heights, merges, frozen panes

```text
Extend the sheet parser per TECH_SPEC §7 and the §9 rendering inputs.

1. cols element → column width map (custom-width entries only), plus defaultColWidth/defaultRowHeight.
2. Char-unit → pixel conversion in ONE central function with KDoc citing the maxDigitWidth formula (TECH_SPEC §7 traps).
3. row ht attribute → row height map (points).
4. mergeCells → list of CellRange (A1-range → range parsing utility + tests).
5. sheetView pane element → FrozenPanes(xSplit, ySplit) when state="frozen".

Tests: golden tests via merged.xlsx and frozen.xlsx; unit tests for range parsing and width conversion.

Acceptance: tests green.
```

## T8 — Public facade + progressive API

```text
Design the public API of :core:parser per TECH_SPEC §7 (progressive loading) and §10.

- XlsxParser.open(file): Result<Workbook> — container check, workbook meta, shared strings, styles parsed eagerly.
- workbook.readSheet(index, chunkSize = 200, onChunk: (RowsChunk) -> Unit): Result<SheetData> — streams the sheet, invoking onChunk as rows accumulate, then returns the complete SheetData.
- Thread-agnostic: no coroutines/dispatchers inside :core:parser; the caller controls threading.
- All failures map to ErrorKind — no raw exceptions escape.
- KDoc on every public symbol.

Tests: end-to-end integration test opening each synthetic fixture and reading all sheets; assert chunk callbacks fire in order and the final model equals chunk accumulation.

Acceptance: tests green; public API surface reviewed (no accidental public internals).
```

## 🧑 OWNER homework — real-producer fixtures (before T9)

Claude Code cannot produce files from real Excel/LibreOffice/Google Sheets/WPS. Create these yourself, drop into the matching producer folder:

| File | Producers | Why |
|---|---|---|
| values-basic | all 4 | baseline cross-producer variance |
| strings (shared vs inline) | Excel + WPS | others often emit inlineStr |
| styles-basic | Excel + LibreOffice | style XML differs most here |
| merged + frozen | Excel + Google Sheets export | pane/merge quirks |
| dates | Excel (1900 and 1904 if possible) | epoch + format ids |
| uzbek-text | any 2 | UTF-8, non-ASCII sheet names |
| big-50k-rows | any 1 | M2 performance target file |
| password.xlsx | Excel | must yield Encrypted, not a crash |

Keep files tiny (except big-50k-rows). Google Sheets: File → Download → .xlsx.

## T9 — CI + real-producer corpus lock

```text
1. Add GitHub Actions workflow .github/workflows/ci.yml: on push/PR — JDK 17, Gradle cache, run :core:model:test and :core:parser:test.
2. Add CI badge to README.md.
3. For every real-producer fixture present under fixtures/{excel,libreoffice,gsheets,wps}/, add golden-value tests: sheet count/names, 3–5 spot-checked cells each, one styles assertion where applicable.
4. Update FIXTURES.md accordingly.

Acceptance: full suite green locally; CI green on GitHub after push. M1 done.
```

---

# M2 — Raw grid on screen

## T10 — MVI foundation

```text
In :feature:viewer implement the MVI foundation per TECH_SPEC §10.

- ViewerState (Parsing/Ready/Error), ViewerIntent (OpenFile, SwitchSheet, Scroll, Fling, Zoom, TapCell, Retry), Viewport(scrollX, scrollY, zoom).
- ViewerViewModel: StateFlow<ViewerState>, one reduce entry point for all intents.
- The parser sits behind an interface so the ViewModel is unit-testable with fakes; no rendering yet.
- Unit tests for reducer transitions: open → parsing(progress) → ready; error paths; SwitchSheet resets viewport.

Acceptance: :feature:viewer unit tests green.
```

## T11 — File loading pipeline

```text
Wire real file loading end to end.

- :app: SAF picker (ACTION_OPEN_DOCUMENT) filtered to xlsx mime where possible; takePersistableUriPermission.
- Copy the content:// stream to a cacheDir temp file (ZipFile needs a real file); enforce a size/cell cap → TooLarge per the error taxonomy (pick the cap from TECH_SPEC §13 and document it).
- ViewModel: parse on Dispatchers.IO; progressive chunks drive Parsing(progress) → Ready; a new OpenFile cancels the previous parse safely.
- Minimal Ready UI placeholder: "Loaded: N rows · M sheets".

Acceptance: picking synthetic values-basic.xlsx shows correct counts; device rotation does not re-parse (state survives in ViewModel).
```

## T12 — Grid geometry engine

```text
Pure geometry engine in :feature:viewer (a plain Kotlin class, no Compose/Android imports) per TECH_SPEC §9.

- X offsets from column widths (custom + default) via the central char→px converter; Y offsets from row heights. Do NOT materialize per-row arrays for default-sized rows: offset = index * default + prefix-sum of custom deltas.
- visibleRange(viewport, canvasSize) via binary search → row/col ranges incl. partially visible edge cells.
- cellAt(px, py, viewport) hit test; zoom applied uniformly.
- Must handle 16k columns / 1M rows bounds without allocation blowups.

Unit tests: heavy — offsets with defaults and custom sizes, multiple zoom factors, boundary hit tests, huge sparse sheets.

Acceptance: tests green; zero Android dependencies in the engine.
```

## T13 — Canvas renderer v1 (static)

```text
First pixels per TECH_SPEC §9.

- Single Canvas composable reading Ready state; draw ONLY visibleRange: gridlines, raw cell values, fixed header strips (A,B,C… columns / 1,2,3… rows).
- TextMeasurer with LRU cache keyed (text, zoomBucket); clip text to its cell rect.
- No gestures yet; temporary debug offset controls are fine.
- Log drawn-cell count per frame (debug) to prove culling.

Acceptance: values-basic and sparse-gaps render at correct positions (AA100 lands where it should); moving debug offsets shows a stable, small drawn-cell count.
```

## T14 — Scroll + fling

```text
Gestures per TECH_SPEC §9, strictly through MVI intents.

- Drag via pointerInput + VelocityTracker → Scroll intents; fling decay → either Fling intent resolved ViewModel-side or frame Scroll dispatch — choose one, justify in the summary, keep the flow unidirectional.
- Clamp scrolling to content bounds.
- Hot path discipline: no per-frame allocations; viewport changes must not recompose the chrome (grid-only invalidation).

Acceptance: 🧑 owner's big-50k fixture scrolls smoothly on a real device (no visible jank); reducer tests updated for Scroll/Fling.
```

## T15 — Sheet tabs + M2 performance gate

```text
- Bottom tab bar from WorkbookMeta; SwitchSheet parses on demand and caches SheetData (LRU, max 3 sheets); viewport resets per sheet; parsing indicator while loading.
- Create docs/PERF.md: device model, measured time-to-first-cell for big-50k, subjective scroll verdict, drawn-cells-per-frame sample.

Acceptance: multisheet.xlsx tab switching works; PERF.md committed. M2 done — the app is now demoable.
```

---

# M3 — Fidelity

## T16 — Number/date formatting engine

```text
Value formatting in :core:model (pure JVM) per TECH_SPEC §7/§8.

- Format CellValue by its numFmt: implement builtin subset exactly (General, 0, 0.00, #,##0, #,##0.00, 9 percent, 10 percent.00, 14–22 dates/datetimes, 45–47 times); custom codes → token-based date/time rendering when isDateFormat, else General fallback.
- Excel serial → date honoring date1904 AND the 1900 leap-year bug (document serial 60 behavior in KDoc).
- General rules: up to 11 significant digits, trailing-zero trimming, scientific beyond.
- LRU cache keyed (value bits, styleId).

Tests: table-driven, 40+ cases incl. the 1900-02-29 quirk, 1904 mode, times, percents, thousand separators.

Acceptance: tests green.
```

## T17 — Style rendering

```text
Apply CellStyle in the Canvas per TECH_SPEC §9 and wire the T16 formatter for display text.

- Fill rects, bold/italic, text color, horizontal/vertical alignment inside the cell rect, with clipping.
- Extend the text cache key → (text, styleId, zoomBucket).

Acceptance: styles-basic and dates fixtures visually match a LibreOffice reference (🧑 owner attaches reference screenshots for comparison); no fps regression on big-50k.
```

## T18 — Merged cells

```text
Per TECH_SPEC §9:
- Build a covered-cell skip set from merge ranges.
- Draw the anchor cell spanning the merged pixel bounds: background, gridline suppression inside the span, text aligned within the full span.
- Hit-test maps covered cells → anchor.

Tests: geometry unit tests for span bounds; manual check on merged.xlsx (synthetic + real producer).

Acceptance: tests green; merged.xlsx renders like the reference.
```

## T19 — Frozen panes

```text
Per TECH_SPEC §9: render four clipped regions (frozen corner, frozen rows, frozen columns, body) with translated origins; scroll affects only the unfrozen axes of each region; header strips stay consistent with freezing.

- Extend the geometry engine; unit tests for region bounds at several scroll/zoom combos.

Acceptance: frozen.xlsx shows no seams or overlap at region boundaries across zoom levels.
```

## T20 — Pinch zoom

```text
- Centroid-anchored pinch zoom via pointerInput: the focal cell stays under the fingers (compensate scroll on zoom); clamp 0.5–3.0; quantize zoomBucket for the text cache (0.1 steps).
- Double-tap: animate back to 1.0.
- Unit-test the focal-point math (pure function).

Acceptance: zooming big-50k stays smooth; focal stability verified by eye on merged + frozen fixtures. M3 done.
```

---

# M4 — Product polish

## T21 — ACTION_VIEW intent

```text
- Manifest intent filters so file managers open .xlsx with Darcha: mime application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, plus the octet-stream/wildcard + .xlsx-extension fallback pattern (document its limits in a code comment).
- Cold start from intent routes straight to the viewer; failures land on the error screen, not a crash.

Acceptance: opening from the system Files app works on a real device.
```

## T22 — Recent files + home screen

```text
- DataStore-backed recents: uri, displayName, lastOpened, sizeBytes; persistable URI permissions; graceful row state when permission was revoked.
- Home screen: recents list (empty state for first launch) + "Open file" button; tapping a recent opens it.

Acceptance: recents survive app restart; a revoked-permission entry shows a friendly state instead of crashing.
```

## T23 — Error & edge-state UI

```text
Friendly full-screen states for Encrypted / Corrupted / Unsupported / TooLarge: icon, one-sentence explanation, "Open another file" action. All copy via string resources (English now, Uzbek arrives in T24).

Acceptance manual matrix: password.xlsx → Encrypted; a truncated xlsx → Corrupted; an .ods renamed to .xlsx → Corrupted/Unsupported — no crashes anywhere.
```

## T24 — Icon, theme, Uzbek localization

```text
- Adaptive launcher icon: simple grid/cell "K" motif (vector, no external assets).
- Material 3 theme with dark mode support.
- Localize ALL user-facing strings: default English + values-uz (Uzbek latin).

Acceptance: light/dark screenshots; full run-through with device locale set to uz — no untranslated strings.
```

## 🧑 OWNER — before T25

Record GIFs on a real device: (1) open file + scroll a big sheet, (2) pinch zoom, (3) frozen panes + merged cells. Note your measured time-to-first-cell from PERF.md.

## T25 — Portfolio README + metrics

```text
Rewrite README.md as the portfolio front page:
- What/why (fast, private, offline, no INTERNET permission), badges (CI).
- Architecture: mermaid module diagram + 5 key engineering decisions (own streaming parser, Canvas renderer, sparse model, MVI, fixture-driven testing) each in 2–3 sentences.
- Measured metrics table from docs/PERF.md: time-to-first-cell, APK size (fill after T26), device.
- GIF slots wired to owner-provided recordings.
- Fixture philosophy paragraph + roadmap (post-v1 items from TECH_SPEC §14).
- Preserve and integrate the existing "How this was built" section (already in README.md; keep its content and links intact).

Acceptance: README renders cleanly on GitHub; no placeholder text left except the APK size (T26).
```

## T26 — Release v1.0

```text
- Release build: R8 + resource shrinking enabled; verify :core modules need no keep rules (no reflection); fix if any.
- Measure release APK size — must be < 5 MB per CLAUDE.md; investigate if over.
- versionCode/versionName 1.0.0; signing via keystore.properties (🧑 owner creates the keystore locally; never commit it — ensure .gitignore covers it).
- Prepare GitHub Release v1.0.0 notes (features, metrics, known limits); owner uploads the signed APK.
- Fill the APK size into README.

Acceptance: signed release APK installs on a device and opens the full fixture set. v1.0 shipped 🎉
```
