package com.github.jayteealao.playster.screens.player

import com.github.jayteealao.playster.data.firestore.SummaryChapter
import com.github.jayteealao.playster.screens.player.chapters.ChaptersResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC6 — the chapters precedence chain and the NOW-index probe: a valid
 * description wins; a malformed or absent description falls back to the
 * summarizer chapters; neither yields an empty list (the teaching empty state);
 * and the NOW index is the last chapter at or before the position.
 */
class ChaptersResolverTest {
    private val summarizer =
        listOf(
            SummaryChapter(t = 0.0, label = "Summarizer one", dur = 120.0),
            SummaryChapter(t = 120.0, label = "Summarizer two", dur = null),
        )
    private val descriptionWithChapters =
        """
        0:00 Description one
        1:00 Description two
        3:00 Description three
        """.trimIndent()

    @Test
    fun descriptionChapters_winOverSummarizer() {
        val resolved = ChaptersResolver.resolve(descriptionWithChapters, summarizer)
        assertEquals(listOf("Description one", "Description two", "Description three"), resolved.map { it.label })
    }

    @Test
    fun malformedDescription_fallsBackToSummarizer() {
        val resolved = ChaptersResolver.resolve("no timestamps here", summarizer)
        assertEquals(summarizer, resolved)
    }

    @Test
    fun nullDescription_fallsBackToSummarizer() {
        assertEquals(summarizer, ChaptersResolver.resolve(null, summarizer))
    }

    @Test
    fun neitherSource_yieldsEmpty() {
        assertTrue(ChaptersResolver.resolve(null, emptyList()).isEmpty())
        assertTrue(ChaptersResolver.resolve("no chapters", emptyList()).isEmpty())
    }

    @Test
    fun nowIndex_tracksPosition() {
        val chapters =
            listOf(
                SummaryChapter(t = 0.0, label = "a"),
                SummaryChapter(t = 60.0, label = "b"),
                SummaryChapter(t = 180.0, label = "c"),
            )
        assertEquals(0, ChaptersResolver.nowIndex(chapters, 0f))
        assertEquals(0, ChaptersResolver.nowIndex(chapters, 59f))
        assertEquals(1, ChaptersResolver.nowIndex(chapters, 60f))
        assertEquals(1, ChaptersResolver.nowIndex(chapters, 179f))
        assertEquals(2, ChaptersResolver.nowIndex(chapters, 500f))
    }

    @Test
    fun nowIndex_beforeFirstOrEmpty_isMinusOne() {
        val chapters = listOf(SummaryChapter(t = 30.0, label = "late"))
        assertEquals(-1, ChaptersResolver.nowIndex(chapters, 10f))
        assertEquals(-1, ChaptersResolver.nowIndex(emptyList(), 10f))
    }
}
