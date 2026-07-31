# Performance — measured

Numbers from a real device, not an emulator. Re-measure after any change to the
parser, the geometry engine or the renderer, and record the device: these are
comparable only against the same hardware.

## Device

| | |
|---|---|
| Model | Samsung Galaxy A31 (`SM-A315N`) |
| Android | 10 (API 29) |
| SoC | MediaTek Helio P65 (`mt6768`), 8 cores |
| RAM | 3.6 GB |
| Display | 1080 × 2400 @ 420 dpi |

A mid-range phone from 2020 — deliberately not a flagship, since TECH_SPEC §5
targets "a mid-range device".

## APK size

TECH_SPEC §5 sets **< 5 MB**. Measured on the release variant:

| Build | APK |
|---|---|
| Unshrunk, as configured before T26 (`isMinifyEnabled = false`) | **6.27 MB** — over the target |
| Release with R8 + resource shrinking (T22 probe) | **0.87 MB** (0.88 MB signed) |
| …plus DataStore and the recents list (T22) | **1.06 MB** (1.07 signed) — the new dependency cost ~190 KB shrunk |
| **v1.0.0 as shipped (T23 + T24 included, shrinking committed)** | **1.088 MB unsigned, 1.10 MB signed** |

Inside the shrunk APK: `classes.dex` 1.53 MB uncompressed, `resources.arsc`
76 KB. The app carries no images, no fonts and no libraries beyond Compose and
lifecycle, so almost all of the 6.27 MB was unreferenced framework code that R8
removes.

**Against the §5 target of 5 MB, v1.0.0 ships at 1.10 MB — 4.5× of headroom.**
The error screens, adaptive icon and Uzbek locale added between the T22 probe and
release cost about 40 KB in total.

### The shrunk build was then run, not just measured

A steep drop like that is exactly what a **broken** R8 build also looks like, so
the number stayed provisional until the APK was signed with a debug key,
installed on the A31 and exercised. All of it passed, with **no keep rules of our
own** — only AGP's `proguard-android-optimize.txt`:

| Checked | Result |
|---|---|
| `values-basic`, `dates`, `styles-basic`, `merged`, `frozen-both`, `multisheet`, `big-50k` | all open and render correctly |
| Number and date formatting (T16) | `01-15-24`, `13:30`, `1/15/24 13:30` — unchanged |
| Styles, merges, frozen panes (T17–T19) | unchanged |
| Scrolling `big-50k` | works; frame times **better** than debug — 12 / 18 / 20 / 42 ms against 15 / 24 / 27 / 34 |
| Sheet switching (3 sheets, parsed on demand) | works |
| Recents: persist, survive a restart, reopen | works — **DataStore is the app's biggest R8 risk and it survived** |
| FATAL exceptions across the whole run | **0** |

**One real discovery: `Log.d` is stripped in release.** AGP's optimize config
removes it via `-assumenosideeffects`, so the `Darcha.Viewer` and `Darcha.Grid`
diagnostics — which every measurement in this file is read from — simply do not
exist in a shrunk build. That is the right behaviour for a shipped app, but it
means **all performance measurement must be done on the debug build**, and a
release run has to be verified by what is on screen. The first attempt at this
verification reported "no loads" for every file purely because of it.

### Re-verified against the shipping build (T26)

The run above was made at T22. T23 (error screens) and T24 (icon, Material 3,
Uzbek) added code afterwards, so the verification was **repeated against the
v1.0.0 build** rather than assumed to still hold — first with a debug-key copy of
the exact release APK, then **again with the real release-signed APK** once the
keystore existed.

The final run is the one that closes T26's acceptance criterion. The debug build
was uninstalled first (signatures differ), `app-release.apk` installed, and the
APK pulled back off the device to confirm the certificate matches what was
shipped:

| | |
|---|---|
| Certificate DN | `CN=Darcha, O=Darcha, C=UZ` — **not** the debug key's `CN=Android Debug, O=Android, C=US` |
| SHA-256 | `219b4af8516337c9ebae7f3fd31a321611913510a72841d50f400d60b4eda8bc` |
| Same digest read back from `/data/app/…/base.apk` | ✓ |
| Signature scheme | v2, `Verifies` at `--min-sdk-version 26` |
| Certificate valid until | 2053 |
| **Signed APK** | **1,149,310 bytes = 1.096 MB** |

