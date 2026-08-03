# Darcha — Full Build Playbook (M1 → M6, T0 → T34)

Every task below is a ready-to-paste Claude Code prompt. **One task = one session.** This file replaces `M1_PLAYBOOK.md`.

## The ritual (never skip)

1. Claude Code in **Plan mode** → paste ONE task prompt.
2. Review the plan: inside scope? no extra deps? Approve.
3. Execute → tests green.
4. Ask: *"Explain the key decisions in this diff in 5 bullets."*
5. Review the diff yourself → commit → `/clear` → next task.

Prerequisites in repo: `CLAUDE.md` (root), `docs/TECH_SPEC.md`, this file.

## Progress checklist

**M1 — Parser core (no UI) — ✅ COMPLETE**
- [x] T0 Skeleton · [x] T1 Fixtures infra · [x] T2 Container+errors · [x] T3 Workbook · [x] T4 SharedStrings · [x] T5 Styles · [x] T6 Sheet data · [x] T7 Layout extras · [x] T8 Facade · [x] 🧑 OWNER: real fixtures · [x] T9 CI

> **T9 — DONE:**
> - ✅ CI workflow (`.github/workflows/ci.yml`) + README badge; green on GitHub (`Abdurahim1995/Darcha`).
> - ✅ Real-producer corpus: 7 **Microsoft Excel Online** files in `fixtures/excel/`, golden-locked in `ExcelFixturesTest.kt`; `FIXTURES.md` records the values and the producer variance they capture.
> - ✅ Also landed: `big-50k-rows.xlsx` perf fixture (producer-agnostic, for M2), and `tools/check_fixtures.py` to validate any future real-producer files.
>
> **M1 exit criteria — met.** The parser reads real Excel output, not just our own; every assertion was read out of the committed files. `gsheets/`, `libreoffice/` and `wps/` remain optional future additions — recipes are in `docs/FIXTURE_RECIPES.md` and the check script is in place.

**M2 — Raw grid on screen — ✅ COMPLETE**
- [x] T10 MVI foundation · [x] T11 File loading · [x] T12 Geometry engine · [x] T13 Canvas render · [x] T14 Scroll+fling · [x] T15 Sheet tabs + perf gate

> **M2 exit criteria — met.** The app opens a real `.xlsx`, draws it, scrolls it with gestures and switches sheets. Measurements in `docs/PERF.md`. The first-paint gap found here was closed straight after, in T15.5.

**M2.5 — Progressive first paint**
- [x] T15.5 Progressive first paint · [x] T15.6 Chunks carry their layout

> **T15.5 — DONE.** `big-50k-rows.xlsx` showed its first cells in 2,427 ms, violating §5. The parser already streamed in chunks (§7, T8) but the grid waited for the last row. Chunks now reach the renderer as partial snapshots: **175 ms to first cells**, complete parse unchanged. Before/after in `docs/PERF.md`.
>
> **T15.6 — DONE.** T15.5 left partial paints using default column widths, which real business spreadsheets almost never have — reflow-on-completion would have been the normal case. Chunks now carry their layout (§7), so the first paint is already correctly sized.

**M3 — Fidelity — ✅ COMPLETE**
- [x] T16 Format engine · [x] T17 Style render · [x] T18 Merged cells · [x] T19 Frozen panes · [x] T20 Pinch zoom

