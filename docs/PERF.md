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

## Cells drawn per frame

The renderer visits only `GridGeometry.visibleRange`, so cost tracks the window,
not the sheet (TECH_SPEC §9). Logged under the `Darcha.Grid` tag when the count
changes.

| Situation | Drawn |
|---|---|
| `big-50k-rows.xlsx`, portrait, top of sheet | 37 × 7 = **259** |
| `multisheet.xlsx`, landscape | 15 × 13 = **195** |
| `sparse-gaps.xlsx`, scrolled to `AA100` (viewport at x=4608, y=6272) | 31 × 7 = **217** |

The count is a function of window size and zoom only. Scrolling 50,000 rows deep
or into a sheet whose used range ends at `AA100` does not change it — which is
the whole point of the culling.

## Scroll

Verified mechanically on the device (drag events injected over adb, so this is
not a substitute for a human hand on glass):

- A drag moves the viewport proportionally and the drawn-cell count stays flat.
- A flick glides and decays to a stop.
- Scrolling clamps at the used range: on the 7-column fixture the offset stops at
  `x=912` and repeated flicks do not move it further.

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

That writes both `big-50k-rows.xlsx` and `big-50k-wide.xlsx`. Push the wide one,
open it, and screenshot once while the progress bar is still up and once after it
disappears; the grid body of the two frames must be identical.
