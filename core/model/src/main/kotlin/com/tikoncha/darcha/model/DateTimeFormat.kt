package com.tikoncha.darcha.model

/**
 * Renders an Excel date/time serial through a number-format code (TECH_SPEC §7).
 *
 * This is the one renderer for every date and time Darcha shows. The builtin
 * date formats (ids 14–22, 45–47) are not special-cased: the parser resolves
 * them to their spec-defined codes (`mm-dd-yy`, `h:mm:ss`, …) and they run
 * through the same tokenizer as a custom code, so "implemented exactly" and
 * "implemented once" are the same thing here.
 *
 * Month and weekday names come in as [DateNames] rather than living here: Excel
 * resolves them per locale, so they belong to the caller (T24 supplies Uzbek
 * ones). None of the builtin date formats spell a name out — only custom codes
 * with `mmm`/`dddd` and friends do.
 */
internal object DateTimeFormat {

    /**
     * Render [serial] through [code], or `null` when [code] carries no date or
     * time token at all and the caller should fall back to `General`.
     *
     * @param serial the cell's raw value: whole days plus a fraction of a day.
     * @param date1904 the workbook's epoch flag.
     * @param names the names to spell `mmm`/`dddd` and friends with.
     */
    fun render(serial: Double, code: String, date1904: Boolean, names: DateNames): String? {
        val tokens = resolveMonths(tokenize(firstSection(code)))
        if (tokens.none { it.isTemporal }) return null

        val ticksPerDay = ticksPerDayFor(tokens)
        val subScale = subSecondScale(tokens)

        // A date-only format shows the day the serial falls on; it does not round
        // up to tomorrow because the time is late. Anything with a clock in it
        // rounds to its smallest visible unit, which may carry into the next day.
        val ticks = if (ticksPerDay == 1L) {
            Math.floor(serial).toLong()
        } else {
            ExcelSerial.ticksOf(serial, ticksPerDay)
        }

        val serialDay = Math.floorDiv(ticks, ticksPerDay)
        val withinDay = Math.floorMod(ticks, ticksPerDay)

        val secondsInDay = secondsInDayOf(withinDay, ticksPerDay, subScale)
        val subTicks = if (subScale > 1L) withinDay % subScale else 0L
        val date = ExcelSerial.dateOf(serialDay, date1904)

        val hour = (secondsInDay / SECONDS_PER_HOUR).toInt()
        val minute = ((secondsInDay % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toInt()
        val second = (secondsInDay % SECONDS_PER_MINUTE).toInt()
        val twelveHour = tokens.any { it is Token.Meridiem }
        val totalSeconds = serialDay * SECONDS_PER_DAY + secondsInDay

        val out = StringBuilder(code.length + 8)
        for (token in tokens) {
            out.append(
                when (token) {
                    is Token.Literal -> token.text
                    is Token.Year -> year(date.year, token.width)
                    is Token.Month -> month(date.month, token.width, names)
                    is Token.Minute -> pad(minute.toLong(), token.width)
                    is Token.Day -> day(date, token.width, names)
                    is Token.Hour -> pad(displayHour(hour, twelveHour).toLong(), token.width)
                    is Token.Second -> pad(second.toLong(), token.width)
                    is Token.SubSecond -> "." + pad(subTicks, token.decimals)
                    is Token.Elapsed -> pad(elapsed(token.unit, totalSeconds), token.width)
                    is Token.Meridiem -> meridiem(hour, token.short)
                    is Token.MonthOrMinute -> "" // resolved away above
                },
            )
        }
        return out.toString()
    }

    // --- tokens ---

    private sealed interface Token {
        /** Whether this token reads something out of the serial. */
        val isTemporal: Boolean get() = true

        data class Literal(val text: String) : Token {
            override val isTemporal: Boolean get() = false
        }

        data class Year(val width: Int) : Token
        data class Month(val width: Int) : Token
        data class Minute(val width: Int) : Token
        data class Day(val width: Int) : Token
        data class Hour(val width: Int) : Token
        data class Second(val width: Int) : Token
        data class SubSecond(val decimals: Int) : Token
        data class Elapsed(val unit: Char, val width: Int) : Token
        data class Meridiem(val short: Boolean) : Token

        /** An `m` run that is still either a month or a minute. */
        data class MonthOrMinute(val width: Int) : Token
    }

    /**
     * Split a format code at `;` and keep the first section.
     *
     * The later sections are the negative / zero / text variants, and a negative
     * serial is not a date Excel can display anyway.
     */
    private fun firstSection(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            when (val c = code[i]) {
                ';' -> return out.toString()
                '"' -> {
                    val end = code.indexOf('"', i + 1)
                    val stop = if (end < 0) code.length else end + 1
                    out.append(code, i, stop)
                    i = stop
                }
                '\\' -> {
                    out.append(c)
                    if (i + 1 < code.length) out.append(code[i + 1])
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun tokenize(code: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> {
                    val end = code.indexOf('"', i + 1)
                    val stop = if (end < 0) code.length else end
                    tokens.add(Token.Literal(code.substring(i + 1, stop)))
                    i = if (end < 0) code.length else end + 1
                }
                // An escaped character is a literal.
                c == '\\' -> {
                    if (i + 1 < code.length) tokens.add(Token.Literal(code[i + 1].toString()))
                    i += 2
                }
                // "_x" reserves the width of x; "*x" fills with x. Neither prints
                // anything a viewer can honour, so one becomes a space and the
                // other nothing.
                c == '_' -> {
                    tokens.add(Token.Literal(" "))
                    i += 2
                }
                c == '*' -> i += 2
                c == '[' -> {
                    val end = code.indexOf(']', i + 1)
                    val stop = if (end < 0) code.length else end
                    elapsedToken(code.substring(i + 1, stop))?.let { tokens.add(it) }
                    i = if (end < 0) code.length else end + 1
                }
                matchesAt(code, i, "AM/PM") -> {
                    tokens.add(Token.Meridiem(short = false))
                    i += 5
                }
                matchesAt(code, i, "A/P") -> {
                    tokens.add(Token.Meridiem(short = true))
                    i += 3
                }
                c == 'y' || c == 'Y' -> i = runOf(code, i) { tokens.add(Token.Year(it)) }
                c == 'd' || c == 'D' -> i = runOf(code, i) { tokens.add(Token.Day(it)) }
                c == 'h' || c == 'H' -> i = runOf(code, i) { tokens.add(Token.Hour(it)) }
                c == 's' || c == 'S' -> i = runOf(code, i) { tokens.add(Token.Second(it)) }
                // Three or more m's can only be a month name; one or two is still
                // ambiguous and gets resolved from its neighbours.
                c == 'm' || c == 'M' -> i = runOf(code, i) { width ->
                    tokens.add(if (width >= 3) Token.Month(width) else Token.MonthOrMinute(width))
                }
                // ".0" after a seconds token is a fraction of a second; anywhere
                // else a dot is just a dot.
                c == '.' && decimalsAt(code, i) > 0 && tokens.lastTemporal() is Token.Second -> {
                    val decimals = decimalsAt(code, i)
                    tokens.add(Token.SubSecond(decimals))
                    i += 1 + decimals
                }
                else -> {
                    tokens.add(Token.Literal(c.toString()))
                    i++
                }
            }
        }
        return tokens
    }

    /** `[h]`, `[mm]`, `[ss]` are elapsed totals; every other `[...]` is skipped. */
    private fun elapsedToken(inner: String): Token? {
        if (inner.isEmpty()) return null
        val unit = inner[0].lowercaseChar()
        if (unit !in "hms") return null
        if (!inner.all { it.lowercaseChar() == unit }) return null
        return Token.Elapsed(unit, inner.length)
    }

    private inline fun runOf(code: String, start: Int, emit: (Int) -> Unit): Int {
        val marker = code[start].lowercaseChar()
        var end = start
        while (end < code.length && code[end].lowercaseChar() == marker) end++
        emit(end - start)
        return end
    }

    private fun decimalsAt(code: String, dot: Int): Int {
        var end = dot + 1
        while (end < code.length && code[end] == '0') end++
        return end - dot - 1
    }

    private fun matchesAt(code: String, at: Int, literal: String): Boolean =
        code.regionMatches(at, literal, 0, literal.length, ignoreCase = true)

    private fun List<Token>.lastTemporal(): Token? = lastOrNull { it.isTemporal }

    /**
     * Decide whether each ambiguous `m` run is a month or a minute.
     *
     * Excel's rule, and the reason `mm` means two different things in `mm-dd-yy`
     * and `h:mm`: an `m` is a minute when it sits next to an hour or a second —
     * looking past the punctuation between them — and a month otherwise.
     */
    private fun resolveMonths(tokens: List<Token>): List<Token> {
        if (tokens.none { it is Token.MonthOrMinute }) return tokens
        return tokens.mapIndexed { index, token ->
            if (token !is Token.MonthOrMinute) return@mapIndexed token
            val before = tokens.subList(0, index).lastTemporal()
            val after = tokens.subList(index + 1, tokens.size).firstOrNull { it.isTemporal }
            val isMinute = before is Token.Hour ||
                (before is Token.Elapsed && before.unit == 'h') ||
                after is Token.Second ||
                (after is Token.Elapsed && after.unit == 's')
            if (isMinute) Token.Minute(token.width) else Token.Month(token.width)
        }
    }

    // --- granularity ---

    /**
     * The smallest unit the code actually shows, as ticks per day. Everything
     * finer than that is rounded away before the serial is split up.
     */
    private fun ticksPerDayFor(tokens: List<Token>): Long {
        val subScale = subSecondScale(tokens)
        if (subScale > 1L) return SECONDS_PER_DAY * subScale
        if (tokens.any { it is Token.Second || (it is Token.Elapsed && it.unit == 's') }) {
            return SECONDS_PER_DAY
        }
        if (tokens.any { it is Token.Minute || (it is Token.Elapsed && it.unit == 'm') }) {
            return MINUTES_PER_DAY
        }
        if (tokens.any { it is Token.Hour || (it is Token.Elapsed && it.unit == 'h') }) {
            return HOURS_PER_DAY
        }
        return 1L
    }

    private fun subSecondScale(tokens: List<Token>): Long {
        val decimals = tokens.filterIsInstance<Token.SubSecond>().maxOfOrNull { it.decimals } ?: 0
        var scale = 1L
        repeat(decimals) { scale *= 10L }
        return scale
    }

    /** Normalize a within-day tick count to whole seconds. */
    private fun secondsInDayOf(withinDay: Long, ticksPerDay: Long, subScale: Long): Long = when {
        subScale > 1L -> withinDay / subScale
        ticksPerDay == SECONDS_PER_DAY -> withinDay
        ticksPerDay == MINUTES_PER_DAY -> withinDay * SECONDS_PER_MINUTE
        ticksPerDay == HOURS_PER_DAY -> withinDay * SECONDS_PER_HOUR
        else -> 0L
    }

    // --- rendering ---

    private fun year(year: Int, width: Int): String =
        if (width <= 2) pad((year % 100).toLong(), 2) else pad(year.toLong(), 4)

    /** `m`/`mm` are the month number; `mmm` and longer spell the name out. */
    private fun month(month: Int, width: Int, names: DateNames): String =
        if (width >= 3) names.month(month, width) else pad(month.toLong(), width)

    /** `d`/`dd` are the day of the month; `ddd` and longer are the weekday name. */
    private fun day(date: ExcelDate, width: Int, names: DateNames): String =
        if (width >= 3) names.day(date.dayOfWeek, width) else pad(date.day.toLong(), width)

    private fun displayHour(hour: Int, twelveHour: Boolean): Int {
        if (!twelveHour) return hour
        val h = hour % 12
        return if (h == 0) 12 else h
    }

    private fun meridiem(hour: Int, short: Boolean): String = when {
        short -> if (hour < 12) "A" else "P"
        else -> if (hour < 12) "AM" else "PM"
    }

    private fun elapsed(unit: Char, totalSeconds: Long): Long = when (unit) {
        'h' -> totalSeconds / SECONDS_PER_HOUR
        'm' -> totalSeconds / SECONDS_PER_MINUTE
        else -> totalSeconds
    }

    private fun pad(value: Long, width: Int): String {
        val digits = value.toString()
        return if (digits.length >= width) digits else digits.padStart(width, '0')
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_DAY = 86_400L
    private const val MINUTES_PER_DAY = 1_440L
    private const val HOURS_PER_DAY = 24L

}
