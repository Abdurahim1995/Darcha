package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.R
import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.Viewport

/**
 * The grid screen: the document header, the [GridCanvas], and the sheet tabs.
 *
 * Nothing here reads the viewport during composition. [viewport] is a lambda the
 * Canvas calls inside its draw block, so dragging invalidates the draw phase
 * only — the header, the tabs and the layout are never recomposed while
 * scrolling (TECH_SPEC §9).
 */
@Composable
internal fun GridScreen(
    sheet: SheetSnapshot,
    docMeta: DocumentMeta,
    activeSheetId: Int,
    loadingSheetId: Int?,
    loadProgress: Float?,
    viewport: () -> Viewport,
    onOpenFile: () -> Unit,
    onScroll: (dx: Float, dy: Float) -> Unit,
    onFling: (vx: Float, vy: Float) -> Unit,
    onZoom: (scale: Float, focalX: Float, focalY: Float) -> Unit,
    onResetZoom: (focalX: Float, focalY: Float) -> Unit,
    onBoundsChanged: (ScrollBounds) -> Unit,
    onSelectSheet: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drawnCells by remember { mutableIntStateOf(0) }

    // Keep the chrome clear of the status and navigation bars.
    Column(modifier = modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(docMeta.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${docMeta.rowCount} rows · ${docMeta.sheetNames.size} sheets · " +
                        "drawn $drawnCells cells",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onOpenFile) { Text(stringResource(R.string.action_open_short)) }
        }

        GridCanvas(
            sheet = sheet,
            viewport = viewport,
            onScroll = onScroll,
            onFling = onFling,
            onZoom = onZoom,
            onResetZoom = onResetZoom,
            onBoundsChanged = onBoundsChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onDrawnCells = { drawnCells = it },
        )

        // Either the first sheet is still streaming in (T15.5) or another tab is
        // being read on demand (T15). Both keep the grid on screen and show the
        // bar underneath it.
        when {
            loadProgress != null -> LinearProgressIndicator(
                progress = { loadProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            loadingSheetId != null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        SheetTabs(
            sheetNames = docMeta.sheetNames,
            activeSheetId = activeSheetId,
            loadingSheetId = loadingSheetId,
            onSelectSheet = onSelectSheet,
        )
    }
}

/**
 * The sheet tabs, in workbook order. Scrollable because a workbook may hold more
 * names than fit, and they can be long.
 */
@Composable
private fun SheetTabs(
    sheetNames: List<String>,
    activeSheetId: Int,
    loadingSheetId: Int?,
    onSelectSheet: (Int) -> Unit,
) {
    if (sheetNames.isEmpty()) return
    ScrollableTabRow(
        selectedTabIndex = activeSheetId.coerceIn(sheetNames.indices),
        edgePadding = 8.dp,
    ) {
        sheetNames.forEachIndexed { index, name ->
            Tab(
                selected = index == activeSheetId,
                // Ignore taps on a tab already being read, so a double tap cannot
                // queue the same parse twice.
                onClick = { if (index != loadingSheetId) onSelectSheet(index) },
                text = {
                    Text(
                        text = name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}
