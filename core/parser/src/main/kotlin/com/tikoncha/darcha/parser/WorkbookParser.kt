package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.ErrorKind
import com.tikoncha.darcha.model.SheetRef
import com.tikoncha.darcha.model.WorkbookMeta
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Parses `xl/workbook.xml` and `xl/_rels/workbook.xml.rels` into [WorkbookMeta]
 * (TECH_SPEC §7 step 2). Streaming only — no DOM is ever materialized.
 *
 * Sheet order is preserved as declared in `<sheets>`. Each sheet's `r:id` is
 * resolved through the relationships part to a normalized worksheet part path.
 * Missing parts, unknown relationships, and malformed XML all surface as
 * [ErrorKind.Corrupted]; no raw exception escapes.
 */
internal object WorkbookParser {

    /** Relationship namespace for the `r:id` attribute on `<sheet>`. */
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val WORKBOOK_PART = "xl/workbook.xml"
    private const val WORKBOOK_RELS_PART = "xl/_rels/workbook.xml.rels"

    /** Directory that workbook relationship targets resolve against. */
    private const val WORKBOOK_BASE_DIR = "xl"

    /** Read [zip]'s workbook part + rels and produce ordered sheet metadata. */
    fun parse(zip: ZipFile): ParseResult<WorkbookMeta> =
        try {
            val rels = readPart(zip, WORKBOOK_RELS_PART) { parseRels(it) }
                ?: return corrupted("missing $WORKBOOK_RELS_PART")
            val workbook = readPart(zip, WORKBOOK_PART) { parseWorkbookXml(it) }
                ?: return corrupted("missing $WORKBOOK_PART")

            val sheets = workbook.sheets.map { raw ->
                val target = rels[raw.relId]
                    ?: return corrupted("sheet '${raw.name}' references unknown relationship '${raw.relId}'")
                SheetRef(
                    name = raw.name,
                    sheetId = raw.sheetId,
                    relId = raw.relId,
                    partPath = resolvePartPath(WORKBOOK_BASE_DIR, target),
                )
            }
            ParseResult.Ok(WorkbookMeta(date1904 = workbook.date1904, sheets = sheets))
        } catch (e: XmlPullParserException) {
            corrupted("malformed workbook XML: ${e.message}")
        } catch (e: IOException) {
            corrupted("could not read workbook parts: ${e.message}")
        }

    // --- Stream-parsing helpers (internal so inline-XML unit tests can call them) ---

    /** A `<sheet>` entry from workbook.xml, before its relationship is resolved. */
    internal data class RawSheet(val name: String, val sheetId: Int, val relId: String)

    /** The parsed shape of `xl/workbook.xml`: the 1904 flag and ordered sheets. */
    internal data class WorkbookXml(val date1904: Boolean, val sheets: List<RawSheet>)

    /** Stream-parse `xl/workbook.xml`. Matches elements by local name. */
    internal fun parseWorkbookXml(input: InputStream): WorkbookXml {
        val parser = Xml.newPullParser(input)
        var date1904 = false
        val sheets = mutableListOf<RawSheet>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "workbookPr" -> date1904 = parseXmlBoolean(parser.getAttributeValue(null, "date1904"))
                    "sheet" -> sheets += RawSheet(
                        name = parser.getAttributeValue(null, "name").orEmpty(),
                        sheetId = parser.getAttributeValue(null, "sheetId")?.toIntOrNull() ?: 0,
                        relId = parser.getAttributeValue(R_NS, "id").orEmpty(),
                    )
                }
            }
            event = parser.next()
        }
        return WorkbookXml(date1904, sheets)
    }

    /** Stream-parse `xl/_rels/workbook.xml.rels` into an ordered relId → Target map. */
    internal fun parseRels(input: InputStream): Map<String, String> {
        val parser = Xml.newPullParser(input)
        val rels = LinkedHashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) rels[id] = target
            }
            event = parser.next()
        }
        return rels
    }

    /**
     * Resolve a relationship [target] against [baseDir] into a normalized ZIP
     * part path. Absolute targets (leading `/`) are package-root relative;
     * relative targets are joined to [baseDir], collapsing `.` / `..` segments.
     *
     * Examples (with `baseDir = "xl"`):
     * - `worksheets/sheet1.xml`   → `xl/worksheets/sheet1.xml`
     * - `/xl/worksheets/sheet1.xml` → `xl/worksheets/sheet1.xml`
     */
    internal fun resolvePartPath(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val segments = ArrayDeque<String>()
        baseDir.split("/").forEach { if (it.isNotEmpty()) segments.addLast(it) }
        for (segment in target.split("/")) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/")
    }

    /** OOXML booleans: `1` or `true` are true; anything else (incl. null) is false. */
    private fun parseXmlBoolean(value: String?): Boolean =
        value == "1" || value.equals("true", ignoreCase = true)

    /** Run [block] on the [part] entry's stream, or return `null` if absent. */
    private inline fun <T> readPart(zip: ZipFile, part: String, block: (InputStream) -> T): T? {
        val entry = zip.getEntry(part) ?: return null
        return zip.getInputStream(entry).use(block)
    }

    private fun corrupted(message: String): ParseResult<WorkbookMeta> =
        ParseResult.Err(ErrorKind.Corrupted(message))
}