| Checked | Result |
|---|---|
| `values-basic`, `excel-dates`, `styles-basic`, `merged`, `frozen-both`, `multisheet`, `big-50k` | all open and render correctly |
| Number and date formatting (T16) | `01-15-24`, `13:30`, `1/15/24 13:30`, `12-31-24` — unchanged |
| Styling in dark mode (T17, T24) | bold / italic / red text / yellow fill / centre + right align, all correct — including the near-black substitution, which leaves black-on-yellow alone |
| Merged anchors, frozen panes after a scroll (T18, T19) | correct; the frozen column stays pinned with a clean seam |
| Sheet switching, parsed on demand (T12) | works — tab 2 of `multisheet` loads its own content |
| **Error screen (T23) in a shrunk build** | **renders — icon, title, body and action button all present** |
| Localisation (T24) | the whole run was in Uzbek + dark; no missing or fallback strings |
| Recents: persist, survive a force-stop, reopen | works — on the release-signed run this started from an **empty** store (a fresh install after uninstall), so the empty state, a SAF pick, persistence and reopening were all exercised in order |
| Two real-world business `.xlsx` files from the device | open and render, no crash |
| FATAL exceptions across the whole run | **0** (`logcat -b crash` empty) |

One note for whoever repeats this: on this device, **injected taps do not register
in the system file picker**. Both the list and the search results ignore
`input tap`; `input keyevent DPAD_DOWN … ENTER` works. That is a DocumentsUI
quirk on this A31, not an app behaviour — the same taps drive Darcha's own UI
fine.

### T29 — selection, verified in all four frozen regions

The hit-test is the part that breaks, so it was checked where it breaks:
`excel/frozen-both.xlsx` (1 frozen column, 2 frozen rows), tapping each region
and reading the address off the selection bar rather than judging by eye.

| Region | Tapped | Selected |
|---|---|---|
| Frozen corner | column A, row 1 | **A1 · Nomi** |
| Frozen rows | column B, row 1 | **B1 · Soni** |
| Frozen columns | column A, row 3 | **A3 · Anor** |
| Body | column C, row 3 | **C3 · 8000** |

Four regions, four different answers. A hit-test that ignored the region origins
would have returned the body's cell for all of them.

| Also checked on device | Result |
|---|---|
| Tap inside a merged range (`synthetic/merged`) | selects the **anchor** `A1`, and the outline spans the whole `A1:C1` range rather than one cell of it |
| Copy, pasted back into another app's text field | clipboard held **`01-15-24`** — the displayed date, not the serial `45306` |
| Copy confirmation on Android 10 | the app's own toast appears (there is no system confirmation below API 33, where the app stays silent instead) |
| Tap during a fling | **stops the glide, selects nothing** |
| Tap with nothing moving | selects — `B99 · 686` |
| Tap on the row-header strip | clears the selection |
| Rotation to landscape | selection survives, bar still shows `A1 · 01-15-24` |
| Sheet switch (`multisheet`) | selection resets, bar disappears |
| FATAL exceptions | **0** |

**Frame cost: none measurable.** Twelve injected flings over `big-50k-rows.xlsx`,
the two runs back to back so thermal drift cannot separate them:

| | 50th | 90th | 95th | 99th |
|---|---|---|---|---|
| No selection | 24 ms | 32 ms | 36 ms | 65 ms |
| Selection active | 23 ms | 31 ms | 36 ms | 61 ms |

The selected run measures marginally *faster*, which is noise rather than an
improvement — the honest reading is that one stroked rect per frame is below what
this method can resolve. It is bounded by construction anyway: four integer
comparisons reject the three regions the selection is not in, and only the
remaining one pays for a binary search and a `drawRect`. Nothing allocates —
`Offset` and `Size` are value classes.

*(These medians sit above the 15–18 ms recorded earlier for the debug build. The
device had been running for hours by this point, and PERF.md's own caveat applies:
only runs close together in time are comparable. The A/B pair above is.)*

### T28 — text colour, verified in both themes and both directions

