package com.github.jayteealao.playster.screens.settings

/**
 * The Settings screen's UI model — deliberately free of Compose, Firestore, and
 * Android types so the stateless [SettingsContent] renders every state under
 * Robolectric goldens without a ViewModel or the network. The four reading axes
 * are a uniform [AxisRow] (current value + the option pills it expands to) so the
 * screen draws them in one loop; playback and the account/version/licenses
 * furniture round out the mock.
 */
data class SettingsUiState(
    val profile: Profile,
    val stats: List<Stat>,
    val readingAxes: List<AxisRow>,
    val defaultSpeed: SpeedRow,
    val version: String,
    val licenses: List<LicenseEntry>,
) {
    /** The masthead: accent since-line, serif name, italic shelf-count deck. */
    data class Profile(
        val sinceLabel: String,
        val name: String,
        val deck: String,
    )

    /** One cell of the 3-up stats rule: the numeral over its tracked caption. */
    data class Stat(
        val value: String,
        val caption: String,
    )

    /** A reading-preference row: its current value, and the options it expands to. */
    data class AxisRow(
        val axis: SettingsAxis,
        val title: String,
        val currentLabel: String,
        val options: List<AxisOption>,
    )

    data class AxisOption(
        val key: String,
        val label: String,
        val selected: Boolean,
    )

    /** The default-speed row + its speed-pill options. */
    data class SpeedRow(
        val currentLabel: String,
        val options: List<SpeedOption>,
    )

    data class SpeedOption(
        val rate: Float,
        val label: String,
        val selected: Boolean,
    )

    /** One bundled OFL notice — the font family name and its full license text. */
    data class LicenseEntry(
        val name: String,
        val text: String,
    )

    companion object {
        val Empty =
            SettingsUiState(
                profile = Profile(sinceLabel = "", name = "", deck = ""),
                stats = emptyList(),
                readingAxes = emptyList(),
                defaultSpeed = SpeedRow(currentLabel = "", options = emptyList()),
                version = "",
                licenses = emptyList(),
            )
    }
}

/** The four live reading axes the Settings screen exposes. */
enum class SettingsAxis {
    FACE,
    SIZE,
    LINE_HEIGHT,
    PAPER,
}
