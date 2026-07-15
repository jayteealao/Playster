package com.github.jayteealao.playster.data.firestore

import android.util.Log
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
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

        /**
         * Create side (Player slice): write a timestamped note into
         * `users/{uid}/notes/{autoId}`, stamping [t] with the playback position
         * at the moment the reader saved it. Conforms to `isValidNote`
         * (videoId + playlistId strings, non-negative `t`, text ≤ 5000 chars,
         * `createdAt`/`updatedAt` server timestamps). The read side is untouched,
         * so a created note flows straight back into both Notes tabs (Player and
         * Playlist) via the existing listener. Blank text is dropped; overlong
         * text is trimmed to the rules ceiling rather than being rejected.
         */
        suspend fun createNote(
            videoId: String,
            playlistId: String,
            t: Double,
            text: String,
        ) {
            val uid = authBridge.currentUid.value?.takeIf { it.isNotBlank() } ?: return
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return
            val data =
                mapOf(
                    "videoId" to videoId,
                    "playlistId" to playlistId,
                    "t" to t.coerceAtLeast(0.0),
                    "text" to trimmed.take(MAX_NOTE_CHARS),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            firestore
                .collection("users")
                .document(uid)
                .collection("notes")
                .add(data)
                .await()
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

        private companion object {
            /** The `isValidNote` text ceiling — a note is trimmed, never rejected, at this length. */
            const val MAX_NOTE_CHARS = 5_000
        }
    }
