package com.tikoncha.darcha.feature.viewer

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.data.WorkbookLoad
import com.tikoncha.darcha.feature.viewer.data.WorkbookRepository
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.feature.viewer.mvi.ViewerIntent
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tikoncha.darcha.model.ErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ViewModel orchestration tests, driven with a fake repository.
 *
 * The ViewModel takes its scope as a constructor parameter, so these run on
 * [Dispatchers.Unconfined] — no main dispatcher, no instrumentation.
 */
class ViewerViewModelTest {

    private val meta = DocumentMeta("book.xlsx", listOf("Sheet1", "Sheet2"), rowCount = 7)

    private val source = object : WorkbookSource {
        override val displayName: String = "book.xlsx"
        override val declaredSizeBytes: Long? = null
        override fun openStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    }

    /** Emits [progress] steps, then returns [result]. */
    private class FakeRepository(
        private val result: WorkbookLoad,
        private val progress: List<Float> = emptyList(),
    ) : WorkbookRepository {
        var loadCount: Int = 0
            private set
        var closeCount: Int = 0
            private set

        override suspend fun load(
            source: WorkbookSource,
            onProgress: (Float) -> Unit,
        ): WorkbookLoad {
            loadCount++
            progress.forEach(onProgress)
            return result
        }

        var readSheetCount: Int = 0
            private set

        override suspend fun readSheet(index: Int, onProgress: (Float) -> Unit): WorkbookLoad {
            readSheetCount++
            return result
        }

        override fun closeDocument() {
            closeCount++
        }
    }

    /**
     * The store the ViewModel lives in, so tests can end its lifecycle the way
     * the framework does — [ViewModelStore.clear] runs `onCleared`. Each JUnit
     * test gets a fresh instance of this class, so the store starts empty.
     */
    private val viewModelStore = ViewModelStore()

