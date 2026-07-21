package com.github.jayteealao.playster.screens.player.chapters

import com.github.jayteealao.playster.data.firestore.SummaryChapter

/**
 * Pure parser for YouTube description chapters — the convention where a video's
 * description lists timestamped sections (`0:00 Intro`, `1:23 The main point`,
 * `1:12:04 Q&A`). Canonical units are seconds, mirroring [SummaryChapter]; the
 * parser is total and side-effect-free (JVM-unit-tested — AC7).
 *
 * YouTube's own chapter rules, enforced here so a malformed or partial list
 * falls through to the resolver's fallback chain rather than rendering a broken
 * Chapters tab:
 *  - the first timestamp must be `0:00`;
 *  - there must be at least three timestamps;
 *  - each chapter must be at least ten seconds long.
 * A description that satisfies none of these yields an empty list (invalid →
 * fall through); a valid set yields chapters with `dur = next.t − t` and a null
 * `dur` on the final entry.
 */
object ChapterParser {
    private const val MIN_CHAPTERS = 3
    private const val MIN_CHAPTER_SECONDS = 10.0
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60

    /** `H:MM:SS` or `M:SS` (optionally after a bullet/dash) then the label. */
    private val LINE = Regex("""^\s*[-–•*]?\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\b[\s\-–:]*(.*)$""")

    // Capture-group indices in [LINE]: hours (optional), minutes, seconds, label.
    private const val GROUP_HOURS = 1
    private const val GROUP_MINUTES = 2
    private const val GROUP_SECONDS = 3
    private const val GROUP_LABEL = 4

    @Suppress("ReturnCount") // Validation guards read clearest as early returns to the fallback chain.
    fun parse(description: String): List<SummaryChapter> {
        if (description.isBlank()) return emptyList()

        data class Raw(val t: Double, val label: String)

        val marks =
            description.lineSequence().mapNotNull { line ->
                val groups = LINE.find(line)?.groupValues ?: return@mapNotNull null
                val seconds =
                    (groups[GROUP_HOURS].toIntOrNull() ?: 0) * MINUTES_PER_HOUR * SECONDS_PER_MINUTE +
                        groups[GROUP_MINUTES].toInt() * SECONDS_PER_MINUTE +
                        groups[GROUP_SECONDS].toInt()
                val trimmed = groups[GROUP_LABEL].trim()
                if (trimmed.isEmpty()) null else Raw(seconds.toDouble(), trimmed)
            }.toList()

        // Rule: at least three, first at 0:00, strictly increasing.
        if (marks.size < MIN_CHAPTERS) return emptyList()
        if (marks.first().t != 0.0) return emptyList()
        if (marks.zipWithNext().any { (a, b) -> b.t <= a.t }) return emptyList()

        // Rule: every bounded chapter is at least ten seconds long.
        val bounded = marks.zipWithNext().all { (a, b) -> b.t - a.t >= MIN_CHAPTER_SECONDS }
        if (!bounded) return emptyList()

        return marks.mapIndexed { index, raw ->
            val next = marks.getOrNull(index + 1)
            SummaryChapter(
                t = raw.t,
                label = raw.label,
                dur = next?.let { it.t - raw.t },
            )
        }
    }
}
