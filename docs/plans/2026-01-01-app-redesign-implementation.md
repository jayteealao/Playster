# Playster App Redesign Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Redesign all Playster screens with a bold, playful visual style featuring electric blue/cyan accents on a light background.

**Architecture:** Update theme layer (colors, typography, shapes), then redesign each screen (Loading, Auth, Playlist) and the PlayCard component. Keep all existing auth logic intact.

**Tech Stack:** Jetpack Compose, Material 3, Kotlin

---

## Task 1: Update Color System

**Files:**
- Modify: `app/src/main/java/com/github/jayteealao/playster/ui/theme/Color.kt`

**Step 1: Replace Color.kt with new color tokens**

```kotlin
package com.github.jayteealao.playster.ui.theme

import androidx.compose.ui.graphics.Color

// Primary Palette
val Cyan500 = Color(0xFF00B8D9)
val Cyan600 = Color(0xFF0095B3)
val Purple500 = Color(0xFF7B61FF)

// Backgrounds
val White = Color(0xFFFFFFFF)
val Gray50 = Color(0xFFF8FAFC)
val Gray100 = Color(0xFFF1F5F9)
val Gray200 = Color(0xFFE2E8F0)

// Text
val Gray400 = Color(0xFF94A3B8)
val Gray600 = Color(0xFF475569)
val Gray900 = Color(0xFF0F172A)

// Semantic
val Success = Color(0xFF10B981)
val Error = Color(0xFFEF4444)
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/github/jayteealao/playster/ui/theme/Color.kt
git commit -m "feat(theme): update color palette with cyan/purple scheme"
```

---

## Task 2: Update Typography

**Files:**
- Modify: `app/src/main/java/com/github/jayteealao/playster/ui/theme/Type.kt`

**Step 1: Replace Type.kt with full typography scale**

```kotlin
package com.github.jayteealao.playster.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/github/jayteealao/playster/ui/theme/Type.kt
git commit -m "feat(theme): add complete typography scale"
```

---

## Task 3: Create Shape System

**Files:**
- Create: `app/src/main/java/com/github/jayteealao/playster/ui/theme/Shape.kt`

**Step 1: Create Shape.kt with corner radius tokens**

```kotlin
package com.github.jayteealao.playster.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/github/jayteealao/playster/ui/theme/Shape.kt
git commit -m "feat(theme): add shape system with rounded corners"
```

---

## Task 4: Update Theme

**Files:**
- Modify: `app/src/main/java/com/github/jayteealao/playster/ui/theme/Theme.kt`

**Step 1: Replace Theme.kt with new light-mode-first theme**

```kotlin
package com.github.jayteealao.playster.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Cyan500,
    onPrimary = White,
    primaryContainer = Gray100,
    onPrimaryContainer = Gray900,
    secondary = Purple500,
    onSecondary = White,
    background = White,
    onBackground = Gray900,
    surface = Gray50,
    onSurface = Gray900,
    surfaceVariant = Gray100,
    onSurfaceVariant = Gray600,
    outline = Gray200,
    error = Error,
    onError = White
)

private val DarkColorScheme = darkColorScheme(
    primary = Cyan500,
    onPrimary = Gray900,
    primaryContainer = Cyan600,
    onPrimaryContainer = White,
    secondary = Purple500,
    onSecondary = White,
    background = Gray900,
    onBackground = White,
    surface = Color(0xFF1B2838),
    onSurface = White,
    surfaceVariant = Color(0xFF253447),
    onSurfaceVariant = Gray400,
    outline = Color(0xFF253447),
    error = Error,
    onError = White
)

@Composable
fun PlaysterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/github/jayteealao/playster/ui/theme/Theme.kt
git commit -m "feat(theme): configure light-mode-first theme with new colors"
```

---

## Task 5: Redesign Loading Screen

**Files:**
- Modify: `app/src/main/java/com/github/jayteealao/playster/screens/LoadingScreen.kt`

**Step 1: Replace LoadingScreen with branded splash with pulsing dots**

