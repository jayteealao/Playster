package com.github.jayteealao.playster.screens.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AC1 logic leg — the pure position → active-line mapping the derivedStateOf
 * fold rides. Boundary, before-first, after-last, and exact-start cases; the
 * on-device active-line advance is the live-embed leg deferred to verify.
 */
class ActiveLineIndexTest {
    private val starts = listOf(0.0, 12.0, 30.5, 61.0, 90.0)

    @Test
    fun beforeFirstStart_isMinusOne() {
        // A transcript that starts at 0 is always "on" once playing; use a later first start.
        assertEquals(-1, ActiveLineIndex.activeIndex(5f, listOf(12.0, 30.0)))
    }

    @Test
    fun exactStart_selectsThatSegment() {
        assertEquals(2, ActiveLineIndex.activeIndex(30.5f, starts))
    }

    @Test
    fun betweenStarts_selectsTheEarlierSegment() {
        assertEquals(1, ActiveLineIndex.activeIndex(29.9f, starts))
        assertEquals(3, ActiveLineIndex.activeIndex(89.9f, starts))
    }

    @Test
    fun afterLastStart_selectsTheFinalSegment() {
        assertEquals(4, ActiveLineIndex.activeIndex(9_999f, starts))
    }

    @Test
    fun zeroPositionAtZeroStart_selectsFirst() {
        assertEquals(0, ActiveLineIndex.activeIndex(0f, starts))
    }

    @Test
    fun emptyTranscript_isMinusOne() {
        assertEquals(-1, ActiveLineIndex.activeIndex(42f, emptyList()))
    }
}
