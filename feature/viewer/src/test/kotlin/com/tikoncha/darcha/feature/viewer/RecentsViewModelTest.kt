package com.tikoncha.darcha.feature.viewer

import com.tikoncha.darcha.feature.viewer.data.RecentDocument
import com.tikoncha.darcha.feature.viewer.data.RecentsRepository
import com.tikoncha.darcha.feature.viewer.data.SheetProgress
import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.data.WorkbookLoad
import com.tikoncha.darcha.feature.viewer.data.WorkbookRepository
import com.tikoncha.darcha.feature.viewer.data.WorkbookSource
import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.feature.viewer.mvi.ViewerIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The rule that keeps the recents list honest (T22): **only documents that can
 * actually be reopened are remembered.**
 */
class RecentsViewModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /** An in-memory stand-in for the DataStore-backed store. */
    private class FakeRecents : RecentsRepository {
        val state = MutableStateFlow<List<RecentDocument>>(emptyList())
        override val recents = state
        override suspend fun remember(document: RecentDocument) {
            state.value = listOf(document) + state.value.filterNot { it.id == document.id }
        }
        override suspend fun forget(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
    }

    private class Source(
        override val displayName: String,
        override val recentId: String?,
        override val declaredSizeBytes: Long? = 1234,
    ) : WorkbookSource {
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private class OkRepository : WorkbookRepository {
        override suspend fun load(source: WorkbookSource, onPartial: (SheetProgress) -> Unit) =
            WorkbookLoad.Success(
                meta = DocumentMeta(source.displayName, listOf("Sheet1"), rowCount = 1),
                sheet = SheetSnapshot.EMPTY,
            )
        override suspend fun readSheet(index: Int, onPartial: (SheetProgress) -> Unit) =
            WorkbookLoad.Failure(com.tikoncha.darcha.model.ErrorKind.Corrupted())
        override fun closeDocument() = Unit
    }

    private fun viewModel(
        recents: RecentsRepository,
        available: suspend (String) -> Boolean = { true },
    ) = ViewerViewModel(
        repository = OkRepository(),
        scope = scope,
        recents = recents,
        isRecentAvailable = available,
    )

    // --- the honesty rule ---

    @Test
    fun aReopenableDocumentIsRemembered() = runBlocking {
        val store = FakeRecents()
        val model = viewModel(store)

        model.dispatch(ViewerIntent.OpenFile(Source("book.xlsx", recentId = "content://a")))

        val remembered = store.state.value.single()
        assertEquals("content://a", remembered.id)
        assertEquals("book.xlsx", remembered.displayName)
        assertEquals(1234L, remembered.sizeBytes)
        assertTrue("lastOpened should be stamped", remembered.lastOpened > 0)
    }

    /**
     * The T21 trap: an `ACTION_VIEW` grant is one-shot, so such a document has
     * no reopen handle and must not appear in the list at all.
     */
    @Test
    fun aDocumentThatCannotBeReopenedIsNotRemembered() = runBlocking {
        val store = FakeRecents()
        val model = viewModel(store)

        model.dispatch(ViewerIntent.OpenFile(Source("from-file-manager.xlsx", recentId = null)))

        assertTrue("the list must stay empty", store.state.value.isEmpty())
    }

    @Test
    fun reopeningTheSameDocumentDoesNotDuplicateIt() = runBlocking {
        val store = FakeRecents()
        val model = viewModel(store)

        model.dispatch(ViewerIntent.OpenFile(Source("book.xlsx", recentId = "content://a")))
        model.dispatch(ViewerIntent.OpenFile(Source("other.xlsx", recentId = "content://b")))
        model.dispatch(ViewerIntent.OpenFile(Source("book.xlsx", recentId = "content://a")))

        assertEquals(2, store.state.value.size)
        assertEquals("the one just opened comes first", "content://a", store.state.value.first().id)
    }

    // --- availability ---

    @Test
    fun availabilityIsRecomputedNotStored() = runBlocking {
        val store = FakeRecents()
        store.state.value = listOf(
            RecentDocument("content://gone", "deleted.xlsx", lastOpened = 2),
            RecentDocument("content://here", "kept.xlsx", lastOpened = 1),
        )
        val model = viewModel(store, available = { it != "content://gone" })
        model.refreshRecents()

        val shown = model.recentDocuments.value
        assertEquals(2, shown.size)
        assertFalse("a revoked entry is marked, not hidden", shown.first { it.id == "content://gone" }.available)
        assertTrue(shown.first { it.id == "content://here" }.available)
    }

    @Test
    fun anEntryCanBeForgotten() = runBlocking {
        val store = FakeRecents()
        store.state.value = listOf(RecentDocument("content://gone", "deleted.xlsx", lastOpened = 1))
        val model = viewModel(store)

        model.forgetRecent("content://gone")

        assertTrue(store.state.value.isEmpty())
    }
}