> **T16 — DONE.** `ValueFormatter` in `:core:model`, with the 1900 leap-year bug reproduced deliberately (§8). Purely additive — 8 new files, nothing existing edited, `:core:parser` untouched.
>
> **T17 — DONE.** The Canvas applies fills, bold/italic, colour and alignment, and draws formatted text through T16. Month/day names are now injected (`DateNames`), so T24 can supply Uzbek ones without touching `:core:model`. The end-to-end seam — fixture → parser → formatter → display string — is finally tested, in `:feature:viewer`. Cache and frame-time measurements in `docs/PERF.md`; **styling cost zero extra drawn cells.**
>
> **T18 — DONE.** Merged ranges draw once at their anchor across the span, covered cells are skipped, and hit-testing maps a covered cell to its anchor. The skip set is a sorted interval index, not a set of cells — one `A1:C1048576` merge would otherwise cost three million entries. Verified against both merged fixtures; late-arriving merges documented in §7 and `docs/PERF.md`.
>
> **T19 — DONE.** Four clipped regions with translated origins; header strips split the same way. `<sheetViews>` precedes `<sheetData>` on every fixture checked, so frozen panes now ride with the first chunk (a narrow additive `:core:parser` change) — the grid never re-splits mid-parse. Seams asserted as exact equalities at seven zoom levels, and inspected at 4× magnification on device at four. Scrolling gained a floor, clamped in the reducer *and* in the region maths, which fixed a real first-frame glitch.
>
> **T20 — DONE.** Centroid-anchored pinch, double-tap back to 1.0, and the §9.2 density/zoom question resolved first: content scales, chrome does not — which also fixed header labels overflowing their strip above ~1.5×. Focal drift measured at **0.0 px over 72 zoom events** on device. Text cache re-measured under a zoom sweep (peak 3,839 live keys) and raised to 4,096. `TapCell` deliberately left unwired — selection is post-v1 (§14).
>
> **M3 exit criteria — met.** The grid now renders what the file actually says: formatted values, cell styles, merged ranges, frozen panes, and zoom.

**M4 — Product polish**
- [x] T21 ACTION_VIEW · [x] T22 Recent files · [x] T23 Error UI · [x] T24 Icon+theme+UZ · 🧑 OWNER: GIFs · [x] T25 README+metrics · 🧑 OWNER: keystore · [x] T26 Release v1.0 🎉

> **T21 — DONE.** Darcha opens `.xlsx` from a file manager, cold start included. Verified on both managers the A31 has — system Files and Samsung My Files — and both reported the correct OOXML MIME, so the octet-stream/wildcard fallback never fired; neither URI contained `.xlsx` anywhere, which is exactly the pathPattern limit the manifest documents. A garbage file with an `.xlsx` name lands on the error screen from a cold start, no crash. **`ACTION_VIEW` grants are one-shot** — recorded in §9.1 and in the code, so T22 does not store URIs it can never reopen.
>
> **T22 — DONE.** DataStore-backed recents (owner-approved, ~190 KB shrunk), home screen with an empty state, and the T21 trap resolved: an entry is written **only** when a persistable grant was taken. Verified on the A31 — seven `ACTION_VIEW` opens left the list empty, picker opens were remembered and survived a restart, a deleted file's row reads "No longer available" and is inert but removable, and `cacheDir` held exactly one temp copy across A → B → A. The availability probe had to be strengthened to open the file rather than query it: the downloads provider keeps answering metadata for a deleted document. Numbers in `docs/PERF.md`.
>
> **T23 — DONE.** A full-screen state per failure — icon, what happened, what to do — with all copy in `strings.xml` and a test that reads the file to keep internals out of it. `ErrorKind.Unreadable` settles the T21 debt: a revoked permission used to claim the file was damaged, about a file that was fine. Matrix verified on device against **real** files, including a genuinely password-protected `.xlsx` built with ECMA-376 agile encryption in a throwaway venv. Times and the one rough edge (a renamed `.ods` reads as "damaged") in `docs/PERF.md`.
>
> **T24 — DONE.** Adaptive icon as pure vectors — a little window with panes, which is what *darcha* means and is already a grid; the playbook's "K" was a leftover from Katak. Material 3 light/dark, with the grid's own palette following it. Uzbek is first-class, and the copy test now holds both languages to the same rules — it caught its own Gradle bug: the test reads `strings.xml` off disk, so the task went UP-TO-DATE after a strings edit and the lint silently stopped running. `DateNames` finally does the job T16 built it for. Four theme/locale combinations on device in `docs/PERF.md`, including what had to be forced and why.
>
> **Icon corrected after v1.1.0.** The concept held; the geometry did not. T24 inset the artwork 21 units on each side and called that "the 66dp safe zone" — but the safe zone is a **circle**, not a square, so the sides fitted and the corners did not: the frame reached r≈38.5 against a 36 maximum, and circle/One UI masks shaved it until the white frame read as the icon's own silhouette. Redrawn to r=30.02 with the corner arithmetic written down, panes made deliberately unequal (14:21 and 11:18), frame:mullion set to 6:3 because 2 units is 1.33px at a 48dp icon on mdpi, and a separate monochrome layer added. Recorded in TECH_SPEC §9 so it is not reintroduced.
>
> **T25 — DONE.** The README leads with a screenshot and the three numbers that matter, and says plainly what the app does *not* do before it lists what it does. The five "key decisions" this playbook drafted before any code existed were replaced by the six the codebase actually produced — progressive first paint (2,427 ms → 175 ms), merges as ranges rather than a covered-cell set, seams that cannot open because there is one number per axis, the dark-mode theme-colour substitution. Every figure was re-read out of `docs/PERF.md`, which corrected two: the frame times quoted here are the current build's, not T16's, and are reported as medians and 90th percentiles rather than as a "60 fps" claim the data does not support. Six device screenshots under `docs/images/` (228 KB) with a commented slot for the owner's GIF. The APK size stays marked provisional until T26 turns shrinking on. "How this was built" is preserved byte-identical.
>
> **T26 — DONE.** R8 and resource shrinking are on in the committed config. No keep rules of our own are needed, and `app/proguard-rules.pro` records *why* rather than staying silently empty — nothing in `:core:*` reflects, and the one reflective-looking call resolves to a framework class R8 never packages. Signing reads `keystore.properties` from the project root: absent → unsigned (CI needs that), present but incomplete → **build fails** rather than quietly producing an unsigned APK that looks signed.
>
> `app-release.apk` is signed with the owner's key: `CN=Darcha, O=Darcha, C=UZ`, SHA-256 `219b4af8…`, v2 scheme, **1,149,310 bytes (1.096 MB)** against a 5 MB budget. Not the debug key — checked explicitly, and the APK was pulled back off the device to confirm the installed certificate is the same one. The T22 R8 verification was **re-run against the shipping build** twice (T23 and T24 landed after it): once debug-signed, then again release-signed. Because the debug build had to be uninstalled first, the final run started from an empty DataStore — empty state → SAF pick → persistence across a force-stop → reopen, all in order — plus every fixture, dates, styling, merges, frozen panes and three-sheet switching. Zero FATALs.
>
> Shrinking dropped `error_unsupported_*`, correctly: `ErrorKind.Unsupported` is declared but never constructed, so R8 proved the branch dead. That is the same gap as the known `.ods` rough edge, now with a landmine attached — **T27 owns both halves.** `docs/RELEASE.md` and `docs/RELEASE_NOTES_v1.0.0.md` are written; `v1.0.0` tagged.
>
> **M4 exit criteria — met. v1.0.0 shipped.** 🎉

