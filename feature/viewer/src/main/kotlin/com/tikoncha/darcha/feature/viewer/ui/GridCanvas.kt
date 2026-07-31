package com.tikoncha.darcha.feature.viewer.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.tikoncha.darcha.feature.viewer.geometry.GridGeometry
import com.tikoncha.darcha.feature.viewer.geometry.MergeIndex
import com.tikoncha.darcha.feature.viewer.geometry.Pane
import com.tikoncha.darcha.feature.viewer.geometry.PaneRegion
import com.tikoncha.darcha.feature.viewer.geometry.PaneRegions
import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.CellStyle
import com.tikoncha.darcha.model.DEFAULT_MAX_DIGIT_WIDTH
import com.tikoncha.darcha.model.FormattedValueCache
import com.tikoncha.darcha.model.SheetData
import com.tikoncha.darcha.model.StyleTable
import kotlin.math.roundToInt

/**
 * The grid, drawn as a single Canvas (TECH_SPEC §9).
 *
 * Only the cells inside [GridGeometry.visibleRange] are visited, so cost tracks
 * the size of the window rather than the size of the sheet — a million-row sheet
 * draws the same few hundred cells as a ten-row one. Around the body sit fixed
 * header strips: column letters along the top, row numbers down the left.
 *
 * Cell styling is applied here (T17): fills, bold/italic, text colour and
 * alignment, each clipped to its cell. Merged ranges are drawn once at their
 * anchor across the whole span, with the covered cells skipped (T18). Frozen
 * panes (T19) are still to come.
 *
 * @param state the sheet to draw.
 * @param onDrawnCells reports how many body cells the last pass visited, so the
 *   caller can show it and prove culling.
 */
