package com.tikoncha.darcha.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-driven golden tests for the format engine (TECH_SPEC §7/§8).
 *
 * The date cases marked **fixture** carry serials and `numFmtId`s read out of the
 * real corpus — `excel/dates.xlsx` is genuine Excel output, so those numbers are
 * what Excel actually wrote, not what anyone believed it would write. They are
 * repeated as literals here because `:core:model` cannot open an `.xlsx`; each
 * one names the file and cell it came from, and `:core:parser`'s own
 * `ExcelFixturesTest` pins the same values at the other end.
 */
class ValueFormatterTest {

    private data class Case(
        val value: CellValue,
        val style: CellStyle,
        val expected: String,
        val date1904: Boolean = false,
        val note: String = "",
    )

    private fun check(cases: List<Case>) {
        for (case in cases) {
            val actual = ValueFormatter.format(case.value, case.style, STRINGS, case.date1904)
            val label = buildString {
                append(case.value)
                append(" as numFmt ").append(case.style.numFmtId)
                case.style.formatCode?.let { append(" '").append(it).append('\'') }
                if (case.date1904) append(" [1904]")
                if (case.note.isNotEmpty()) append(" — ").append(case.note)
            }
            assertEquals(label, case.expected, actual)
        }
    }

    private fun num(value: Double) = CellValue.Number(value)

    /** A numeric style: the id drives the rendering, the code is documentation. */
    private fun numFmt(id: Int, code: String?) =
        CellStyle.DEFAULT.copy(numFmtId = id, formatCode = code, isDate = false)

    /** A date style: the *code* drives the rendering, exactly as in production. */
    private fun dateFmt(id: Int, code: String) =
        CellStyle.DEFAULT.copy(numFmtId = id, formatCode = code, isDate = true)

    // --- General (id 0) ---

    @Test
    fun general_plainNumbers() = check(
        listOf(
            Case(num(0.0), GENERAL, "0"),
            Case(num(-0.0), GENERAL, "0", note = "negative zero is still zero"),
            Case(num(30.0), GENERAL, "30", note = "whole numbers lose the .0"),
            Case(num(100.0), GENERAL, "100"),
            Case(num(25.5), GENERAL, "25.5"),
            Case(num(-12.75), GENERAL, "-12.75"),
            Case(num(0.1), GENERAL, "0.1"),
            Case(num(1.0 / 3.0), GENERAL, "0.33333333333", note = "11 significant digits"),
            Case(num(2.0 / 3.0), GENERAL, "0.66666666667", note = "and it rounds, not truncates"),
            Case(num(1.500), GENERAL, "1.5", note = "trailing zeros trimmed"),
        ),
    )

    @Test
    fun general_switchesToScientificAtTheEdges() = check(
        listOf(
            Case(num(99999999999.0), GENERAL, "99999999999", note = "11 digits still fit"),
            Case(num(1e11), GENERAL, "1E+11", note = "12 would not"),
            Case(num(123456789012.0), GENERAL, "1.2345678901E+11"),
            Case(num(1.5e15), GENERAL, "1.5E+15"),
            Case(num(0.0001), GENERAL, "0.0001", note = "the small-value boundary"),
            Case(num(0.00001), GENERAL, "1E-05", note = "just past it"),
            Case(num(1.2345e-5), GENERAL, "1.2345E-05"),
            Case(num(-1e-7), GENERAL, "-1E-07"),
        ),
    )

    // --- the builtin numeric subset ---

    @Test
    fun builtin_fixedDecimals() = check(
        listOf(
            Case(num(1234.5), numFmt(1, "0"), "1235", note = "half away from zero"),
            Case(num(-1234.5), numFmt(1, "0"), "-1235"),
            Case(num(0.4), numFmt(1, "0"), "0"),
            Case(num(30.0), numFmt(1, "0"), "30"),
            Case(num(25.5), numFmt(2, "0.00"), "25.50", note = "padded out to two"),
            Case(num(3.14159), numFmt(2, "0.00"), "3.14"),
            Case(num(2.5), numFmt(2, "0.00"), "2.50"),
            Case(num(-0.001), numFmt(2, "0.00"), "0.00", note = "never '-0.00'"),
        ),
    )

    @Test
    fun builtin_thousandsSeparators() = check(
        listOf(
            Case(num(999.0), numFmt(3, "#,##0"), "999", note = "no separator needed"),
            Case(num(1000.0), numFmt(3, "#,##0"), "1,000"),
            Case(num(1234567.891), numFmt(3, "#,##0"), "1,234,568"),
            Case(num(-1234567.0), numFmt(3, "#,##0"), "-1,234,567"),
            Case(num(1234567890123.0), numFmt(3, "#,##0"), "1,234,567,890,123"),
            Case(num(1234567.891), numFmt(4, "#,##0.00"), "1,234,567.89"),
            Case(num(0.5), numFmt(4, "#,##0.00"), "0.50"),
        ),
    )