**M5 — v1.1**
- [x] T27 .ods → Unsupported · [x] T28 Theme colour vs black · [x] T29 Selection + copy · [x] 🧑 OWNER: producer fixtures · [x] T30 Corpus lock

> **T27 — DONE.** A renamed `.ods` now says "not supported" instead of "damaged". Detection stayed inside the header-read budget: OpenDocument v1.2 §3.3 pins the `mimetype` entry first, stored and extra-field-free, which puts the media type at byte **38** — so `HEADER_LEN` grew from 8 to 128 and nothing else changed. No `ZipFile`, no central directory, no inflation. The check is strict on all three rules and returns "not ODF" on any deviation, so it can miss but never misfire; six tests pin exactly that.
>
> **Both halves landed together, which was the whole point.** `aapt2` on the signed release APK shows `error_unsupported_title`/`_body` present in **both** locales, and the full source-vs-APK string diff is now empty where T26's had two missing. On device, the renamed `.ods` shows the ⓘ "not supported" screen while a truncated file still shows the ⚠ "damaged" one — the distinction survives shrinking, which a debug-only run could never have proved. APK grew 380 bytes.
>
> The fixture is hand-built to the spec (no LibreOffice on this machine) and its byte layout is asserted **independently of the detector**, at generation time and again in Kotlin — a fixture sharing an author with the code it tests proves nothing otherwise. `.xlsb` and other OOXML ZIPs still report "damaged" and are named as such in TECH_SPEC §7, `ContainerDetector`'s KDoc, FIXTURES.md and PERF.md rather than left as a second silent gap.
>
> **T28 — DONE.** `<color theme="1"/>` is `dk1` = `<a:sysClr val="windowText"/>` — a reference to the system's text colour, not a colour. The parser now returns `null` for it: *the document chose nothing*. `CellStyle.fontColor` never means black any more, and an author who wants black still gets a non-null `Color.BLACK`. **The T24 heuristic is deleted, not demoted** — `grep` for `shouldSubstitute`, `substituteNearBlackText` and `NEAR_BLACK` returns nothing.
>
> What replaced it is one rule in `TextLegibility`: honour what the document chose, unless it cannot be seen on a background *we* chose. Symmetric by construction, which is the point — white text on an unfilled cell had been invisible in **light** mode since v1.0, and near-black detection could never have found it. Never applies to a filled cell (there the document picked both colours), and the 1.5:1 threshold sits deliberately far below WCAG's design targets so that grey-on-white stays grey: this rescues legibility, it does not enforce contrast.
>
> **Decision recorded in TECH_SPEC §7 as the task required: `theme1.xml` is not read**, and the reason is that reading it would not change this outcome — `lt1`/`dk1` are `sysClr` references, so resolving them yields exactly the "let the renderer decide" the model now represents. What it *would* fix is themes 2–11, which are flattened to black today; that is a fidelity gap needing the index swap and `tint` handling, and it has its own pinned test so changing it stays deliberate.
>
> Verified on device in **both themes**, six cases each (One UI ignores `cmd uimode night`; the switch went through Display settings). The grey row is the one that matters: it stays dim in both, proving the rule is bounded. `styles-basic` re-checked as a regression guard. Tests 332 → 350.
>
> **T29 — DONE.** `TapCell` is wired, and it changed shape on the way: the intent now carries a resolved `CellRef` rather than the tap's pixel, because screen coordinates mean nothing without the geometry that produced them — and that geometry lives in the renderer, not in a reducer that must stay a pure function. Same split as `Fling → Scroll`; recorded in TECH_SPEC §9 and §10.
>
> **Hit-testing was the difficulty, as expected.** Each of the four regions has its own origin and a viewport with its frozen axes zeroed, so after translating into region space the calculation is the ordinary unfrozen one. Verified on `excel/frozen-both.xlsx` by tapping all four and reading the address off the selection bar: corner→`A1`, frozen rows→`B1`, frozen columns→`A3`, body→`C3`. Three unit tests pin it, and deliberately breaking the origin lookup fails exactly those three.
>
> **Decisions made and written down.** A touch during a fling stops the glide and selects nothing — standard behaviour, and the surprising one to get wrong; `stopMotion()` returns whether it stopped something so the tap can tell. The **displayed** string is what gets copied, not the raw value: a date cell holds `45306` and nobody who taps a cell reading `01-15-24` means to copy the serial. Verified by pasting into another app. The honest cost — `General` shows 11 significant digits, so a rounded display copies rounded — is in the KDoc. No toast on API 33+, where the system shows its own; the toast below that was verified on this Android 10 device.
>
> A selection bar carries the copy button and the full value, which also solves a real viewer problem: a cell narrower than its contents is clipped in the grid, and this is the only place a long value can be read. `ContentCopy` lives in `material-icons-extended`, so it is a labelled button instead — no new dependency, and more discoverable anyway.
>
> Selection survives rotation (verified in landscape) and resets on sheet switch. Frame cost is not measurable: two back-to-back runs over `big-50k`, 24/32/36/65 ms without a selection against 23/31/36/61 ms with one. Tests 350 → 360.
>
> **T30 — DONE, and the corpus paid for itself on day one.** Three Google Sheets exports went into `gsheets/`, golden-locked in `GoogleSheetsFixturesTest.kt` with every value read out of the files — nothing copied from the `excel/` equivalents, which is the only reason the finding below was possible.
>
> **The finding: `ySplit="2.0"`.** Google Sheets writes pane splits as decimals; Excel writes integers. `"2.0".toIntOrNull()` is `null`, so the parser fell back to `0` and had been **silently discarding the frozen panes of every Google Sheets export** — no error, just a sheet that scrolled when it should not have. The failing test was written first and reported before any fix, exactly as CLAUDE.md requires.
>
> **The root cause was an assumption, so the fix is not one line.** ECMA-376 types `CT_Pane/@xSplit` and `@ySplit` as `xsd:double` — an unfrozen *split* pane can sit between rows — and we had assumed integer. An audit of every numeric attribute in the parser followed: ids and indices (`numFmtId`, `fontId`, `fillId`, `sheetId`, cell `s`, row `r`, `<col>` `min`/`max`, `indexed`, `theme`) really are `xsd:unsignedInt`; measurements (`width`, `ht`, `defaultColWidth`, `defaultRowHeight`) really are doubles and were already read as such. **The two pane splits were the only mismatch.** `String?.asWholeCount()` now carries the contract with the reasoning attached, so the bug cannot return one attribute at a time.
>
> A fractional split floors: you cannot freeze half a row, and freezing one the author never asked for is the worse error. Negatives clamp to zero, absurd values saturate rather than wrapping negative through `Int`.
>
> Other producer variance recorded in FIXTURES.md: Google writes `<v>300.0</v>` for whole numbers, always emits `<sheetFormatPr>` with a 12.63 default width, omits empty rows **even when a merge references one**, ships a `drawing1.xml` per sheet with nothing in it, and declares Microsoft *Mac* namespaces it never uses. Two of those broke `tools/check_fixtures.py` too — its sheet-name regex assumed attribute order — and it was fixed rather than worked around.
>
> Two recipe deviations are left **reported, not accepted**: `12,5` typed with a comma (which turned out to be the most useful cell in the corpus — it locks that a comma decimal stays text) and two swapped city names. `libreoffice/` and `wps/` stay empty as documented placeholders. Tests 360 → 385.
>
> **M5 exit criteria — met. v1.1.0.** Three things v1.0.0 got *wrong* rather than three it was missing: a renamed `.ods` called damaged, dark mode guessing at text colour, and — found by the corpus itself — every Google Sheets frozen pane silently discarded. Plus cell selection and copy, the one feature. `versionCode 2`, `versionName 1.1.0`.
>
> The `check_fixtures.py` divergences are now **accepted rather than left red**, each with its reason printed on every run: a checker that stays red trains people to skim past it, and then the next real divergence goes unnoticed. `docs/FIXTURE_RECIPES.md` was corrected to match what the files actually contain — including an instruction to type the comma decimals deliberately, since that cell turned out to be the most useful one in the corpus.

