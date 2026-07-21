package com.github.jayteealao.playster.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AC4 logic leg — the pure prepend / de-dup / cap / encode-decode ordering
 * engine, device-free. The DataStore round-trip (persist across relaunch,
 * clear) is proven in [RecentSearchesRepositoryTest] under Robolectric.
 */
class RecentSearchesTest {
    @Test
    fun record_prependsMostRecentFirst() {
        var list = emptyList<String>()
        list = RecentSearches.record(list, "design tokens")
        list = RecentSearches.record(list, "a11y")
        assertEquals(listOf("a11y", "design tokens"), list)
    }

    @Test
    fun record_dedupesCaseInsensitively_movingToFront() {
        val list = RecentSearches.record(listOf("a11y", "design tokens"), "DESIGN TOKENS")
        assertEquals(listOf("DESIGN TOKENS", "a11y"), list)
    }

    @Test
    fun record_capsAtEight() {
        var list = emptyList<String>()
        for (i in 1..12) list = RecentSearches.record(list, "q$i")
        assertEquals(RecentSearches.CAP, list.size)
        // Most recent first, oldest four dropped.
        assertEquals("q12", list.first())
        assertEquals("q5", list.last())
    }

    @Test
    fun record_ignoresBlank() {
        val list = RecentSearches.record(listOf("a11y"), "   ")
        assertEquals(listOf("a11y"), list)
    }

    @Test
    fun encodeDecode_roundTrips_andEnforcesCap() {
        val values = (1..12).map { "q$it" }
        val decoded = RecentSearches.decode(RecentSearches.encode(values))
        assertEquals(values.take(RecentSearches.CAP), decoded)
    }

    @Test
    fun decode_null_isEmpty() {
        assertEquals(emptyList<String>(), RecentSearches.decode(null))
    }
}