The T24 near-black heuristic is deleted, not demoted: `grep` for
`shouldSubstitute`, `substituteNearBlackText` and `NEAR_BLACK` returns nothing.
One mechanism decides text colour now.

Checked on the A31 against `text-contrast.xlsx`, whose six rows are one case each.
Both themes were exercised — One UI ignores `cmd uimode night`, so the switch was
made through Display settings.

| Row | Dark mode | Light mode |
|---|---|---|
| theme=1 text | light ✓ | dark ✓ |
| explicit black, no fill | **rescued to light** ✓ | black, untouched ✓ |
| explicit white, no fill | white ✓ | **rescued to dark** ✓ — broken since v1.0 |
| black on yellow fill | black on yellow, untouched ✓ | same ✓ |
| white on navy fill | white on navy, untouched ✓ | same ✓ |
| grey `#999999`, no fill | visibly dimmer, **not** rescued ✓ | same ✓ |

The grey row is the one that proves the rule is bounded. If it came out the same
weight as the rows above it, the viewer would be enforcing contrast rather than
rescuing legibility — rewriting the document instead of showing it.

`styles-basic.xlsx` was re-checked in both themes as a regression guard, since it
is the file T24's heuristic existed for: bold/italic/centre/right all legible,
`Red` still red, and black-on-yellow still black. Zero crashes.

### T27 re-verification — the shrinking landmine, disarmed

T26 left a live hazard: `error_unsupported_*` had been shrunk out of the APK
because nothing constructed the kind. T27 makes the parser construct it, so the
two halves had to be checked **together**, on a signed release build — a debug
build would have looked correct either way and proved nothing.

| Checked on the signed release APK | Result |
|---|---|
| `aapt2 dump resources` for `error_unsupported_title` / `_body` | **present** — and in both configs, `()` English and `(uz)` Uzbek |
| Every string in `values/strings.xml` vs the shrunk APK | **all 25 present**; the T26 diff had two missing |
| Renamed `.ods` on device | **"Bu turdagi fayl qo'llab-quvvatlanmaydi"** with the ⓘ icon |
| Truncated `.xlsx` on device — the distinction must survive | still **"Bu fayl shikastlangan"** with the ⚠ icon, different words and different icon |
| A normal `.xlsx` on device | opens and renders — no regression from the new header read |
| FATAL exceptions | **0** |

APK: 1,149,690 bytes — 380 bytes larger than v1.0.0, which is the two strings
coming back plus the detection code.

**Resource shrinking removed two strings, and it was right to.**
`error_unsupported_title` and `error_unsupported_body` are not in the shipped
APK. The cause is not a broken rule: `ErrorKind.Unsupported` is **declared in
`:core:model` but never constructed anywhere in the app**, so R8 proved the
`ErrorScreen` branch unreachable and the shrinker then dropped the two strings
only that branch referenced. Confirmed by `aapt2 dump resources`; every other
string, both locales, and both `string-array`s survived.

That is correct shrinking, and it is also a finding: T23 built a screen that
cannot appear. It is the same gap as the known rough edge below — a renamed
`.ods` reports "damaged" because nothing ever produces `Unsupported`. **The
landmine to remember:** if a future version starts emitting that kind, the screen
will be blank in release builds until the strings become reachable again. The fix
is to make the parser produce it, not to add a keep rule for dead copy.

**Not covered: pinch zoom.** `sendevent` needs root and the A31 is retail, so a
two-finger gesture cannot be injected (see "Focal-point stability" below). The
zoom path is plain Kotlin reached from the same gesture loop as the scrolling
that was verified, and R8's usual hazards — reflection, resource lookup,
serialization — do not appear in it. Still unproven on a shrunk build.

**T26 owns the real change.** The build config was reverted after measuring. What
this run establishes is that it will work when switched on, and that no keep
rules are needed today.

## Recents on device (T22)

| Check | Result |
|---|---|
| Seven documents opened by `ACTION_VIEW` | recents stayed **empty** — the honesty rule holds on hardware |
| Two opened through the SAF picker | remembered, newest first, and reopening one did not duplicate it |
| Force-stop and relaunch | **both survived**, and tapping one reopened the document |
| `cacheDir` across open A → B → A, then three more | **exactly one** temp copy at every step, with a new name each time |
| A remembered file deleted underneath the app | row reads "No longer available", is **inert** to taps, and Remove works |