    @Test
    fun builtin_percentages() = check(
        listOf(
            Case(num(0.5), numFmt(9, "0%"), "50%"),
            Case(num(1.0), numFmt(9, "0%"), "100%"),
            Case(num(0.075), numFmt(9, "0%"), "8%", note = "7.5 rounds up"),
            Case(num(0.0), numFmt(9, "0%"), "0%"),
            Case(num(0.075), numFmt(10, "0.00%"), "7.50%"),
            Case(num(0.12345), numFmt(10, "0.00%"), "12.35%"),
            Case(num(-0.25), numFmt(10, "0.00%"), "-25.00%"),
        ),
    )

    @Test
    fun unimplementedFormats_fallBackToGeneral() = check(
        listOf(
            Case(num(0.75), numFmt(12, "# ?/?"), "0.75", note = "fractions (12, 13)"),
            Case(num(1234.5), numFmt(11, "0.00E+00"), "1234.5", note = "scientific builtin"),
            Case(num(1234.5), numFmt(38, "#,##0 ;[Red](#,##0)"), "1234.5", note = "accounting"),
            Case(num(30.0), numFmt(49, "@"), "30", note = "text format"),
            Case(num(1.5), numFmt(180, "0.000"), "1.5", note = "a custom numeric code"),
        ),
    )

    // --- dates and times, from the corpus ---

    @Test
    fun dates_fromTheRealExcelFixture() = check(
        listOf(
            // excel/dates.xlsx — genuine Excel output, serials read from the file.
            Case(num(45306.0), dateFmt(14, "mm-dd-yy"), "01-15-24", note = "A1"),
            Case(num(0.5625), dateFmt(20, "h:mm"), "13:30", note = "A2, Excel chose id 20"),
            Case(num(45306.5625), dateFmt(22, "m/d/yy h:mm"), "1/15/24 13:30", note = "A3"),
            Case(num(45657.0), dateFmt(14, "mm-dd-yy"), "12-31-24", note = "A4"),
        ),
    )

    @Test
    fun dates_fromTheSyntheticFixture() = check(
        listOf(
            // synthetic/dates.xlsx — openpyxl picked different ids for the same
            // instants, which is exactly the producer variance the corpus exists
            // to capture.
            Case(num(45306.0), dateFmt(14, "mm-dd-yy"), "01-15-24", note = "A1"),
            Case(num(0.5625), dateFmt(21, "h:mm:ss"), "13:30:00", note = "A2, id 21 here"),
            Case(num(45306.5625), dateFmt(22, "m/d/yy h:mm"), "1/15/24 13:30", note = "A3"),
            Case(num(45657.0), dateFmt(164, "yyyy-mm-dd"), "2024-12-31", note = "A4, custom"),
        ),
    )

    @Test
    fun dates_remainingBuiltinCodes() = check(
        listOf(
            Case(num(45306.0), dateFmt(15, "d-mmm-yy"), "15-Jan-24"),
            Case(num(45306.0), dateFmt(16, "d-mmm"), "15-Jan"),
            Case(num(45306.0), dateFmt(17, "mmm-yy"), "Jan-24"),
            Case(num(0.5625), dateFmt(18, "h:mm AM/PM"), "1:30 PM"),
            Case(num(0.5625), dateFmt(19, "h:mm:ss AM/PM"), "1:30:00 PM"),
            Case(num(0.0), dateFmt(18, "h:mm AM/PM"), "12:00 AM", note = "midnight is 12, not 0"),
            Case(num(0.5), dateFmt(18, "h:mm AM/PM"), "12:00 PM", note = "and so is noon"),
            Case(num(0.5625), dateFmt(45, "mm:ss"), "30:00", note = "mm is minutes before ss"),
            Case(num(1.5), dateFmt(46, "[h]:mm:ss"), "36:00:00", note = "elapsed, past 24h"),
            Case(num(0.5625), dateFmt(47, "mmss.0"), "3000.0", note = "tenths of a second"),
        ),
    )

