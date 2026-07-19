package com.github.jayteealao.playster.screens.transcript

/**
 * The Transcript **screen's** editorial state model — deliberately distinct from
 * the Summary tab's `videoDetail.summary.TranscriptUiState` (that one keeps its
 * plain-Column render for the Summary section; this screen renders a keyed
 * LazyColumn article with highlights + marginalia). JVM-pure (no Compose,
 * Firestore, or playback types) so [TranscriptStateAssembler] is device-free
 * unit-testable and the sealed states render under Robolectric goldens.
 *
 * The live active line is **not** carried here: it is a function of the
 * per-second playback position and is derived in the UI via `derivedStateOf`
 * ([ActiveLineIndex]), so a position tick never re-assembles this state or
 * recomposes the whole list (Step 9 perf discipline).
 */
sealed interface TranscriptUiState {
    /** The transcript doc hasn't resolved yet, or is still being set (pending/processing). */
    data object Loading : TranscriptUiState

    /** The video has no transcript (never processed, or empty) — a readable dead end, never blank. */
    data object Unavailable : TranscriptUiState

    /** A terminal error: a listener fault, a failed large-transcript fetch, or an expired signed URL. */
    data class Error(val message: String) : TranscriptUiState

    /**
     * The assembled article. [paragraphs] is the merged stream (segments +
     * highlights + this-video notes, keyed on the stable segment start);
     * [following] is whether live auto-scroll is currently armed (paused when
     * the reader scrolls away, re-armed by the "Resume following" affordance).
     */
    data class Available(
        val paragraphs: List<TranscriptParagraph>,
        val following: Boolean = true,
    ) : TranscriptUiState
}

/**
 * The transcript dateline — the mock's Ep/title accent kicker over the italic
 * channel/date line. Sourced from the video document; null until it resolves
 * (the AppBar still renders, so the screen is never headerless).
 */
data class TranscriptHeader(
    val kicker: String,
    val byline: String,
)

/**
 * One merged transcript paragraph. [segmentStart] is both the tap-to-seek target
 * and the stable LazyColumn key + highlight anchor; [note] renders as marginalia
 * when a note was taken at (or just after) this line.
 */
data class TranscriptParagraph(
    val segmentStart: Double,
    val timestampLabel: String,
    val text: String,
    val highlighted: Boolean,
    val note: String? = null,
)