**M6 — v1.2**
- [ ] T31 Scroll to cell · [ ] T32 Search engine · [ ] T33 Search UI · [ ] T34 Range selection + copy

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

# M2.5 — Progressive first paint

## T15.5 — Progressive first paint

```text
PERF.md shows 2.4 s time-to-first-cell for big-50k-rows.xlsx (1.78 MB), violating TECH_SPEC §5.
What was measured is time-to-complete-parse: the parser already streams in chunks (§7, T8),
but the grid only rendered once the whole sheet was done. Fix the last mile.

- Render from partial data: chunks emitted during parsing must reach the grid.
- Keep the flow unidirectional and keep parse progress visible until the sheet completes.
- scrollBounds must grow as rows arrive, not clamp the user to the first chunk.
- Guard against a stale in-flight parse painting over a newer document.
- Re-measure and update docs/PERF.md with before/after numbers.

Acceptance: big-50k shows its first cells in well under 1 s on the Samsung A31; scrolling
during an in-flight parse does not break or jump.
```

## T15.6 — Chunks carry their layout

```text
T15.5 left partial paints using the sheet's *default* column widths: <cols> precedes
<sheetData>, so the parser knows them early, but RowsChunk did not carry them. Almost every
real business spreadsheet sets column widths, so reflow-on-completion would be the normal
case, not an edge case.

- :core:parser unfrozen for this task only. ADDITIVE change: no existing public behaviour
  changes, and every existing core test stays green without being edited.
- Chunks carry the layout known so far (RowsChunk.layout). Row heights stream with their
  rows — that is fine and deliberate: a later row never shifts an earlier one. Do not
  "fix" it later.
- The repository merges chunk layouts the way it merges chunk rows.
- Close the corpus gap: a synthetic fixture with clearly non-default column widths
  (openpyxl column_dimensions), so the layout path is covered by tests.
- Record in TECH_SPEC §7 and §9.1 that chunks carry layout.

Acceptance: golden tests on the new fixture; a partial paint of a large file with custom
column widths does not reflow on device when the parse completes.
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

---

# M5 — v1.1

**Scope decision: small and finishable.** Four tasks, each independently
shippable. In-sheet **search is deliberately deferred to v1.2** — it is the
larger of the two usability gaps and dragging it in here would turn a tidy
milestone into an open-ended one. Charts/images and the DOCX viewer are their
own milestones and are not in scope.

**`:core:parser` is UNFROZEN for T27 and T28 only**, under the same conditions as
T15.6:

- **Additive changes.** No existing public behaviour changes as a side effect.
- Existing core tests may be **inverted only when the contract genuinely
  changed**, and the rename must state the new contract. **Never loosened to make
  a failure go away.** If a test needs editing for any other reason, stop and say
  so.
- Every fixture-backed golden value stays read from the file, never typed.

`:core:parser` is frozen again for T29 and T30.

## T27 — .ods detection → Unsupported

```text
A real OpenDocument spreadsheet renamed to .xlsx reports "This file is damaged"
(ErrorKind.Corrupted). It is an intact spreadsheet, just the wrong kind, and
ErrorKind.Unsupported already exists and is documented for exactly this case —
nothing has ever constructed it.

