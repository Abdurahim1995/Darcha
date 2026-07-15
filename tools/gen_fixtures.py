#!/usr/bin/env python3
"""Generate the synthetic XLSX fixture corpus for :core:parser tests.

These files are the ``synthetic`` producer in the fixture taxonomy
(see core/parser/src/test/resources/fixtures/FIXTURES.md). Each one targets a
specific parser concern (values, shared strings, styles, merges, frozen panes,
dates, multiple sheets, sparsity) with **known golden values** that the T3-T7
golden tests assert against.

Note: openpyxl 3.1.5 ALWAYS writes string cells as ``inlineStr`` and never emits
``xl/sharedStrings.xml`` (see cell/_writer.py). So ``strings-shared.xlsx`` — the
one fixture whose whole purpose is to exercise the shared-string table (T4) — is
hand-crafted as minimal OOXML here instead of via openpyxl. See FIXTURES.md.

Setup:
    pip install openpyxl        # tested with openpyxl 3.1.5

Usage:
    python3 tools/gen_fixtures.py

Re-running is safe and (near-)deterministic: document timestamps are pinned so
regenerated files do not churn in git. The exact ZIP bytes may still differ
across openpyxl versions, but the parsed values the tests rely on will not.
"""

from __future__ import annotations

import datetime as dt
import zipfile
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill

# fixtures/synthetic/, resolved relative to this script so it runs from anywhere.
REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "core/parser/src/test/resources/fixtures/synthetic"

# Pinned document metadata -> reproducible output (no timestamp churn).
_PINNED = dt.datetime(2024, 1, 1, 0, 0, 0)


def _new_workbook(first_sheet: str = "Sheet1") -> Workbook:
    """Create a workbook with a single named sheet and pinned properties."""
    wb = Workbook()
    wb.active.title = first_sheet
    wb.properties.creator = "Darcha fixture generator"
    wb.properties.created = _PINNED
    wb.properties.modified = _PINNED
    return wb


def _save(wb: Workbook, name: str) -> None:
    path = OUT_DIR / name
    wb.save(path)
    print(f"  wrote {path.relative_to(REPO_ROOT)}")


# Fixed ZIP entry timestamp so hand-crafted files are byte-reproducible.
_ZIP_DATE = (2024, 1, 1, 0, 0, 0)


