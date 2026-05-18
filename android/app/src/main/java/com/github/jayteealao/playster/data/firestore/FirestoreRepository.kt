package com.github.jayteealao.playster.data.firestore

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FirestoreRepository"

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
}
