package com.tikoncha.darcha.feature.viewer.data

import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.model.ErrorKind
import java.io.InputStream

/**
 * A document the viewer can open, as a stream of bytes plus a name.
 *
 * The MVI layer never sees an `android.net.Uri`: `:app` implements this over
 * whatever the platform hands it (a SAF `content://` URI in T11), which keeps
 * the ViewModel and the repository unit-testable on a plain JVM.
 */
public interface WorkbookSource {

    /** File name to show in the UI. */
    public val displayName: String

    /**
     * Size in bytes as reported by the provider, or `null` when unknown.
     *
     * Treated only as a cheap early hint — providers may omit it or report it
     * wrongly, so the repository counts bytes while copying regardless.
     */
    public val declaredSizeBytes: Long?

    /**
     * Open the document's bytes. Called on an I/O dispatcher; the caller closes
     * the stream.
     */
    public fun openStream(): InputStream

    /**
     * A handle this document can be **reopened** from later, or `null` if it
     * cannot be (T22).
     *
     * Non-null only when the platform granted a permission that outlives this
     * task. That is what keeps the recents list honest: a document opened from a
     * file manager is viewed but not remembered, because its grant is one-shot
     * (TECH_SPEC §9.1) and the entry would fail on the very first tap.
     */
    public val recentId: String? get() = null
}

/**
 * The recent-documents list, behind an interface so the ViewModel can be tested
 * without DataStore or a filesystem — the same split as [WorkbookRepository].
 */
public interface RecentsRepository {

    /** The list, newest first. Emits again after every change. */
    public val recents: kotlinx.coroutines.flow.Flow<List<RecentDocument>>

    /** Record [document] as just opened, moving it to the front. */
    public suspend fun remember(document: RecentDocument)

    /** Drop the entry with [id]. */
    public suspend fun forget(id: String)

    public companion object {
        /** A store that remembers nothing — the default, and what tests get. */
        public val NONE: RecentsRepository = object : RecentsRepository {
            override val recents = kotlinx.coroutines.flow.flowOf(emptyList<RecentDocument>())
            override suspend fun remember(document: RecentDocument) = Unit
            override suspend fun forget(id: String) = Unit
        }
    }
}

/**
 * A sheet mid-parse: the rows read so far, with the metadata already known.
 *
 * The workbook part and the shared strings are parsed before any row, so the
 * names and the string table are complete from the first emission. So are the
 * column widths, which is what keeps the grid from re-laying itself out when the
 * parse ends (T15.6, `RowsChunk.layout`): each row is drawn at its true size
 * from its first paint. Only the rows themselves, their heights, and the merges
 * and frozen panes that follow `<sheetData>` are still filling in.
 *
 * @property progress fraction parsed so far, in `0f..1f`.
 */
public data class SheetProgress(
    public val meta: DocumentMeta,
    public val sheet: SheetSnapshot,
    public val progress: Float,
)

/** The outcome of loading a document. */
public sealed interface WorkbookLoad {

    /** Loading succeeded, yielding [meta] and the sheet that was read. */
    public data class Success(
        public val meta: DocumentMeta,
        public val sheet: SheetSnapshot,
    ) : WorkbookLoad

    /** Loading failed with [kind] from the parser's error taxonomy. */
    public data class Failure(public val kind: ErrorKind) : WorkbookLoad
}

/**
 * The parser as the viewer sees it (TECH_SPEC §10). Putting an interface here
 * lets the ViewModel be tested with a fake, and keeps `:core:parser` details —
 * `ZipFile`, threading, chunk callbacks — out of the MVI layer.
 *
 * Implementations decide their own threading; the ViewModel calls [load] from a
 * coroutine and expects it to suspend rather than block the caller.
 */
public interface WorkbookRepository {

    /**
     * Load the document behind [source] and read its first sheet.
     *
     * [onPartial] receives the sheet **as it is being parsed** — a snapshot of
     * the rows so far, sized by the layout already known for them, plus progress
     * in `0f..1f` — so the grid can paint its first cells long before the last
     * row arrives (TECH_SPEC §7). Emissions are throttled; the first chunk always
     * fires immediately.
     *
     * Opening a document starts a **session** that stays open until the next
     * [load] or a [closeDocument] — later sheets are read from it with [readSheet].
     *
     * Never throws for a bad document: failures come back as
     * [WorkbookLoad.Failure].
     */
    public suspend fun load(
        source: WorkbookSource,
        onPartial: (SheetProgress) -> Unit,
    ): WorkbookLoad

    /**
     * Read another sheet of the currently open document (T15 switches sheets
     * through this). Fails if no document is open.
     */
    public suspend fun readSheet(
        index: Int,
        onPartial: (SheetProgress) -> Unit = {},
    ): WorkbookLoad

    /**
     * End the current session: close the workbook and delete its temp copy.
     *
     * This releases the **document**, not the repository. Implementations stay
     * usable afterwards, and a later [load] starts a fresh session on the same
     * instance — which is what happens when the user backs out of the viewer
     * (clearing the ViewModel) and reopens the app while the process lives on.
     *
     * Safe to call repeatedly, and when nothing is open.
     */
    public fun closeDocument()
}