```kotlin
package com.github.jayteealao.playster.screens

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.theme.Cyan500
import com.github.jayteealao.playster.ui.theme.Gray100
import kotlinx.coroutines.delay

@Composable
fun LoadingScreen(loggedIn: Boolean, navigator: (String) -> Unit = {}) {

    LaunchedEffect(loggedIn) {
        Log.d("Loading Screen", "navigation event - $loggedIn")
        delay(2000L)
        if (loggedIn) {
            navigator("list")
        } else {
            navigator("onboard")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(Gray100, MaterialTheme.colorScheme.background),
                    startY = 0f,
                    endY = 400f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Playster",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(32.dp))

            PulsingDots()
        }
    }
}

@Composable
private fun PulsingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 200)
                ),
                label = "dot_$index"
            )

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(scale)
                    .background(Cyan500, CircleShape)
            )
        }
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/github/jayteealao/playster/screens/LoadingScreen.kt
git commit -m "feat(loading): redesign with branded splash and pulsing dots"
```

---

## Task 6: Redesign Auth Screen

**Files:**
- Modify: `app/src/main/java/com/github/jayteealao/playster/screens/auth/AuthScreen.kt`

**Step 1: Replace AuthScreen UI with gradient background and single Google button**

Keep all the existing auth logic (oneTapClient, legacySignIn, credentialSignIn, process functions, launchers) but replace the Column UI at the bottom with:

```kotlin
@Composable
fun AuthScreen(
    authViewModel: AuthViewModel = hiltViewModel(),
    onSignIn: () -> Unit = {}
) {

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Keep all existing auth setup code (oneTapClient, legacySignIn, etc.)
    // ... (lines 69-229 stay the same)

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

    // NEW UI
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
                    // Google icon placeholder (you can replace with actual icon)
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
```

**Step 2: Add required imports at top of file**

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.github.jayteealao.playster.ui.theme.Cyan500
import com.github.jayteealao.playster.ui.theme.Purple500
import com.github.jayteealao.playster.ui.theme.Gray900
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/github/jayteealao/playster/screens/auth/AuthScreen.kt
git commit -m "feat(auth): redesign with gradient background and single Google button"
```

---

## Task 7: Redesign PlayCard Component

**Files:**
- Modify: `app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlayCard.kt`

**Step 1: Replace PlayCard with horizontal card layout**

```kotlin
package com.github.jayteealao.playster.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.github.jayteealao.playster.ui.theme.Gray50
import com.google.api.services.youtube.model.Playlist

@Composable
fun PlayCard(
    modifier: Modifier = Modifier,
    playlist: Playlist
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Gray50),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            AsyncImage(
                model = playlist.snippet.thumbnails.medium?.url
                    ?: playlist.snippet.thumbnails.default?.url,
                contentDescription = "playlist image",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = playlist.snippet.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${playlist.snippet.channelTitle} · ${playlist.contentDetails.itemCount} videos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlayCard.kt
git commit -m "feat(playcard): redesign with horizontal layout"
```

---

## Task 8: Redesign Playlist Screen

**Files:**
- Modify: `app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlaylistScreen.kt`

**Step 1: Replace PlaylistScreen with header and styled list**

```kotlin
package com.github.jayteealao.playster.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.github.jayteealao.playster.screens.auth.AuthViewModel
import com.github.jayteealao.playster.ui.theme.Cyan500
import com.github.jayteealao.playster.ui.theme.Gray400
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlaylistScreen(authViewModel: AuthViewModel = hiltViewModel()) {

    val service = YouTube.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        authViewModel.userLogin.value.first
    )
        .setApplicationName("Playster")
        .build()

    val displayItems = remember {
        mutableStateListOf<Playlist>()
    }

    LaunchedEffect(true) {
        withContext(Dispatchers.IO) {
            val items = service.playlists().list(listOf("snippet", "contentDetails"))
                .setMaxResults(50L)
                .setMine(true)
                .execute().items
            displayItems.addAll(items)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Playster",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Avatar placeholder
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Cyan500),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "U",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp)
        ) {
            Text(
                text = "Your Playlists",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${displayItems.size} playlists",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Playlist List
        if (displayItems.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No playlists yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your YouTube playlists will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = displayItems.toList(),
                    key = { it.id }
                ) { playlist ->
                    PlayCard(
                        modifier = Modifier.fillMaxWidth(),
                        playlist = playlist
                    )
                }
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlaylistScreen.kt
git commit -m "feat(playlist): redesign with header and styled list"
```

---

## Task 9: Final Verification

**Step 1: Build the app to verify no compile errors**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Commit any fixes if needed**

**Step 3: Create summary commit**

```bash
git add -A
git commit -m "feat: complete app redesign with bold/playful theme"
```
