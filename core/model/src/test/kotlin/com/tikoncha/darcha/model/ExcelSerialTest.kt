package com.tikoncha.darcha.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Serial → civil date conversion, including the 1900 leap-year bug
 * (TECH_SPEC §7, [ExcelSerial]).
 */
class ExcelSerialTest {

    private data class Case(val serial: Long, val date1904: Boolean, val expected: String)

    private fun ExcelDate.iso(): String =
        "%04d-%02d-%02d".format(year, month, day)

    private fun check(cases: List<Case>) {
        for (case in cases) {
            val actual = ExcelSerial.dateOf(case.serial, case.date1904).iso()
            assertEquals(
                "serial ${case.serial} (${if (case.date1904) "1904" else "1900"})",
                case.expected,
                actual,
            )
        }
    }

    // --- the 1900 system, and its phantom day ---

    @Test
    fun serials1900_aroundTheLeapYearBug() = check(
        listOf(
            Case(1, false, "1900-01-01"), // the epoch
            Case(2, false, "1900-01-02"),
            Case(31, false, "1900-01-31"),
            Case(32, false, "1900-02-01"),
            Case(58, false, "1900-02-27"),
            Case(59, false, "1900-02-28"), // last real day before the gap
            Case(60, false, "1900-02-29"), // THE BUG: 1900 was not a leap year
            Case(61, false, "1900-03-01"), // one day is subtracted from here on
            Case(62, false, "1900-03-02"),
        ),
    )

    /**
     * The phantom day is why this test exists: if the epoch offset were applied
     * uniformly, every one of these would be a day out.
     */
    @Test
    fun serials1900_farFromTheEpoch() = check(
        listOf(
            Case(367, false, "1901-01-01"),
            Case(1461, false, "1903-12-31"),
            Case(25569, false, "1970-01-01"), // the Unix epoch
            Case(36526, false, "2000-01-01"), // 2000 *was* a leap year
            Case(36586, false, "2000-03-01"),
            Case(45306, false, "2024-01-15"), // excel/dates.xlsx A1
            Case(45657, false, "2024-12-31"), // excel/dates.xlsx A4
            Case(2958465, false, "9999-12-31"), // Excel's last representable day
        ),
    )

    @Test
    fun serials1900_belowTheEpoch_extrapolateBackwards() = check(
        listOf(
            // Excel shows a placeholder here; plain arithmetic is more useful
            // than "1/0/1900" and costs no special case.
            Case(0, false, "1899-12-31"),
            Case(-1, false, "1899-12-30"),
        ),
    )

    // --- the 1904 system: no bug, different epoch ---

    @Test
    fun serials1904_runContinuously() = check(
        listOf(
            Case(0, true, "1904-01-01"), // the epoch
            Case(1, true, "1904-01-02"),
            Case(59, true, "1904-02-29"), // 1904 *was* a leap year — a real day
            Case(60, true, "1904-03-01"), // no gap here
            Case(61, true, "1904-03-02"),
            Case(43830, true, "2024-01-01"),
        ),
    )

    /** The same instant is 1462 serials apart in the two systems. */
    @Test
    fun theTwoEpochs_are1462DaysApart() {
        val in1900 = ExcelSerial.dateOf(45306, date1904 = false)
        val in1904 = ExcelSerial.dateOf(45306 - 1462, date1904 = true)
        assertEquals(in1900.iso(), in1904.iso())
    }

    // --- weekdays ---

    @Test
    fun weekdays_followExcelsReckoningNotTheCalendar() {
        // Excel calls serial 1 a Sunday. The real 1900-01-01 was a Monday — the
        // phantom day is what puts them back in step from serial 61 on.
        assertEquals(SUNDAY, ExcelSerial.dateOf(1, false).dayOfWeek)
        assertEquals(MONDAY, ExcelSerial.dateOf(2, false).dayOfWeek)
        assertEquals(WEDNESDAY, ExcelSerial.dateOf(60, false).dayOfWeek) // the phantom day
        assertEquals(THURSDAY, ExcelSerial.dateOf(61, false).dayOfWeek) // 1900-03-01, correct
        assertEquals(MONDAY, ExcelSerial.dateOf(45306, false).dayOfWeek) // 2024-01-15
        assertEquals(TUESDAY, ExcelSerial.dateOf(45657, false).dayOfWeek) // 2024-12-31
    }

    @Test
    fun weekdays_in1904Mode() {
        assertEquals(FRIDAY, ExcelSerial.dateOf(0, true).dayOfWeek) // 1904-01-01
        assertEquals(MONDAY, ExcelSerial.dateOf(43830, true).dayOfWeek) // 2024-01-01
    }

    private companion object {
        const val SUNDAY = 0
        const val MONDAY = 1
        const val TUESDAY = 2
        const val WEDNESDAY = 3
        const val THURSDAY = 4
        const val FRIDAY = 5
    }
}
