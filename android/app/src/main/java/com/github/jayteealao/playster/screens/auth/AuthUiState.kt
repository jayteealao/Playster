package com.github.jayteealao.playster.screens.auth

/**
 * The sign-in screen's rendered state. Deliberately small: the cover page is
 * idle until the user asks to sign in, authenticating while the credential
 * sheet and the Firebase bridge are in flight, and failed only for a real
 * error worth an editorial notice. A dismissed credential sheet is a user
 * choice, not a failure — it maps back to [Idle], never [Failed].
 */
sealed interface AuthUiState {
    /** The cover page with its sign-in affordance offered. */
    data object Idle : AuthUiState

    /** The credential sheet or the Firebase bridge is in flight. */
    data object Authenticating : AuthUiState

    /** A real sign-in failure, carrying the kind so the copy can be specific. */
    data class Failed(val kind: FailureKind) : AuthUiState
}

/**
 * Why a sign-in attempt failed, so the editorial error notice can say
 * something specific and actionable rather than a generic apology.
 */
enum class FailureKind {
    /** No Google credential was available (both filtered and unfiltered asks came back empty). */
    NoCredential,

    /** The credential sheet was interrupted — typically a transient connectivity problem. */
    Network,

    /** Google returned a credential, but exchanging it for a Firebase session failed. */
    Bridge,

    /** Anything else — a malformed credential, a provider misconfiguration, an unknown error. */
    Unexpected,
}
