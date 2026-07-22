package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.ErrorKind
import com.tikoncha.darcha.model.FrozenPanes
import com.tikoncha.darcha.model.Row
import com.tikoncha.darcha.model.SheetData
import com.tikoncha.darcha.model.SheetLayout
import com.tikoncha.darcha.model.Worksheet
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Parses a worksheet part (`xl/worksheets/sheetN.xml`) into a [Worksheet]: the
 * sparse cell [SheetData] plus its [SheetLayout] (TECH_SPEC §7 step 5, §8, §9).
 * A single streaming pass collects both — no DOM, and large sheets are read once.
 *
 * Cell types: `n` (default, number), `s` (shared string index), `inlineStr`
 * (flattened `<is>` text), `b` (boolean), `e` (error), `str` (formula string
 * result). Formulas (`<f>`) are skipped — only the cached `<v>` is read. The
 * `<dimension>` element is ignored; only real `<c>` elements are trusted. Only
 * cells with a value are stored — a styled-but-empty `<c>` is dropped in v1.
 *
 * Layout: `<cols>` custom widths, `<sheetFormatPr>` defaults, `<row>` heights,
 * `<mergeCells>`, and a frozen `<pane>`.
 */
internal object SheetParser {

    /**
     * Read the worksheet at [partPath] in [zip] into a [Worksheet], invoking
     * [onChunk] with each batch of up to [chunkSize] rows as they accumulate.
     */
    fun parse(
        zip: ZipFile,
        partPath: String,
        chunkSize: Int = 200,
        onChunk: (RowsChunk) -> Unit = {},
    ): ParseResult<Worksheet> =
        try {
            val entry = zip.getEntry(partPath)
                ?: return ParseResult.Err(ErrorKind.Corrupted("missing worksheet part '$partPath'"))
            zip.getInputStream(entry).use { ParseResult.Ok(parseSheet(it, chunkSize, onChunk)) }
        } catch (e: XmlPullParserException) {
            ParseResult.Err(ErrorKind.Corrupted("malformed worksheet '$partPath': ${e.message}"))
        } catch (e: IOException) {
            ParseResult.Err(ErrorKind.Corrupted("could not read worksheet '$partPath': ${e.message}"))
        }

    /** Stream-parse a worksheet document into a [Worksheet], emitting row chunks. */
    internal fun parseSheet(
        input: InputStream,
        chunkSize: Int = 200,
        onChunk: (RowsChunk) -> Unit = {},
    ): Worksheet {
        val parser = Xml.newPullParser(input)
        val rows = LinkedHashMap<Int, Row>()
        val pending = LinkedHashMap<Int, Row>()

        // Layout accumulators.
        val columnWidths = HashMap<Int, Double>()
        val rowHeights = HashMap<Int, Double>()
        val merges = ArrayList<CellRange>()
        var frozenPanes = FrozenPanes.NONE
        var defaultColWidth = SheetLayout.DEFAULT_COL_WIDTH
        var defaultRowHeight = SheetLayout.DEFAULT_ROW_HEIGHT

        // Per-row cell accumulators.
        var rowIndex = -1
        var cols = ArrayList<Int>()
        var vals = ArrayList<CellValue>()
        var styleIds = ArrayList<Int>()
        var nextCol = 0

        // Per-cell state.
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
                    "sheetFormatPr" -> {
                        parser.getAttributeValue(null, "defaultColWidth")?.toDoubleOrNull()
                            ?.let { defaultColWidth = it }
                        parser.getAttributeValue(null, "defaultRowHeight")?.toDoubleOrNull()
                            ?.let { defaultRowHeight = it }
                    }
                    "col" -> readCol(parser, columnWidths)
                    "pane" -> readPane(parser)?.let { frozenPanes = it }
                    "mergeCell" -> parser.getAttributeValue(null, "ref")
                        ?.let { merges.add(CellRef.parseRange(it)) }

                    "row" -> {
                        rowIndex = parser.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1)
                            ?: (rowIndex + 1)
                        parser.getAttributeValue(null, "ht")?.toDoubleOrNull()
                            ?.let { rowHeights[rowIndex] = it }
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
                    "row" -> if (cols.isNotEmpty()) {
                        val built = buildRow(cols, vals, styleIds)
                        rows[rowIndex] = built
                        pending[rowIndex] = built
                        if (pending.size >= chunkSize) {
                            onChunk(RowsChunk(LinkedHashMap(pending), rows.size))
                            pending.clear()
                        }
                    }
                }
            }
            event = parser.next()
        }
        if (pending.isNotEmpty()) onChunk(RowsChunk(pending, rows.size))

        val layout = SheetLayout(
            columnWidths = columnWidths,
            rowHeights = rowHeights,
            defaultColWidth = defaultColWidth,
            defaultRowHeight = defaultRowHeight,
            merges = merges,
            frozenPanes = frozenPanes,
        )
        return Worksheet(SheetData(rows), layout)
    }

    /** Record a `<col>` element's custom width across its `min..max` range. */
    private fun readCol(parser: XmlPullParser, into: MutableMap<Int, Double>) {
        val customWidth = parser.getAttributeValue(null, "customWidth")
        val isCustom = customWidth == "1" || customWidth.equals("true", ignoreCase = true)
        val width = parser.getAttributeValue(null, "width")?.toDoubleOrNull()
        if (!isCustom || width == null) return
        val min = parser.getAttributeValue(null, "min")?.toIntOrNull() ?: return
        val max = parser.getAttributeValue(null, "max")?.toIntOrNull() ?: return
        for (column in min..max) into[column - 1] = width // min/max are 1-based
    }

    /** Read a `<pane>` element, returning frozen panes only when `state="frozen"`. */
    private fun readPane(parser: XmlPullParser): FrozenPanes? {
        if (parser.getAttributeValue(null, "state") != "frozen") return null
        val xSplit = parser.getAttributeValue(null, "xSplit")?.toIntOrNull() ?: 0
        val ySplit = parser.getAttributeValue(null, "ySplit")?.toIntOrNull() ?: 0
        return FrozenPanes(frozenCols = xSplit, frozenRows = ySplit)
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
