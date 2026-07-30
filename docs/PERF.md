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
| Release as configured today (`isMinifyEnabled = false`) | **6.27 MB** — over the target |
| Release with R8 + resource shrinking | **0.87 MB** (0.88 MB signed) |

Inside the shrunk APK: `classes.dex` 1.53 MB uncompressed, `resources.arsc`
76 KB. The app carries no images, no fonts and no libraries beyond Compose and
lifecycle, so almost all of the 6.27 MB was unreferenced framework code that R8
removes.

**The §5 target is comfortably reachable — 5.7× of headroom** — and the current
overshoot is only because shrinking is not switched on yet. Worth knowing early
rather than at release: there is room to spend, not a problem to solve.

Two honest caveats on that 0.87 MB:

- It was built with the AGP default ProGuard rules and **no keep rules of our
  own**. R8 can strip something that only fails at runtime, so this is a
  measurement of a build that *compiled and packaged*, not one proven to run.
  The smoke test was cut short when the device disconnected mid-run.
- The build config was reverted immediately afterwards. **T26 owns the real
  change**, and must re-measure and verify the shrunk build actually runs before
  relying on this number.

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
