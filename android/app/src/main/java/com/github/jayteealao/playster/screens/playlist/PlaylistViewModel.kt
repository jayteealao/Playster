package com.github.jayteealao.playster.screens.playlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.google.firebase.functions.FirebaseFunctions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

private const val TAG = "PlaylistViewModel"

@HiltViewModel
class PlaylistViewModel
    @Inject
    constructor(
        private val firestoreRepository: FirestoreRepository,
        private val firebaseFunctions: FirebaseFunctions,
    ) : ViewModel() {
        val playlists: StateFlow<List<PlaylistDoc>> =
            firestoreRepository.playlistsFlow()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = emptyList(),
                )

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

        fun refresh() {
            if (_isRefreshing.value) return
            viewModelScope.launch {
                _isRefreshing.value = true
                try {
                    firebaseFunctions
                        .getHttpsCallable("syncAllPlaylists")
                        .call(emptyMap<String, Any>())
                        .await()
                } catch (e: Exception) {
                    Log.e(TAG, "syncAllPlaylists call failed", e)
                } finally {
                    _isRefreshing.value = false
                }
            }
        }
    }
