package com.github.jayteealao.playster.functions

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

private const val TAG = "playster.summary"

/**
 * Thin wrapper around the `requestVideoSummary` callable. Returns a [Result]
 * so the caller can branch on success/failure without throwing on the typical
 * "quota exhausted" / "not found" paths.
 */
@Singleton
class SummaryFunctions @Inject constructor(
    private val functions: FirebaseFunctions,
) {
    suspend fun requestSummary(videoId: String, model: String? = null): Result<String> {
        Log.d(TAG, "requestVideoSummary{videoId=$videoId,model=${model ?: "<default>"}}")
        val payload: Map<String, Any> = if (model != null) {
            mapOf("videoId" to videoId, "model" to model)
        } else {
            mapOf("videoId" to videoId)
        }
        return try {
            val result = functions
                .getHttpsCallable("requestVideoSummary")
                .call(payload)
                .await()
            @Suppress("UNCHECKED_CAST")
            val data = result.data as? Map<String, Any?>
            val summaryId = data?.get("summaryId") as? String
                ?: return Result.failure(IllegalStateException("Missing summaryId in callable response"))
            Result.success(summaryId)
        } catch (e: FirebaseFunctionsException) {
            Log.w(TAG, "requestVideoSummary failed code=${e.code} videoId=$videoId", e)
            Result.failure(e)
        } catch (t: Throwable) {
            Log.w(TAG, "requestVideoSummary unexpected error videoId=$videoId", t)
            Result.failure(t)
        }
    }
}