- ContainerDetector reads the ODF `mimetype` entry: in an .ods it is the FIRST
  zip entry and is STORED, not deflated, so it can be identified cheaply and
  without decompressing the archive. Return Unsupported, not Corrupted.
- Decide and state what happens for .xlsb and other recognizable-but-unsupported
  containers, rather than leaving a second silent gap behind this one.

CRITICAL — the two halves must land in the SAME task:
Resource shrinking removed error_unsupported_title and error_unsupported_body
from the v1.0.0 APK, correctly: R8 proved the ErrorScreen branch unreachable
because the kind was never constructed. The moment the parser starts producing
it, those strings must be reachable again. Ship one without the other and the
release build shows a BLANK error screen while debug looks perfectly fine —
the worst class of bug, invisible until a user hits it.

- Restore/confirm both strings in values/ AND values-uz/, and keep them within
  ErrorCopyTest's word-boundary rules (no internals: no "OOXML", no "ZIP", no
  "ErrorKind", no ".ods" jargon the reader does not need).
- Fixture: a real .ods renamed to .xlsx, committed under fixtures/synthetic/
  (or its own producer folder), described in FIXTURES.md, with a golden test.
- Verify on a SIGNED RELEASE build, not just debug: aapt2 dump resources must
  show both strings present, and the error screen must render on device.