@Composable
public fun GridCanvas(
    sheet: SheetSnapshot,
    viewport: () -> Viewport,
    onScroll: (dx: Float, dy: Float) -> Unit,
    onFling: (vx: Float, vy: Float) -> Unit,
    onZoom: (scale: Float, focalX: Float, focalY: Float) -> Unit,
    onResetZoom: (focalX: Float, focalY: Float) -> Unit,
    onBoundsChanged: (ScrollBounds) -> Unit,
    modifier: Modifier = Modifier,
    onDrawnCells: (Int) -> Unit = {},
) {
    val density = LocalDensity.current.density
    val layout = sheet.layout

    // Geometry is built in *physical* pixels by folding the display density into
    // its two unit converters, so its output can be used as Canvas coordinates
    // directly. Its own arithmetic stays device-independent (TECH_SPEC §9.2).
    val geometry = remember(layout, density) {
        GridGeometry(
            layout = layout,
            maxDigitWidth = (DEFAULT_MAX_DIGIT_WIDTH * density).roundToInt(),
            pointToPixel = GridGeometry.POINTS_TO_PIXELS_96DPI * density,
        )
    }

    val textMeasurer = rememberTextMeasurer()

    // Both caches are keyed on the workbook's tables rather than on the sheet:
    // style ids and shared-string indices mean nothing outside the workbook they
    // came from, but they do not change while one streams in — so a partial
    // paint keeps everything measured so far (T15.6) and a new document starts
    // clean.
    val textCache = remember(sheet.styles) { CellTextCache<TextLayoutResult>() }

    // Built once per sheet, not per frame. Merges arrive only when the parse
    // completes (<mergeCells> follows <sheetData>, TECH_SPEC §7), so during a
    // progressive load this is empty and the cells draw unmerged until the last
    // chunk lands.
    val merges = remember(layout.merges) { MergeIndex.of(layout.merges) }

    // Frozen panes, unlike merges, are known from the first chunk (T19), so the
    // grid never re-splits mid-parse.
    val panes = remember(geometry, layout.frozenPanes) {
        PaneRegions(geometry, layout.frozenPanes)
    }

    // What a merged span paints over the gridlines it hides, when its anchor
    // carries no fill of its own.
    val surface = MaterialTheme.colorScheme.surface

    // The grid's own palette: gridlines, header strips and the freeze marker all
    // change with the theme, or dark mode stops at the edge of the sheet (T24).
    val colors = gridColors
    // Locale lives here, not in the formatter (T16/T24) — see rememberDateNames.
    val dateNames = rememberDateNames()
    val formatted = remember(sheet.styles, sheet.sharedStrings, sheet.date1904, dateNames) {
        FormattedValueCache(
            styles = sheet.styles,
            strings = sheet.sharedStrings,
            date1904 = sheet.date1904,
            names = dateNames,
        )
    }

    val rowHeaderWidth = with(LocalDensity.current) { ROW_HEADER_WIDTH.toPx() }
    val columnHeaderHeight = with(LocalDensity.current) { COLUMN_HEADER_HEIGHT.toPx() }

    // How far this sheet can scroll. Computed once per sheet — the full grid is
    // 16k x 1M, so the *used* range is what makes scrolling feel finite.
    val used = remember(sheet.data) { sheet.data.usedBounds() }
    LaunchedEffect(geometry, used, panes) {
        onBoundsChanged(
            ScrollBounds(
                maxScrollX = geometry.columnOffset(used.lastColumn),
                maxScrollY = geometry.rowOffset(used.lastRow),
                // The scrolling region starts past the frozen strips.
                minScrollX = panes.minScrollX,
                minScrollY = panes.minScrollY,
            ),
        )
    }

    // One gesture loop rather than stacked detectors, because a pinch and a drag
    // are the same stream of pointers and only the pointer *count* tells them
    // apart. detectDragGestures cannot see that count, so a second detector for
    // zoom would double-apply the pan.
    val gestures = Modifier.pointerInput(geometry, panes) {
        val tracker = VelocityTracker()
        awaitEachGesture {
            tracker.resetTracking()
            awaitFirstDown(requireUnconsumed = false)
            var pinching = false
            var lastCentroid = Offset.Zero
            var lastSpread = 0f

            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                if (pressed.size >= 2) {
                    val centroid = centroidOf(pressed)
                    val spread = spreadOf(pressed, centroid)
                    if (!pinching) {
                        // First frame of the pinch: record the baseline only, or
                        // the second finger landing would read as a huge scale.
                        pinching = true
                    } else if (lastSpread > 0f && spread > 0f) {
                        val current = viewport()
                        onZoom(
                            spread / lastSpread,
                            focalX(centroid.x, rowHeaderWidth, panes.frozenWidth(current)),
                            focalY(centroid.y, columnHeaderHeight, panes.frozenHeight(current)),
                        )
                        // Two fingers moving together still pan.
                        val pan = centroid - lastCentroid
                        if (pan != Offset.Zero) {
                            onScroll(-pan.x / current.zoom, -pan.y / current.zoom)
                        }
                    }
                    lastCentroid = centroid
                    lastSpread = spread
                    pressed.forEach { it.consume() }
                } else if (!pinching) {
                    // Once a gesture has become a pinch it stays one, so lifting
                    // to a single finger does not turn into a drag.
                    val change = pressed.first()
                    val delta = change.position - change.previousPosition
                    if (delta != Offset.Zero) {
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val zoom = viewport().zoom
                        onScroll(-delta.x / zoom, -delta.y / zoom)
                        change.consume()
                    }
                }
            }

            // Lifting two fingers is not a flick, so a pinch never flings.
            if (!pinching) {
                val velocity = tracker.calculateVelocity()
                val zoom = viewport().zoom
                onFling(-velocity.x / zoom, -velocity.y / zoom)
            }
        }
    }

    val taps = Modifier.pointerInput(geometry, panes) {
        detectTapGestures(
            onDoubleTap = { offset ->
                val current = viewport()
                onResetZoom(
                    focalX(offset.x, rowHeaderWidth, panes.frozenWidth(current)),
                    focalY(offset.y, columnHeaderHeight, panes.frozenHeight(current)),
                )
            },
        )
    }

    Canvas(modifier = modifier.then(taps).then(gestures)) {
        // Read inside the draw block: a viewport change then invalidates only the
        // draw phase, never recomposing the surrounding chrome.
        val current = viewport()
        val regions = panes.regions(
            viewport = current,
            originX = rowHeaderWidth,
            originY = columnHeaderHeight,
            width = size.width,
            height = size.height,
        )

        for (region in regions) {
            if (!region.isVisible) continue
            drawRegion(
                region = region,
                geometry = geometry,
                sheetData = sheet.data,
                styles = sheet.styles,
                formatted = formatted,
                merges = merges,
                surface = surface,
                colors = colors,
                textMeasurer = textMeasurer,
                cache = textCache,
            )
        }

        drawHeaders(
            colors = colors,
            regions = regions,
            geometry = geometry,
            rowHeaderWidth = rowHeaderWidth,
            columnHeaderHeight = columnHeaderHeight,
            textMeasurer = textMeasurer,
            cache = textCache,
        )

        // The freeze separators, drawn once and last. Every region that meets a
        // split was positioned from the same Float this line uses, so the line
        // lands exactly on the seam instead of near it.
        drawFreezeSeparators(
            colors = colors,
            panes = panes,
            viewport = current,
            originX = rowHeaderWidth,
            originY = columnHeaderHeight,
        )

        // Every region's cells, not just the body's — freezing splits the same
        // window into pieces, so the total is what proves the culling.
        var drawn = 0
        for (region in regions) if (region.isVisible) drawn += region.cellCount
        if (drawn != lastLoggedCellCount) {
            lastLoggedCellCount = drawn
            val body = regions.first()
            // Proof of culling: this stays small and steady however far the
            // viewport travels into a large sheet.
            Log.d(
                LOG_TAG,
                "visible $drawn cells in ${regions.count { it.isVisible }} pane(s); " +
                    "body rows ${body.firstRow}..${body.lastRow}, " +
                    "cols ${body.firstColumn}..${body.lastColumn}; " +
                    "textCache ${textCache.size} entries, " +
                    "hit rate ${(textCache.hitRate * 100f).roundToInt()}%",
            )
        }
        onDrawnCells(drawn)
    }
}

