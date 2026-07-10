package com.github.jayteealao.playster.screens.videoDetail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.VideoDoc
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val TAG = "VideoDetailViewModel"

@HiltViewModel
class VideoDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        firestoreRepository: FirestoreRepository,
    ) : ViewModel() {
        val videoId: String = savedStateHandle["videoId"] ?: ""
        val autoDispatch: Boolean = savedStateHandle["autoDispatch"] ?: false

        val video: StateFlow<VideoDoc?> =
            firestoreRepository
                .videoFlow(videoId)
                .catch { err ->
                    // A Firestore listener error (e.g. PERMISSION_DENIED) closes the
                    // flow with the exception; without this guard it would propagate
                    // to viewModelScope and crash the app. Degrade to "no video".
                    Log.w(TAG, "videoFlow closed with error for $videoId", err)
                    emit(null)
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )
    }
