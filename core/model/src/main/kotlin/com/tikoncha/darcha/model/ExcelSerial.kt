package com.tikoncha.darcha.model

/**
 * A civil date decoded from an Excel date serial.
 *
 * @property year the year.
 * @property month month of the year, `1..12`.
 * @property day day of the month, `1..31`.
 * @property dayOfWeek day of the week, `0` = Sunday .. `6` = Saturday, counted
 *   the way Excel counts it — see [ExcelSerial] on the 1900 bug.
 */
public data class ExcelDate(
    public val year: Int,
    public val month: Int,
    public val day: Int,
    public val dayOfWeek: Int,
)

/**
 * Excel's date serial numbers (TECH_SPEC §7, "Known traps").
 *
 * A date in a spreadsheet is a plain number: whole days since an epoch, with the
 * fractional part carrying the time of day. Which epoch applies depends on the
 * workbook's `date1904` flag.
 *
 * ## The 1900 leap-year bug
 *
 * In the 1900 system **serial 60 is 1900-02-29 — a day that never existed.**
 * 1900 was not a leap year (divisible by 100, not by 400). Lotus 1-2-3 had the
 * bug, Excel reproduced it for file compatibility, and every spreadsheet
 * application since has kept it.
 *
 * It is not a rounding artifact and it must not be "corrected": doing so would
 * shift every date before 1900-03-01 by one day relative to what the authoring
 * application displays. Darcha is a viewer — showing something different from
 * Excel would be the bug.
 *
 * | Serial | Date | Note |
 * |---|---|---|
 * | 1 | 1900-01-01 | the epoch |
 * | 59 | 1900-02-28 | the last real day before the gap |
 * | **60** | **1900-02-29** | **never existed**; reproduced deliberately |
 * | 61 | 1900-03-01 | from here on one day is subtracted |
 *
 * Serial 60 is returned as `1900-02-29` rather than normalized away. `java.time`
 * cannot represent that date at all, which is why the conversion below is plain
 * arithmetic instead of `LocalDate`.
 *
 * **Weekdays follow the serial, not the calendar.** Excel treats serial 1 as a
 * Sunday; the real 1900-01-01 was a Monday. Because the phantom day shifts
 * everything, Excel's weekdays are one off from the true calendar for serials
 * `1..60` and correct from `61` on. [dateOf] reproduces Excel's reckoning.
 *
 * The **1904 system** (Excel for Mac's original default) has no such bug: serial
 * 0 is 1904-01-01 and the days run continuously.
 */
public object ExcelSerial {

    /** The phantom day: 1900-02-29 in the 1900 system. */
    public const val LEAP_BUG_SERIAL: Long = 60L

    /** Days from 1970-01-01 back to 1899-12-31 — the day before serial 1. */
    private val EPOCH_1900: Long = daysFromCivil(1899, 12, 31)

    /** Days from 1970-01-01 back to 1904-01-01 — serial 0 in the 1904 system. */
    private val EPOCH_1904: Long = daysFromCivil(1904, 1, 1)

    private const val DAYS_PER_WEEK = 7L

    /**
     * The civil date [serialDay] whole days into the workbook's epoch.
     *
     * @param serialDay the integer part of a date serial. Negative values are
     *   extrapolated backwards rather than rejected; Excel itself cannot display
     *   them, and the caller decides what to do about that.
     * @param date1904 the workbook's epoch flag: `true` for the 1904 system.
     */
    public fun dateOf(serialDay: Long, date1904: Boolean): ExcelDate {
        if (date1904) {
            // Serial 0 is 1904-01-01, which was a Friday (weekday index 5).
            return civil(EPOCH_1904 + serialDay, floorMod(serialDay + 5L, DAYS_PER_WEEK))
        }

        // Excel's serial 1 is a Sunday, so the weekday is (serial - 1) mod 7 —
        // taken from the serial itself, which is what keeps the phantom day from
        // knocking the sequence out of step with Excel.
        val weekday = floorMod(serialDay - 1L, DAYS_PER_WEEK)

        if (serialDay == LEAP_BUG_SERIAL) return ExcelDate(1900, 2, 29, weekday.toInt())

        // Everything after the phantom day is one real day earlier than its
        // serial suggests.
        val offset = if (serialDay > LEAP_BUG_SERIAL) serialDay - 1L else serialDay
        return civil(EPOCH_1900 + offset, weekday)
    }

    private fun civil(daysSinceUnixEpoch: Long, weekday: Long): ExcelDate {
        val (year, month, day) = civilFromDays(daysSinceUnixEpoch)
        return ExcelDate(year, month, day, weekday.toInt())
    }

    /**
     * Round [serial] to the nearest `1/[ticksPerDay]` of a day, as a tick count.
     *
     * Rounding happens on the serial as a whole, before it is split into a date
     * and a time, so a value that rounds up past midnight carries into the next
     * day the way Excel's does.
     */
    internal fun ticksOf(serial: Double, ticksPerDay: Long): Long =
        Math.round(serial * ticksPerDay.toDouble())

    // --- civil calendar arithmetic ---
    //
    // Howard Hinnant's days-from-civil / civil-from-days, which are exact for any
    // year and need no library. They are used instead of java.time because the
    // phantom 1900-02-29 is not a representable LocalDate.

    /** Days from 1970-01-01 to the given proleptic Gregorian date. */
    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val year = if (m <= 2) y - 1 else y
        val era = (if (year >= 0) year else year - 399) / 400
        val yearOfEra = year - era * 400 // 0..399
        val dayOfYear = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1 // 0..365
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era.toLong() * 146_097L + dayOfEra.toLong() - 719_468L
    }

    /** The proleptic Gregorian date [days] days after 1970-01-01. */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val dayOfEra = z - era * 146_097L // 0..146096
        val yearOfEra =
            (dayOfEra - dayOfEra / 1460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val year = yearOfEra + era * 400L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val mp = (5L * dayOfYear + 2L) / 153L // 0..11, March-based
        val day = (dayOfYear - (153L * mp + 2L) / 5L + 1L).toInt()
        val month = (if (mp < 10L) mp + 3L else mp - 9L).toInt()
        return Triple((if (month <= 2) year + 1L else year).toInt(), month, day)
    }

    private fun floorMod(value: Long, modulus: Long): Long = ((value % modulus) + modulus) % modulus
}
