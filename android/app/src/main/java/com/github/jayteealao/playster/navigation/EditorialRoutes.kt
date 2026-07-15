package com.github.jayteealao.playster.navigation

/**
 * The editorial information architecture: seven string routes with typed
 * arguments. This object is the single source of truth for route patterns,
 * argument names, and the bottom-nav tab mapping — screen slices build on
 * these constants and never re-declare a pattern.
 *
 * Deep links (Search → Transcript-at-timestamp) are wired by the search
 * screen over [TRANSCRIPT]; this object only fixes the route shape.
 */
object EditorialRoutes {
    const val AUTH = "auth"
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    const val ARG_PLAYLIST_ID = "playlistId"
    const val ARG_VIDEO_ID = "videoId"

    const val PLAYLIST = "playlist/{$ARG_PLAYLIST_ID}"
    const val PLAYER = "player/{$ARG_VIDEO_ID}"
    const val TRANSCRIPT = "transcript/{$ARG_VIDEO_ID}"

    /** Concrete route for one playlist volume. */
    fun playlist(playlistId: String): String = "playlist/$playlistId"

    /** Concrete route for one episode's player. */
    fun player(videoId: String): String = "player/$videoId"

    /** Concrete route for one episode's transcript. */
    fun transcript(videoId: String): String = "transcript/$videoId"

    /**
     * Which bottom-nav tab reads as active for a route — the prototype's
     * `tabFor()` mapping: home/search/settings own their tabs; the playlist,
     * player, and transcript contexts light the "Library" indicator; auth
     * shows no chrome at all (null).
     */
    fun tabForRoute(route: String?): EditorialTab? =
        when (route) {
            HOME -> EditorialTab.READING
            SEARCH -> EditorialTab.SEARCH
            SETTINGS -> EditorialTab.YOU
            PLAYLIST, PLAYER, TRANSCRIPT -> EditorialTab.LIBRARY
            else -> null
        }

    /**
     * Where a bottom-nav tap lands. "Library" is a contextual indicator, not
     * a destination of its own — tapping it goes to the home root, which is
     * the prototype's own rendered fallback for the `library` id. The home
     * or playlist screen slice may revisit this with a richer Library
     * destination; it is a one-line change here.
     */
    fun rootRouteFor(tab: EditorialTab): String =
        when (tab) {
            EditorialTab.READING -> HOME
            EditorialTab.LIBRARY -> HOME
            EditorialTab.SEARCH -> SEARCH
            EditorialTab.YOU -> SETTINGS
        }
}

/** The four bottom-nav tabs, labelled exactly as the prototype's `BottomNav`. */
enum class EditorialTab(val label: String) {
    READING("Reading"),
    LIBRARY("Library"),
    SEARCH("Search"),
    YOU("You"),
}
