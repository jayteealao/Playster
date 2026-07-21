package com.github.jayteealao.playster.data.firestore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-function coverage for [pickDeterministic] — the tie-break
 * [FirestoreRepository.videoContextFlow] uses when a video lives under more
 * than one playlist (multiple `videos` docs match the same `videoId`, so
 * `limit(1)` alone would return an arbitrary, possibly-flipping copy). No
 * Firestore instance is needed here: the function only sorts on a caller-
 * supplied path string.
 */
class FirestoreRepositoryTest {
    @Test
    fun pickDeterministic_singleCandidate_returnsIt() {
        val result = pickDeterministic(listOf("playlists/a/videos/v1")) { it }
        assertEquals("playlists/a/videos/v1", result)
    }

    @Test
    fun pickDeterministic_multipleCandidates_picksLexicographicallySmallestPath() {
        val paths =
            listOf(
                "playlists/zeta/videos/v1",
                "playlists/alpha/videos/v1",
                "playlists/mid/videos/v1",
            )
        assertEquals("playlists/alpha/videos/v1", pickDeterministic(paths) { it })
    }

    @Test
    fun pickDeterministic_isStableRegardlessOfInputOrder() {
        val forward =
            pickDeterministic(
                listOf("playlists/b/videos/v1", "playlists/a/videos/v1"),
            ) { it }
        val reversed =
            pickDeterministic(
                listOf("playlists/a/videos/v1", "playlists/b/videos/v1"),
            ) { it }
        assertEquals(forward, reversed)
    }

    @Test
    fun pickDeterministic_empty_returnsNull() {
        assertNull(pickDeterministic(emptyList<String>()) { it })
    }
}
