package com.github.jayteealao.playster.screens.auth

import android.accounts.Account
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.SettingsManager
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.youtube.YouTubeScopes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject


@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager
) : ViewModel() {

    // Pair(Account, Error)
    val userLogin = mutableStateOf(Pair<GoogleAccountCredential?, Exception?>(null, null))

    val loggedIn = mutableStateOf(false)

    fun saveLoginSuccess(context: Context, account: Account) {
        // Use the authenticated account to sign in to the Drive service.
        userLogin.value = Pair(
            GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(YouTubeScopes.YOUTUBE_READONLY)
            ).setSelectedAccount(account), null
        )
        loggedIn.value = true
        Log.d("saveLoginSuccess", "account saved")
        viewModelScope.launch {
            settingsManager.saveAccount(account)
        }
    }

    fun saveLoginFailure(exception: Exception? = null) {
        userLogin.value = Pair(null, exception)
    }

    private suspend fun setUserLogin() {
        Log.d("init block - set user", "in init block")
        val account = settingsManager.getAccount().collect {
            if (it != null) {
                Log.d("init block - set user", "account found")
                if (userLogin.value.first == null && it.name != null && it.type != null ) {
                    loggedIn.value = true
                    userLogin.value = Pair(
                        GoogleAccountCredential.usingOAuth2(
                            context, Collections.singleton(YouTubeScopes.YOUTUBE_READONLY)
                        ).setSelectedAccount(it), null
                    )
                    Log.d("init block - set user", "user login set")
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            Log.d("init block", "running init block")
            setUserLogin()
        }
    }


}