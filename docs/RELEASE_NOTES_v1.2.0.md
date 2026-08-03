# Darcha v1.2.0

Finding things, and taking them with you. v1.1.0 could show you a file; this
release lets you search it and copy a block out of it.

**minSdk 26 (Android 8.0) · no INTERNET permission**

---

## Search within the sheet

Open the search bar, type, and every matching cell is highlighted at once. The
count sits beside the box — `3 of 47` — with next and previous stepping through
matches in reading order and wrapping round at the ends.

Two levels of highlight, not one: **every** match is marked, and the **current**
one is marked differently again, so stepping through a dense sheet never leaves
you hunting for where you just landed. Both levels are picked per cell for
contrast against whatever the document put underneath — on a yellow fill the
current-match outline switches to orange, because the yellow one would have
disappeared into it.

Search matches **what you see and what is stored**, and reports a cell once
either way. A date cell displaying `01-15-24` is found by typing `01-15` and
also by typing the serial `45306` underneath it — which matters, because the
number in the formula bar of the program that wrote the file is not the number
on your screen.

Matching is case-insensitive and finds text anywhere in the cell, since you are
usually looking for something half-remembered. On the 350,000-cell test file a
query completes in **25–30 ms** and does not disturb the renderer: the scan
keeps its own scratch space rather than evicting the text the grid is about to
draw.

While a large file is still loading, the count reads `1 of 12+`. The `+` is
there because it is honest — those are the matches found so far, in the rows
read so far, and presenting that as a total would be a lie the progress bar
already contradicts.

## Jumping to a cell without losing it under a frozen row

Stepping to a match scrolls it into view, and it lands **clear of the frozen
rows and columns**, never tucked underneath them. That sounds obvious and is the
one thing this kind of feature reliably gets wrong: the frozen bands are painted
over the sheet, so a cell can be scrolled perfectly into range and still be
invisible.

Here it cannot happen at all. The scroll is solved inside the region the sheet
actually draws in, so there is no arrangement of frozen rows, columns and zoom
that puts the target under a band. A target already on screen does not move,
and each one lands with half a cell of clearance rather than flush against an
edge.

Stepping to a match also **selects** it, so the bar at the bottom shows its full
value and Copy works straight away. A tap of your own moves the selection and
keeps the search — clearing your matches because you glanced at a neighbouring
cell would be hostile.

## Select a range and copy it as a grid

Press and hold a cell, then drag: the selection extends to a rectangle, and the
bar names it the way a spreadsheet does — `B7:D19`. A plain tap still selects a
single cell exactly as it did in v1.1.0. Long-press is deliberate — a plain drag
already scrolls, and a long press requires your finger to be still, so the two
gestures can never fight over the same movement.

**Copy pastes as cells, not as one blob of text.** Copy a block here, paste it
into Excel, Google Sheets, LibreOffice or Numbers, and it arrives as a grid with
the values in the columns they came from. An empty cell inside your selection
stays empty *and keeps its place* — dropping it would slide every value after it
one column to the left, which is the difference between an inconvenience and
corrupt data. Cells containing tabs or line breaks are quoted, so one unusual
cell cannot silently rewrite the shape of the whole paste.

This was tested by actually doing it, not by reasoning about it: the output was
opened in a real spreadsheet and read back cell by cell.

## Merged cells are selected whole

A range that touches any part of a merged cell contains **all** of it, and the
outline follows that widened shape rather than cutting through the middle of a
merged title. Half a merge holds no value and has no honest outline.

Widening repeats until it settles, because swallowing one merge can bring the
rectangle up against another that reaches further still — a common shape in real
reports, where a wide banner sits above narrower merged sub-headings.

When copied, a merged cell contributes its value **once, at its top-left**, with
empty cells beside it. That is what the file itself contains, and what a
spreadsheet produces when you copy the same block there.

## A new launcher icon

The old icon drew its own circle inside the system's, which left it small and
sitting slightly off-centre on every launcher that applies a mask. The
replacement is a four-pane window — the shape of the thing the app actually
shows you — drawn to fit the adaptive-icon safe circle properly, with a themed
monochrome variant designed rather than borrowed from the colour one.

## Known limits

Carried forward from v1.1.0 where still true.

- **No editing.** Darcha is a viewer. It never writes to your file.
- **`.xlsx` only.** No `.xls`, no `.xlsm` macros, no `.ods`, no `.csv`. A
  renamed `.ods` says "not supported"; other Office ZIP files such as `.xlsb`
  and `.docx` still report "damaged".
- **No charts, images, pivot tables, conditional formatting or comments.**
- **No formula engine.** A formula cell shows the value last cached in the file.
- **Search covers the sheet you are on**, not the whole workbook. Switching
  sheets drops the old results and searches the new sheet for the same text,
  rather than carrying a stale count across.
- **No regular expressions and no whole-word matching.** Search is a
  case-insensitive substring, and there is no way to ask for anything narrower.
- **No selecting text *within* a cell.** You select whole cells; you cannot drag
  out half a sentence from one of them.
- **Very large files stop at ~1,000,000 cells**, taking about 5.5 s to get
  there. The cap counts cells as they stream, so it cannot trip until a million
  have been read.
- **Theme accent colours render as black.** Text coloured with one of a
  workbook's six accent colours is visible and readable but not the right
  colour. Reading the workbook's theme is a separate piece of work.
- ~~No in-sheet search~~ — **added in this release.**

## Verifying this build

```bash
apksigner verify --print-certs darcha-1.2.0.apk
```

Or build it yourself — JDK 17 and the Android SDK are all it needs:

```bash
./gradlew :app:assembleRelease
```