- Update docs/PERF.md (the error matrix row currently says Corrupted, and the
  "known rough edge" paragraph) and docs/RELEASE_NOTES — this closes a limit
  that v1.0.0 shipped with.

Acceptance: renamed .ods reports "not supported" on a signed release build;
aapt2 confirms the strings survive shrinking; golden test on the fixture.
```

## T28 — Theme colour vs explicit black

```text
Every real Excel file writes <color theme="1"/> for ordinary text. Theme 1 is
"window text", not black — but the parser resolves it to black as a documented
v1 fallback (§7), so by the time a style reaches the renderer, "the default
text colour" and "deliberately black" are the same value and cannot be told
apart. T24 worked around this with a near-black heuristic in dark mode.

Carry the distinction properly instead:

- The model represents "theme text colour" as its own thing, distinct from an
  explicit RGB that happens to be black. Additive — existing consumers of the
  resolved colour keep working.
- The renderer then substitutes on the real distinction, and the near-black
  heuristic in GridColors.shouldSubstitute goes away. Delete the workaround;
  do not leave both paths in place.
- This fixes the INVERSE case too, which the heuristic cannot: white text on an
  unfilled cell is invisible in LIGHT mode today. A file authored for a dark
  background is as legitimate as one authored for a light one.
- The "never substitute on a filled cell" rule stays: black on the author's
  yellow is a deliberate pairing and must survive.
- Reading the actual workbook theme part (xl/theme/theme1.xml) is a separate
  question. Decide whether T28 needs it or whether the theme-vs-explicit flag is
  enough on its own, and write down which and why — do not half-do it.

Acceptance: a fixture with explicit black text and a fixture with theme-default
text render differently in dark mode; white-on-unfilled is readable in light
mode; the near-black heuristic is gone from the renderer.
```

## T29 — Cell selection and copy

```text
TapCell was built in T18 (merged cells map covered → anchor via anchorOf) and
deliberately left unwired for v1.0. Wire it.

- Tap selects a cell; the selection is drawn clearly at any zoom.
- A tap anywhere inside a merged range selects the ANCHOR, not the covered
  cell — that is what anchorOf already returns; the selection outline must
  follow the merged bounds, not one cell of it.
- The selected cell's value can be copied to the clipboard. Decide and state
  WHICH value: the displayed text (what the user sees, formatted) or the raw
  value. One of them, documented, not both silently.