    private fun viewModel(repository: WorkbookRepository): ViewerViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ViewerViewModel(repository, CoroutineScope(Dispatchers.Unconfined)) as T
        }
        return ViewModelProvider(viewModelStore, factory)[ViewerViewModel::class.java]
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(ViewerState.Idle, viewModel(FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY))).state.value)
    }

    @Test
    fun openFile_endsReadyWithMetadata() {
        val vm = viewModel(FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY), progress = listOf(0.5f, 1f)))
        vm.dispatch(ViewerIntent.OpenFile(source))

        val state = vm.state.value as ViewerState.Ready
        assertEquals(meta, state.docMeta)
        assertEquals(0, state.activeSheetId)
        assertEquals(Viewport.INITIAL, state.viewport)
    }

    @Test
    fun openFile_failure_endsInError() {
        val kind = ErrorKind.Encrypted("password protected")
        val vm = viewModel(FakeRepository(WorkbookLoad.Failure(kind)))
        vm.dispatch(ViewerIntent.OpenFile(source))

        assertEquals(ViewerState.Error(kind), vm.state.value)
    }

    @Test
    fun retry_afterFailure_loadsAgain() {
        val repository = FakeRepository(WorkbookLoad.Failure(ErrorKind.Corrupted("truncated")))
        val vm = viewModel(repository)

        vm.dispatch(ViewerIntent.OpenFile(source))
        assertTrue(vm.state.value is ViewerState.Error)

        vm.dispatch(ViewerIntent.Retry)
        assertEquals(2, repository.loadCount)
        assertTrue(vm.state.value is ViewerState.Error)
    }

    @Test
    fun retry_withoutAnyOpen_isIgnored() {
        val repository = FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY))
        val vm = viewModel(repository)

        vm.dispatch(ViewerIntent.Retry)

        assertEquals(ViewerState.Idle, vm.state.value)
        assertEquals(0, repository.loadCount)
    }

    @Test
    fun gestureIntents_afterReady_updateViewport() {
        val vm = viewModel(FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY)))
        vm.dispatch(ViewerIntent.OpenFile(source))

        vm.dispatch(ViewerIntent.Scroll(dx = 60f, dy = 25f))
        vm.dispatch(ViewerIntent.Zoom(scale = 2f, focalX = 0f, focalY = 0f))

        val viewport = (vm.state.value as ViewerState.Ready).viewport
        assertEquals(60f, viewport.scrollX, 0f)
        assertEquals(25f, viewport.scrollY, 0f)
        assertEquals(2f, viewport.zoom, 0f)
    }

    @Test
    fun switchSheet_readsOnDemandAndResetsViewport() {
        val repository = FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY))
        val vm = viewModel(repository)
        vm.dispatch(ViewerIntent.OpenFile(source))
        vm.dispatch(ViewerIntent.Scroll(dx = 300f, dy = 400f))

        vm.dispatch(ViewerIntent.SwitchSheet(1))

        assertEquals("sheet 1 must be read through the session", 1, repository.readSheetCount)
        val state = vm.state.value as ViewerState.Ready
        assertEquals(1, state.activeSheetId)
        assertEquals(Viewport.INITIAL, state.viewport)
        assertEquals(null, state.loadingSheetId)
    }

    @Test
    fun switchingBack_usesTheCacheInsteadOfReReading() {
        // The LRU keeps recent sheets parsed, so returning to a tab is instant.
        val repository = FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY))
        val vm = viewModel(repository)
        vm.dispatch(ViewerIntent.OpenFile(source))

        vm.dispatch(ViewerIntent.SwitchSheet(1))
        assertEquals(1, repository.readSheetCount)

        vm.dispatch(ViewerIntent.SwitchSheet(0)) // sheet 0 was cached at load
        assertEquals("no second read for a cached sheet", 1, repository.readSheetCount)

        vm.dispatch(ViewerIntent.SwitchSheet(1)) // cached by the first switch
        assertEquals(1, repository.readSheetCount)
        assertEquals(1, (vm.state.value as ViewerState.Ready).activeSheetId)
    }

    @Test
    fun switchSheet_toTheActiveOne_doesNotRead() {
        val repository = FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY))
        val vm = viewModel(repository)
        vm.dispatch(ViewerIntent.OpenFile(source))

        vm.dispatch(ViewerIntent.SwitchSheet(0))

        assertEquals(0, repository.readSheetCount)
    }

    // --- fling (T14) ---

    @Test
    fun fling_startsMovingImmediately() {
        // The decay runs in the ViewModel, so the first frame lands before any
        // delay — on Unconfined that is synchronous with the dispatch.
        val vm = viewModel(FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY)))
        vm.dispatch(ViewerIntent.OpenFile(source))

        vm.dispatch(ViewerIntent.Fling(vx = 1_000f, vy = 0f))

        val viewport = (vm.state.value as ViewerState.Ready).viewport
        assertTrue("expected the glide to have begun, got ${viewport.scrollX}", viewport.scrollX > 0f)
    }

    @Test
    fun fling_belowTheThreshold_doesNothing() {
        val vm = viewModel(FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY)))
        vm.dispatch(ViewerIntent.OpenFile(source))

        vm.dispatch(ViewerIntent.Fling(vx = 5f, vy = 5f))

        assertEquals(Viewport.INITIAL, (vm.state.value as ViewerState.Ready).viewport)
    }

    @Test
    fun aNewScroll_cancelsAnInFlightFling() = runBlocking {
        // A finger going down mid-glide must take over, not fight the decay.
        val vm = viewModel(FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY)))
        vm.dispatch(ViewerIntent.OpenFile(source))
        vm.dispatch(ViewerIntent.Fling(vx = 3_000f, vy = 0f))

        vm.dispatch(ViewerIntent.Scroll(dx = 0f, dy = 0f))
        val parked = (vm.state.value as ViewerState.Ready).viewport.scrollX

        delay(120) // several frames' worth
        assertEquals(
            "the glide must not have continued",
            parked,
            (vm.state.value as ViewerState.Ready).viewport.scrollX,
            0f,
        )
    }

    @Test
    fun clearing_closesTheOpenDocument() {
        // The temp copy behind the document must not outlive the screen.
        val repository = FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY))
        val vm = viewModel(repository)
        vm.dispatch(ViewerIntent.OpenFile(source))

        // The supported way to end a ViewModel's life: clearing its store.
        viewModelStore.clear()

        assertEquals(1, repository.closeCount)
    }

    @Test
    fun openingASecondFile_reloadsAndResetsState() {
        val repository = FakeRepository(WorkbookLoad.Success(meta, SheetSnapshot.EMPTY))
        val vm = viewModel(repository)

        vm.dispatch(ViewerIntent.OpenFile(source))
        vm.dispatch(ViewerIntent.Scroll(dx = 200f, dy = 200f))
        vm.dispatch(ViewerIntent.OpenFile(source))

        assertEquals(2, repository.loadCount)
        assertEquals(Viewport.INITIAL, (vm.state.value as ViewerState.Ready).viewport)
    }
}
