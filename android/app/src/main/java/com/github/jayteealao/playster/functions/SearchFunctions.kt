package com.github.jayteealao.playster.functions

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "playster.search"

/** One transcript full-text hit — the Kotlin mirror of the callable's result row. */
data class TranscriptSearchHit(
    val videoId: String,
    val start: Double,
    val snippet: String,
    val score: Double,
)

/**
 * Thin wrapper around the `searchTranscripts` callable (built + emulator-proven
 * by the backend slice). Returns a [Result] so the caller can degrade the
 * transcript group alone on failure while instant title results keep rendering.
 *
 * On a backend failure it emits the 04b `transcript_search_error` signal
 * (source=backend, code) — the RIM-6 detector that tells a real search outage
 * apart from a well-designed empty state. The query text is NEVER logged or
 * attached (04b §4 PII rule): only the error code and source leave the device.
 */
@Singleton
class SearchFunctions
    @Inject
    constructor(
        private val functions: FirebaseFunctions,
    ) {
        suspend fun search(
            query: String,
            limit: Int? = null,
        ): Result<List<TranscriptSearchHit>> {
            val payload: Map<String, Any> =
                if (limit != null) mapOf("query" to query, "limit" to limit) else mapOf("query" to query)
            return try {
                val result =
                    functions
                        .getHttpsCallable("searchTranscripts")
                        .call(payload)
                        .await()

                @Suppress("UNCHECKED_CAST")
                val data = result.data as? Map<String, Any?>

                @Suppress("UNCHECKED_CAST")
                val rows = data?.get("results") as? List<Map<String, Any?>> ?: emptyList()
                Result.success(rows.mapNotNull(::toHit))
            } catch (e: FirebaseFunctionsException) {
                SearchInstrumentation.onBackendError(e.code.name)
                Log.w(TAG, "searchError{source=backend,code=${e.code.name}}", e) // NEVER the query text
                Result.failure(e)
            } catch (t: Throwable) {
                SearchInstrumentation.onClientError(t)
                Log.w(TAG, "searchError{source=client}", t) // NEVER the query text
                Result.failure(t)
            }
        }

        private fun toHit(row: Map<String, Any?>): TranscriptSearchHit? {
            val videoId = row["videoId"] as? String ?: return null
            val snippet = row["snippet"] as? String ?: ""
            val start = (row["start"] as? Number)?.toDouble() ?: 0.0
            val score = (row["score"] as? Number)?.toDouble() ?: 0.0
            return TranscriptSearchHit(videoId = videoId, start = start, snippet = snippet, score = score)
        }
    }

/**
 * The `transcript_search_error` dark-path signal (04b-instrument.md §search),
 * in the repo's existing idiom: a Crashlytics non-fatal with low-cardinality
 * custom keys (`source`, `code`) mirrored to the `playster.search` logcat tag.
 * The search query text is never a key or a log field.
 */
object SearchInstrumentation {
    fun onBackendError(code: String) {
        Firebase.crashlytics.apply {
            setCustomKey("source", "backend")
            setCustomKey("code", code)
            recordException(SearchException("transcript search failed: backend/$code"))
        }
    }

    fun onClientError(cause: Throwable) {
        Firebase.crashlytics.apply {
            setCustomKey("source", "client")
            setCustomKey("code", cause::class.java.simpleName)
            recordException(cause)
        }
    }
}

/** Non-fatal marker recorded to Crashlytics for a transcript-search failure. */
class SearchException(message: String) : Exception(message)