The `cacheDir` result is the §9.1 session lifetime holding under the access
pattern recents creates: the copy belongs to the open document, so each load
releases the previous one before making its own.

**The availability probe had to be strengthened to get that last row right.**
Checking `persistedUriPermissions` and then querying the provider for metadata
was not enough: the downloads provider keeps answering `query` for a document
whose file has been deleted, so the row still claimed to be fine. It now opens
the file descriptor and closes it — the only question the row is really making is
"can this be read", and that is the way to ask it.

What could **not** be triggered from adb is a genuine permission revocation:
there is no command to drop a single persisted URI grant, and the provider here
is DocumentsUI, which cannot be uninstalled. Deleting the file exercises the same
branch and the same rendering; the grant-held half is covered by unit tests.

## Error states, verified on device (T23)

Every failure was produced from a real file rather than a simulated one, and
timed — a cap that only trips after a long parse is a slow error screen, which is
a user-facing cost.

| File | Result | Time to the screen |
|---|---|---|
| A genuinely password-protected `.xlsx` | **Encrypted** | 602 ms |
| A truncated `.xlsx` (first 3 KB of a real one) | **Corrupted** | 594 ms |
| A real OpenDocument spreadsheet renamed to `.xlsx` | **Corrupted** | 574 ms |
| …the same case after T27 | **Unsupported** | — |
| 60,000 × 20 = 1.2 M cells | **TooLarge**, stopped at 1,004,000 cells | 5.5 s |
| A `file://` URI Darcha has no permission for | **Unreadable** | 670 ms |

No crashes in any of it.

**The password-protected file is real.** msoffcrypto-tool 5.4.2 encrypted a
fixture with ECMA-376 agile encryption in a throwaway virtualenv — verified
before use: OLE container, the right password decrypts it back to a `PK`
archive, the wrong one is rejected. The venv and the file were deleted
afterwards; neither is committed. To reproduce:

```bash
python3 -m venv /tmp/enc && /tmp/enc/bin/pip install msoffcrypto-tool
/tmp/enc/bin/python -c "
from msoffcrypto.format.ooxml import OOXMLFile
src='core/parser/src/test/resources/fixtures/synthetic/values-basic.xlsx'
OOXMLFile(open(src,'rb')).encrypt('darcha-test', open('/tmp/e-password.xlsx','wb'))"
```

**TooLarge takes 5.5 s, and that is inherent.** The cap counts cells as they
stream, so it cannot fire until a million of them have been parsed — which is
most of the work. Stopping earlier would mean trusting `<dimension>`, and our own
`big-50k-rows.xlsx` has no `<dimension>` at all. The alternative to waiting is
guessing wrong on exactly the largest files.

**~~Known rough edge~~ — fixed in T27.** A renamed `.ods` used to report "This
file is damaged", which is untrue of an intact spreadsheet of the wrong kind. It
now reports "not supported". The check costs one short header read, not an
archive open: OpenDocument v1.2 §3.3 requires the `mimetype` entry to be first,
stored and free of any extra field, which pins the media type to byte 38. See
`ContainerDetector` and the `ods-renamed.xlsx` fixture.

**Still reported as "damaged": `.xlsb`, `.docx` and other OOXML ZIPs.** They
carry no fixed-offset marker — the discriminator is `[Content_Types].xml`, found
only via the central directory at the end of the archive — so a header check
cannot reach them and T27 deliberately did not try. The honest home for that is
`WorkbookParser`, which already has the `ZipFile` open. Named here rather than
left as a second silent gap behind the first.

## Themes and languages, verified on device (T24)

Four combinations, error screens included in each run rather than only the happy
path:

| | Uzbek | English |
|---|---|---|
| **Dark** | ✅ home, styled sheet, frozen panes, error | ✅ home, styled sheet, error |
| **Light** | ✅ home, styled sheet, error | ✅ home, error |

No crashes, and no untranslated string in the Uzbek run.

