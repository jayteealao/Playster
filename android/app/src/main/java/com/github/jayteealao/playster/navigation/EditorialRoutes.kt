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

    /**
     * The player route carries the videoId in the path and the playlist context
     * as an *optional* query arg — the header kicker (ep/total) and a created
     * note both need a playlist, and the nav sources (home headliner, playlist
     * rows) know it. When absent, the ViewModel derives it from the video's
     * collection-group path (Assumption 8), so the bare `player/{videoId}` link
     * still works — the query arg is backward-compatible.
     */
    const val PLAYER = "player/{$ARG_VIDEO_ID}?$ARG_PLAYLIST_ID={$ARG_PLAYLIST_ID}"
    const val TRANSCRIPT = "transcript/{$ARG_VIDEO_ID}"

    /** Concrete route for one playlist volume. */
    fun playlist(playlistId: String): String = "playlist/$playlistId"

    /** Concrete route for one episode's player, optionally carrying its playlist context. */
    fun player(
        videoId: String,
        playlistId: String? = null,
    ): String =
        if (playlistId.isNullOrBlank()) {
            "player/$videoId"
        } else {
            "player/$videoId?$ARG_PLAYLIST_ID=$playlistId"
        }

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