/**
 * Fills, gridlines and values for one frozen region (TECH_SPEC §9).
 *
 * The region already carries its clip, its origin and a viewport with the frozen
 * axes zeroed, so this is the ordinary unfrozen draw — it never asks which pane
 * it is in. An unfrozen sheet is simply the case where the only region is the
 * body, covering everything.
 *
 * Order matters. Cell fills go under the gridlines the way they do in Excel;
 * then merged spans paint over both, because a merge has to hide the gridlines
 * running through it; then text, so nothing paints over a value.
 */
private fun DrawScope.drawRegion(
    region: PaneRegion,
    geometry: GridGeometry,
    sheetData: SheetData,
    styles: StyleTable,
    formatted: FormattedValueCache,
    merges: MergeIndex,
    surface: Color,
    colors: GridColors,
    textMeasurer: TextMeasurer,
    cache: CellTextCache<TextLayoutResult>,
) {
    val viewport = region.viewport
    val originX = region.originX
    val originY = region.originY

    clipRect(left = region.left, top = region.top, right = region.right, bottom = region.bottom) {
        // A frozen strip has to be opaque: the body region is drawn first and
        // scrolls underneath it.
        if (region.pane != Pane.BODY) {
            drawRect(
                color = surface,
                topLeft = Offset(region.left, region.top),
                size = Size(region.right - region.left, region.bottom - region.top),
            )
        }

        // Fills first, so gridlines stay visible on top of a filled cell. Covered
        // cells are skipped — their span is painted with the anchor's fill below.
        for (row in region.firstRow..region.lastRow) {
            val cells = sheetData.row(row) ?: continue
            val top = originY + geometry.screenYOf(row, viewport)
            val height = geometry.screenHeightOf(row, viewport)
            for (i in cells.columns.indices) {
                val column = cells.columns[i]
                if (column < region.firstColumn) continue
                if (column > region.lastColumn) break
                if (merges.indexOf(row, column) != MergeIndex.NONE) continue
                val fill = styles[cells.styleIds[i]]?.fillColor ?: continue
                drawRect(
                    color = fill.toCompose(),
                    topLeft = Offset(originX + geometry.screenXOf(column, viewport), top),
                    size = Size(geometry.screenWidthOf(column, viewport), height),
                )
            }
        }

        // Gridlines, one per visible boundary, drawn only inside this region.
        for (column in region.firstColumn..region.lastColumn + 1) {
            val x = originX + geometry.screenXOf(column, viewport)
            if (x in region.left..region.right) {
                drawLine(colors.gridLine, Offset(x, region.top), Offset(x, region.bottom), GRID_STROKE)
            }
        }
        for (row in region.firstRow..region.lastRow + 1) {
            val y = originY + geometry.screenYOf(row, viewport)
            if (y in region.top..region.bottom) {
                drawLine(colors.gridLine, Offset(region.left, y), Offset(region.right, y), GRID_STROKE)
            }
        }

        // Merged spans: one rect per range, painted over the gridlines crossing
        // it, then outlined so the merge still reads as a cell.
        if (!merges.isEmpty) {
            merges.forEachIntersecting(
                region.firstRow,
                region.lastRow,
                region.firstColumn,
                region.lastColumn,
            ) { index ->
                val anchorRow = merges.startRow(index)
                val anchorCol = merges.startCol(index)
                val left = originX + geometry.screenXOf(anchorCol, viewport)
                val top = originY + geometry.screenYOf(anchorRow, viewport)
                val width = geometry.spanWidthOf(anchorCol, merges.endCol(index), viewport)
                val height = geometry.spanHeightOf(anchorRow, merges.endRow(index), viewport)
                val fill = sheetData.styleIdAt(anchorRow, anchorCol)
                    ?.let { styles[it]?.fillColor?.toCompose() }
                    ?: surface
                drawRect(color = fill, topLeft = Offset(left, top), size = Size(width, height))
                drawRect(
                    color = colors.gridLine,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = GRID_STROKE),
                )
            }
        }

        // Values. Rows are sparse, so absent rows cost nothing.
        for (row in region.firstRow..region.lastRow) {
            val cells = sheetData.row(row) ?: continue
            val top = originY + geometry.screenYOf(row, viewport)
            val height = geometry.screenHeightOf(row, viewport)
            for (i in cells.columns.indices) {
                val column = cells.columns[i]
                if (column < region.firstColumn) continue
                if (column > region.lastColumn) break // columns are sorted ascending
                val styleId = cells.styleIds[i]
                val value = cells.values[i]
                val text = formatted.format(value, styleId)
                if (text.isEmpty()) continue

                // A merged range shows one value, at its anchor, laid out across
                // the whole span. Whatever a covered cell holds is not shown —
                // Excel keeps such values but does not display them either.
                val merge = merges.indexOf(row, column)
                if (merge != MergeIndex.NONE && !merges.isAnchor(merge, row, column)) continue
                val cellLeft = originX + geometry.screenXOf(column, viewport)
                val cellWidth: Float
                val cellHeight: Float
                if (merge == MergeIndex.NONE) {
                    cellWidth = geometry.screenWidthOf(column, viewport)
                    cellHeight = height
                } else {
                    cellWidth = geometry.spanWidthOf(column, merges.endCol(merge), viewport)
                    cellHeight = geometry.spanHeightOf(row, merges.endRow(merge), viewport)
                }

                val style = styles[styleId] ?: CellStyle.DEFAULT
                drawCellText(
                    text = text,
                    styleId = styleId,
                    left = cellLeft,
                    top = top,
                    width = cellWidth,
                    height = cellHeight,
                    zoom = viewport.zoom,
                    textMeasurer = textMeasurer,
                    cache = cache,
                    color = cellTextColor(style, colors, surface),
                    weight = style.fontWeight,
                    italic = style.fontStyle,
                    align = style.resolveAlignment(value),
                    padding = CELL_PADDING * viewport.zoom,
                    verticalOffset = { textHeight ->
                        style.verticalOffset(cellHeight, textHeight, CELL_PADDING * viewport.zoom)
                    },
                )
            }
        }
    }
}

