package com.github.jayteealao.playster.data.firestore

import android.util.Log
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "playster.notes"

/**
 * Read side of the reading-notes collection (`users/{uid}/notes`), the Playlist
 * slice's hybrid-placement deliverable: the Notes tab is the first consumer, so
 * it builds the repository. Note *creation* arrives with the player/transcript
 * slices; this repository is read-only.
 *
 * Runs exactly the indexed query the backend doc-comment names — filter by
 * `playlistId`, order by `createdAt` desc — backed by the deployed
 * `notes(playlistId ASC, createdAt DESC)` composite index. The flow mirrors
 * [FirestoreRepository]'s snapshot-listener idiom (a listener error closes the
 * flow with the exception so a collector sees a terminal error rather than a
 * frozen screen). A missing uid short-circuits to an empty emission — the
 * signed-out window before the session gate redirects.
 */
@Singleton
class NotesRepository
    @Inject
    constructor(
        private val firestore: FirebaseFirestore,
        private val authBridge: FirebaseAuthBridge,
    ) {
        /** Notes for one playlist, newest first — the Notes-tab query. */
        fun notesByPlaylistFlow(playlistId: String): Flow<List<NoteDoc>> {
            val uid = authBridge.currentUid.value?.takeIf { it.isNotBlank() }
            if (uid == null || playlistId.isBlank()) return flowOf(emptyList())
            return firestore
                .collection("users")
                .document(uid)
                .collection("notes")
                .whereEqualTo("playlistId", playlistId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .notesFlow()
        }

        private fun Query.notesFlow(): Flow<List<NoteDoc>> =
            callbackFlow {
                val listener =
                    addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "notesByPlaylistFlow listen error", error)
                            close(error)
                            return@addSnapshotListener
                        }
                        trySend(snapshot?.documents?.mapNotNull { it.toNoteDoc() } ?: emptyList())
                    }
                awaitClose { listener.remove() }
            }
    }
