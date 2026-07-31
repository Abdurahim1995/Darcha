# Darcha v1.0.0

A fast, private, ad-free viewer for `.xlsx` spreadsheets on Android.

**1.10 MB · minSdk 26 (Android 8.0) · no INTERNET permission**

---

## What it does

- **Opens `.xlsx`** from any file manager or the system picker
- **Multiple sheets**, parsed on demand
- **Cell styling** — bold, italic, text colour, fills, alignment, column widths, row heights
- **Number and date formatting**, including Excel's 1900 leap-year quirk
- **Merged cells** and **frozen panes**
- **Pinch zoom** anchored on the point between your fingers, and fling scrolling
- **Recent files**, remembered only when they can actually be reopened
- **English and Uzbek**, light and dark

## Private by construction

There is no `INTERNET` permission in the manifest. Not a policy — a structural
fact you can check in
[one file](https://github.com/Abdurahim1995/Darcha/blob/main/app/src/main/AndroidManifest.xml).
No accounts, no analytics, no ads.

## Measured

On a Samsung Galaxy A31 — a mid-range phone from 2020. Full method and caveats in
[docs/PERF.md](https://github.com/Abdurahim1995/Darcha/blob/main/docs/PERF.md).

| | |
|---|---|
| First cells of a 1.78 MB, ~350k-cell sheet | **175 ms** |
| Complete parse of the same file | 2,380 ms |
| Scroll frame time, median / 90th percentile | **12 ms** / 18 ms |
| Cells drawn per frame, at any sheet size | **259** |
| APK | **1.10 MB** |
| Tests | **319** |

The 60 fps budget is 16.7 ms. The median frame fits inside it; the 90th
percentile does not — so this is not a 60 fps claim.

## Known limits

These are scope decisions, fixed in the spec before any code was written, plus
two rough edges worth naming.

- **No editing.** Darcha is a viewer. It never writes to your file.
- **`.xlsx` only.** No `.xls`, no `.xlsm` macros, no `.ods`, no `.csv`.
- **No charts, images, pivot tables, conditional formatting or comments.** Cells
  and their formatting only.
- **No formula engine.** A formula cell shows the value Excel last cached in the
  file. If a file was written by a tool that cached nothing, that cell is blank.
- **Very large files stop at ~1,000,000 cells**, and take about **5.5 s** to get
  there. The cap counts cells as they stream, so it cannot trip until a million
  have been read. Stopping sooner would mean trusting the file's declared
  `<dimension>`, which real files sometimes omit entirely.
- **An `.ods` renamed to `.xlsx` reports "This file is damaged"**, when "not
  supported" would be truer — it is a real spreadsheet, just the wrong kind.
  Telling the two apart means reading the OpenDocument `mimetype` entry, which is
  a parser change deliberately left out of v1.

## Verifying this build

The APK attached here is signed with the project release key. Check it with:

```bash
apksigner verify --print-certs darcha-1.0.0.apk
```

Or build it yourself — JDK 17 and the Android SDK are all it needs:

```bash
./gradlew :app:assembleRelease
```

## Under the hood

No third-party runtime dependency does the parsing: the XLSX reader is
hand-written on `java.util.zip` and `XmlPullParser`, streaming rather than
building a DOM. The document model is sparse, on primitive arrays. The grid is a
single Compose `Canvas` that draws 259 cells per frame whether the sheet has a
thousand rows or fifty thousand.

The [README](https://github.com/Abdurahim1995/Darcha#engineering-decisions) walks
through the six decisions that shaped it, and
[docs/TECH_SPEC.md](https://github.com/Abdurahim1995/Darcha/blob/main/docs/TECH_SPEC.md)
is the full specification.
