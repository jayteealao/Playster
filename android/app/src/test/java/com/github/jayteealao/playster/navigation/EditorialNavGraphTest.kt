package com.github.jayteealao.playster.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
 * AC: route definitions resolve and arguments round-trip — all seven routes
 * land on their skeletons, the parameterized routes carry their ids through
 * the back stack, bottom-nav roots never stack, and system back walks the
 * reading journey in order (transcript → player → playlist → home).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class EditorialNavGraphTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: TestNavHostController

    @Test
    fun allSevenRoutes_resolveToTheirSkeletons() {
        setSignedInGraph()
        // Home is a real screen now (not a skeleton); the graph hosts it behind
        // the Hilt-free homeContent slot, tagged here for the route-resolves check.
        composeTestRule.onNodeWithTag("home-content").assertExists()
        listOf(
            EditorialRoutes.playlist("PL_1") to "playlist-content",
            EditorialRoutes.player("VID_1") to "route-skeleton-player",
            EditorialRoutes.transcript("VID_1") to "route-skeleton-transcript",
            EditorialRoutes.SEARCH to "route-skeleton-search",
            EditorialRoutes.SETTINGS to "route-skeleton-settings",
            EditorialRoutes.AUTH to "auth-cover",
        ).forEach { (route, skeletonTag) ->
            navigate(route)
            composeTestRule.onNodeWithTag(skeletonTag).assertExists()
        }
    }

    @Test
    fun parameterizedRoutes_roundTripTheirIds() {
        setSignedInGraph()

        navigate(EditorialRoutes.playlist("PL_123"))
        assertEquals(EditorialRoutes.PLAYLIST, currentRoute())
        assertEquals("PL_123", currentArg(EditorialRoutes.ARG_PLAYLIST_ID))

        navigate(EditorialRoutes.player("VID_9"))
        assertEquals(EditorialRoutes.PLAYER, currentRoute())
        assertEquals("VID_9", currentArg(EditorialRoutes.ARG_VIDEO_ID))

        navigate(EditorialRoutes.transcript("VID_9"))
        assertEquals(EditorialRoutes.TRANSCRIPT, currentRoute())
        assertEquals("VID_9", currentArg(EditorialRoutes.ARG_VIDEO_ID))
    }

    @Test
    fun repeatedBottomNavRootTaps_doNotStack() {
        setSignedInGraph()

        navigateToRoot(EditorialRoutes.SEARCH)
        val sizeAfterFirstTap = navController.backStack.size
        navigateToRoot(EditorialRoutes.SEARCH)
        assertEquals(sizeAfterFirstTap, navController.backStack.size)
        assertEquals(1, navController.backStack.count { it.destination.route == EditorialRoutes.SEARCH })

        // Switching between section roots never accumulates entries: only the
        // start root and the active section sit on the stack (the inactive
        // section's state is saved off-stack for restoration).
        navigateToRoot(EditorialRoutes.SETTINGS)
        navigateToRoot(EditorialRoutes.SEARCH)
        navigateToRoot(EditorialRoutes.SETTINGS)
        assertEquals(1, navController.backStack.count { it.destination.route == EditorialRoutes.SETTINGS })
        assertEquals(0, navController.backStack.count { it.destination.route == EditorialRoutes.SEARCH })
        assertEquals(
            listOf(EditorialRoutes.HOME, EditorialRoutes.SETTINGS),
            navController.backStack.mapNotNull { it.destination.route },
        )

        // Back from a section root exits to the start root, not another copy.
        back()
        assertEquals(EditorialRoutes.HOME, currentRoute())
    }

    @Test
    fun readingJourneyBack_walksPlayerPlaylistHome() {
        setSignedInGraph()
        navigate(EditorialRoutes.playlist("PL_1"))
        navigate(EditorialRoutes.player("VID_1"))

        back()
        assertEquals(EditorialRoutes.PLAYLIST, currentRoute())
        assertEquals("PL_1", currentArg(EditorialRoutes.ARG_PLAYLIST_ID))

        back()
        assertEquals(EditorialRoutes.HOME, currentRoute())
    }

    @Test
    fun transcriptBack_popsToItsPlayer() {
        setSignedInGraph()
        navigate(EditorialRoutes.player("VID_2"))
        navigate(EditorialRoutes.transcript("VID_2"))

        back()
        assertEquals(EditorialRoutes.PLAYER, currentRoute())
        assertEquals("VID_2", currentArg(EditorialRoutes.ARG_VIDEO_ID))
    }

    private fun setSignedInGraph() {
        composeTestRule.setContent {
            val context = LocalContext.current
            navController =
                remember {
                    TestNavHostController(context).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            EditorialTheme {
                EditorialNavGraph(
                    navController = navController,
                    loggedIn = true,
                    authContent = { AuthCoverPage(state = AuthUiState.Idle, onSignIn = {}) },
                    // Hilt-free stand-ins for the real screens so the graph is JVM-testable.
                    homeContent = { Box(Modifier.fillMaxSize().testTag("home-content")) },
                    playlistContent = { Box(Modifier.fillMaxSize().testTag("playlist-content")) },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun navigate(route: String) {
        composeTestRule.runOnUiThread { navController.navigate(route) }
        composeTestRule.waitForIdle()
    }

    private fun navigateToRoot(route: String) {
        composeTestRule.runOnUiThread { navController.navigateToEditorialRoot(route) }
        composeTestRule.waitForIdle()
    }

    private fun back() {
        composeTestRule.runOnUiThread { navController.popBackStack() }
        composeTestRule.waitForIdle()
    }

    private fun currentRoute(): String? = navController.currentBackStackEntry?.destination?.route

    private fun currentArg(name: String): String? = navController.currentBackStackEntry?.arguments?.getString(name)
}
