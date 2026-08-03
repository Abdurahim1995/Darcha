package com.tikoncha.darcha

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.tikoncha.darcha.feature.viewer.ViewerViewModel
import com.tikoncha.darcha.feature.viewer.data.RecentsRepository
import com.tikoncha.darcha.feature.viewer.data.RecentsStore
import com.tikoncha.darcha.feature.viewer.data.WorkbookRepository
import com.tikoncha.darcha.feature.viewer.data.XlsxWorkbookRepository
import com.tikoncha.darcha.feature.viewer.mvi.ViewerIntent
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState
import com.tikoncha.darcha.feature.viewer.ui.DarchaTheme
import com.tikoncha.darcha.feature.viewer.ui.ViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's single entry point: wires the parser-backed repository into
 * [ViewerViewModel] and hosts the viewer UI (TECH_SPEC §6).
 *
 * The ViewModel is retained by the activity's `ViewModelStore`, so a rotation
 * re-renders from the state already in memory and never re-parses.
 *
 * Two ways in, and they are different code paths: the in-app SAF picker, and an
 * `ACTION_VIEW` from a file manager (T21) — including a **cold start**, where
 * the intent is the first thing that happens to the process.
 */
class MainActivity : ComponentActivity() {

    /** The one repository for this process — see [DarchaApplication]. */
    private val repository: XlsxWorkbookRepository
        get() = (application as DarchaApplication).workbookRepository

    /** The one recents store for this process — see [DarchaApplication]. */
    private val recentsStore: RecentsStore
        get() = (application as DarchaApplication).recentsStore

    private val viewModel: ViewerViewModel by lazy {
        ViewModelProvider(
            this,
            ViewerViewModelFactory(
                repository = repository,
                recents = recentsStore,
                isRecentAvailable = ::isRecentAvailable,
            ),
        )[ViewerViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Process death skips the ViewModel's cleanup, so a previous run can leave
        // temp copies behind. Safe to call on every Activity creation: the sweep
        // runs against the process-wide repository, which excludes the open
        // document's copy and waits for any load still in flight.
        lifecycleScope.launch { repository.sweepStaleTempFiles() }

        // Only on a genuine start. A rotation re-runs onCreate with the same
        // intent, and the ViewModel has survived it — re-dispatching would
        // re-parse a document that is already on screen.
        if (savedInstanceState == null) openFromIntent(intent)

        setContent {
            DarchaTheme {
                // Held, not read: passing the holder down lets the grid observe
                // viewport changes in its draw phase instead of recomposing here.
                val state = viewModel.state.collectAsState()
                val recents = viewModel.recentDocuments.collectAsState()

                // Availability is a fact about the world, not about the record,
                // so it is re-checked every time the list comes back into view.
                LaunchedEffect(state.value) {
                    if (state.value is ViewerState.Idle) viewModel.refreshRecents()
                }

                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    viewModel.dispatch(ViewerIntent.OpenFile(sourceFor(uri)))
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    ViewerScreen(
                        state = state,
                        onOpenFile = { picker.launch(OPEN_DOCUMENT_MIME_TYPES) },
                        onRetry = { viewModel.dispatch(ViewerIntent.Retry) },
                        onScroll = { dx, dy -> viewModel.dispatch(ViewerIntent.Scroll(dx, dy)) },
                        onFling = { vx, vy -> viewModel.dispatch(ViewerIntent.Fling(vx, vy)) },
                        onZoom = { scale, fx, fy ->
                            viewModel.dispatch(ViewerIntent.Zoom(scale, fx, fy))
                        },
                        onResetZoom = { fx, fy ->
                            viewModel.dispatch(ViewerIntent.ResetZoom(fx, fy))
                        },
                        onBoundsChanged = { viewModel.onScrollBoundsChanged(it) },
                        onSelectSheet = { viewModel.dispatch(ViewerIntent.SwitchSheet(it)) },
                        onSelect = { viewModel.dispatch(ViewerIntent.SelectCell(it)) },
                        onStopMotion = { viewModel.stopMotion() },
                        onSearchOpen = { viewModel.dispatch(ViewerIntent.SetSearchOpen(it)) },
                        onSearchQuery = { viewModel.dispatch(ViewerIntent.SetSearchQuery(it)) },
                        onStepMatch = { viewModel.dispatch(ViewerIntent.StepMatch(it)) },
                        onReveal = { viewModel.dispatch(ViewerIntent.RevealViewport(it)) },
                        onDateNames = { viewModel.setDateNames(it) },
                        recents = recents.value,
                        onOpenRecent = { id ->
                            viewModel.dispatch(ViewerIntent.OpenFile(sourceFor(Uri.parse(id))))
                        },
                        onForgetRecent = { viewModel.forgetRecent(it) },
                    )
                }
            }
        }
    }