/**
 * The two lines marking where the sheet is frozen.
 *
 * Drawn once, after every region, and derived from the same frozen extent the
 * regions were positioned from — so the seam is covered by a line that is
 * exactly on it, at any zoom, rather than one that has been rounded to a
 * neighbouring pixel.
 */
private fun DrawScope.drawFreezeSeparators(
    colors: GridColors,
    panes: PaneRegions,
    viewport: Viewport,
    originX: Float,
    originY: Float,
) {
    if (!panes.isFrozen) return
    val splitX = (originX + panes.frozenWidth(viewport)).coerceIn(originX, size.width)
    val splitY = (originY + panes.frozenHeight(viewport)).coerceIn(originY, size.height)

    if (panes.minScrollX > 0f && splitX < size.width) {
        drawLine(colors.freezeLine, Offset(splitX, 0f), Offset(splitX, size.height), FREEZE_STROKE)
    }
    if (panes.minScrollY > 0f && splitY < size.height) {
        drawLine(colors.freezeLine, Offset(0f, splitY), Offset(size.width, splitY), FREEZE_STROKE)
    }
}

/**
 * The fixed column-letter and row-number strips.
 *
 * They have to be split the same way the grid is (TECH_SPEC §9): if column A is
 * frozen, its letter must stay put while the letters to its right scroll past.
 * Each strip therefore reuses the regions rather than deriving its own split —
 * the letter over a column is drawn from that column's own region, so a header
 * can never disagree with the cells beneath it.
 */
