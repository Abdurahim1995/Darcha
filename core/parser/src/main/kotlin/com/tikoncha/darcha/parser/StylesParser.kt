package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellStyle
import com.tikoncha.darcha.model.Color
import com.tikoncha.darcha.model.ErrorKind
import com.tikoncha.darcha.model.HorizontalAlignment
import com.tikoncha.darcha.model.StyleTable
import com.tikoncha.darcha.model.VerticalAlignment
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Parses `xl/styles.xml` into a [StyleTable] (TECH_SPEC §7 step 4). Streaming
 * only — no DOM.
 *
 * Resolves each `cellXfs/xf` into a [CellStyle] by joining its font (bold /
 * italic / color), fill (solid foreground color), alignment, and number format.
 * Number formats are custom (`<numFmts>`) or built-in ([BuiltinNumberFormats]);
 * colors are `rgb`, `indexed` ([IndexedColors]), or `theme` (documented
 * fallback). A missing `styles.xml` yields [StyleTable.EMPTY].
 */
internal object StylesParser {

    private const val STYLES_PART = "xl/styles.xml"

    /** Read [zip]'s styles part, or [StyleTable.EMPTY] if it is absent. */
    fun parse(zip: ZipFile): ParseResult<StyleTable> =
        try {
            val entry = zip.getEntry(STYLES_PART)
                ?: return ParseResult.Ok(StyleTable.EMPTY)
            zip.getInputStream(entry).use { ParseResult.Ok(parseStyles(it)) }
        } catch (e: XmlPullParserException) {
            ParseResult.Err(ErrorKind.Corrupted("malformed $STYLES_PART: ${e.message}"))
        } catch (e: IOException) {
            ParseResult.Err(ErrorKind.Corrupted("could not read $STYLES_PART: ${e.message}"))
        }

    private enum class Section { NONE, NUM_FMTS, FONTS, FILLS, CELL_XFS }

    private data class FontStyle(val bold: Boolean, val italic: Boolean, val color: Color?)

    private data class RawXf(
        val numFmtId: Int,
        val fontId: Int,
        val fillId: Int,
        val hAlign: HorizontalAlignment,
        val vAlign: VerticalAlignment,
    )

    /** Stream-parse a `styles.xml` document into a resolved [StyleTable]. */
    internal fun parseStyles(input: InputStream): StyleTable {
        val parser = Xml.newPullParser(input)

        val customFormats = HashMap<Int, String>()
        val fonts = ArrayList<FontStyle>()
        val fills = ArrayList<Color?>()
        val xfs = ArrayList<RawXf>()

        var section = Section.NONE

        var fontBold = false
        var fontItalic = false
        var fontColor: Color? = null

        var fillPattern: String? = null
        var fillFg: Color? = null

        var xfNumFmtId = 0
        var xfFontId = 0
        var xfFillId = 0
        var xfHAlign = HorizontalAlignment.GENERAL
        var xfVAlign = VerticalAlignment.BOTTOM

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "numFmts" -> section = Section.NUM_FMTS
                    "fonts" -> section = Section.FONTS
                    "fills" -> section = Section.FILLS
                    "cellXfs" -> section = Section.CELL_XFS

                    "numFmt" -> if (section == Section.NUM_FMTS) {
                        val id = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                        val code = parser.getAttributeValue(null, "formatCode")
                        if (id != null && code != null) customFormats[id] = code
                    }

                    "font" -> if (section == Section.FONTS) {
                        fontBold = false; fontItalic = false; fontColor = null
                    }
                    "b" -> if (section == Section.FONTS) fontBold = boolPresent(parser)
                    "i" -> if (section == Section.FONTS) fontItalic = boolPresent(parser)
                    "color" -> if (section == Section.FONTS) fontColor = parseColor(parser)

                    "fill" -> if (section == Section.FILLS) { fillPattern = null; fillFg = null }
                    "patternFill" -> if (section == Section.FILLS) {
                        fillPattern = parser.getAttributeValue(null, "patternType")
                    }
                    "fgColor" -> if (section == Section.FILLS) fillFg = parseColor(parser)

                    "xf" -> if (section == Section.CELL_XFS) {
                        xfNumFmtId = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                        xfFontId = parser.getAttributeValue(null, "fontId")?.toIntOrNull() ?: 0
                        xfFillId = parser.getAttributeValue(null, "fillId")?.toIntOrNull() ?: 0
                        xfHAlign = HorizontalAlignment.GENERAL
                        xfVAlign = VerticalAlignment.BOTTOM
                    }
                    "alignment" -> if (section == Section.CELL_XFS) {
                        xfHAlign = parseHorizontal(parser.getAttributeValue(null, "horizontal"))
                        xfVAlign = parseVertical(parser.getAttributeValue(null, "vertical"))
                    }
                }

