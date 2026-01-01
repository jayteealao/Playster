package com.github.jayteealao.playster.screens.auth

import android.accounts.Account
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import com.github.jayteealao.playster.ui.theme.Cyan500
import com.github.jayteealao.playster.ui.theme.Gray900
import com.github.jayteealao.playster.ui.theme.Purple500
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInCredential
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.api.services.youtube.YouTubeScopes
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    authViewModel: AuthViewModel = hiltViewModel(),
    onSignIn: () -> Unit = {}
) {

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val oneTapClient = Identity.getSignInClient(context)

    val signInRequestOneTap = BeginSignInRequest.builder()
        .setGoogleIdTokenRequestOptions(
            BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                .setSupported(true)
                .setServerClientId("510333739373-ust5kheckkg2oiuoghp08l5ghm1fsmat.apps.googleusercontent.com")
                .setFilterByAuthorizedAccounts(false)
                .build()
        )
        .setAutoSelectEnabled(true)
        .build()

    val legacySignIn = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(YouTubeScopes.YOUTUBE_READONLY))
            .build()
    )

    val credentialSignIn = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId("510333739373-ust5kheckkg2oiuoghp08l5ghm1fsmat.apps.googleusercontent.com")
        .build()


    fun processLegacySignIn(data: Intent?) {
        if (data == null) return
        GoogleSignIn.getSignedInAccountFromIntent(data)
            .addOnSuccessListener { googleAccount: GoogleSignInAccount ->
                Log.d(TAG, "Signed in as " + googleAccount.email)
                val account = googleAccount.account
                if (account == null) {
                    authViewModel.saveLoginFailure(null)
                    Log.d(TAG, "Failure to sign in")
                } else {
                    authViewModel.saveLoginSuccess(context, account)
                    Log.d(TAG, "Signed in as " + googleAccount.email)
                    onSignIn()
                }
            }
            .addOnFailureListener { exception: Exception? ->
                Log.e(TAG, "Unable to sign in.", exception)
                authViewModel.saveLoginFailure(exception)
            }
    }

    fun processOneTapSignIn(data: Intent?) {
        try {
            val oneTapCredential: SignInCredential =
                oneTapClient.getSignInCredentialFromIntent(data)
            Log.d(TAG, "Signed in as " + oneTapCredential.displayName)
            authViewModel.saveLoginSuccess(context, Account(oneTapCredential.id, context.packageName))
            onSignIn()
        } catch (e: ApiException) {
            Log.e(TAG, "Credentials API error", e)
            authViewModel.saveLoginFailure(e)
        }
    }

    fun processCredentialSignIn(result: GetCredentialResponse) {
        val credential = result.credential

        when (credential) {
            is PublicKeyCredential -> { }
            is PasswordCredential -> {
                val username = credential.id
                val password = credential.password
            }
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential
                            .createFrom(credential.data)
                        authViewModel.saveLoginSuccess(context, Account(googleIdTokenCredential.id, context.packageName))
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(TAG, "Received an invalid google id token response", e)
                    }
                } else {
                    Log.e(TAG, "Unexpected type of credential")
                }
            }
            else -> {
                Log.e(TAG, "Unexpected type of credential")
            }
        }
    }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        processLegacySignIn(it.data)
    }

    val oneTapLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        processOneTapSignIn(it.data)
    }

    fun startLegacySignIn() {
        legacyLauncher.launch(legacySignIn.signInIntent)
    }

    fun startOneTapSignIn() {
        oneTapClient.beginSignIn(signInRequestOneTap).addOnSuccessListener(context as Activity) { result ->
            try {
                oneTapLauncher.launch(
                    IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                )
            } catch (e: IntentSender.SendIntentException) {
                Log.e(TAG, "Couldn't start One Tap UI: ${e.localizedMessage}")
                authViewModel.saveLoginFailure(e)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Couldn't start One Tap UI: ${e.localizedMessage}")
                authViewModel.saveLoginFailure(e)
            }
            Log.d(TAG, "SignIn started")
        }.addOnFailureListener(context) { e ->
            Log.d(TAG, "SignIn failed", e)
            authViewModel.saveLoginFailure(e)
        }
    }

    fun startCredentialSignIn() {
        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(credentialSignIn)
            .build()
        val credentialManager = CredentialManager.create(context)

        coroutineScope.launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = context,
                )
                processCredentialSignIn(result)
                onSignIn()
            } catch (e: GetCredentialException) {
                authViewModel.saveLoginFailure(e)
                Log.d(TAG, "Failure to sign in")
            }
        }
    }

    // New UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Cyan500, Purple500),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.35f))

            // Logo placeholder
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "P",
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Playster",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your YouTube, organized",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f)
            )

            Spacer(modifier = Modifier.weight(0.35f))

            // Google Sign In Button
            Button(
                onClick = { startLegacySignIn() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Gray900
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "G",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4285F4)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Sign in with Google",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Footer links
            Row(
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Terms",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = "Privacy",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

private const val TAG = "AuthScreen"
