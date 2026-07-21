package com.github.jayteealao.playster.screens.transcript

import com.github.jayteealao.playster.data.firestore.TranscriptSegment
import org.json.JSONArray

/**
 * Pure parser for the out-of-doc transcript blob [TranscriptViewModel.fetchSegments]
 * fetches over `signedUrl` — a JSON array of `{text, start}` objects. Extracted to
 * a top-level object (mirroring the sibling pure engines, e.g. `ChapterParser`) so
 * it's independently JVM-unit-testable without constructing the view model or its
 * Firestore/Firebase collaborators.
 *
 * Total and side-effect-free: a malformed array throws (the caller's
 * `runCatching` in `fetchSegments` degrades that to the editorial fetch error),
 * but any structurally valid-but-partial element degrades gracefully rather than
 * failing the whole parse — an entry with blank/missing `text` is dropped, and a
 * missing `start` defaults to `0.0`.
 */
object TranscriptSegmentParser {
    fun parse(body: String): List<TranscriptSegment> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val text = obj.optString("text", "")
            if (text.isEmpty()) return@mapNotNull null
            TranscriptSegment(start = obj.optDouble("start", 0.0), text = text)
        }
    }
}
