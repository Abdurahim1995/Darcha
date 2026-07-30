package com.tikoncha.darcha

import android.app.Application
import com.tikoncha.darcha.feature.viewer.data.RecentsStore
import com.tikoncha.darcha.feature.viewer.data.XlsxWorkbookRepository

/**
 * Process-scoped wiring (TECH_SPEC §6 — `:app` owns DI).
 *
 * The repository holds the **open document session**: the parsed workbook and
 * the temp copy backing it. That state belongs to the process, not to an
 * Activity — a rotation destroys the Activity while the retained ViewModel keeps
 * reading from the same document. Creating a repository per Activity produced a
 * second, session-less instance whose view of "what is open" was empty, which is
 * how the startup sweep came to delete a file that was still in use.
 *
 * Everything that needs the repository takes it from here, so there is exactly
 * one and it always reflects the live session.
 */
internal class DarchaApplication : Application() {

    /** The single repository for this process. Created on first use. */
    val workbookRepository: XlsxWorkbookRepository by lazy {
        XlsxWorkbookRepository(cacheDir = cacheDir)
    }

    /**
     * The recent-documents list (T22).
     *
     * DataStore insists on one instance per file per process — a second would
     * race the first — which is another reason this lives beside the repository
     * rather than being built where it is used.
     */
    val recentsStore: RecentsStore by lazy { RecentsStore.create(this) }
}
