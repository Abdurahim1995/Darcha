package com.tikoncha.darcha.model

/**
 * The month and weekday names a date format spells out — `mmm`, `mmmm`,
 * `mmmmm`, `ddd`, `dddd`.
 *
 * Excel resolves these against the authoring locale, which the file does not
 * record in a way a viewer can trust. So they are an **input** to formatting
 * rather than a constant inside it: `:core:model` stays free of any locale
 * dependency (no `java.util.Locale`, no resources, no platform calls), and the
 * UI layer supplies whatever names it wants — Uzbek ones, in T24.
 *
 * @property months the twelve month names, January first. Long forms; the
 *   three-letter and one-letter variants are taken from these.
 * @property days the seven weekday names, **Sunday first**, matching
 *   [ExcelDate.dayOfWeek].
 */
public data class DateNames(
    public val months: List<String>,
    public val days: List<String>,
) {
    init {
        require(months.size == MONTHS_IN_YEAR) { "expected $MONTHS_IN_YEAR months, got ${months.size}" }
        require(days.size == DAYS_IN_WEEK) { "expected $DAYS_IN_WEEK days, got ${days.size}" }
    }

    /** The name for [month] (`1..12`), abbreviated to [width] the way Excel does. */
    internal fun month(month: Int, width: Int): String {
        val name = months[month - 1]
        return when {
            width >= 5 -> name.take(1) // "mmmmm" — the initial only
            width == 4 -> name
            else -> name.take(3) // "mmm"
        }
    }

    /** The name for [dayOfWeek] (`0` = Sunday), abbreviated to [width]. */
    internal fun day(dayOfWeek: Int, width: Int): String {
        val name = days[dayOfWeek]
        return if (width >= 4) name else name.take(3)
    }

    public companion object {
        private const val MONTHS_IN_YEAR = 12
        private const val DAYS_IN_WEEK = 7

        /**
         * Excel's own default, and Darcha's until the UI supplies something
         * better. English is not a claim that the reader speaks it — it is the
         * one set of names that is predictable when the file tells us nothing.
         */
        public val ENGLISH: DateNames = DateNames(
            months = listOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December",
            ),
            days = listOf(
                "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
            ),
        )
    }
}