- Keep the MVI discipline: selection is state, produced by reduce, not held in
  the Canvas.
- Frozen panes: a tap in each of the four regions must select the right cell.
  The regions have translated origins, so this is where hit-testing breaks.
- No text SELECTION inside a cell (dragging across characters) — that is a
  different feature and belongs with search in v1.2. Say so in the commit so it
  is not read as an oversight.

TECH_SPEC §14 currently lists "text selection & copy" as post-v1. Update the
spec: cell selection and copy are v1.1 scope now, and in-cell text selection
stays post-v1.

Acceptance: tap-to-select works in all four pane regions and on merged ranges;
copy puts the documented value on the clipboard; verified on device.
```

## 🧑 OWNER — before T30

```text
The corpus is "grouped by producer", but only two folders are filled:
excel/ (Microsoft Excel Online, golden-locked) and synthetic/ (openpyxl plus
one hand-crafted file). gsheets/, libreoffice/ and wps/ are empty.

Produce the same small workbook from each tool you can reach — Google Sheets
export first, then LibreOffice Calc and WPS Office if available — following
docs/FIXTURE_RECIPES.md. Values, dates, a few styles, one merge, one frozen
pane. Do not re-save any file through another application: that destroys the
producer identity that makes it worth having.

Validate before handing over:  python3 tools/check_fixtures.py
```

## T30 — Fill the fixture corpus

```text
Lock whatever the new producer files actually contain.

- Golden tests per producer, values READ OUT OF the committed files. Never typed
  from memory, never copied from the excel/ expectations on the assumption that
  two producers agree — the whole point is that they do not.
- FIXTURES.md documents each file and the divergences it captures. Where a
  producer differs from Excel or openpyxl, say WHAT differs; that paragraph is
  the actual deliverable, not the file count.
- If a new file breaks the parser: add it as a FAILING test first, then fix.
  A parser fix here reopens :core:parser — stop and ask before touching it.
- If a producer turns out to be unreachable, say so in FIXTURES.md and leave the
  folder empty rather than filling it with a re-saved impostor.

Acceptance: every non-empty producer folder is golden-locked and described;
README's "half the corpus comes from Microsoft Excel Online" line is updated to
match reality.
```

---

# M6 — v1.2

**Theme: making a large sheet navigable.** Search is the biggest remaining gap.
In a 50,000-row file the only way to find anything today is to scroll, and no
amount of rendering fidelity fixes that.

**`:core:parser` stays FROZEN — verified, not assumed.** Everything these four
tasks need is already public API:

| Needed | Already exists |
|---|---|
| Walk the sparse model | `SheetData.rows: Map<Int, Row>`, and `Row.columns` / `values` / `styleIds` |
| The string a cell displays | `FormattedValueCache.format(value, styleId)` |
| A rectangular region | `CellRange(startRow, startCol, endRow, endCol)` in `:core:model` |
| Viewport, bounds, region origins | `Viewport`, `ScrollBounds`, `PaneRegions`, `GridGeometry` |

So this milestone is viewer-side from end to end. `:core:model` is expected to be
untouched as well; if any task finds it needs something there, **stop and ask**
rather than widening the change — the same rule that has applied since T15.6.

## T31 — Scroll to cell

```text
A programmatic way to bring a cell into view. Prerequisite for search, and
useful on its own.

- Given a CellRef, produce the Viewport that shows it, clamped to ScrollBounds.
- Leave MARGIN: a cell flush against the edge of the screen is technically
  visible and practically useless. Decide the margin (in cells or in content
  px), state it, and make it survive zoom.
- CRITICAL: the target must land in the BODY region. Frozen rows and columns
  are drawn OVER the body (TECH_SPEC §9, T19), so a naive scroll can park the
  target underneath a frozen strip where it is invisible while the maths
  believes it is on screen. The scroll floor is minScrollX/minScrollY, but that
  is not sufficient on its own -- work out what is, and write down why.
- A cell already comfortably visible must NOT move the viewport. Scrolling on
  every match when the next match is already on screen is disorienting.
- A target inside a merged range should bring the WHOLE range into view where
  it fits, or its anchor when it does not.
- Pure geometry: no Compose, no coroutines. Unit-testable, and tested at
  several zoom levels and against all four pane configurations (none, rows,
  columns, both).

