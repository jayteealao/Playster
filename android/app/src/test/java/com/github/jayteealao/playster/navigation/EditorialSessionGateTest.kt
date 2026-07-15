package com.github.jayteealao.playster.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import com.github.jayteealao.playster.screens.auth.AuthCoverPage
import com.github.jayteealao.playster.screens.auth.AuthUiState
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AC: a signed-out session always lands on the auth route — at cold start,
 * over a cleared stack; and a mid-session sign-out redirects from anywhere.
 * Sign-in from auth lands on home with auth gone from the stack.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class EditorialSessionGateTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: TestNavHostController
    private lateinit var loggedIn: MutableState<Boolean>

    @Test
    fun signedOutStart_landsOnAuthOverClearedStack() {
        setGraph(initiallyLoggedIn = false)

        assertEquals(EditorialRoutes.AUTH, currentRoute())
        assertEquals(0, routeCount(EditorialRoutes.HOME))
        // The real auth cover — not the old skeleton — renders on the route.
        composeTestRule.onNodeWithTag("auth-cover").assertIsDisplayed()
    }

    @Test
    fun signInFromAuth_landsOnHomeWithAuthCleared() {
        setGraph(initiallyLoggedIn = false)
        assertEquals(EditorialRoutes.AUTH, currentRoute())
        composeTestRule.onNodeWithTag("auth-cover").assertIsDisplayed()

        setLoggedIn(true)

        assertEquals(EditorialRoutes.HOME, currentRoute())
        assertEquals(0, routeCount(EditorialRoutes.AUTH))
    }

    @Test
    fun midSessionSignOut_redirectsToAuthOverClearedStack() {
        setGraph(initiallyLoggedIn = true)
        composeTestRule.runOnUiThread { navController.navigate(EditorialRoutes.SEARCH) }
        composeTestRule.waitForIdle()
        assertEquals(EditorialRoutes.SEARCH, currentRoute())

        setLoggedIn(false)

        assertEquals(EditorialRoutes.AUTH, currentRoute())
        assertEquals(0, routeCount(EditorialRoutes.SEARCH))
        assertEquals(0, routeCount(EditorialRoutes.HOME))
    }

    private fun setGraph(initiallyLoggedIn: Boolean) {
        composeTestRule.setContent {
            val context = LocalContext.current
            loggedIn = remember { mutableStateOf(initiallyLoggedIn) }
            navController =
                remember {
                    TestNavHostController(context).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            EditorialTheme {
                EditorialNavGraph(
                    navController = navController,
                    loggedIn = loggedIn.value,
                    // Hilt-free stand-in for the real screen so the gate is JVM-testable;
                    // it renders the same `auth-cover` surface the production screen does.
                    authContent = { AuthCoverPage(state = AuthUiState.Idle, onSignIn = {}) },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun setLoggedIn(value: Boolean) {
        composeTestRule.runOnUiThread { loggedIn.value = value }
        composeTestRule.waitForIdle()
    }

    private fun currentRoute(): String? = navController.currentBackStackEntry?.destination?.route

    private fun routeCount(route: String): Int = navController.backStack.count { it.destination.route == route }
}