**The dark-mode text rule was the point of the styled-sheet screenshot.** On
`styles-basic.xlsx` in dark: "Bold", "Italic" and "Center" carry theme-1 black on
no fill and come out light and readable; "Fill" is black on the author's yellow
and **stays black**, because the substitution skips filled cells; "Red" is not
near-black and is left alone. See TECH_SPEC §9.

**How the combinations were reached.** The A31 is genuinely set to `uz-Latn-UZ`
with One UI dark mode on, so *uz + dark is the device's own configuration* — that
one is fully native, and it proves `isSystemInDarkTheme()` is wired. The other
three had to be forced, because this is a retail device:

- `persist.sys.locale` needs root, and `cmd locale` does not exist on Android 10,
  so English was reached by building with `resourceConfigurations = "en"` — the
  uz resources are absent, so the device falls back to default. That renders the
  English resources for real; it does not exercise Android's locale switch.
- One UI overrides `cmd uimode night` and AOSP's `ui_night_mode`, both of which
  report success and change nothing, so light mode was reached by forcing
  `DarchaTheme(darkTheme = false)` in a temporary build.

Both temporary changes were reverted. What is unproven is the *transition* —
changing language or theme while the app is open — not the rendering.

## Time to first cell

Measured in the ViewModel from the `OpenFile` intent to the `Ready` state — the
whole pipeline: SAF stream → cache copy → ZIP open → workbook, shared strings and
styles → first sheet parsed.

Two numbers matter, and they are no longer the same thing (T15.5):

- **First cells** — the grid appears with the rows parsed so far. This is what
  §5 means by time-to-first-cell.
- **Complete parse** — the last row lands and the progress bar goes away.

| File | Size | Sheet | First cells | Complete |
|---|---|---|---|---|
| `multisheet.xlsx` | 5.8 KB | 1 row × 3 sheets | — | **86 ms** |
| `values-basic.xlsx` | 4.9 KB | 4 rows × 3 cols | — | **116 ms** |
| `big-50k-rows.xlsx` | 1.78 MB | 50,001 rows × 7 cols ≈ 350k cells | **175 ms** (223 / 159 / 175) | **2,380 ms** (2,773 / 2,442 / 2,380) |

Small files finish before a partial emission is ever due, so first-cells and
complete are the same moment for them.

**Against the §5 target (< 1 s for typical files under 5 MB): met.** Everything
measured shows its first cells inside 250 ms.

### Before and after T15.5

| | Before | After |
|---|---|---|
| `big-50k-rows.xlsx` first cells | 2,427 ms | **175 ms** — 14× |
| `big-50k-rows.xlsx` complete | 2,427 ms | 2,380 ms — unchanged, as expected |

The parse itself did not get faster; the grid simply stopped waiting for it. Rows
reach the renderer as they are parsed (first chunk immediately, then throttled to
250 ms), and the viewport survives every update — scrolling through the first
rows while the rest streams in does not jump.

## Layout on first paint (T15.6)

T15.5 left one gap: partial paints used the sheet's *default* column widths, so a
file with custom widths re-laid itself out when the parse completed. Almost every
real business spreadsheet sets column widths, so that was the normal case, not an
edge case — the corpus simply had no fixture with a `<cols>` block big enough to
show it. Chunks now carry their layout (TECH_SPEC §7).

**Verified on the device, not by eye.** A 50,000-row copy of the perf fixture with
wildly non-default widths (`A`=42, `D`=30, `F`=26 characters, the rest 4) was
opened, and two frames captured: one mid-parse (**24,800 rows**, progress bar up)
and one after completion (**50,001 rows**, bar gone). Cropping the grid body out
of both — column headers, row headers, gridlines, cells — gives two
**byte-identical** PNGs, while the header text and the progress-bar strip differ,
proving the frames really are the two different states.

| | Wide fixture, 50k rows |
|---|---|
| First cells | **248 ms** cold, 57 ms warm |
| Complete parse | 2,882 ms cold, 2,191 ms warm |
| Grid pixels moved between first paint and completion | **0** |

The renderer's own culling log agrees: `visible 37x4 = 148 cells` is logged once
for the whole load. It is emitted only when the drawn-cell count *changes*, so a
reflow would have produced a second line with a different column count.

