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

| File | Size | Sheet | Time |
|---|---|---|---|
| `multisheet.xlsx` | 5.8 KB | 1 row × 3 sheets | **86 ms** |
| `values-basic.xlsx` | 4.9 KB | 4 rows × 3 cols | **116 ms** |
| `big-50k-rows.xlsx` | 1.78 MB | 50,001 rows × 7 cols ≈ 350k cells | **2,427 ms** (2,373 / 2,427 / 2,503 over three cold runs) |

**Against the §5 target (< 1 s for typical files under 5 MB):** typical files land
an order of magnitude inside it — around 100 ms. The 50k-row fixture does not, at
~2.4 s.

> **Known gap.** TECH_SPEC §7 describes progressive loading — "the first ~200 rows
> are emitted to the UI immediately; the rest continues on `Dispatchers.IO`". The
> parser does stream in chunks and the UI shows a progress bar, but the grid is
> only rendered once the sheet is fully parsed, so what is measured above is
> really *time-to-complete-parse*. Rendering the first chunk while the rest
> streams in would bring the large-file number close to the small-file one. Not
> attempted in M2; a candidate for M3 or a dedicated task.

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
