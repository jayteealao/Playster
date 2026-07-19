package com.github.jayteealao.playster.data.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.jayteealao.playster.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reader's recent searches — an ordered, capped, device-local list over the
 * app's existing settings DataStore (AC4). Local-only by construction: nothing
 * here leaves the device, satisfying the privacy obligation that no search text
 * escapes except via the transcript callable itself.
 *
 * Ordering matters (most-recent-first) and `stringSetPreferencesKey` is
 * *unordered*, so the list is stored as one ordered [stringPreferencesKey],
 * newline-joined; [RecentSearches] holds the pure prepend/de-dup/cap transform
 * so the ordering contract is unit-testable device-free.
 */
@Singleton
class RecentSearchesRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val dataStore = context.dataStore
        private val key = stringPreferencesKey("recent_searches")

        /** Most-recent-first, capped at [RecentSearches.CAP]. */
        val recent: Flow<List<String>> =
            dataStore.data.map { preferences ->
                RecentSearches.decode(preferences[key])
            }

        /**
         * Record a committed query at the head — de-duplicated (a re-run moves to
         * the front, not a second entry) and capped, so the pill row stays short
         * and most-recent-first.
         */
        suspend fun record(query: String) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return
            dataStore.edit { preferences ->
                val next = RecentSearches.record(RecentSearches.decode(preferences[key]), trimmed)
                preferences[key] = RecentSearches.encode(next)
            }
        }

        /** Clear the whole list (the "clear recents" affordance). */
        suspend fun clear() {
            dataStore.edit { preferences -> preferences.remove(key) }
        }
    }

/**
 * The pure ordering engine for [RecentSearchesRepository]: prepend, de-duplicate
 * case-insensitively, cap. Kept side-effect-free so AC4's cap/de-dup/order
 * contract is proven without a device; the repository is the thin DataStore
 * shell around it.
 */
object RecentSearches {
    const val CAP = 8
    private const val SEPARATOR = "\n"

    /** Prepend [query], drop any prior case-insensitive duplicate, cap at [CAP]. */
    fun record(
        current: List<String>,
        query: String,
    ): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return current
        val withoutDuplicate = current.filterNot { it.equals(trimmed, ignoreCase = true) }
        return (listOf(trimmed) + withoutDuplicate).take(CAP)
    }

    /** Split the stored newline-joined blob into the ordered list. */
    fun decode(stored: String?): List<String> =
        stored
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /** Join for storage — never persist more than [CAP]. */
    fun encode(values: List<String>): String = values.take(CAP).joinToString(SEPARATOR)
}
