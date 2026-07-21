package com.github.jayteealao.playster.screens.player

import com.github.jayteealao.playster.screens.player.chapters.ChapterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC7 — the pure YouTube description chapter parser rules: first at 0:00, at
 * least three entries, each at least ten seconds. Valid sets parse with computed
 * durations; every malformed variant falls through to an empty list so the
 * resolver's fallback chain takes over.
 */
class ChapterParserTest {
    @Test
    fun validDescription_parsesWithComputedDurations() {
        val description =
            """
            Some intro prose about the video.
            0:00 Introduction
            2:30 The main argument
            10:00 A worked example
            1:02:04 Questions
            """.trimIndent()

        val chapters = ChapterParser.parse(description)

        assertEquals(
            listOf("Introduction", "The main argument", "A worked example", "Questions"),
            chapters.map { it.label },
        )
        assertEquals(0.0, chapters[0].t, 0.0)
        assertEquals(150.0, chapters[1].t, 0.0)
        assertEquals(600.0, chapters[2].t, 0.0)
        assertEquals(3724.0, chapters[3].t, 0.0)
        // dur = next.t - t; last is null.
        assertEquals(150.0, chapters[0].dur!!, 0.0)
        assertEquals(null, chapters.last().dur)
    }

    @Test
    fun bulletedTimestamps_parse() {
        val description =
            """
            - 0:00 Cold open
            - 0:45 Setup
            - 3:10 Payoff
            """.trimIndent()
        assertEquals(3, ChapterParser.parse(description).size)
    }

    @Test
    fun firstNotAtZero_fallsThrough() {
        val description =
            """
            0:30 Late start
            2:00 Second
            5:00 Third
            """.trimIndent()
        assertTrue(ChapterParser.parse(description).isEmpty())
    }

    @Test
    fun fewerThanThree_fallsThrough() {
        val description =
            """
            0:00 One
            1:00 Two
            """.trimIndent()
        assertTrue(ChapterParser.parse(description).isEmpty())
    }

    @Test
    fun subTenSecondChapter_fallsThrough() {
        val description =
            """
            0:00 One
            0:05 Two
            0:40 Three
            """.trimIndent()
        assertTrue(ChapterParser.parse(description).isEmpty())
    }

    @Test
    fun nonMonotonic_fallsThrough() {
        val description =
            """
            0:00 One
            2:00 Two
            1:00 Back in time
            """.trimIndent()
        assertTrue(ChapterParser.parse(description).isEmpty())
    }

    @Test
    fun blankOrProseOnly_returnsEmpty() {
        assertTrue(ChapterParser.parse("").isEmpty())
        assertTrue(ChapterParser.parse("Just a normal description with no timestamps.").isEmpty())
    }
}
