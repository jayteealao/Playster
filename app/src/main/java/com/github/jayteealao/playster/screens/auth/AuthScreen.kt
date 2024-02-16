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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
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

    var email by remember {
        mutableStateOf("")
    }

    var password by remember {
        mutableStateOf("")
    }

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
        // Handle the successfully returned credential.
        val credential = result.credential

        when (credential) {
            is PublicKeyCredential -> {
                // Share responseJson such as a GetCredentialResponse on your server to
                // validate and authenticate
//                responseJson = credential.authenticationResponseJson
            }

            is PasswordCredential -> {
                // Send ID and password to your server to validate and authenticate.
                val username = credential.id
                val password = credential.password
            }

            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        // Use googleIdTokenCredential and extract id to validate and
                        // authenticate on your server.
                        val googleIdTokenCredential = GoogleIdTokenCredential
                            .createFrom(credential.data)
                        authViewModel.saveLoginSuccess(context, Account(googleIdTokenCredential.id, context.packageName))
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(TAG, "Received an invalid google id token response", e)
                    }
                } else {
                    // Catch any unrecognized custom credential type here.
                    Log.e(TAG, "Unexpected type of credential")
                }
            }

            else -> {
                // Catch any unrecognized credential type here.
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
//                handleFailure(e)
                authViewModel.saveLoginFailure(e)
                Log.d(TAG, "Failure to sign in")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Sign in/Sign up")
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = email,
            onValueChange = { email = it },
            maxLines = 1,
            label = {
                Text(text = "Email")
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = password,
            onValueChange = { password = it },
            maxLines = 1,
            label = {
                Text(text = "Password")
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = {
            startOneTapSignIn()
        }) {
            Text(text = "One Tap Sign In")
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = {
                startLegacySignIn()
            }
        ) {
            Text(text = "Legacy Sign In")
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = {
                startCredentialSignIn()
            }
        ) {
            Text(text = "Credential Sign In")
        }

}
}

private const val TAG = "AuthScreen"