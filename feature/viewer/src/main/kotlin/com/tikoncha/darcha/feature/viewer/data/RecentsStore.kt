package com.tikoncha.darcha.feature.viewer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The recent-documents list, persisted (T22).
 *
 * Backed by DataStore, whose [Flow] feeds the ViewModel's state directly — the
 * list updates itself after a write with no change listener to wire up.
 *
 * The whole list lives in one preference key rather than one key per entry:
 * ten records are a few hundred bytes, and a single value makes reordering and
 * trimming atomic instead of a sequence of edits that could interleave.
 */
public class RecentsStore(
    private val store: DataStore<Preferences>,
    private val maxEntries: Int = MAX_ENTRIES,
) : RecentsRepository {

    /**
     * The list, newest first.
     *
     * A read failure yields an empty list rather than an exception: a corrupt or
     * unreadable preferences file must not stop the app from opening, and the
     * next write repairs it.
     */
    override val recents: Flow<List<RecentDocument>> = store.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { preferences -> RecentsCodec.decode(preferences[KEY].orEmpty()) }

    /**
     * Record [document] as just opened, moving it to the front.
     *
     * Re-opening a document must not add a second row, so any existing entry
     * with the same id is replaced — which also refreshes a name that changed
     * since last time.
     */
    override suspend fun remember(document: RecentDocument) {
        store.edit { preferences ->
            val existing = RecentsCodec.decode(preferences[KEY].orEmpty())
            val updated = (listOf(document) + existing.filterNot { it.id == document.id })
                .take(maxEntries)
            preferences[KEY] = RecentsCodec.encode(updated)
        }
    }

    /** Drop the entry with [id] — the user removing a row they can no longer open. */
    override suspend fun forget(id: String) {
        store.edit { preferences ->
            val remaining = RecentsCodec.decode(preferences[KEY].orEmpty())
                .filterNot { it.id == id }
            preferences[KEY] = RecentsCodec.encode(remaining)
        }
    }

    /** Drop every entry. */
    public suspend fun clear() {
        store.edit { preferences -> preferences.remove(KEY) }
    }

    private fun emptyPreferences(): Preferences =
        androidx.datastore.preferences.core.emptyPreferences()

    public companion object {
        /**
         * Build the store for [context].
         *
         * A factory rather than a constructor taking a `DataStore`, so the
         * DataStore dependency stays inside this module and `:app` only has to
         * know that a store exists — the same reason `WorkbookSource` keeps
         * `android.net.Uri` out of here.
         *
         * **Call once per process.** DataStore permits one instance per file and
         * a second would race the first; `DarchaApplication` holds it.
         */
        public fun create(context: Context): RecentsStore = RecentsStore(
            PreferenceDataStoreFactory.create {
                context.applicationContext.preferencesDataStoreFile(FILE_NAME)
            },
        )

        /** The DataStore file name. */
        public const val FILE_NAME: String = "darcha_recents"

        /**
         * How many documents to keep. Long enough to cover "the thing I was just
         * looking at" and a few before it; short enough that the home screen
         * stays a list you scan rather than one you search.
         */
        public const val MAX_ENTRIES: Int = 10

        private val KEY = stringPreferencesKey("recents")
    }
}
