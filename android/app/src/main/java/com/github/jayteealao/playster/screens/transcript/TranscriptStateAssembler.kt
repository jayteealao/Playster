package com.github.jayteealao.playster.screens.transcript

import com.github.jayteealao.playster.data.editorial.EditorialDressing
import com.github.jayteealao.playster.data.firestore.HighlightDoc
import com.github.jayteealao.playster.data.firestore.NoteDoc
import com.github.jayteealao.playster.data.firestore.TranscriptDoc
import com.github.jayteealao.playster.data.firestore.TranscriptSegment

/**
 * Pure one-pass merge of a [TranscriptDoc] (its inline or fetched [segments]),
 * the reader's [highlights], and this video's [notes] into the editorial
 * [TranscriptUiState] the screen renders. Device-free (no Compose / Firestore
 * calls beyond the DTOs), so the merge, the highlight anchoring, the note
 * attachment, and the sealed-state / expiry mapping are all JVM-unit-testable
 * (AC2 / AC3 / AC6 logic legs).
 *
 * Anchoring: highlights and notes both ride the stable `segmentStart` — a
 * highlight is on iff a [HighlightDoc.segmentStart] matches the segment, a note
 * attaches to the last segment that started at or before its timestamp. A
 * highlight whose segment is absent (a re-segmented re-fetch) is tolerated — it
 * simply doesn't render, never crashes.
 */
object TranscriptStateAssembler {
    /**
     * @param resolvedSegments the segments to render — inline `doc.segments`, or
     *   the segments fetched from a large-transcript signed URL by the caller.
     * @param fetchError true when a large-transcript signed-URL fetch failed or
     *   the URL expired and could not be refreshed (AC6 recovery exhausted).
     */
    @Suppress("ReturnCount") // Each early return is one distinct sealed-state branch — flatter than nesting.
    fun assemble(
        doc: TranscriptDoc?,
        resolvedSegments: List<TranscriptSegment>,
        highlights: List<HighlightDoc>,
        notes: List<NoteDoc>,
        fetchError: Boolean = false,
    ): TranscriptUiState {
        if (fetchError) return TranscriptUiState.Error(FETCH_ERROR_MESSAGE)
        if (doc == null) return TranscriptUiState.Loading
        if (doc.errorCode != null || doc.statusWire?.lowercase() in ERROR_STATUSES) {
            return TranscriptUiState.Error(UNAVAILABLE_MESSAGE)
        }
        if (resolvedSegments.isNotEmpty()) {
            return TranscriptUiState.Available(
                paragraphs = merge(resolvedSegments, highlights, notes),
            )
        }
        // No segments yet. Still being set, or a large transcript whose blob is
        // still being fetched → keep the quiet loading voice, never blank.
        if (doc.statusWire?.lowercase() in PENDING_STATUSES || doc.signedUrl != null) {
            return TranscriptUiState.Loading
        }
        return TranscriptUiState.Unavailable
    }

    private fun merge(
        segments: List<TranscriptSegment>,
        highlights: List<HighlightDoc>,
        notes: List<NoteDoc>,
    ): List<TranscriptParagraph> {
        val sorted = segments.sortedBy { it.start }
        val highlightedStarts = highlights.mapTo(HashSet()) { it.segmentStart }
        val starts = sorted.map { it.start }
        return sorted.mapIndexed { index, segment ->
            TranscriptParagraph(
                segmentStart = segment.start,
                timestampLabel = EditorialDressing.clockLabel(segment.start.toLong()),
                text = segment.text,
                highlighted = segment.start in highlightedStarts,
                note = noteForSegment(index, starts, notes),
            )
        }
    }

    /** The newest note whose timestamp falls in this segment's window [start, nextStart). */
    private fun noteForSegment(
        index: Int,
        starts: List<Double>,
        notes: List<NoteDoc>,
    ): String? {
        if (notes.isEmpty()) return null
        val start = starts[index]
        val next = starts.getOrNull(index + 1) ?: Double.MAX_VALUE
        return notes
            .filter { it.t >= start && it.t < next }
            .maxByOrNull { it.createdAt?.seconds ?: 0L }
            ?.text
    }

    // REL-8 (triaged copy-only): no backend re-sign path exists for an expired
    // signed URL, so the copy no longer promises one — it names what's true
    // (this attempt failed) and what the reader can actually do (come back
    // later; a fresh transcript write is what changes the URL).
    private const val FETCH_ERROR_MESSAGE =
        "The full transcript couldn't be loaded just now. Try reopening it in a little while."
    private const val UNAVAILABLE_MESSAGE =
        "This episode's transcript could not be set. Try the recording instead."
    private val PENDING_STATUSES = setOf("pending", "processing", "in_progress", "queued")
    private val ERROR_STATUSES = setOf("error", "failed")
}