**Row heights streaming is not a remaining gap.** A row's `ht` arrives with the
row, so every row is drawn at its true height from its first paint; later rows
only ever shift rows *below* them, which are not on screen yet. Only the column
axis had to be known up front, and `<cols>` precedes `<sheetData>` — so it is.

## Merged cells arriving late (T18)

`<mergeCells>` follows `<sheetData>`, so merges cannot reach a partial paint
(TECH_SPEC §7). What the user actually sees was checked rather than assumed, on a
50,000-row file with **501 merges** — a merged banner plus a merged section
header every 100 rows.

| | Mid-parse (1.3 s in) | Complete (2.6 s) |
|---|---|---|
| Merged title | drawn as a plain cell, text clipped to column A, fill one column wide | spans all seven columns, text centred across the span |
| Everything below it | — | **byte-identical to mid-parse** |

Cropping the two frames below the title row gives the same SHA-256, so **no
geometry moved when the merges landed**; only the title row repainted. That is
the T15.6 guarantee holding: column widths and row heights are known from the
first chunk, and merges change neither.

It reads as a cell whose text is too long for its column — ordinary, not broken —
and then snaps together. Waiting for merges would mean waiting for the whole
parse, which is exactly what T15.5 removed.

## Cells drawn per frame

The renderer visits only `GridGeometry.visibleRange`, so cost tracks the window,
not the sheet (TECH_SPEC §9). Logged under the `Darcha.Grid` tag when the count
changes.

| Situation | Drawn |
|---|---|
| `big-50k-rows.xlsx`, portrait, top of sheet | 37 × 7 = **259** |
| `multisheet.xlsx`, landscape | 15 × 13 = **195** |
| `sparse-gaps.xlsx`, scrolled to `AA100` (viewport at x=4608, y=6272) | 31 × 7 = **217** |
| `styled-20k.xlsx`, portrait, every cell filled and styled (T17) | 37 × 7 = **259** |

The count is a function of window size and zoom only. Scrolling 50,000 rows deep,
into a sheet whose used range ends at `AA100`, or into one where every cell
carries a fill and a font does not change it — which is the whole point of the
culling. **T17 added styling without adding a single drawn cell.**

## Text measurement cache (T17, re-measured in T20)

The cache key gained the style id in T17 — bold, italic and colour are different
glyphs, so one measurement can no longer serve two styles. T20 added a zoom
bucket to the same key. Both widen the key, so the capacity was re-measured each
time rather than assumed.

Measured by scrolling ~200 rows at a fixed zoom and reading the `Darcha.Grid` log:

| Sheet | Styles | Live keys | Hit rate at 2048 |
|---|---|---|---|
| `big-50k-rows.xlsx` | 1 | **1,109–1,111** | 100% |
| `styled-20k.xlsx` — fill, colour, bold/italic and alignment cycling over 8 combinations | 8 | **1,756** | 100% |

The pre-T17 capacity was **512**. On both sheets the cache sat pegged at exactly
512 entries — saturated, evicting layouts still on screen — against a working set
2–3.4× that.

### Under a zoom sweep (T20)

A pinch keeps several buckets alive at once, so the fixed-zoom numbers above are
a floor, not the worst case. Measured on `styled-20k.xlsx` across a scripted
1.0 → 2.9 → 0.5 → 1.0 sweep:

| Capacity | Peak entries | Hit rate |
|---|---|---|
| 2,048 | **2,048 — saturated** | 80% |
| effectively unlimited | **3,839** | 81% |

**The hit rate barely moves**, which is the interesting part: during a zoom most
misses are *compulsory* — a bucket never measured before — not capacity misses,
so a bigger cache cannot prevent them. Capacity was raised to **4,096** anyway,
to cover the measured peak: the cost of saturation is not the hit rate but the
~1,800 `TextLayoutResult`s evicted and re-measured on every gesture, which is GC
pressure the hit rate does not show. On the evidence, 2,048 would also have been
defensible; 4,096 buys the eviction churn for about 5 MB of transient heap.

Java heap: **21.9 MB** at 1,756 cached layouts, **26.9 MB** at 3,839
(`dumpsys meminfo`; total PSS 100–114 MB).

## Scroll

