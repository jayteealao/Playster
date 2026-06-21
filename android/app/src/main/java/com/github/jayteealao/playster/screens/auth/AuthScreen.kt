package com.github.jayteealao.playster.screens.auth

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import com.github.jayteealao.playster.ui.theme.Cyan500
import com.github.jayteealao.playster.ui.theme.Gray900
import com.github.jayteealao.playster.ui.theme.Purple500
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch

private const val TAG = "AuthScreen"
private const val SERVER_CLIENT_ID =
    "510333739373-ust5kheckkg2oiuoghp08l5ghm1fsmat.apps.googleusercontent.com"

@Composable
fun AuthScreen(
    authViewModel: AuthViewModel = hiltViewModel(),
    onSignIn: () -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val googleIdOption =
        GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(SERVER_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .build()

    fun handleGoogleIdToken(idToken: String) {
        authViewModel.bridgeToFirebase(idToken) { uid ->
            if (uid != null) {
                onSignIn()
            } else {
                Log.w(TAG, "Firebase Auth bridge failed", authViewModel.lastError)
            }
        }
    }

    fun processCredentialSignIn(result: GetCredentialResponse) {
        when (val credential = result.credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        handleGoogleIdToken(googleIdTokenCredential.idToken)
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(TAG, "Received an invalid google id token response", e)
                        authViewModel.saveLoginFailure(e)
                    }
                } else {
                    Log.e(TAG, "Unexpected credential type: ${credential.type}")
                    authViewModel.saveLoginFailure(null)
                }
            }
            else -> {
                Log.e(TAG, "Unexpected credential class: ${credential.javaClass.name}")
                authViewModel.saveLoginFailure(null)
            }
        }
    }

    fun startCredentialSignIn() {
        val request =
            GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
        val credentialManager = CredentialManager.create(context)

        coroutineScope.launch {
            try {
                val result =
                    credentialManager.getCredential(
                        request = request,
                        context = context,
                    )
                processCredentialSignIn(result)
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Credential Manager sign-in failed", e)
                authViewModel.saveLoginFailure(e)
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    brush =
                        Brush.linearGradient(
                            colors = listOf(Cyan500, Purple500),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                        ),
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(0.35f))

            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "P",
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Playster",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your YouTube, organized",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
            )

            Spacer(modifier = Modifier.weight(0.35f))

            Button(
                onClick = { startCredentialSignIn() },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Gray900,
                    ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "G",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4285F4),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Sign in with Google",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.Center) {
                Text(
                    text = "Terms",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Text(
                    text = "Privacy",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
