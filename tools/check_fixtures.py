#!/usr/bin/env python3
"""Check the real-producer fixture corpus against docs/FIXTURE_RECIPES.md.

Reads each expected fixture and reports what is present, missing, or wrong, so
the files can be corrected without a round-trip. Read-only: it never modifies a
fixture (that would destroy the producer identity the corpus exists to capture).

Usage:
    python3 tools/check_fixtures.py            # check every producer folder
    python3 tools/check_fixtures.py excel      # check one producer folder

Requires only the standard library.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURES = REPO_ROOT / "core/parser/src/test/resources/fixtures"

M = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
BUILTIN_DATE_IDS = set(range(14, 23)) | set(range(45, 48))

OK, BAD, WARN, NOTE = "✅", "❌", "⚠️", "📌"


# --- tiny OOXML reader -------------------------------------------------------


class Sheet:
    """The bits of a worksheet the recipes care about."""

    def __init__(self, path: Path):
        self.zip = zipfile.ZipFile(path)
        self.strings = self._shared_strings()
        self.numfmts, self.fonts, self.fills, self.xfs = self._styles()
        # Attribute order is not guaranteed by XML and producers differ: Excel
        # writes `<sheet name=... sheetId=...>`, Google Sheets writes
        # `<sheet state="visible" name=...>`. Parse it rather than pattern-match
        # the one spelling we happened to see first (T30).
        wb = self._part("xl/workbook.xml")
        self.sheet_names = (
            [e.get("name") for e in ET.fromstring(wb).iter(f"{M}sheet")] if wb else []
        )
        root = ET.fromstring(self._part("xl/worksheets/sheet1.xml"))
        self.merges = [m.get("ref") for m in root.iter(f"{M}mergeCell")]
        self.panes = [
            (p.get("xSplit"), p.get("ySplit"), p.get("state"))
            for p in root.iter(f"{M}pane")
        ]
        self.cells = {}
        for c in root.iter(f"{M}c"):
            ref, ctype = c.get("r"), c.get("t")
            v = c.find(f"{M}v")
            raw = v.text if v is not None else None
            if ctype == "s" and raw is not None:
                value = self.strings[int(raw)]
            elif ctype == "inlineStr":
                value = "".join(t.text or "" for t in c.iter(f"{M}t"))
            elif ctype == "b":
                value = raw == "1"
            elif raw is None:
                value = None
            else:
                value = raw
            self.cells[ref] = dict(value=value, type=ctype, style=int(c.get("s", "0")))

    def _part(self, name: str) -> str | None:
        try:
            return self.zip.read(name).decode("utf-8")
        except KeyError:
            return None

    def _shared_strings(self) -> list[str]:
        raw = self._part("xl/sharedStrings.xml")
        if not raw:
            return []
        return [
            "".join(t.text or "" for t in si.iter(f"{M}t"))
            for si in ET.fromstring(raw).findall(f"{M}si")
        ]

    def _styles(self):
        raw = self._part("xl/styles.xml")
        if not raw:
            return {}, [], [], []
        root = ET.fromstring(raw)
        numfmts = {int(n.get("numFmtId")): n.get("formatCode") for n in root.iter(f"{M}numFmt")}
        fonts = []
        for f in root.find(f"{M}fonts") or []:
            color = f.find(f"{M}color")
            fonts.append(
                dict(
                    bold=f.find(f"{M}b") is not None,
                    italic=f.find(f"{M}i") is not None,
                    rgb=color.get("rgb") if color is not None else None,
                )
            )
        fills = []
        for f in root.find(f"{M}fills") or []:
            pf = f.find(f"{M}patternFill")
            fg = pf.find(f"{M}fgColor") if pf is not None else None
            fills.append(
                dict(
                    pattern=pf.get("patternType") if pf is not None else None,
                    rgb=fg.get("rgb") if fg is not None else None,
                )
            )
        xfs = []
        for x in root.find(f"{M}cellXfs") or []:
            al = x.find(f"{M}alignment")
            xfs.append(
                dict(
                    numfmt=int(x.get("numFmtId", "0")),
                    font=int(x.get("fontId", "0")),
                    fill=int(x.get("fillId", "0")),
                    halign=al.get("horizontal") if al is not None else None,
                )
            )
        return numfmts, fonts, fills, xfs

    # -- helpers used by the checks --

    def text_column(self, col: str = "A", rows: int = 6) -> list:
        return [self.cells.get(f"{col}{r}", {}).get("value") for r in range(1, rows + 1)]

    def style_of(self, ref: str) -> dict:
        idx = self.cells.get(ref, {}).get("style", 0)
        return self.xfs[idx] if idx < len(self.xfs) else {}

    def font_of(self, ref: str) -> dict:
        i = self.style_of(ref).get("font", 0)
        return self.fonts[i] if i < len(self.fonts) else {}

    def fill_of(self, ref: str) -> dict:
        i = self.style_of(ref).get("fill", 0)
        return self.fills[i] if i < len(self.fills) else {}

    def is_date(self, ref: str) -> bool:
        nf = self.style_of(ref).get("numfmt", 0)
        code = self.numfmts.get(nf)
        return nf in BUILTIN_DATE_IDS or bool(code and re.search(r"[ymdhs]", code, re.I))


# --- per-recipe checks -------------------------------------------------------


def check_values_basic(s: Sheet) -> list[str]:
    """A1: text / number / boolean grid."""
    problems = []
    header = [s.cells.get(c, {}).get("value") for c in ("A1", "B1", "C1")]
    if header != ["Product", "Price", "InStock"]:
        problems.append(f"A1:C1 must be Product/Price/InStock, found {header}")
    names = [s.cells.get(f"A{r}", {}).get("value") for r in (2, 3, 4)]
    if names != ["Olma", "Anor", "Uzum"]:
        problems.append(f"A2:A4 must be Olma/Anor/Uzum, found {names}")
    for ref, want in (("B2", 12.5), ("B3", 300.0), ("B4", 0.75)):
        got = s.cells.get(ref, {})
        # Compared as numbers: Excel writes <v>300</v> and Google Sheets writes
        # <v>300.0</v>, and those are the same value (T30).
        try:
            same = got.get("type") in (None, "n") and float(got.get("value")) == want
        except (TypeError, ValueError):
            same = False
        if not same:
            problems.append(f"{ref} must be the number {want:g}, found {got.get('value')!r}")
    for ref, want in (("C2", True), ("C3", False), ("C4", True)):
        got = s.cells.get(ref, {})
        if got.get("type") != "b":
            problems.append(f"{ref} must be a real boolean (type b), found type={got.get('type')!r} — type TRUE/FALSE in caps")
        elif got.get("value") is not want:
            problems.append(f"{ref} must be {want}, found {got.get('value')}")
    return problems


def check_strings(s: Sheet) -> list[str]:
    """A2: repeated text, exercising the shared string table."""
    problems = []
    want = ["apple", "banana", "apple", "cherry", "banana", "apple"]
    got = s.text_column("A", 6)
    if got != want:
        problems.append(f"A1:A6 must be {want}, found {got}")
    if not s.strings:
        problems.append("no sharedStrings.xml — this fixture exists to test the shared string table")
    return problems


def check_styles_basic(s: Sheet) -> list[str]:
    """A3: one distinct style per row."""
    problems = []
    want = ["Bold", "Italic", "Red", "Yellow", "Center", "Right"]
    got = s.text_column("A", 6)
    if got != want:
        problems.append(f"A1:A6 must be {want}, found {got}")
    if len(s.xfs) <= 1:
        problems.append("no cell formats at all (cellXfs has only the default) — apply the six formats")
        return problems
    if not s.font_of("A1").get("bold"):
        problems.append("A1 must be bold")
    if not s.font_of("A2").get("italic"):
        problems.append("A2 must be italic")
    rgb = s.font_of("A3").get("rgb")
    if not rgb:
        problems.append("A3 must have a red font color")
    fill = s.fill_of("A4")
    if fill.get("pattern") in (None, "none"):
        problems.append("A4 must have a yellow fill")
    if s.style_of("A5").get("halign") != "center":
        problems.append(f"A5 must be centered, found halign={s.style_of('A5').get('halign')!r}")
    if s.style_of("A6").get("halign") != "right":
        problems.append(f"A6 must be right-aligned, found halign={s.style_of('A6').get('halign')!r}")
    return problems


def check_merged(s: Sheet) -> list[str]:
    """A4 (merge half): a merged title row."""
    problems = []
    if s.cells.get("A1", {}).get("value") != "Hisobot":
        problems.append(f"A1 must be 'Hisobot', found {s.cells.get('A1', {}).get('value')!r}")
    if not s.merges:
        problems.append("no merged range — select A1:C1 and use Merge & Center")
    elif "A1:C1" not in s.merges:
        problems.append(f"expected a merge of A1:C1, found {s.merges}")
    if s.panes:
        problems.append(f"this file must NOT be frozen, found pane {s.panes}")
    return problems


def check_frozen(s: Sheet) -> list[str]:
    """A4 (freeze half): frozen first row and column."""
    problems = []
    header = [s.cells.get(c, {}).get("value") for c in ("A1", "B1", "C1")]
    if header != ["Nomi", "Soni", "Narxi"]:
        problems.append(f"A1:C1 must be Nomi/Soni/Narxi, found {header}")
    frozen = [p for p in s.panes if p[2] == "frozen"]
    if not frozen:
        problems.append("no frozen pane — select B2, then View > Freeze Panes > Freeze Panes")
    if s.merges:
        problems.append(f"this file must NOT contain merges, found {s.merges}")
    return problems


def check_frozen_both(s: Sheet) -> list[str]:
    """Companion to frozen.xlsx — both axes frozen (freeze at B3)."""
    problems = []
    header = [s.cells.get(c, {}).get("value") for c in ("A1", "B1", "C1")]
    if header != ["Nomi", "Soni", "Narxi"]:
        problems.append(f"A1:C1 must be Nomi/Soni/Narxi, found {header}")
    frozen = [p for p in s.panes if p[2] == "frozen"]
    if not frozen:
        problems.append("no frozen pane — select B3, then View > Freeze Panes > Freeze Panes")
    else:
        xsplit, ysplit, _ = frozen[0]
        if not xsplit or xsplit == "0":
            problems.append(f"expected a frozen column (xSplit), found xSplit={xsplit!r}")
        if not ysplit or ysplit == "0":
            problems.append(f"expected frozen rows (ySplit), found ySplit={ysplit!r} — freeze at B3, not B1")
    if s.merges:
        problems.append(f"this file must NOT contain merges, found {s.merges}")
    return problems


def check_merged_frozen(s: Sheet) -> list[str]:
    """A4, whole: a merged title row AND a frozen header, in one file.

    The Excel corpus splits this across merged.xlsx and frozen*.xlsx; the Google
    Sheets recipe asks for one file covering both, so this is its own check
    rather than a reuse of either (T30).
    """
    problems = []
    header = [s.cells.get(c, {}).get("value") for c in ("A2", "B2", "C2")]
    if header != ["Nomi", "Soni", "Narxi"]:
        problems.append(f"A2:C2 must be Nomi/Soni/Narxi, found {header}")
    if "A1:C1" not in s.merges:
        problems.append(f"expected a merge of A1:C1, found {s.merges}")

    frozen = [p for p in s.panes if p[2] == "frozen"]
    if not frozen:
        problems.append("no frozen pane — View > Freeze > Up to row 2")
    else:
        _, ysplit, _ = frozen[0]
        # Read as a number, not a string: ECMA-376 types the splits xsd:double,
        # so "2" and "2.0" both mean two frozen rows. Google Sheets writes the
        # decimal form, and assuming the integer one is what broke the parser
        # until T30.
        try:
            rows = float(ysplit or 0)
        except ValueError:
            rows = 0
        if rows < 1:
            problems.append(f"expected frozen rows, found ySplit={ysplit!r}")
    return problems


def check_dates(s: Sheet) -> list[str]:
    """A5: date/time values recognized as dates, not text."""
    problems = []
    for ref in ("A1", "A2", "A3", "A4"):
        cell = s.cells.get(ref)
        if not cell:
            problems.append(f"{ref} is empty")
            continue
        if cell.get("type") == "s" or cell.get("type") == "inlineStr":
            problems.append(f"{ref} was stored as TEXT — reformat it as Date/Time")
        elif not s.is_date(ref):
            problems.append(f"{ref} has no date number format (numFmt={s.style_of(ref).get('numfmt')})")
    return problems


def check_uzbek_text(s: Sheet) -> list[str]:
    """A6: non-ASCII content and a non-ASCII sheet name."""
    problems = []
    want = ["O'zbekiston", "Toshkent", "Farg'ona", "Namangan", "Andijon"]
    got = s.text_column("A", 5)
    if got != want:
        problems.append(f"A1:A5 must be {want}, found {got}")
    if "Jadval 1" not in s.sheet_names:
        problems.append(f"sheet tab must be renamed to 'Jadval 1', found {s.sheet_names}")
    return problems


CHECKS = {
    "values-basic.xlsx": check_values_basic,
    "strings.xlsx": check_strings,
    "styles-basic.xlsx": check_styles_basic,
    "merged.xlsx": check_merged,
    "merged-frozen.xlsx": check_merged_frozen,
    "frozen.xlsx": check_frozen,
    "frozen-both.xlsx": check_frozen_both,
    "dates.xlsx": check_dates,
    "uzbek-text.xlsx": check_uzbek_text,
}

# Which files each producer folder is expected to contain.
EXPECTED = {
    # Listed explicitly rather than derived from CHECKS: the two corpora do not
    # contain the same files. Excel splits merging and freezing across
    # merged.xlsx and frozen*.xlsx, while the Google Sheets recipe asks for one
    # merged-frozen.xlsx covering both.
    "excel": [
        "values-basic.xlsx", "strings.xlsx", "styles-basic.xlsx", "merged.xlsx",
        "frozen.xlsx", "frozen-both.xlsx", "dates.xlsx", "uzbek-text.xlsx",
    ],
    # docs/FIXTURE_RECIPES.md asks Google Sheets for merged-frozen.xlsx (one file
    # covering both), where the Excel corpus splits them.
    "gsheets": ["values-basic.xlsx", "merged-frozen.xlsx", "uzbek-text.xlsx"],
    "wps": ["values-basic.xlsx", "strings.xlsx"],
    "numbers": ["values-basic.xlsx", "uzbek-text.xlsx"],
}

OPTIONAL_PRODUCERS = {"gsheets", "wps", "numbers"}

# Divergences from the recipe that were reviewed and deliberately kept.
#
# These are not the checker being loosened. Each entry names the exact problem
# text it accepts and why, and the run still PRINTS it every time — so the
# divergence stays visible and can be revisited, while a genuinely new one still
# turns the folder red.
#
# The reason this list exists at all: a checker that is permanently red trains
# everyone to skim past it, and then the next real divergence goes unnoticed.
# Accepting a known one on the record is what keeps the red meaningful.
ACCEPTED = {
    ("gsheets", "values-basic.xlsx"): [
        (
            "B2 must be the number 12.5",
            "typed with a comma decimal, so Google Sheets stored it as TEXT. "
            "Kept: it is how prices are written across this app's audience, and "
            "it locks that the parser reports what the file says instead of "
            "guessing. See FIXTURES.md.",
        ),
        (
            "B4 must be the number 0.75",
            "same as B2 — comma decimal, stored as text, kept on purpose.",
        ),
    ],
    ("gsheets", "uzbek-text.xlsx"): [
        (
            "A1:A5 must be",
            "Namangan and Farg'ona are in the opposite order to the recipe. "
            "Cosmetic: the golden test reads the order out of the file, so "
            "nothing is ambiguous.",
        ),
    ],
}


def split_accepted(producer: str, name: str, problems: list[str]) -> tuple[list[str], list[str]]:
    """Partition problems into (still failing, accepted-and-explained)."""
    rules = ACCEPTED.get((producer, name), [])
    failing, notes = [], []
    for problem in problems:
        reason = next((why for prefix, why in rules if problem.startswith(prefix)), None)
        if reason is None:
            failing.append(problem)
        else:
            notes.append(f"{problem}\n         ↳ accepted: {reason}")
    return failing, notes


def check_folder(producer: str) -> tuple[int, int]:
    """Check one producer folder. Returns (ok_count, problem_count)."""
    folder = FIXTURES / producer
    optional = producer in OPTIONAL_PRODUCERS
    print(f"\n=== {producer}/ {'(optional)' if optional else ''}")
    if not folder.is_dir():
        print(f"  {WARN} folder does not exist")
        return 0, 0

    ok = bad = 0
    for name in EXPECTED[producer]:
        path = folder / name
        if not path.exists():
            mark = WARN if optional else BAD
            print(f"  {mark} {name} — missing")
            if not optional:
                bad += 1
            continue
        if path.stat().st_size == 0:
            print(f"  {BAD} {name} — empty file (0 bytes); the download did not complete")
            bad += 1
            continue
        try:
            sheet = Sheet(path)
        except (zipfile.BadZipFile, ET.ParseError, KeyError) as e:
            print(f"  {BAD} {name} — not a readable .xlsx ({type(e).__name__})")
            bad += 1
            continue
        problems, notes = split_accepted(producer, name, CHECKS[name](sheet))
        if problems:
            print(f"  {BAD} {name}")
            for p in problems:
                print(f"       - {p}")
            bad += 1
        else:
            print(f"  {OK} {name}")
            ok += 1
        # Printed either way: an accepted divergence stays visible.
        for note in notes:
            print(f"       {NOTE} {note}")

    extras = sorted(
        p.name for p in folder.glob("*.xlsx") if p.name not in EXPECTED[producer]
    )
    for extra in extras:
        print(f"  {WARN} {extra} — unexpected file (not in the recipes)")
    return ok, bad


def main() -> None:
    producers = sys.argv[1:] or list(EXPECTED)
    unknown = [p for p in producers if p not in EXPECTED]
    if unknown:
        print(f"Unknown producer(s): {', '.join(unknown)}")
        print(f"Valid: {', '.join(EXPECTED)}")
        raise SystemExit(2)

    total_ok = total_bad = 0
    for producer in producers:
        ok, bad = check_folder(producer)
        total_ok += ok
        total_bad += bad

    print(f"\n{'-' * 46}")
    if total_bad == 0:
        print(f"{OK} All checked fixtures match the recipes ({total_ok} files).")
    else:
        print(f"{total_ok} correct, {total_bad} need work. See the notes above.")
    raise SystemExit(1 if total_bad else 0)


if __name__ == "__main__":
    main()