Verified mechanically on the device (drag events injected over adb, so this is
not a substitute for a human hand on glass):

- A drag moves the viewport proportionally and the drawn-cell count stays flat.
- A flick glides and decays to a stop.
- Scrolling clamps at the used range: on the 7-column fixture the offset stops at
  `x=912` and repeated flicks do not move it further.

### Focal-point stability (T20)

Pinch zoom promises that the cell under the fingers stays under them. That was
measured, not eyeballed: the ViewModel was temporarily instrumented to log the
content coordinate under the focal point before and after every zoom step, and a
scripted sweep drove **72 zoom events** about a fixed focal point on
`styled-20k.xlsx`.

**Drift: 0.0 content pixels on both axes, every event.** That covers the whole
wiring — gesture coordinate space (past the header strips and any frozen extent)
→ intent → reducer → viewport — not just the arithmetic, which
`FocalZoomTest` already pins across 180 combinations of zoom, focus and scale.

Frozen seams were checked at arbitrary *intermediate* zooms from the same sweep,
rather than only at the fixed levels T19 used: no gap, no doubled line, and the
header strips stayed consistent with the freeze throughout.

**What could not be measured this way.** The Samsung A31 is a retail device, so
`sendevent` on `/dev/input/event2` is denied and a real two-finger pinch cannot
be injected — `adb shell input` has no multi-touch. Everything above drives the
same intents through the same reducer and renderer, but the **two-finger centroid
and spread computation in the gesture loop is not covered on device**; it rests
on code review and on the double-tap path, whose gesture detection *was* verified
on hardware. Judge the pinch by hand.

### Frame times — did T17 cost anything?

`dumpsys gfxinfo` percentiles over 12 injected flings per run, three runs per
build, same device session. Styling adds per-cell fill rects and a formatter
call, so this needed checking rather than assuming.

| Build | 50th | 90th | 95th | 99th |
|---|---|---|---|---|
| T16 (before styling) | 15 / 15 / 16 ms | 24 / 23 / 26 | 27 / 27 / 31 | 34 / 32 / 40 |
| T17 (after styling) | 18 / 16 / 15 ms | 30 / 25 / 25 | 34 / 28 / 29 | 61 / 34 / 36 |

**No regression once warm.** The first scroll after opening a document is a
little slower — every `(text, styleId)` pair has to be measured once — and from
the second run the two builds are indistinguishable.

Two honesty notes about this method. `Janky frames %` is useless here: injected
swipes are flagged as high-input-latency, which counts as jank whatever the
renderer does (391–418 of ~460 frames in every run, before and after). And the
device drifts thermally over a long session — later runs are 2–4 ms slower
across the board — so **only runs close together in time are comparable.** For
that reason the 512-vs-2048 cache capacity comparison came out inconclusive on
frame times, and the capacity decision rests on the working-set measurement
above, not on a frame-time win.

Fling parameters live in `FlingDecay`: geometric decay at **0.95 per frame**,
stopping below **40 px/s**, giving a glide of roughly `v₀ × 0.33 s` — about
1,000 px for a 3,000 px/s flick.

**Subjective verdict: pending owner review.** Feel is judged by hand, not by
injected events.

## How to reproduce

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push core/parser/src/test/resources/fixtures/synthetic/big-50k-rows.xlsx /sdcard/Download/
adb logcat -c && adb logcat -s Darcha.Viewer Darcha.Grid
```

Open the file in the app; the load time and the drawn-cell counts appear in the
log.

For the T15.6 layout check you also need the wide variant. It is **not committed**
— a second 1.8 MB file would double the corpus for no assertion — so generate it,
measure, then delete it:

```bash
python3 tools/gen_fixtures.py big
```

That writes `big-50k-rows.xlsx` plus three measurement aids: `big-50k-wide.xlsx`
(T15.6 layout), `styled-20k.xlsx` (T17 styling and cache) and `big-merged.xlsx`
(T18 merges arriving late). Push
the wide one, open it, and screenshot once while the progress bar is still up and
once after it disappears; the grid body of the two frames must be identical.

None of the three is committed — they are measurement aids, not golden fixtures,
and together they would multiply the size of the corpus several times over for no
assertion. Delete them when the measurement is done.