    @Test
    fun dates_customCodes() = check(
        listOf(
            Case(
                num(45306.0),
                dateFmt(165, "dddd, mmmm d, yyyy"),
                "Monday, January 15, 2024",
                note = "name tokens",
            ),
            Case(num(45306.0), dateFmt(166, "ddd d mmm yy"), "Mon 15 Jan 24"),
            Case(num(45306.0), dateFmt(167, "yyyy-mm-dd;@"), "2024-01-15", note = "first section only"),
            Case(
                num(45306.0),
                dateFmt(168, "[\$-409]d-mmm-yy"),
                "15-Jan-24",
                note = "a locale prefix is skipped, not printed",
            ),
            Case(num(45306.0), dateFmt(169, "mmmmm"), "J", note = "single-letter month"),
            Case(
                num(45306.0),
                dateFmt(170, "dd\\.mm\\.yyyy"),
                "15.01.2024",
                note = "escaped literals",
            ),
            Case(
                num(45306.0),
                dateFmt(171, "\"Sana: \"yyyy"),
                "Sana: 2024",
                note = "quoted literal",
            ),
        ),
    )

    @Test
    fun dates_the1900LeapYearBugSurvivesFormatting() = check(
        listOf(
            Case(num(59.0), dateFmt(14, "mm-dd-yy"), "02-28-00"),
            Case(num(60.0), dateFmt(14, "mm-dd-yy"), "02-29-00", note = "the phantom day"),
            Case(num(61.0), dateFmt(14, "mm-dd-yy"), "03-01-00"),
            Case(num(60.0), dateFmt(172, "dddd"), "Wednesday", note = "Excel's weekday for it"),
        ),
    )

    @Test
    fun dates_in1904Mode() = check(
        listOf(
            Case(num(43830.0), dateFmt(14, "mm-dd-yy"), "01-01-24", date1904 = true),
            Case(num(0.0), dateFmt(164, "yyyy-mm-dd"), "1904-01-01", date1904 = true),
            Case(
                num(59.0),
                dateFmt(164, "yyyy-mm-dd"),
                "1904-02-29",
                date1904 = true,
                note = "1904 really was a leap year — no bug here",
            ),
            Case(
                num(45306.0),
                dateFmt(164, "yyyy-mm-dd"),
                "2024-01-15",
                note = "the same serial, 1900 mode",
            ),
            Case(
                num(45306.0 - 1462.0),
                dateFmt(164, "yyyy-mm-dd"),
                "2024-01-15",
                date1904 = true,
                note = "the epochs are 1462 days apart",
            ),
        ),
    )

    @Test
    fun times_roundToTheSmallestVisibleUnit() = check(
        listOf(
            Case(num(3723.0 / 86400.0), dateFmt(173, "hh:mm:ss"), "01:02:03"),
            Case(
                num(3723.0 / 86400.0),
                dateFmt(20, "h:mm"),
                "1:02",
                note = "seconds rounded away, not shown",
            ),
            Case(
                num(45306.9999999),
                dateFmt(22, "m/d/yy h:mm"),
                "1/16/24 0:00",
                note = "rounding up past midnight carries the date",
            ),
            Case(
                num(45306.9),
                dateFmt(14, "mm-dd-yy"),
                "01-15-24",
                note = "a date-only format truncates instead of rounding",
            ),
        ),
    )

    @Test
    fun aNegativeSerial_fallsBackToANumber() = check(
        listOf(
            // Excel fills the cell with ##### here, which tells the reader
            // nothing; the number at least says what is in the file.
            Case(num(-1.0), dateFmt(14, "mm-dd-yy"), "-1"),
            Case(num(-0.5), dateFmt(21, "h:mm:ss"), "-0.5"),
        ),
    )

    @Test
    fun aDateStyleWithNoCode_fallsBackToANumber() = check(
        listOf(
            Case(
                num(45306.0),
                CellStyle.DEFAULT.copy(numFmtId = 200, formatCode = null, isDate = true),
                "45306",
                note = "no code to render with",
            ),
        ),
    )

    // --- the non-numeric values ---

    @Test
    fun nonNumericValues_ignoreTheNumberFormat() = check(
        listOf(
            Case(CellValue.Bool(true), GENERAL, "TRUE"),
            Case(CellValue.Bool(false), numFmt(3, "#,##0"), "FALSE", note = "format is irrelevant"),
            Case(CellValue.Error("#DIV/0!"), GENERAL, "#DIV/0!"),
            Case(CellValue.InlineText("Toshkent"), GENERAL, "Toshkent"),
            Case(CellValue.InlineText(""), GENERAL, ""),
            Case(CellValue.SharedText(0), GENERAL, "apple"),
            Case(CellValue.SharedText(2), GENERAL, "cherry"),
            Case(CellValue.SharedText(9), GENERAL, "", note = "an index past the table"),
        ),
    )

    @Test
    fun defaults_areGeneralAndThe1900Epoch() {
        assertEquals("30", ValueFormatter.format(num(30.0)))
        assertEquals("", ValueFormatter.format(CellValue.SharedText(0)))
    }

    private companion object {
        val GENERAL: CellStyle = CellStyle.DEFAULT
        val STRINGS = StringTable(listOf("apple", "banana", "cherry"))
    }
}
