package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for date-format detection and the indexed color palette. */
class DateFormatsTest {

    @Test
    fun builtinDateTimeIds_areDates() {
        assertTrue(DateFormats.isDateFormat(14, null)) // mm-dd-yy
        assertTrue(DateFormats.isDateFormat(18, null)) // h:mm AM/PM
        assertTrue(DateFormats.isDateFormat(22, "m/d/yy h:mm"))
        assertTrue(DateFormats.isDateFormat(45, "mm:ss"))
        assertTrue(DateFormats.isDateFormat(47, null)) // mmss.0
    }

    @Test
    fun customDateCodes_areDates() {
        assertTrue(DateFormats.isDateFormat(164, "yyyy-mm-dd"))
        assertTrue(DateFormats.isDateFormat(165, "m/d/yy h:mm"))
        assertTrue(DateFormats.isDateFormat(200, "d-mmm-yy"))
        assertTrue(DateFormats.isDateFormat(201, "dddd"))
        assertTrue(DateFormats.isDateFormat(202, "[h]:mm:ss")) // elapsed → mm:ss survives
        assertTrue(DateFormats.isDateFormat(203, "[\$-409]d/m/yyyy")) // locale bracket stripped
    }

    @Test
    fun nonDateCodes_areNotDates() {
        assertFalse(DateFormats.isDateFormat(0, "General"))
        assertFalse(DateFormats.isDateFormat(0, null))
        assertFalse(DateFormats.isDateFormat(1, "0"))
        assertFalse(DateFormats.isDateFormat(2, "0.00"))
        assertFalse(DateFormats.isDateFormat(9, "0%"))
        assertFalse(DateFormats.isDateFormat(3, "#,##0.00"))
        assertFalse(DateFormats.isDateFormat(49, "@"))
    }

    @Test
    fun quotedAndEscapedTokens_areNotDates() {
        assertFalse(DateFormats.isDateFormat(200, "\"mm\"0"))  // quoted literal "mm"
        assertFalse(DateFormats.isDateFormat(201, "\\y0"))      // escaped y
        assertFalse(DateFormats.isDateFormat(202, "[Red]0.0"))  // color bracket only
    }

    @Test
    fun indexedColors_standardPalette() {
        assertEquals(Color(0xFF000000.toInt()), IndexedColors.colorFor(0)) // black
        assertEquals(Color(0xFFFFFFFF.toInt()), IndexedColors.colorFor(1)) // white
        assertEquals(Color(0xFFFF0000.toInt()), IndexedColors.colorFor(2)) // red
        assertEquals(Color(0xFFFFFF00.toInt()), IndexedColors.colorFor(5)) // yellow
        assertEquals(Color(0xFFC0C0C0.toInt()), IndexedColors.colorFor(22)) // silver
    }

    @Test
    fun indexedColors_outOfRange_isNull() {
        assertNull(IndexedColors.colorFor(64)) // system foreground
        assertNull(IndexedColors.colorFor(-1))
        assertNull(IndexedColors.colorFor(999))
    }
}
