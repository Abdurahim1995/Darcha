package com.tikoncha.darcha

import android.content.Intent
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.tikoncha.darcha.feature.viewer.ViewerViewModel
import com.tikoncha.darcha.feature.viewer.data.WorkbookRepository
import com.tikoncha.darcha.feature.viewer.data.XlsxWorkbookRepository
import com.tikoncha.darcha.feature.viewer.mvi.ViewerIntent
import com.tikoncha.darcha.feature.viewer.ui.ViewerScreen
import kotlinx.coroutines.launch

/**
 * The app's single entry point: wires the parser-backed repository into
 * [ViewerViewModel] and hosts the viewer UI (TECH_SPEC §6).
 *
 * The ViewModel is retained by the activity's `ViewModelStore`, so a rotation
 * re-renders from the state already in memory and never re-parses.
 */
class MainActivity : ComponentActivity() {

    /** The one repository for this process — see [DarchaApplication]. */
    private val repository: XlsxWorkbookRepository
        get() = (application as DarchaApplication).workbookRepository

    private val viewModel: ViewerViewModel by lazy {
        ViewModelProvider(this, ViewerViewModelFactory(repository))[ViewerViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Process death skips the ViewModel's cleanup, so a previous run can leave
        // temp copies behind. Safe to call on every Activity creation: the sweep
        // runs against the process-wide repository, which excludes the open
        // document's copy and waits for any load still in flight.
        lifecycleScope.launch { repository.sweepStaleTempFiles() }

        setContent {
            MaterialTheme {
                // Held, not read: passing the holder down lets the grid observe
                // viewport changes in its draw phase instead of recomposing here.
                val state = viewModel.state.collectAsState()

                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    // Needed so T22 can reopen this document from the recents
                    // list. Not every provider grants it, so failure is survivable.
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    viewModel.dispatch(
                        ViewerIntent.OpenFile(ContentUriSource.from(contentResolver, uri)),
                    )
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    ViewerScreen(
                        state = state,
                        onOpenFile = { picker.launch(OPEN_DOCUMENT_MIME_TYPES) },
                        onRetry = { viewModel.dispatch(ViewerIntent.Retry) },
                        onScroll = { dx, dy -> viewModel.dispatch(ViewerIntent.Scroll(dx, dy)) },
                        onFling = { vx, vy -> viewModel.dispatch(ViewerIntent.Fling(vx, vy)) },
                        onBoundsChanged = { viewModel.onScrollBoundsChanged(it) },
                        onSelectSheet = { viewModel.dispatch(ViewerIntent.SwitchSheet(it)) },
                    )
                }
            }
        }
    }

    private companion object {
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
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ViewerViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return ViewerViewModel(
            repository = repository,
            onDiagnostic = { Log.d("Darcha.Viewer", it) },
        ) as T
    }
}