Acceptance: unit tests covering already-visible, off-screen in each of four
directions, clamped at each bound, inside a merge, and -- the one that matters
-- a target that would otherwise land under a frozen strip.
```

## T32 — Search engine

```text
Find matches within the ACTIVE SHEET. Cross-sheet search is deferred to a later
version; say so in the code and the spec rather than leaving it ambiguous.

Decisions to make and document, not guess:

- MATCH AGAINST DISPLAYED TEXT OR RAW VALUE? T29 chose the displayed string for
  copy, and searching is the same question wearing a different hat: a user
  hunting "01-15-24" is looking at the screen, but one hunting "45306" may know
  the underlying serial. Consider matching BOTH and say why -- including what
  that costs, and what a match count means when one cell matches on two
  different strings.
- BEHAVIOUR DURING A PROGRESSIVE PARSE. Rows arrive in chunks (T15.5), so a
  search started at 20% sees a fifth of the sheet. Decide: search what is
  loaded and re-run as more arrives, refuse until complete, or something else.
  Whatever it is, the match count must never silently lie about a sheet that is
  still growing.
- CASE SENSITIVITY, and partial vs whole-cell matching. Pick defaults that suit
  a viewer rather than a database, and justify them.

Performance, and this is where it gets interesting:

- big-50k is ~350k cells. Formatting every one of them is not free, and
  FormattedValueCache holds 2,048 entries -- sized for a viewport, not for a
  full-sheet scan. A naive search will evict everything the renderer needs and
  leave the grid re-measuring text on the next frame. Do not do that. Decide
  how search reads cell text without trashing the viewport's cache, and record
  the reasoning where the cache lives.
- Must not block the UI. Must be cancellable -- a user typing "January" issues
  seven searches, and six of them are dead the moment the next keystroke lands.
- No per-frame allocation in whatever the renderer ends up reading.

Acceptance: search over big-50k finds known matches with the UI still
responsive; measured on the A31 and recorded in docs/PERF.md, including what
the cache did. Unit tests for the matching rules, with fixtures.
```

## T33 — Search UI

```text
The surface for T32, using T31 to move.

- A search bar: open it, type, close it. Decide where it lives relative to the
  document header and the sheet tabs, and what it displaces.
- Match count -- and an honest empty state. "0/0" and "still searching" are
  different things and must look different.
- Next / previous navigation, wrapping at the ends. Wrapping should be
  perceptible; silently jumping from the last match to the first looks like a
  bug.
- The CURRENT match must be visually distinct from the other matches, not just
  selected. Two levels of highlight, both legible over any cell fill the
  document might have -- see TextLegibility (T28) for why "over any fill" is
  not a given.
- Integrate with T29 selection: decide whether the current match IS the
  selection or sits alongside it, and make the copy button do the obvious thing
  either way.
- All copy in strings.xml, both locales, and ErrorCopyTest's word-boundary
  rules apply. Verified in both themes on device.

Acceptance: find, step through, wrap, and close, on big-50k in both locales and
both themes, on device.
```

## T34 — Range selection and copy

```text
Extend T29's single cell to a rectangle.

- Selection becomes a range. CellRange already exists in :core:model -- decide
  whether to reuse it or keep a viewer-side type, and say why.
- Two ways to extend, and both should work: a drag, and a shift-style extend
  from the existing anchor. Decide what a plain tap does to an existing range.
- Drawn across the whole range, with the anchor still distinguishable. The
  outline must clip correctly per pane region (T19/T29) -- a range spanning a
  freeze boundary is drawn in more than one region and must not leak across the
  seam.
- MERGED CELLS: a range that touches a merge must contain the whole merge, not
  a slice of it, and the drawn outline must follow. This is the case that will
  be wrong first.
- COPY AS TSV so it pastes into a spreadsheet as cells rather than one blob:
  tab between columns, newline between rows, empty cells preserved as empty
  fields. Decide what a merged range contributes -- the value once, at its
  anchor, and empty for the covered cells, or something else. Say which.
- Hot path unchanged: still no per-frame allocation, and drawing a range must
  not cost measurably more than drawing one cell on big-50k.

Acceptance: drag-select across a freeze boundary and across a merge on device;
paste the result into another app and confirm it arrives as a grid, not a
string. Frame cost measured on big-50k.
```
