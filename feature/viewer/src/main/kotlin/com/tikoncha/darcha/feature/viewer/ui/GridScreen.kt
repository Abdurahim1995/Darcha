package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.Viewport

/**
 * The grid screen: a header line, the [GridCanvas], and temporary debug controls.
 *
 * Nothing here reads the viewport during composition. [viewport] is a lambda the
 * Canvas calls inside its draw block, so dragging invalidates the draw phase
 * only — the title, the buttons and the layout are never recomposed while
 * scrolling. The one intentional exception is [ViewportReadout], isolated in its
 * own composable so it recomposes alone.
 *
 * The debug buttons dispatch the same `Scroll` / `Zoom` intents the gestures do;
 * they stay until T15 as an A/B reference while the fling is tuned.
 */
@Composable
internal fun GridScreen(
    sheet: SheetSnapshot,
    docMeta: DocumentMeta,
    viewport: () -> Viewport,
    onOpenFile: () -> Unit,
    onScroll: (dx: Float, dy: Float) -> Unit,
    onFling: (vx: Float, vy: Float) -> Unit,
    onZoom: (scale: Float) -> Unit,
    onBoundsChanged: (ScrollBounds) -> Unit,
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
            TextButton(onClick = onOpenFile) { Text("Open") }
        }

        GridCanvas(
            sheet = sheet,
            viewport = viewport,
            onScroll = onScroll,
            onFling = onFling,
            onBoundsChanged = onBoundsChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onDrawnCells = { drawnCells = it },
        )

        // Debug viewport controls — an A/B reference for the gestures, removed in T15.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onScroll(-STEP, 0f) }) { Text("←") }
            OutlinedButton(onClick = { onScroll(STEP, 0f) }) { Text("→") }
            OutlinedButton(onClick = { onScroll(0f, -STEP) }) { Text("↑") }
            OutlinedButton(onClick = { onScroll(0f, STEP) }) { Text("↓") }
            OutlinedButton(onClick = { onZoom(1 / ZOOM_STEP) }) { Text("−") }
            OutlinedButton(onClick = { onZoom(ZOOM_STEP) }) { Text("+") }
        }
        ViewportReadout(viewport)
    }
}

/**
 * The live scroll/zoom numbers. Split out because showing them *requires*
 * reading the viewport during composition; keeping that read here means this
 * one line recomposes while scrolling, and nothing else does.
 */
@Composable
private fun ViewportReadout(viewport: () -> Viewport) {
    val current = viewport()
    Text(
        text = "x=${current.scrollX.toInt()} y=${current.scrollY.toInt()} " +
            "zoom=${"%.1f".format(current.zoom)}",
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
    )
}

/** Content pixels moved per debug step — roughly two default columns. */
private const val STEP = 128f

/** Multiplier per zoom step. */
private const val ZOOM_STEP = 1.25f

/** Convenience for reading a state holder without recomposing on every change. */
internal inline fun <T> State<T>.read(): T = value
