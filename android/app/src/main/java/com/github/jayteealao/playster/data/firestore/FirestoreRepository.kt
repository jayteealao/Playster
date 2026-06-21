package com.github.jayteealao.playster.data.firestore

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FirestoreRepository"
private const val SUMMARY_TAG = "playster.summary"

/**
 * Shared helper: attaches a snapshot listener to a [Query], maps each
 * [QuerySnapshot] via [map], and propagates listener errors by closing the
 * flow with the exception (so downstream collectors see a terminal error
 * rather than freezing).
 */
private inline fun <T> Query.asCollectionFlow(
    tag: String,
    logLabel: String,
    crossinline map: (QuerySnapshot) -> List<T>,
): Flow<List<T>> =
    callbackFlow {
        val listener =
            addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(tag, "$logLabel listen error", error)
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.let(map) ?: emptyList())
            }
        awaitClose { listener.remove() }
    }

/**
 * Thin wrapper around top-level Firestore collections used by the operator UI.
 * Every public flow is backed by `addSnapshotListener` and tears the listener
 * down on `awaitClose` so cancelled subscriptions don't leak.
 */
@Singleton
class FirestoreRepository
    @Inject
    constructor(
        private val firestore: FirebaseFirestore,
    ) {
        fun playlistsFlow(): Flow<List<PlaylistDoc>> =
            firestore.collection("playlists").asCollectionFlow(
                tag = TAG,
                logLabel = "playlistsFlow",
            ) { snap -> snap.documents.mapNotNull { it.toObject(PlaylistDoc::class.java) } }

        fun videosFlow(playlistId: String): Flow<List<VideoDoc>> =
            firestore.collection("playlists")
                .document(playlistId)
                .collection("videos")
                .asCollectionFlow(
                    tag = TAG,
                    logLabel = "videosFlow[$playlistId]",
                ) { snap -> snap.documents.mapNotNull { it.toObject(VideoDoc::class.java) } }

        fun videoFlow(videoId: String): Flow<VideoDoc?> =
            callbackFlow {
                val listener =
                    firestore.collectionGroup("videos")
                        .whereEqualTo("videoId", videoId)
                        .limit(1)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "videoFlow listen error for $videoId", error)
                                close(error)
                                return@addSnapshotListener
                            }
                            trySend(snapshot?.documents?.firstOrNull()?.toObject(VideoDoc::class.java))
                        }
                awaitClose { listener.remove() }
            }
    }

/**
 * Firestore listener over `summaries/{videoId}`. Emits null when the doc
 * doesn't exist (initial NoSummary state), the mapped DTO otherwise. Used by
 * the SummaryViewModel. Listener errors close the flow with the exception so
 * the ViewModel can surface a terminal error rather than freezing in-progress.
 */
@Singleton
class SummaryRepository
    @Inject
    constructor(
        private val firestore: FirebaseFirestore,
    ) {
        fun observe(videoId: String): Flow<SummaryDoc?> =
            callbackFlow {
                Log.d(SUMMARY_TAG, "listen{videoId=$videoId,attach=true}")
                val listener =
                    firestore.collection("summaries")
                        .document(videoId)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(SUMMARY_TAG, "summary listen error for $videoId", error)
                                close(error)
                                return@addSnapshotListener
                            }
                            trySend(snapshot?.toSummaryDoc())
                        }
                awaitClose {
                    Log.d(SUMMARY_TAG, "listen{videoId=$videoId,attach=false}")
                    listener.remove()
                }
            }

        suspend fun getOnce(videoId: String): SummaryDoc? {
            val snapshot = firestore.collection("summaries").document(videoId).get().await()
            return snapshot.toSummaryDoc()
        }
    }

/**
 * Firestore listener over `quota/openrouter`. Emits the parsed [QuotaDoc] or
 * null when no doc exists. The derived [QuotaState] is computed at the
 * Composable layer to avoid stale "now" values. Listener errors close the
 * flow so upstream collectors receive a terminal error.
 */
@Singleton
class QuotaRepository
    @Inject
    constructor(
        private val firestore: FirebaseFirestore,
    ) {
        fun observe(): Flow<QuotaDoc?> =
            callbackFlow {
                Log.d(SUMMARY_TAG, "quotaListen{attach=true}")
                val listener =
                    firestore.collection("quota")
                        .document("openrouter")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(SUMMARY_TAG, "quota listen error", error)
                                close(error)
                                return@addSnapshotListener
                            }
                            trySend(snapshot?.toQuotaDoc())
                        }
                awaitClose {
                    Log.d(SUMMARY_TAG, "quotaListen{attach=false}")
                    listener.remove()
                }
            }
    }
