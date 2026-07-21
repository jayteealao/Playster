package com.github.jayteealao.playster.screens.transcript

import com.github.jayteealao.playster.data.firestore.HighlightDoc
import com.github.jayteealao.playster.data.firestore.NoteDoc
import com.github.jayteealao.playster.data.firestore.TranscriptDoc
import com.github.jayteealao.playster.data.firestore.TranscriptSegment
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC2 / AC3 / AC6 logic legs — the pure merge + sealed-state / expiry mapping.
 * The live persistence + cross-screen loops ride the Firebase-emulator leg
 * deferred to verify; here the merge, highlight anchoring, note attachment, and
 * the never-blank state mapping are proven device-free.
 */
class TranscriptStateAssemblerTest {
    private fun doc(
        status: String? = "ready",
        segments: List<TranscriptSegment> = emptyList(),
        signedUrl: String? = null,
        errorCode: String? = null,
    ) = TranscriptDoc(
        id = "vid",
        videoId = "vid",
        statusWire = status,
        segments = segments,
        signedUrl = signedUrl,
        errorCode = errorCode,
    )

    private val segments =
        listOf(
            TranscriptSegment(start = 0.0, text = "First line."),
            TranscriptSegment(start = 12.0, text = "Second line."),
            TranscriptSegment(start = 30.0, text = "Third line."),
        )

    @Test
    fun nullDoc_isLoading() {
        val state = TranscriptStateAssembler.assemble(null, emptyList(), emptyList(), emptyList())
        assertEquals(TranscriptUiState.Loading, state)
    }

    @Test
    fun pendingStatus_isLoading() {
        val state = TranscriptStateAssembler.assemble(doc(status = "processing"), emptyList(), emptyList(), emptyList())
        assertEquals(TranscriptUiState.Loading, state)
    }

    @Test
    fun errorStatusOrCode_isError_neverBlank() {
        val empty = emptyList<Nothing>()
        val failed = TranscriptStateAssembler.assemble(doc(status = "failed"), empty, empty, empty)
        val coded = TranscriptStateAssembler.assemble(doc(errorCode = "TOO_LARGE"), empty, empty, empty)
        assertTrue(failed is TranscriptUiState.Error)
        assertTrue(coded is TranscriptUiState.Error)
    }

    @Test
    fun noSegmentsNoUrl_isUnavailable() {
        val state = TranscriptStateAssembler.assemble(doc(status = "ready"), emptyList(), emptyList(), emptyList())
        assertEquals(TranscriptUiState.Unavailable, state)
    }

    @Test
    fun signedUrlPresentButNoSegmentsYet_staysLoading_notBlank() {
        val state =
            TranscriptStateAssembler.assemble(
                doc(status = "ready", signedUrl = "https://gcs/blob?sig=abc"),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        assertEquals(TranscriptUiState.Loading, state)
    }

    @Test
    fun fetchError_isError_recoveryExhausted() {
        val state =
            TranscriptStateAssembler.assemble(
                doc(status = "ready", signedUrl = "https://gcs/blob"),
                emptyList(),
                emptyList(),
                emptyList(),
                fetchError = true,
            )
        assertTrue(state is TranscriptUiState.Error)
    }

    @Test
    fun segments_mergeIntoParagraphs_inOrder() {
        val state = TranscriptStateAssembler.assemble(doc(segments = segments), segments, emptyList(), emptyList())
        val available = state as TranscriptUiState.Available
        assertEquals(listOf("0:00", "0:12", "0:30"), available.paragraphs.map { it.timestampLabel })
        assertEquals(listOf("First line.", "Second line.", "Third line."), available.paragraphs.map { it.text })
    }

    @Test
    fun highlightAnchorsOnSegmentStart() {
        val highlights = listOf(HighlightDoc(videoId = "vid", segmentStart = 12.0, text = "Second line."))
        val state = TranscriptStateAssembler.assemble(doc(segments = segments), segments, highlights, emptyList())
        val paragraphs = (state as TranscriptUiState.Available).paragraphs
        assertFalse(paragraphs[0].highlighted)
        assertTrue(paragraphs[1].highlighted)
        assertFalse(paragraphs[2].highlighted)
    }

    @Test
    fun highlightForMissingSegment_isToleratedNotCrashed() {
        // A re-segmented re-fetch: the highlight's anchor (99.0) is not in the stream.
        val highlights = listOf(HighlightDoc(videoId = "vid", segmentStart = 99.0, text = "gone"))
        val state = TranscriptStateAssembler.assemble(doc(segments = segments), segments, highlights, emptyList())
        val paragraphs = (state as TranscriptUiState.Available).paragraphs
        assertTrue(paragraphs.none { it.highlighted })
    }

    @Test
    fun noteAttachesToTheSegmentWindowItFallsIn() {
        val notes =
            listOf(
                NoteDoc(videoId = "vid", t = 18.0, text = "Note in the second window."),
            )
        val state = TranscriptStateAssembler.assemble(doc(segments = segments), segments, emptyList(), notes)
        val paragraphs = (state as TranscriptUiState.Available).paragraphs
        assertNull(paragraphs[0].note)
        assertEquals("Note in the second window.", paragraphs[1].note)
        assertNull(paragraphs[2].note)
    }

    @Test
    fun newestNoteWinsWithinAWindow() {
        val notes =
            listOf(
                NoteDoc(videoId = "vid", t = 2.0, text = "older", createdAt = Timestamp(100, 0)),
                NoteDoc(videoId = "vid", t = 5.0, text = "newer", createdAt = Timestamp(200, 0)),
            )
        val state = TranscriptStateAssembler.assemble(doc(segments = segments), segments, emptyList(), notes)
        val paragraphs = (state as TranscriptUiState.Available).paragraphs
        assertEquals("newer", paragraphs[0].note)
    }
}
