# Darcha v1.1.0

Four changes, three of them things v1.0.0 got wrong rather than things it was
missing.

**minSdk 26 (Android 8.0) · no INTERNET permission**

---

## Tap a cell to select and copy it

Tapping a cell outlines it and opens a bar showing its address and value, with a
Copy button. The bar earns its row of screen twice: a cell narrower than its
contents is clipped in the grid, so this is the only place a long value can
actually be read.

What gets copied is **what you see** — a date cell showing `01-15-24` copies
`01-15-24`, not the serial number `45306` underneath it. Darcha has no editing
and no export, so there is nothing for a raw value to round-trip into.

Tapping inside a merged range selects the whole range. A touch during a fling
stops the glide instead of selecting, the way every scrolling surface behaves.
Selection survives rotation and clears when you switch sheets.

## A renamed `.ods` now says "not supported", not "damaged"

v1.0.0 told you an OpenDocument spreadsheet was damaged. It was not damaged — it
was intact and simply the wrong kind of file, and saying otherwise was false.

Detection costs one short read of the file header rather than opening the
archive, which is possible because OpenDocument requires its `mimetype` entry to
be first, uncompressed and free of padding.

`.xlsb`, `.docx` and other Office ZIP files still report "damaged". They carry no
marker in a fixed position, so telling them apart means opening the archive — a
cost this check exists to avoid. Named here rather than left as a surprise.

## Text is legible in both light and dark

Every Excel file writes ordinary text as "the system's text colour" rather than
as black. v1.0.0 read that as black, so almost every cell of almost every real
file was invisible in dark mode, and the first fix was a guess: treat anything
nearly black as probably-default.

The guess is gone. The distinction is now carried properly — a colour the
document never chose is recorded as *not chosen*, and never as black. Where the
document did choose a colour that cannot be seen against the background Darcha
supplies, the theme's colour is used instead.

That fixes the case nobody had noticed: **white text on an unfilled cell was
invisible in light mode**, from the first release onward, and no amount of
looking for near-black could ever have found it.

A cell the document filled is never touched. There the author chose both
colours, and black on their yellow is a pairing, not an accident.

## Frozen panes now survive Google Sheets

The test corpus gained three files exported from Google Sheets, and they found a
bug within minutes of being read.

Google Sheets writes a frozen pane as `ySplit="2.0"` where Excel writes
`ySplit="2"`. Both mean two frozen rows, and the spreadsheet format permits
both — but Darcha read the value as a whole number, got nothing back from
`"2.0"`, and fell back to zero. **Every Google Sheets export with frozen rows or
columns had been opening unfrozen**, silently, since the first release.

The fix went past the one line that caused it: every number the parser reads was
audited against the format specification. The two pane splits were the only
mismatch — everything else was already right — and the check now lives in one
shared place so the same mistake cannot reappear one attribute at a time.

No file this project generates would ever have shown this. That is what the
real-producer corpus is for.

## Known limits

Carried forward from v1.0.0 where still true.

- **No editing.** Darcha is a viewer. It never writes to your file.
- **`.xlsx` only.** No `.xls`, no `.xlsm` macros, no `.ods`, no `.csv`.
- **No charts, images, pivot tables, conditional formatting or comments.**
- **No formula engine.** A formula cell shows the value last cached in the file.
- **No in-sheet search**, and no selecting text *within* a cell — you select
  whole cells. Both are the next thing on the list.
- **Very large files stop at ~1,000,000 cells**, taking about 5.5 s to get
  there. The cap counts cells as they stream, so it cannot trip until a million
  have been read.
- **Theme accent colours render as black.** Text coloured with one of a
  workbook's six accent colours is visible and readable but not the right
  colour. Reading the workbook's theme is a separate piece of work.
- ~~An `.ods` renamed to `.xlsx` reports "damaged"~~ — **fixed in this release.**

## Verifying this build

```bash
apksigner verify --print-certs darcha-1.1.0.apk
```

Or build it yourself — JDK 17 and the Android SDK are all it needs:

```bash
./gradlew :app:assembleRelease
```
