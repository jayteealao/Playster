package com.github.jayteealao.playster.data.youtube

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "playster.player"

/**
 * Best-effort client-side fetch of a video's description via the YouTube Data
 * API `videos.list(part=snippet)` — the chosen source for description chapters
 * (Assumption 1). It is *chosen over a backend fetch specifically to keep the C6
 * backend fence intact*: storing the description on `VideoDocument` would add a
 * persisted field outside that fence, which is a PO scope call, not a plan one.
 *
 * Everything about this source is graceful-degrade so the description never
 * harms the player: no API key is committed, so a build without a
 * `youtube_data_api_key` string resource returns null immediately and chapters
 * fall back to the summarizer source (or the empty state). A quota error, a
 * network failure, or a malformed response also return null. Successful reads
 * are cached in-memory for the process so a tab re-open doesn't re-spend quota.
 *
 * sdlc-debt: in-memory-only cache (no disk persistence) — a cold start re-fetches;
 * upgrade path is a small DataStore/Room cache if quota pressure ever shows up.
 */
@Singleton
class YouTubeDescriptionSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val cache = ConcurrentHashMap<String, String>()

        private val client =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

        /** The description for [videoId], or null when unavailable (degrade to fallback). */
        @Suppress("ReturnCount") // Best-effort guards degrade to null through clear early returns.
        suspend fun descriptionFor(videoId: String): String? {
            if (videoId.isBlank()) return null
            cache[videoId]?.let { return it }
            val key = apiKey() ?: return null
            val vidHash = videoId.hashCode().toUInt().toString(HEX_RADIX)
            return withContext(Dispatchers.IO) {
                runCatching { fetchDescription(videoId, key) }
                    .onFailure { Log.w(TAG, "descriptionFetch{videoId=$vidHash,failed}") }
                    .getOrNull()
                    ?.also { cache[videoId] = it }
            }
        }

        private fun fetchDescription(
            videoId: String,
            key: String,
        ): String? {
            val encodedId = URLEncoder.encode(videoId, Charsets.UTF_8.name())
            val url =
                "https://www.googleapis.com/youtube/v3/videos" +
                    "?part=snippet&id=$encodedId&key=$key"
            val request = Request.Builder().url(url).get().build()
            return client.newCall(request).execute().use { response ->
                response.takeIf { it.isSuccessful }?.body?.string()?.let(::parseDescription)
            }
        }

        @Suppress("ReturnCount") // Degrade to null at each missing JSON hop.
        private fun parseDescription(body: String): String? {
            val items = JSONObject(body).optJSONArray("items") ?: return null
            val snippet = items.optJSONObject(0)?.optJSONObject("snippet") ?: return null
            return snippet.optString("description").takeIf { it.isNotBlank() }
        }

        /** The optional Data-API key resource; absent/blank → no client fetch. */
        private fun apiKey(): String? {
            val resId =
                context.resources.getIdentifier("youtube_data_api_key", "string", context.packageName)
            if (resId == 0) return null
            return context.getString(resId).takeIf { it.isNotBlank() }
        }

        private companion object {
            const val CONNECT_TIMEOUT_MS = 5_000L
            const val READ_TIMEOUT_MS = 5_000L
            const val HEX_RADIX = 16
        }
    }
