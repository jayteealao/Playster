package com.github.jayteealao.playster.screens.transcript

import com.github.jayteealao.playster.data.firestore.TranscriptSegment
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TST-4 — the out-of-doc transcript fetch/parse path
 * ([TranscriptViewModel.fetchSegments]) had zero coverage; this pure
 * `String -> List<TranscriptSegment>` step is extracted to
 * [TranscriptSegmentParser] (mirroring the `ChapterParser` pattern) purely so
 * it's reachable without constructing the view model or its Firestore/Firebase
 * collaborators.
 *
 * Robolectric-only requirement: plain JUnit's `android.jar` stub for
 * `org.json.JSONArray` throws `RuntimeException("... not mocked")` on every
 * method (confirmed empirically); Robolectric shadows it with a real
 * implementation instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptSegmentParserTest {
    @Test
    fun wellFormedSegments_parseInOrderWithFields() {
        val json =
            """
            [
                {"text":"hello","start":1.5},
                {"text":"world","start":3.25}
            ]
            """.trimIndent()

        val segments = TranscriptSegmentParser.parse(json)

        assertEquals(
            listOf(
                TranscriptSegment(start = 1.5, text = "hello"),
                TranscriptSegment(start = 3.25, text = "world"),
            ),
            segments,
        )
    }

    @Test
    fun emptyArray_yieldsEmptyList() {
        assertEquals(emptyList<TranscriptSegment>(), TranscriptSegmentParser.parse("[]"))
    }

    @Test
    fun malformedJson_throwsRatherThanSilentlyDegrading() {
        // The caller (fetchSegments) wraps this in runCatching and degrades to the
        // editorial fetch error - the parser itself is not responsible for that
        // recovery, only for being honest that the input wasn't parseable.
        assertTrue(
            runCatching { TranscriptSegmentParser.parse("not json") }
                .exceptionOrNull() is JSONException,
        )
    }

    @Test
    fun blankOrMissingText_dropsTheSegment() {
        val json = """[{"text":"hello","start":1.5},{"text":""},{"start":9.0}]"""

        val segments = TranscriptSegmentParser.parse(json)

        assertEquals(listOf(TranscriptSegment(start = 1.5, text = "hello")), segments)
    }

    @Test
    fun missingStart_defaultsToZero() {
        val json = """[{"text":"hello"}]"""

        val segments = TranscriptSegmentParser.parse(json)

        assertEquals(listOf(TranscriptSegment(start = 0.0, text = "hello")), segments)
    }

    @Test
    fun nonObjectArrayElements_areSkippedNotCrashed() {
        val json = """["a string element", 42, {"text":"kept","start":2.0}]"""

        val segments = TranscriptSegmentParser.parse(json)

        assertEquals(listOf(TranscriptSegment(start = 2.0, text = "kept")), segments)
    }
}
