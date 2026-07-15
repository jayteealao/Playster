package com.github.jayteealao.playster.screens.player.chapters

import com.github.jayteealao.playster.data.firestore.SummaryChapter

/**
 * The chapters precedence chain, pure and JVM-tested (AC6): a valid set parsed
 * from the video's own description wins; failing that, the summarizer's parsed
 * chapters ([com.github.jayteealao.playster.data.firestore.SummaryDoc.chapters])
 * are the fallback; failing both, the empty list drives the Chapters tab's
 * teaching empty state. Every source degrades to the next — the resolver NEVER
 * blocks the player, so a missing description key, a quota-exhausted fetch, or a
 * chapters-less summary all resolve without harm.
 */
object ChaptersResolver {
    /**
     * @param description the video's description (client-fetched, may be null on
     *   missing key / quota / failure — degrade to the summarizer source)
     * @param summaryChapters the summarizer's chapters (fallback), possibly empty
     */
    fun resolve(
        description: String?,
        summaryChapters: List<SummaryChapter>,
    ): List<SummaryChapter> {
        val parsed = description?.let { ChapterParser.parse(it) }.orEmpty()
        return if (parsed.isNotEmpty()) parsed else summaryChapters
    }

    /**
     * The index of the chapter the given playback position falls in — the last
     * chapter whose start is at or before [positionSeconds] — for the NOW badge.
     * Returns -1 when there are no chapters or the position precedes the first
     * chapter's start (so the badge simply doesn't show yet).
     */
    fun nowIndex(
        chapters: List<SummaryChapter>,
        positionSeconds: Float,
    ): Int {
        var index = -1
        for (i in chapters.indices) {
            if (chapters[i].t <= positionSeconds) index = i else break
        }
        return index
    }
}