                XmlPullParser.END_TAG -> when (name) {
                    "numFmts", "fonts", "fills", "cellXfs" -> section = Section.NONE
                    "font" -> if (section == Section.FONTS) fonts.add(FontStyle(fontBold, fontItalic, fontColor))
                    "fill" -> if (section == Section.FILLS) {
                        fills.add(if (fillPattern == "solid") fillFg else null)
                    }
                    "xf" -> if (section == Section.CELL_XFS) {
                        xfs.add(RawXf(xfNumFmtId, xfFontId, xfFillId, xfHAlign, xfVAlign))
                    }
                }
            }
            event = parser.next()
        }

        return StyleTable(xfs.map { xf -> resolve(xf, fonts, fills, customFormats) })
    }

    private fun resolve(
        xf: RawXf,
        fonts: List<FontStyle>,
        fills: List<Color?>,
        customFormats: Map<Int, String>,
    ): CellStyle {
        val font = fonts.getOrNull(xf.fontId) ?: FontStyle(bold = false, italic = false, color = null)
        val fillColor = fills.getOrNull(xf.fillId)
        val code = customFormats[xf.numFmtId] ?: BuiltinNumberFormats.codeFor(xf.numFmtId)
        return CellStyle(
            bold = font.bold,
            italic = font.italic,
            fontColor = font.color,
            fillColor = fillColor,
            horizontalAlignment = xf.hAlign,
            verticalAlignment = xf.vAlign,
            numFmtId = xf.numFmtId,
            formatCode = code,
            isDate = DateFormats.isDateFormat(xf.numFmtId, code),
        )
    }

    /** A boolean toggle element like `<b/>`: absent `val` means present/true. */
    private fun boolPresent(parser: XmlPullParser): Boolean {
        val value = parser.getAttributeValue(null, "val") ?: return true
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    /**
     * Resolve a color element's attributes to ARGB. Order: explicit `rgb`, then
     * `indexed` (standard palette), then `theme` (documented v1 fallback). An
     * `auto`/empty color returns `null` (default).
     */
    private fun parseColor(parser: XmlPullParser): Color? {
        parser.getAttributeValue(null, "rgb")?.let { return rgbToColor(it) }
        parser.getAttributeValue(null, "indexed")?.toIntOrNull()?.let { return IndexedColors.colorFor(it) }
        parser.getAttributeValue(null, "theme")?.toIntOrNull()?.let { return themeFallback(it) }
        return null
    }

    /** Parse an `AARRGGBB` (or `RRGGBB`, assumed opaque) hex color. */
    private fun rgbToColor(hex: String): Color? {
        val h = hex.removePrefix("#")
        return when (h.length) {
            8 -> h.toLongOrNull(16)?.let { Color(it.toInt()) }
            6 -> h.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) }
            else -> null
        }
    }

    /**
     * v1 theme-color fallback: theme 1 (window text) → black, theme 0 (window
     * background) → white, all others → black. Full theme resolution is a
     * post-v1 item (TECH_SPEC §7 traps).
     */
    private fun themeFallback(theme: Int): Color = when (theme) {
        0 -> Color.WHITE
        else -> Color.BLACK
    }

    private fun parseHorizontal(value: String?): HorizontalAlignment = when (value) {
        "left" -> HorizontalAlignment.LEFT
        "center" -> HorizontalAlignment.CENTER
        "right" -> HorizontalAlignment.RIGHT
        "fill" -> HorizontalAlignment.FILL
        "justify" -> HorizontalAlignment.JUSTIFY
        "centerContinuous" -> HorizontalAlignment.CENTER_CONTINUOUS
        "distributed" -> HorizontalAlignment.DISTRIBUTED
        else -> HorizontalAlignment.GENERAL
    }

    private fun parseVertical(value: String?): VerticalAlignment = when (value) {
        "top" -> VerticalAlignment.TOP
        "center" -> VerticalAlignment.CENTER
        "justify" -> VerticalAlignment.JUSTIFY
        "distributed" -> VerticalAlignment.DISTRIBUTED
        else -> VerticalAlignment.BOTTOM // includes "bottom" and absent (Excel default)
    }
}
