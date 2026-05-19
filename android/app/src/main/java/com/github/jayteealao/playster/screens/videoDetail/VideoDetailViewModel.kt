package com.github.jayteealao.playster.screens.videoDetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.VideoDoc
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class VideoDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    firestoreRepository: FirestoreRepository,
) : ViewModel() {

    val videoId: String = savedStateHandle["videoId"] ?: ""
    val autoDispatch: Boolean = savedStateHandle["autoDispatch"] ?: false

    val video: StateFlow<VideoDoc?> = firestoreRepository
        .videoFlow(videoId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )
}
