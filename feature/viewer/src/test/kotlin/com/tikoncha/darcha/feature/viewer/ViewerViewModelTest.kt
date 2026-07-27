package com.tikoncha.darcha.feature.viewer

import com.tikoncha.darcha.feature.viewer.data.WorkbookLoad
import com.tikoncha.darcha.feature.viewer.data.WorkbookRepository
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.feature.viewer.mvi.ViewerIntent
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.ErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val meta = DocumentMeta("book.xlsx", listOf("Sheet1", "Sheet2"))

    private val source = object : WorkbookSource {
        override val displayName: String = "book.xlsx"
    }

    /** Emits [progress] steps, then returns [result]. */
    private class FakeRepository(
        private val result: WorkbookLoad,
        private val progress: List<Float> = emptyList(),
    ) : WorkbookRepository {
        var loadCount: Int = 0
            private set

        override suspend fun load(
            source: WorkbookSource,
            onProgress: (Float) -> Unit,
        ): WorkbookLoad {
            loadCount++
            progress.forEach(onProgress)
            return result
        }
    }

    private fun viewModel(repository: WorkbookRepository) =
        ViewerViewModel(repository, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun initialState_isIdle() {
        assertEquals(ViewerState.Idle, viewModel(FakeRepository(WorkbookLoad.Success(meta))).state.value)
    }

    @Test
    fun openFile_endsReadyWithMetadata() {
        val vm = viewModel(FakeRepository(WorkbookLoad.Success(meta), progress = listOf(0.5f, 1f)))
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
        val repository = FakeRepository(WorkbookLoad.Success(meta))
        val vm = viewModel(repository)

        vm.dispatch(ViewerIntent.Retry)

        assertEquals(ViewerState.Idle, vm.state.value)
        assertEquals(0, repository.loadCount)
    }

    @Test
    fun gestureIntents_afterReady_updateViewport() {
        val vm = viewModel(FakeRepository(WorkbookLoad.Success(meta)))
        vm.dispatch(ViewerIntent.OpenFile(source))

        vm.dispatch(ViewerIntent.Scroll(dx = 60f, dy = 25f))
        vm.dispatch(ViewerIntent.Zoom(scale = 2f, focalX = 0f, focalY = 0f))

        val viewport = (vm.state.value as ViewerState.Ready).viewport
        assertEquals(60f, viewport.scrollX, 0f)
        assertEquals(25f, viewport.scrollY, 0f)
        assertEquals(2f, viewport.zoom, 0f)
    }

    @Test
    fun switchSheet_resetsViewport() {
        val vm = viewModel(FakeRepository(WorkbookLoad.Success(meta)))
        vm.dispatch(ViewerIntent.OpenFile(source))
        vm.dispatch(ViewerIntent.Scroll(dx = 300f, dy = 400f))

        vm.dispatch(ViewerIntent.SwitchSheet(1))

        val state = vm.state.value as ViewerState.Ready
        assertEquals(1, state.activeSheetId)
        assertEquals(Viewport.INITIAL, state.viewport)
    }

    @Test
    fun openingASecondFile_reloadsAndResetsState() {
        val repository = FakeRepository(WorkbookLoad.Success(meta))
        val vm = viewModel(repository)

        vm.dispatch(ViewerIntent.OpenFile(source))
        vm.dispatch(ViewerIntent.Scroll(dx = 200f, dy = 200f))
        vm.dispatch(ViewerIntent.OpenFile(source))

        assertEquals(2, repository.loadCount)
        assertEquals(Viewport.INITIAL, (vm.state.value as ViewerState.Ready).viewport)
    }
}
