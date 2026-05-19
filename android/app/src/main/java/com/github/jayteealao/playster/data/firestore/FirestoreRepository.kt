package com.github.jayteealao.playster.data.firestore

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FirestoreRepository"
private const val SUMMARY_TAG = "playster.summary"

/**
 * Thin wrapper around top-level Firestore collections used by the operator UI.
 * Every public flow is backed by `addSnapshotListener` and tears the listener
 * down on `awaitClose` so cancelled subscriptions don't leak.
 */
@Singleton
class FirestoreRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    fun playlistsFlow(): Flow<List<PlaylistDoc>> = callbackFlow {
        val listener: ListenerRegistration = firestore.collection("playlists")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "playlistsFlow listen error", error)
                    return@addSnapshotListener
                }
                val items = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(PlaylistDoc::class.java)
                } ?: emptyList()
                trySend(items)
            }
        awaitClose { listener.remove() }
    }

    fun videosFlow(playlistId: String): Flow<List<VideoDoc>> = callbackFlow {
        val listener: ListenerRegistration = firestore.collection("videos")
            .whereEqualTo("playlistId", playlistId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "videosFlow listen error for $playlistId", error)
                    return@addSnapshotListener
                }
                val items = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(VideoDoc::class.java)
                } ?: emptyList()
                trySend(items)
            }
        awaitClose { listener.remove() }
    }

    fun videoFlow(videoId: String): Flow<VideoDoc?> = callbackFlow {
        val listener: ListenerRegistration = firestore.collectionGroup("videos")
            .whereEqualTo("videoId", videoId)
            .limit(1)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "videoFlow listen error for $videoId", error)
                    return@addSnapshotListener
                }
                val first = snapshot?.documents?.firstOrNull()
                trySend(first?.toObject(VideoDoc::class.java))
            }
        awaitClose { listener.remove() }
    }
}

/**
 * Firestore listener over `summaries/{videoId}`. Emits null when the doc
 * doesn't exist (initial NoSummary state), the mapped DTO otherwise. Used by
 * the SummaryViewModel.
 */
@Singleton
class SummaryRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    fun observe(videoId: String): Flow<SummaryDoc?> = callbackFlow {
        Log.d(SUMMARY_TAG, "listen{videoId=$videoId,attach=true}")
        val listener: ListenerRegistration = firestore.collection("summaries")
            .document(videoId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(SUMMARY_TAG, "summary listen error for $videoId", error)
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
 * Composable layer to avoid stale "now" values.
 */
@Singleton
class QuotaRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    fun observe(): Flow<QuotaDoc?> = callbackFlow {
        Log.d(SUMMARY_TAG, "quotaListen{attach=true}")
        val listener: ListenerRegistration = firestore.collection("quota")
            .document("openrouter")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(SUMMARY_TAG, "quota listen error", error)
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
