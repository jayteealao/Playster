package com.github.jayteealao.playster.data.firestore

import android.util.Log
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
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

private const val TAG = "playster.highlights"
private const val HEX_RADIX = 16

/**
 * Read+write side of the reading-highlights collection
 * (`users/{uid}/highlights`) — the Transcript slice's identity feature, and the
 * first Android consumer of the collection, so it builds the repository. The
 * write feeds Settings' "highlights this week" stat (Settings reads later; this
 * slice only writes).
 *
 * Runs exactly the indexed query the backend deployed — filter by `videoId`,
 * order by `segmentStart` asc, backed by the `highlights(videoId ASC,
 * segmentStart ASC)` composite index. The flow mirrors [NotesRepository]'s
 * snapshot-listener idiom (a listener error closes the flow so a collector sees
 * a terminal error rather than a frozen screen). A missing uid short-circuits to
 * an empty emission — the signed-out window before the session gate redirects.
 *
 * A highlight rides a **deterministic doc-id** (`${videoId}_${segmentStartMillis}`)
 * so toggling is an idempotent set/delete on a known id — no read-then-write
 * race, no duplicate underline for the same line. The persisted shape is
 * identical to the backend's illustrative `{autoId}`; only the id is chosen
 * (`isValidHighlight` is id-agnostic — owner + field validation only).
 */
@Singleton
class HighlightsRepository
    @Inject
    constructor(
        private val firestore: FirebaseFirestore,
        private val authBridge: FirebaseAuthBridge,
    ) {
        /** Highlights for one video, earliest segment first — the transcript merge query. */
        fun highlightsByVideoFlow(videoId: String): Flow<List<HighlightDoc>> {
            val uid = authBridge.currentUid.value?.takeIf { it.isNotBlank() }
            if (uid == null || videoId.isBlank()) return flowOf(emptyList())
            return firestore
                .collection("users")
                .document(uid)
                .collection("highlights")
                .whereEqualTo("videoId", videoId)
                .orderBy("segmentStart", Query.Direction.ASCENDING)
                .highlightsFlow()
        }

        /**
         * Add (underline) the line anchored at [segmentStart]. Idempotent: the
         * deterministic id means re-adding the same line overwrites rather than
         * duplicates. Conforms to `isValidHighlight` (videoId, non-negative
         * `segmentStart`, `text` ≤ 5000 chars, `createdAt` server timestamp).
         * Blank text is still written (a highlight is the mark, not the words) but
         * overlong text is trimmed to the rules ceiling rather than rejected.
         *
         * A write failure loses the underline; mirrored to logcat and Crashlytics
         * with the hashed videoId (never a crash), the same dark-path posture the
         * progress/notes writes take.
         */
        suspend fun addHighlight(
            videoId: String,
            segmentStart: Double,
            text: String,
        ) {
            if (videoId.isBlank()) return
            val uid = authBridge.currentUid.value?.takeIf { it.isNotBlank() } ?: return
            val data =
                mapOf(
                    "videoId" to videoId,
                    "segmentStart" to segmentStart.coerceAtLeast(0.0),
                    "text" to text.trim().take(MAX_HIGHLIGHT_CHARS),
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            runWrite(videoId, "add") {
                firestore
                    .collection("users")
                    .document(uid)
                    .collection("highlights")
                    .document(highlightId(videoId, segmentStart))
                    .set(data)
                    .await()
            }
        }

        /** Remove the underline anchored at [segmentStart] (the toggle-off side). */
        suspend fun removeHighlight(
            videoId: String,
            segmentStart: Double,
        ) {
            if (videoId.isBlank()) return
            val uid = authBridge.currentUid.value?.takeIf { it.isNotBlank() } ?: return
            runWrite(videoId, "delete") {
                firestore
                    .collection("users")
                    .document(uid)
                    .collection("highlights")
                    .document(highlightId(videoId, segmentStart))
                    .delete()
                    .await()
            }
        }

        private inline fun runWrite(
            videoId: String,
            op: String,
            block: () -> Unit,
        ) {
            try {
                block()
            } catch (e: Exception) {
                val vidHash = videoId.hashCode().toUInt().toString(HEX_RADIX)
                Log.w(TAG, "writeFailed{collection=highlights,op=$op,videoId=$vidHash}", e)
                Firebase.crashlytics.apply {
                    setCustomKey("collection", "highlights")
                    setCustomKey("op", op)
                    setCustomKey("video_id_hash", vidHash)
                    recordException(e)
                }
            }
        }

        private fun Query.highlightsFlow(): Flow<List<HighlightDoc>> =
            callbackFlow {
                val listener =
                    addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "highlightsByVideoFlow listen error", error)
                            close(error)
                            return@addSnapshotListener
                        }
                        trySend(snapshot?.documents?.mapNotNull { it.toHighlightDoc() } ?: emptyList())
                    }
                awaitClose { listener.remove() }
            }

        internal companion object {
            /** The `isValidHighlight` text ceiling — trimmed, never rejected, at this length. */
            const val MAX_HIGHLIGHT_CHARS = 5_000
            private const val MILLIS_PER_SECOND = 1_000.0

            /**
             * Deterministic doc-id keyed to the segment start so toggle is a known-id
             * set/delete. Millis granularity is unique within a transcript (segment
             * offsets are monotonic and never collide at ms resolution).
             */
            fun highlightId(
                videoId: String,
                segmentStart: Double,
            ): String {
                val millis = (segmentStart.coerceAtLeast(0.0) * MILLIS_PER_SECOND).toLong()
                return "${videoId}_$millis"
            }
        }
    }