    /**
     * Build a source for [uri], taking a **persistable** read permission first.
     *
     * Whether that succeeds is exactly what decides if the document can join the
     * recents list (T22). A SAF pick offers a persistable grant and this
     * succeeds; an `ACTION_VIEW` from a file manager does not, and it throws —
     * so the document is opened and viewed, but never remembered. That is the
     * whole of the honesty rule in `RecentDocument`: the list holds only things
     * that will open.
     *
     * A provider is also free to refuse for its own reasons, which lands in the
     * same place: viewable now, not remembered.
     */
    private fun sourceFor(uri: Uri): ContentUriSource {
        val reopenable = runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        if (!reopenable) Log.d(LOG_TAG, "no persistable grant for $uri; not remembering it")
        return ContentUriSource.from(contentResolver, uri, reopenable = reopenable)
    }

    /**
     * Whether a remembered document can still be opened right now.
     *
     * Two things can have changed since it was written: the grant may have been
     * revoked (the provider's app uninstalled, or the user cleared it), and the
     * file itself may be gone. Both are checked, because holding a grant to a
     * deleted file is a perfectly ordinary state and would otherwise show a row
     * that fails on tap.
     */
    private suspend fun isRecentAvailable(id: String): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(id) }.getOrNull() ?: return@withContext false
        val held = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!held) return@withContext false

        // Then actually try to open it. Querying is not enough: the downloads
        // provider keeps answering for a document whose file has been deleted,
        // so a metadata probe reports a row that then fails on tap. Opening the
        // descriptor asks the only question the row is really making — can this
        // be read — and closing it immediately costs a file handle for a moment.
        runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    /**
     * A file manager sending a second document while Darcha is already running.
     * Without this the new intent would be delivered and ignored, leaving the
     * previous document on screen.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    /**
     * Open the document an `ACTION_VIEW` points at, if there is one.
     *
     * Nothing here decides whether the file is really a workbook: the filters
     * that make Darcha visible are deliberately loose (see the manifest), so
     * anything can arrive. It goes through the same pipeline as a picked file
     * and comes out as a `ViewerState.Error` if it is not readable — which is
     * why this method must not throw for a bad URI. Building the source queries
     * the provider, and a provider is free to refuse.
     */
    private fun openFromIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        // NOTE FOR T22 (recents): an ACTION_VIEW grant is ONE-SHOT. It lives as
        // long as this task and cannot be promoted — takePersistableUriPermission
        // throws SecurityException here, unlike a SAF pick, because the sender
        // never offered FLAG_GRANT_PERSISTABLE_URI_PERMISSION. So a URI that
        // arrived this way must NOT be stored as a reopenable recent: it would
        // look fine in the list and fail on every tap. Recents can hold picker
        // URIs; for intent URIs it needs the file copied, or the entry marked
        // one-shot and shown differently.
        Log.d(LOG_TAG, "ACTION_VIEW ${intent.type} $uri")
        viewModel.dispatch(ViewerIntent.OpenFile(sourceFor(uri)))
    }

    private companion object {
        const val LOG_TAG = "Darcha.Intent"

        /**
         * MIME filter for the picker. The official spreadsheet type comes first;
         * `application/octet-stream` is included because many providers report
         * `.xlsx` that way and would otherwise hide the file entirely.
         */
        val OPEN_DOCUMENT_MIME_TYPES = arrayOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream",
        )
    }
}

/** Supplies [ViewerViewModel] with its repository (TECH_SPEC §6 — DI wiring). */
private class ViewerViewModelFactory(
    private val repository: WorkbookRepository,
    private val recents: RecentsRepository,
    private val isRecentAvailable: suspend (String) -> Boolean,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ViewerViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return ViewerViewModel(
            repository = repository,
            onDiagnostic = { Log.d("Darcha.Viewer", it) },
            recents = recents,
            isRecentAvailable = isRecentAvailable,
        ) as T
    }
}