private fun DrawScope.drawHeaders(
    colors: GridColors,
    regions: List<PaneRegion>,
    geometry: GridGeometry,
    rowHeaderWidth: Float,
    columnHeaderHeight: Float,
    textMeasurer: TextMeasurer,
    cache: CellTextCache<TextLayoutResult>,
) {
    drawRect(colors.headerFill, Offset.Zero, Size(size.width, columnHeaderHeight))
    drawRect(colors.headerFill, Offset.Zero, Size(rowHeaderWidth, size.height))

    // Column letters: one pass per region that owns columns, clipped to that
    // region's horizontal span so a scrolling letter cannot slide over a frozen
    // one. CORNER and LEFT share the frozen columns, TOP and BODY the scrolling
    // ones, so one of each pair is enough.
    for (region in regions) {
        if (!region.isVisible) continue
        if (region.pane == Pane.CORNER || region.pane == Pane.TOP) continue
        clipRect(
            left = region.left,
            top = 0f,
            right = region.right,
            bottom = columnHeaderHeight,
        ) {
            for (column in region.firstColumn..region.lastColumn) {
                drawCellText(
                    text = columnLabel(column),
                    styleId = CellTextCache.HEADER_STYLE_ID,
                    align = TextAlign.Center,
                    left = region.originX + geometry.screenXOf(column, region.viewport),
                    top = 0f,
                    width = geometry.screenWidthOf(column, region.viewport),
                    height = columnHeaderHeight,
                    zoom = HEADER_ZOOM,
                    textMeasurer = textMeasurer,
                    cache = cache,
                    color = colors.headerText,
                )
            }
        }
    }

    // Row numbers: the mirror image — CORNER and TOP share the frozen rows,
    // LEFT and BODY the scrolling ones.
    for (region in regions) {
        if (!region.isVisible) continue
        if (region.pane == Pane.CORNER || region.pane == Pane.LEFT) continue
        clipRect(
            left = 0f,
            top = region.top,
            right = rowHeaderWidth,
            bottom = region.bottom,
        ) {
            for (row in region.firstRow..region.lastRow) {
                drawCellText(
                    text = (row + 1).toString(),
                    styleId = CellTextCache.HEADER_STYLE_ID,
                    align = TextAlign.Center,
                    left = 0f,
                    top = region.originY + geometry.screenYOf(row, region.viewport),
                    width = rowHeaderWidth,
                    height = geometry.screenHeightOf(row, region.viewport),
                    zoom = HEADER_ZOOM,
                    textMeasurer = textMeasurer,
                    cache = cache,
                    color = colors.headerText,
                )
            }
        }
    }

    // Separators between the strips and the body.
    drawLine(colors.gridLine, Offset(0f, columnHeaderHeight), Offset(size.width, columnHeaderHeight), GRID_STROKE)
    drawLine(colors.gridLine, Offset(rowHeaderWidth, 0f), Offset(rowHeaderWidth, size.height), GRID_STROKE)
}

/**
 * Draw [text] inside its cell rect, measured through [cache] and clipped to fit.
 *
 * The layout is measured **unbounded** and positioned afterwards, so one
 * measurement serves the same text in any column width — the clip below is what
 * stops a long value bleeding into its neighbour.
 */
