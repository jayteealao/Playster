package com.github.jayteealao.playster.data.search

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AC4 — recent searches persist locally across relaunch, are capped, de-dup, and
 * clear. The store is the app's real Preferences DataStore under Robolectric; the
 * repository is reconstructed between records to prove the list survives the
 * "app relaunch" (a fresh repository over the same on-disk store reads it back).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentSearchesRepositoryTest {
    private fun repository() = RecentSearchesRepository(RuntimeEnvironment.getApplication())

    @Before
    fun clearStore() = runBlocking { repository().clear() }

    @Test
    fun record_persistsAcrossRelaunch_mostRecentFirst() =
        runBlocking {
            repository().record("design tokens")
            repository().record("a11y")
            // A fresh repository over the same on-disk store == an app relaunch.
            val recents = repository().recent.first()
            assertEquals(listOf("a11y", "design tokens"), recents)
        }

    @Test
    fun record_dedupesMovingToFront_withoutDuplicating() =
        runBlocking {
            val repo = repository()
            repo.record("a11y")
            repo.record("design tokens")
            repo.record("a11y") // moves to front, no duplicate
            val recents = repo.recent.first()
            assertEquals(listOf("a11y", "design tokens"), recents)
            assertEquals(1, recents.count { it == "a11y" })
        }

    @Test
    fun record_capsAtEight_evictingOldest() =
        runBlocking {
            val repo = repository()
            for (i in 1..12) repo.record("q$i")
            val recents = repo.recent.first()
            assertEquals(RecentSearches.CAP, recents.size)
            assertEquals("q12", recents.first())
            assertEquals("q5", recents.last())
        }

    @Test
    fun clear_emptiesTheList() =
        runBlocking {
            val repo = repository()
            repo.record("design tokens")
            repo.clear()
            assertEquals(emptyList<String>(), repo.recent.first())
        }
}