def _write_ooxml(name: str, parts: list[tuple[str, str]], note: str = "") -> None:
    """Write a raw OOXML package (an .xlsx ZIP) from ordered (path, xml) parts.

    Used only where openpyxl cannot produce the XML we need to test (shared
    strings). Entry timestamps are pinned for reproducibility.
    """
    path = OUT_DIR / name
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for part_name, content in parts:
            info = zipfile.ZipInfo(part_name, date_time=_ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(info, content.lstrip("\n"))
    print(f"  wrote {path.relative_to(REPO_ROOT)}  {note}".rstrip())


def gen_values_basic() -> None:
    """Numbers, text and booleans in a small dense grid."""
    wb = _new_workbook()
    ws = wb.active
    ws["A1"], ws["B1"], ws["C1"] = "Name", "Age", "Active"
    ws["A2"], ws["B2"], ws["C2"] = "Alice", 30, True
    ws["A3"], ws["B3"], ws["C3"] = "Bob", 25.5, False
    ws["A4"], ws["B4"], ws["C4"] = "Carol", 0, True
    _save(wb, "values-basic.xlsx")


def gen_strings_shared() -> None:
    """Hand-crafted minimal OOXML with a REAL shared string table (T4).

    openpyxl cannot emit sharedStrings.xml (it always writes inlineStr), so this
    one fixture is built as raw OOXML. Column A rows 1-7 reference the shared
    table by index:

        table:  0=fruit  1=apple  2=banana  3=cherry   (uniqueCount=4)
        A1..A7: 0, 1, 2, 1, 3, 2, 1                     (count=7)

    So A2, A4, A7 all resolve to "apple" via the same index 1.
    """
    content_types = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>
"""
    root_rels = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>
"""
    core = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:creator>Darcha fixture generator</dc:creator>
  <dcterms:created xsi:type="dcterms:W3CDTF">2024-01-01T00:00:00Z</dcterms:created>
  <dcterms:modified xsi:type="dcterms:W3CDTF">2024-01-01T00:00:00Z</dcterms:modified>
</cp:coreProperties>
"""
    app = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
  <Application>Darcha fixture generator</Application>
</Properties>
"""
    workbook = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>
"""
    workbook_rels = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>
"""
    styles = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
  <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
  <borders count="1"><border/></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>
"""
    shared_strings = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="7" uniqueCount="4">
  <si><t>fruit</t></si>
  <si><t>apple</t></si>
  <si><t>banana</t></si>
  <si><t>cherry</t></si>
</sst>
"""
    indices = [0, 1, 2, 1, 3, 2, 1]
    rows = "".join(
        f'<row r="{i}"><c r="A{i}" t="s"><v>{idx}</v></c></row>'
        for i, idx in enumerate(indices, start=1)
    )
    sheet1 = f"""
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <dimension ref="A1:A7"/>
  <sheetData>{rows}</sheetData>
</worksheet>
"""

    _write_ooxml(
        "strings-shared.xlsx",
        [
            ("[Content_Types].xml", content_types),
            ("_rels/.rels", root_rels),
            ("docProps/core.xml", core),
            ("docProps/app.xml", app),
            ("xl/workbook.xml", workbook),
            ("xl/_rels/workbook.xml.rels", workbook_rels),
            ("xl/styles.xml", styles),
            ("xl/sharedStrings.xml", shared_strings),
            ("xl/worksheets/sheet1.xml", sheet1),
        ],
        note="(hand-crafted shared strings)",
    )


def gen_styles_basic() -> None:
    """Bold, italic, font color, solid fill and alignments."""
    wb = _new_workbook()
    ws = wb.active

    ws["A1"] = "Bold"
    ws["A1"].font = Font(bold=True)

    ws["A2"] = "Italic"
    ws["A2"].font = Font(italic=True)

    ws["A3"] = "Red"
    ws["A3"].font = Font(color="FFFF0000")  # ARGB: opaque red

    ws["B1"] = "Fill"
    ws["B1"].fill = PatternFill(
        start_color="FFFFFF00", end_color="FFFFFF00", fill_type="solid"  # yellow
    )

    ws["C1"] = "Center"
    ws["C1"].alignment = Alignment(horizontal="center", vertical="center")

    ws["C2"] = "Right"
    ws["C2"].alignment = Alignment(horizontal="right")

    _save(wb, "styles-basic.xlsx")


def gen_merged() -> None:
    """Three merged ranges: a horizontal title, a vertical side, a block."""
    wb = _new_workbook()
    ws = wb.active
    ws["A1"] = "Title"
    ws.merge_cells("A1:C1")
    ws["A2"] = "Side"
    ws.merge_cells("A2:A4")
    ws["B2"] = "Block"
    ws.merge_cells("B2:C3")
    _save(wb, "merged.xlsx")


def gen_frozen() -> None:
    """Freeze the first row and first column (pane anchor B2)."""
    wb = _new_workbook()
    ws = wb.active
    ws["A1"], ws["B1"], ws["C1"] = "Corner", "H1", "H2"
    ws["A2"], ws["B2"], ws["C2"] = "R1", 1, 2
    ws["A3"], ws["B3"], ws["C3"] = "R2", 3, 4
    ws.freeze_panes = "B2"  # xSplit=1, ySplit=1
    _save(wb, "frozen.xlsx")


def gen_dates() -> None:
    """Date/time/datetime cells across builtin and custom number formats.

    A1 builtin 14 (date), A2 builtin 21 (time), A3 builtin 22 (datetime),
    A4 custom 'yyyy-mm-dd' (date detected via y/m/d tokens).
    """
    wb = _new_workbook()
    ws = wb.active

    ws["A1"] = dt.date(2024, 1, 15)
    ws["A1"].number_format = "mm-dd-yy"  # builtin id 14

    ws["A2"] = dt.time(13, 30, 0)
    ws["A2"].number_format = "h:mm:ss"  # builtin id 21

    ws["A3"] = dt.datetime(2024, 1, 15, 13, 30, 0)
    ws["A3"].number_format = "m/d/yy h:mm"  # builtin id 22

    ws["A4"] = dt.date(2024, 12, 31)
    ws["A4"].number_format = "yyyy-mm-dd"  # custom (>=164), date by tokens

    _save(wb, "dates.xlsx")


def gen_multisheet() -> None:
    """Three sheets in order, including a non-ASCII (Cyrillic) name."""
    wb = _new_workbook(first_sheet="Jadval 1")
    wb.active["A1"] = "birinchi"

    ws2 = wb.create_sheet("Narxlar")
    ws2["A1"] = "ikkinchi"

    ws3 = wb.create_sheet("Ҳисобот")  # non-ASCII UTF-8 sheet name
    ws3["A1"] = "uchinchi"

    _save(wb, "multisheet.xlsx")


def gen_sparse_gaps() -> None:
    """Only three populated cells with large gaps: A1, C5, AA100.

    Column AA is index 26 (0-based); row 100 is index 99. The <dimension>
    element will claim A1:AA100, but only 3 <c> elements exist.
    """
    wb = _new_workbook()
    ws = wb.active
    ws["A1"] = "start"
    ws["C5"] = 42
    ws["AA100"] = "end"
    _save(wb, "sparse-gaps.xlsx")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Generating synthetic fixtures into {OUT_DIR.relative_to(REPO_ROOT)} ...")
    gen_values_basic()
    gen_strings_shared()
    gen_styles_basic()
    gen_merged()
    gen_frozen()
    gen_dates()
    gen_multisheet()
    gen_sparse_gaps()
    print("Done: 8 fixtures generated.")


if __name__ == "__main__":
    main()