private fun DrawScope.drawCellText(
    text: String,
    styleId: Int,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    zoom: Float,
    textMeasurer: TextMeasurer,
    cache: CellTextCache<TextLayoutResult>,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    italic: FontStyle = FontStyle.Normal,
    align: TextAlign = TextAlign.Left,
    padding: Float = CELL_PADDING,
    verticalOffset: (textHeight: Float) -> Float = { textHeight -> (height - textHeight) / 2f },
) {
    if (width <= 0f || height <= 0f) return
    // TextStyle is built inside the lambda so a cache hit — the common case —
    // allocates nothing on the per-cell hot path.
    val layoutResult = cache.get(text, styleId, zoom) {
        textMeasurer.measure(
            text = text,
            style = TextStyle(
                // The bucket's zoom, not the exact one — see measuredZoomOf.
                fontSize = CELL_FONT_SIZE * CellTextCache.measuredZoomOf(zoom),
                color = color,
                fontWeight = weight,
                fontStyle = italic,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
    // Clip so a long value cannot bleed into the neighbouring cell.
    clipRect(left = left, top = top, right = left + width, bottom = top + height) {
        drawText(
            textLayoutResult = layoutResult,
            color = color,
            topLeft = Offset(
                x = left + horizontalOffset(align, width, layoutResult.size.width.toFloat(), padding),
                y = top + verticalOffset(layoutResult.size.height.toFloat()),
            ),
        )
    }
}

/** Average position of the pressed pointers. */
private fun centroidOf(pointers: List<PointerInputChange>): Offset {
    var sum = Offset.Zero
    for (pointer in pointers) sum += pointer.position
    return sum / pointers.size.toFloat()
}

/** Mean distance of the pressed pointers from [centroid] — the pinch's "size". */
private fun spreadOf(pointers: List<PointerInputChange>, centroid: Offset): Float {
    var total = 0f
    for (pointer in pointers) total += (pointer.position - centroid).getDistance()
    return total / pointers.size
}

/**
 * A screen x turned into the coordinate the scroll addresses: past the row
 * header, and past the frozen columns, which do not scroll (TECH_SPEC §9.2).
 */
private fun focalX(screenX: Float, headerWidth: Float, frozenWidth: Float): Float =
    (screenX - headerWidth - frozenWidth).coerceAtLeast(0f)

/** The vertical mirror of [focalX]. */
private fun focalY(screenY: Float, headerHeight: Float, frozenHeight: Float): Float =
    (screenY - headerHeight - frozenHeight).coerceAtLeast(0f)

/** Last count written to logcat, so an unchanged viewport does not spam it. */
private var lastLoggedCellCount = -1

private const val LOG_TAG = "Darcha.Grid"
private val ROW_HEADER_WIDTH = 44.dp
private val COLUMN_HEADER_HEIGHT = 22.dp
private val CELL_FONT_SIZE = 11.sp
private const val CELL_PADDING = 4f

/**
 * Header labels never scale with zoom (TECH_SPEC §9.2): the strips are a fixed
 * size, so scaling their text would overflow them, and row numbers are most
 * useful precisely when the sheet is zoomed out.
 */
private const val HEADER_ZOOM = 1f
private const val GRID_STROKE = 1f
private const val FREEZE_STROKE = 2f

/**
 * What colour a cell's text is drawn in.
 *
 * The document wins wherever it actually chose. Where it chose nothing, the
 * theme supplies a colour; where it chose something that cannot be seen on a
 * background *we* picked, [TextLegibility] steps in. See that file for why those
 * are two different situations and why only one mechanism now decides.
 */
private fun cellTextColor(style: CellStyle, colors: GridColors, surface: Color): Color {
    val fill = style.fillColor?.toCompose()
    return TextLegibility.resolve(
        own = style.fontColor?.toCompose(),
        fallback = colors.cellText,
        behind = fill ?: surface,
        documentOwnsBackground = fill != null,
    )
}

/** The last populated row and column of a sheet. */
internal data class UsedBounds(val lastRow: Int, val lastColumn: Int)

/**
 * Find the sheet's used range — one pass over the sparse rows, done once per
 * sheet rather than per frame. Columns within a row are sorted, so the last
 * entry is the rightmost.
 */
internal fun SheetData.usedBounds(): UsedBounds {
    var lastRow = 0
    var lastColumn = 0
    for ((row, cells) in rows) {
        if (row > lastRow) lastRow = row
        if (cells.size > 0) {
            val rightmost = cells.columns[cells.size - 1]
            if (rightmost > lastColumn) lastColumn = rightmost
        }
    }
    return UsedBounds(lastRow, lastColumn)
}
