package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.tikoncha.darcha.feature.viewer.R
import com.tikoncha.darcha.feature.viewer.data.RecentDocument
import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.ErrorKind

/**
 * The viewer screen: a render of [ViewerState] (TECH_SPEC §10).
 *
 * Takes the state as a **holder** rather than a value so that scrolling, which
 * changes the viewport many times a second, does not recompose this tree. The
 * pieces that actually change shape — which screen to show, the open sheet — are
 * pulled out with `derivedStateOf`, which only fires when *they* change; the
 * viewport itself is passed down as a lambda and read in the draw phase.
 *
 * The friendly per-error screens arrive in T23.
 *
 * @param state holder for the current state.
 * @param onOpenFile invoked when the user asks for the file picker.
 * @param onRetry invoked to retry the last failed load.
 */
@Composable
public fun ViewerScreen(
    state: State<ViewerState>,
    onOpenFile: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onScroll: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onFling: (vx: Float, vy: Float) -> Unit = { _, _ -> },
    onZoom: (scale: Float, focalX: Float, focalY: Float) -> Unit = { _, _, _ -> },
    onResetZoom: (focalX: Float, focalY: Float) -> Unit = { _, _ -> },
    onBoundsChanged: (ScrollBounds) -> Unit = {},
    onSelectSheet: (Int) -> Unit = {},
    onSelect: (CellRef?) -> Unit = {},
    onSelectRange: (com.tikoncha.darcha.model.CellRange?) -> Unit = {},
    onStopMotion: () -> Boolean = { false },
    onSearchOpen: (Boolean) -> Unit = {},
    onSearchQuery: (String) -> Unit = {},
    onStepMatch: (Boolean) -> Unit = {},
    onReveal: (Viewport) -> Unit = {},
    onDateNames: (com.tikoncha.darcha.model.DateNames) -> Unit = {},
    recents: List<RecentDocument> = emptyList(),
    onOpenRecent: (String) -> Unit = {},
    onForgetRecent: (String) -> Unit = {},
) {
    // Stable slices: these change on load, not on every frame of a drag.
    val sheet by remember(state) { derivedStateOf { (state.value as? ViewerState.Ready)?.sheet } }
    val docMeta by remember(state) { derivedStateOf { (state.value as? ViewerState.Ready)?.docMeta } }
    val activeSheetId by remember(state) {
        derivedStateOf { (state.value as? ViewerState.Ready)?.activeSheetId ?: 0 }
    }
    val loadingSheetId by remember(state) {
        derivedStateOf { (state.value as? ViewerState.Ready)?.loadingSheetId }
    }
    val loadProgress by remember(state) {
        derivedStateOf { (state.value as? ViewerState.Ready)?.loadProgress }
    }
    // Its own slice, so moving the selection does not recompose anything that
    // reads the viewport, and vice versa.
    val selection by remember(state) {
        derivedStateOf { (state.value as? ViewerState.Ready)?.selection }
    }
    val search by remember(state) {
        derivedStateOf { (state.value as? ViewerState.Ready)?.search }
    }

    val readySheet = sheet
    val readyMeta = docMeta
    if (readySheet != null && readyMeta != null) {
        GridScreen(
            sheet = readySheet,
            docMeta = readyMeta,
            activeSheetId = activeSheetId,
            loadingSheetId = loadingSheetId,
            loadProgress = loadProgress,
            viewport = {
                (state.value as? ViewerState.Ready)?.viewport ?: Viewport.INITIAL
            },
            onOpenFile = onOpenFile,
            onScroll = onScroll,
            onFling = onFling,
            onZoom = onZoom,
            onResetZoom = onResetZoom,
            onBoundsChanged = onBoundsChanged,
            onSelectSheet = onSelectSheet,
            selection = selection,
            onSelect = onSelect,
            onSelectRange = onSelectRange,
            onStopMotion = onStopMotion,
            search = search,
            onSearchOpen = onSearchOpen,
            onSearchQuery = onSearchQuery,
            onStepMatch = onStepMatch,
            onReveal = onReveal,
            onDateNames = onDateNames,
            modifier = modifier,
        )
        return
    }

    val current = state.value
    if (current is ViewerState.Error) {
        ErrorScreen(
            kind = current.kind,
            onOpenFile = onOpenFile,
            onRetry = onRetry,
            modifier = modifier,
        )
        return
    }

    // The home screen is a full-height list, not a centred block of text, so it
    // is rendered directly rather than through NonGridStates' container.
    if (state.value is ViewerState.Idle) {
        HomeScreen(
            recents = recents,
            onOpenFile = onOpenFile,
            onOpenRecent = onOpenRecent,
            onForgetRecent = onForgetRecent,
            modifier = modifier,
        )
        return
    }

    NonGridStates(
        state = state.value,
        onOpenFile = onOpenFile,
        onRetry = onRetry,
        modifier = modifier,
    )
}

/** Parsing — the one remaining state that is a centred column of text. */
@Composable
private fun NonGridStates(
    state: ViewerState,
    onOpenFile: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            // Handled by ViewerScreen before we get here.
            ViewerState.Idle -> Unit

            is ViewerState.Parsing -> {
                Text(stringResource(R.string.parsing), style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }

            // Handled by GridScreen before we get here.
            is ViewerState.Ready -> Unit

            // Handled by ViewerScreen before we get here.
            is ViewerState.Error -> Unit
        }
    }
}
