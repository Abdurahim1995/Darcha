package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.ErrorKind
import com.tikoncha.darcha.model.Row
import com.tikoncha.darcha.model.SheetData
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Parses a worksheet part (`xl/worksheets/sheetN.xml`) into the sparse
 * [SheetData] model (TECH_SPEC §7 step 5, §8). Streaming only — no DOM.
 *
 * Cell types: `n` (default, number), `s` (shared string index), `inlineStr`
 * (flattened `<is>` text), `b` (boolean), `e` (error), `str` (formula string
 * result). Formulas (`<f>`) are skipped — only the cached `<v>` is read. The
 * `<dimension>` element is ignored; only real `<c>` elements are trusted.
 *
 * Only cells with a value are stored — a styled-but-empty `<c>` is dropped in v1
 * ("never allocate for empty cells", §8).
 */
internal object SheetParser {

    /** Read the worksheet at [partPath] in [zip] into a [SheetData]. */
    fun parse(zip: ZipFile, partPath: String): ParseResult<SheetData> =
        try {
            val entry = zip.getEntry(partPath)
                ?: return ParseResult.Err(ErrorKind.Corrupted("missing worksheet part '$partPath'"))
            zip.getInputStream(entry).use { ParseResult.Ok(parseSheet(it)) }
        } catch (e: XmlPullParserException) {
            ParseResult.Err(ErrorKind.Corrupted("malformed worksheet '$partPath': ${e.message}"))
        } catch (e: IOException) {
            ParseResult.Err(ErrorKind.Corrupted("could not read worksheet '$partPath': ${e.message}"))
        }

    /** Stream-parse a worksheet document into [SheetData]. */
    internal fun parseSheet(input: InputStream): SheetData {
        val parser = Xml.newPullParser(input)
        val rows = LinkedHashMap<Int, Row>()

        var rowIndex = -1
        var cols = ArrayList<Int>()
        var vals = ArrayList<CellValue>()
        var styleIds = ArrayList<Int>()
        var nextCol = 0

        var cellCol = 0
        var cellStyle = 0
        var cellType: String? = null
        val vBuf = StringBuilder()
        val inlineBuf = StringBuilder()
        var inV = false
        var inT = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        rowIndex = parser.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1)
                            ?: (rowIndex + 1)
                        cols = ArrayList()
                        vals = ArrayList()
                        styleIds = ArrayList()
                        nextCol = 0
                    }
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r")
                        cellCol = if (ref != null) CellRef.columnIndexOf(ref) else nextCol
                        cellStyle = parser.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        cellType = parser.getAttributeValue(null, "t")
                        vBuf.setLength(0)
                        inlineBuf.setLength(0)
                        inV = false
                        inT = false
                    }
                    "v" -> inV = true
                    "t" -> inT = true // inline string text (<is><t> or <is><r><t>)
                }

                XmlPullParser.TEXT -> when {
                    inV -> vBuf.append(parser.text)
                    inT -> inlineBuf.append(parser.text)
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val value = buildCellValue(cellType, vBuf, inlineBuf)
                        if (value != null) {
                            cols.add(cellCol)
                            vals.add(value)
                            styleIds.add(cellStyle)
                        }
                        nextCol = cellCol + 1
                    }
                    "row" -> if (cols.isNotEmpty()) rows[rowIndex] = buildRow(cols, vals, styleIds)
                }
            }
            event = parser.next()
        }
        return SheetData(rows)
    }

    /** Build a [CellValue] from the cell's `t` type and captured text buffers. */
    private fun buildCellValue(
        type: String?,
        vBuf: StringBuilder,
        inlineBuf: StringBuilder,
    ): CellValue? = when (type) {
        "s" -> vBuf.toString().trim().toIntOrNull()?.let { CellValue.SharedText(it) }
        "b" -> CellValue.Bool(vBuf.toString().trim().let { it == "1" || it.equals("true", ignoreCase = true) })
        "e" -> vBuf.toString().takeIf { it.isNotEmpty() }?.let { CellValue.Error(it) }
        "str" -> CellValue.InlineText(vBuf.toString())
        "inlineStr" -> CellValue.InlineText(inlineBuf.toString())
        else -> vBuf.toString().trim().toDoubleOrNull()?.let { CellValue.Number(it) } // "n" or absent
    }

    /**
     * Build a [Row] with columns sorted ascending (cells are usually already in
     * column order; the sort guards against out-of-order input).
     */
    private fun buildRow(cols: List<Int>, vals: List<CellValue>, styleIds: List<Int>): Row {
        val order = cols.indices.sortedBy { cols[it] }
        return Row(
            columns = IntArray(order.size) { cols[order[it]] },
            values = Array(order.size) { vals[order[it]] },
            styleIds = IntArray(order.size) { styleIds[order[it]] },
        )
    }
}
