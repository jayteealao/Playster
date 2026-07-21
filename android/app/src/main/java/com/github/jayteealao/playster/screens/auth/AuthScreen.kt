package com.github.jayteealao.playster.screens.auth

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.Dateline
import com.github.jayteealao.playster.ui.editorial.components.Deck
import com.github.jayteealao.playster.ui.editorial.components.DisplayTitle
import com.github.jayteealao.playster.ui.editorial.components.EditorialErrorNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialLoadingNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialRule
import com.github.jayteealao.playster.ui.editorial.components.EditorialTextAction
import com.github.jayteealao.playster.ui.editorial.components.Folio
import com.github.jayteealao.playster.ui.editorial.components.Kicker
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch

private const val TAG = "AuthScreen"
private const val SERVER_CLIENT_ID =
    "510333739373-ust5kheckkg2oiuoghp08l5ghm1fsmat.apps.googleusercontent.com" // gitleaks:allow

/**
 * The sign-in surface — a publication cover page (Probe A, "Front page"): a
 * masthead high on the page, a single Google sign-in offered as a
 * rule-bounded editorial action in the lower third, and the folio at the
 * foot. It composes only existing editorial primitives; the credential flow
 * lives here in the UI layer because the Credential Manager sheet needs the
 * Activity context. The graph-level session gate routes on the resulting
 * session flip — this screen navigates nowhere itself.
 */
@Composable
fun AuthScreen(authViewModel: AuthViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by authViewModel.uiState.collectAsStateWithLifecycle()

    fun startCredentialSignIn() {
        authViewModel.onSignInStarted()
        coroutineScope.launch {
            val credentialManager = CredentialManager.create(context)
            try {
                // Attempt 1 (returning users): only previously-authorized accounts,
                // auto-selecting a single match — the documented Sign in with Google
                // pattern for the fastest returning-user path.
                val response = requestGoogleCredential(credentialManager, context, filterByAuthorized = true)
                handleCredentialResponse(response, authViewModel)
            } catch (e: NoCredentialException) {
                Log.i(TAG, "No authorized account; retrying with all accounts", e)
                try {
                    // Attempt 2 (first sign-in): offer every Google account on device.
                    val response = requestGoogleCredential(credentialManager, context, filterByAuthorized = false)
                    handleCredentialResponse(response, authViewModel)
                } catch (retry: NoCredentialException) {
                    Log.w(TAG, "No Google credential available", retry)
                    authViewModel.onSignInFailed(FailureKind.NoCredential)
                } catch (retry: GetCredentialException) {
                    handleCredentialException(retry, authViewModel)
                }
            } catch (e: GetCredentialException) {
                handleCredentialException(e, authViewModel)
            }
        }
    }

    AuthCoverPage(state = uiState, onSignIn = ::startCredentialSignIn)
}

private suspend fun requestGoogleCredential(
    credentialManager: CredentialManager,
    context: Context,
    filterByAuthorized: Boolean,
): GetCredentialResponse {
    val option =
        GetGoogleIdOption.Builder()
            .setServerClientId(SERVER_CLIENT_ID)
            .setFilterByAuthorizedAccounts(filterByAuthorized)
            .setAutoSelectEnabled(filterByAuthorized)
            .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    return credentialManager.getCredential(request = request, context = context)
}

private fun handleCredentialResponse(
    response: GetCredentialResponse,
    authViewModel: AuthViewModel,
) {
    val credential = response.credential
    if (credential is CustomCredential &&
        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        try {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            authViewModel.onGoogleIdToken(googleIdTokenCredential.idToken)
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Received an invalid Google ID token response", e)
            authViewModel.onSignInFailed(FailureKind.Unexpected)
        }
    } else {
        Log.e(TAG, "Unexpected credential type: ${credential.type}")
        authViewModel.onSignInFailed(FailureKind.Unexpected)
    }
}

private fun handleCredentialException(
    e: GetCredentialException,
    authViewModel: AuthViewModel,
) {
    when (e) {
        is GetCredentialCancellationException -> authViewModel.onSignInCancelled()
        is GetCredentialInterruptedException -> {
            Log.w(TAG, "Credential sign-in interrupted", e)
            authViewModel.onSignInFailed(FailureKind.Network)
        }
        else -> {
            Log.e(TAG, "Credential Manager sign-in failed", e)
            authViewModel.onSignInFailed(FailureKind.Unexpected)
        }
    }
}

/**
 * The stateless cover page — the Roborazzi subject. Every element is an
 * existing editorial primitive; the composition is Probe A ("Front page"),
 * with the sign-in affordance, the loading line, and the error notice
 * occupying the same lower-third slot across the three states.
 */
@Composable
fun AuthCoverPage(
    state: AuthUiState,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(tokens.palette.paper)
                .testTag("auth-cover"),
    ) {
        // Masthead, high on the page — the Home spread idiom.
        Column(modifier = Modifier.padding(start = 22.dp, top = 96.dp, end = 22.dp)) {
            Kicker(text = "Playster · A YouTube Reader")
            Spacer(Modifier.height(10.dp))
            DisplayTitle(text = coverTitle(), sizeSp = COVER_TITLE_SP)
            Spacer(Modifier.height(10.dp))
            Deck(
                text = "Talks and essays from your YouTube, set in type you can underline and come back to.",
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // Lower-third slot: the sign-in affordance, or the state that replaced it.
        Column(modifier = Modifier.padding(horizontal = 22.dp)) {
            when (state) {
                AuthUiState.Idle -> {
                    EditorialRule()
                    EditorialTextAction(
                        text = "Continue with Google",
                        onClick = onSignIn,
                        icon = EditorialIcons.Next,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 11.dp)
                                .testTag("auth-action"),
                    )
                    EditorialRule(color = tokens.palette.ruleFaint)
                }
                AuthUiState.Authenticating ->
                    EditorialLoadingNotice(label = "Opening your library…")
                is AuthUiState.Failed ->
                    EditorialErrorNotice(
                        message = failureMessage(state.kind),
                        actionLabel = "Try again",
                        onAction = onSignIn,
                        modifier = Modifier.testTag("auth-action"),
                    )
            }
            Spacer(Modifier.height(10.dp))
            Dateline(text = "One account — your library, your progress, your margins.")
        }

        Spacer(Modifier.height(56.dp))
        Folio(left = "Playster", right = "Est. 2026 · V 2.1")
        Spacer(Modifier.height(10.dp))
    }
}

private const val COVER_TITLE_SP = 32.0

/** "The reading" / italic "room." — the masthead title, exactly as Probe A sets it. */
private fun coverTitle(): AnnotatedString =
    buildAnnotatedString {
        append("The reading\n")
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("room.") }
    }

/** Specific, actionable error copy per failure kind — the gap-state contract. */
private fun failureMessage(kind: FailureKind): String =
    when (kind) {
        FailureKind.NoCredential ->
            "No Google account was available to sign in with. Add one to your device, then try again."
        FailureKind.Network ->
            "We couldn't reach Google to sign you in. Check your connection and try again."
        FailureKind.Bridge ->
            "Google signed you in, but we couldn't open your library. Try again in a moment."
        FailureKind.Unexpected ->
            "Something interrupted the sign-in. Try again."
    }
