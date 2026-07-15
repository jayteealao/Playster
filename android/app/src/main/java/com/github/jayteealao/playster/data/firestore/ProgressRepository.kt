package com.github.jayteealao.playster.data.firestore

import android.util.Log
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "playster.progress"

/**
 * Read side of the reading-progress collection (`users/{uid}/progress`), the
 * Home slice's hybrid-placement deliverable: Home is the first consumer, so it
 * builds the repository. The Player writes these documents in a later slice;
 * until then Home reads the seeded verification corpus.
 *
 * Runs exactly the two indexed queries backend-schema deployed:
 *  - Q1 `kind == "playlist"` ordered by `lastOpenedAt` desc — the shelf order.
 *  - Q2 `kind == "video"` ordered by `updatedAt` desc, limit 1 — the continue
 *    headliner.
 * A third un-ordered `kind == "video"` read feeds per-playlist progress
 * aggregation. Each flow mirrors [FirestoreRepository]'s snapshot-listener
 * idiom (a listener error closes the flow with the exception so a collector
 * sees a terminal error rather than a frozen screen). A missing uid short-
 * circuits to an empty emission — the signed-out window before the session
 * gate redirects.
 */
@Singleton
class ProgressRepository
    @Inject
    constructor(
        private val firestore: FirebaseFirestore,
        private val authBridge: FirebaseAuthBridge,
    ) {
        private fun progressCollection(): CollectionReference? {
            val uid = authBridge.currentUid.value?.takeIf { it.isNotBlank() } ?: return null
            return firestore.collection("users").document(uid).collection("progress")
        }

        /** Q1: playlist-kind progress, newest last-opened first — the shelf order. */
        fun shelfProgressFlow(): Flow<List<ProgressDoc>> {
            val collection = progressCollection() ?: return flowOf(emptyList())
            return collection
                .whereEqualTo("kind", "playlist")
                .orderBy("lastOpenedAt", Query.Direction.DESCENDING)
                .progressListFlow("shelfProgressFlow")
        }

        /** Q2: the single most-recently-touched video-kind progress doc, or null. */
        fun continueVideoFlow(): Flow<ProgressDoc?> {
            val collection = progressCollection() ?: return flowOf(null)
            return callbackFlow {
                val listener =
                    collection
                        .whereEqualTo("kind", "video")
                        .orderBy("updatedAt", Query.Direction.DESCENDING)
                        .limit(1)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "continueVideoFlow listen error", error)
                                close(error)
                                return@addSnapshotListener
                            }
                            trySend(
                                snapshot?.documents?.firstOrNull()
                                    ?.toObject(ProgressDoc::class.java),
                            )
                        }
                awaitClose { listener.remove() }
            }
        }

        /** Every video-kind progress doc — grouped by playlist for shelf-row progress. */
        fun videoProgressFlow(): Flow<List<ProgressDoc>> {
            val collection = progressCollection() ?: return flowOf(emptyList())
            return collection
                .whereEqualTo("kind", "video")
                .progressListFlow("videoProgressFlow")
        }

        private fun Query.progressListFlow(logLabel: String): Flow<List<ProgressDoc>> =
            callbackFlow {
                val listener =
                    addSnapshotListener { snapshot: QuerySnapshot?, error ->
                        if (error != null) {
                            Log.w(TAG, "$logLabel listen error", error)
                            close(error)
                            return@addSnapshotListener
                        }
                        trySend(
                            snapshot?.documents
                                ?.mapNotNull { it.toObject(ProgressDoc::class.java) }
                                ?: emptyList(),
                        )
                    }
                awaitClose { listener.remove() }
            }
    }
