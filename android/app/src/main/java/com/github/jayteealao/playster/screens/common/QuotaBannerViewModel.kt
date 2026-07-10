package com.github.jayteealao.playster.screens.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.firestore.QuotaDoc
import com.github.jayteealao.playster.data.firestore.QuotaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class QuotaBannerViewModel
    @Inject
    constructor(
        quotaRepository: QuotaRepository,
    ) : ViewModel() {
        val quotaDoc: StateFlow<QuotaDoc?> =
            quotaRepository.observe()
                .catch {
                    // listener error — treat as no quota doc; banner stays hidden
                    emit(null)
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )
    }
