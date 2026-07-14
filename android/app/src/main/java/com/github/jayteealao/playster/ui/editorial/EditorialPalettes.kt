package com.github.jayteealao.playster.ui.editorial

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * One editorial paper palette: nine color roles plus a dark flag.
 *
 * Values are byte-equal to the design prototype's theme source
 * (design-handoff/playster/project/editorial/theme.jsx); a unit test proves
 * every role against a fixture generated from that file. Rule colors are the
 * palette's ink at the prototype's exact rgba alpha (never a gray hex), so
 * hairlines always composite naturally over any paper tone.
 */
@Immutable
data class PaperPalette(
    val key: String,
    val displayName: String,
    val paper: Color,
    val paperDeep: Color,
    val paperEdge: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val rule: Color,
    val ruleFaint: Color,
    val highlight: Color,
    val dark: Boolean = false,
)

/** An accent pair: the single chromatic event and its soft companion. */
@Immutable
data class EditorialAccent(
    val key: String,
    val displayName: String,
    val color: Color,
    val soft: Color,
)

object EditorialPalettes {
    val Cream =
        PaperPalette(
            key = "cream",
            displayName = "Cream",
            paper = Color(0xFFF3EEE4),
            paperDeep = Color(0xFFEAE3D2),
            paperEdge = Color(0xFFDDD4C0),
            ink = Color(0xFF22201A),
            inkSoft = Color(0xFF56503F),
            inkFaint = Color(0xFF8A8472),
            // ink @ .16
            rule = Color(0x2922201A),
            // ink @ .08
            ruleFaint = Color(0x1422201A),
            highlight = Color(0xFFF3DA7A),
        )

    val Vellum =
        PaperPalette(
            key = "vellum",
            displayName = "Vellum",
            paper = Color(0xFFF7F2E7),
            paperDeep = Color(0xFFEFE7D3),
            paperEdge = Color(0xFFDDD1B3),
            ink = Color(0xFF1A1812),
            inkSoft = Color(0xFF5A5340),
            inkFaint = Color(0xFF8E8770),
            // ink @ .16
            rule = Color(0x291A1812),
            // ink @ .08
            ruleFaint = Color(0x141A1812),
            highlight = Color(0xFFFFE082),
        )

    val Newsprint =
        PaperPalette(
            key = "newsprint",
            displayName = "Newsprint",
            paper = Color(0xFFEBE7DF),
            paperDeep = Color(0xFFDFD9CC),
            paperEdge = Color(0xFFCCC6B6),
            ink = Color(0xFF16161A),
            inkSoft = Color(0xFF4D4A45),
            inkFaint = Color(0xFF807D76),
            // ink @ .18
            rule = Color(0x2E16161A),
            // ink @ .08
            ruleFaint = Color(0x1416161A),
            highlight = Color(0xFFFFF58A),
        )

    /** Night is THE dark theme: a warm inversion, never a gray Material dark. */
    val Night =
        PaperPalette(
            key = "night",
            displayName = "Night",
            paper = Color(0xFF1C1A17),
            paperDeep = Color(0xFF15130F),
            paperEdge = Color(0xFF272420),
            ink = Color(0xFFECE6D6),
            inkSoft = Color(0xFFA8A191),
            inkFaint = Color(0xFF6A6457),
            // ink @ .16
            rule = Color(0x29ECE6D6),
            // ink @ .07
            ruleFaint = Color(0x12ECE6D6),
            // translucent amber @ .32
            highlight = Color(0x52F8C248),
            dark = true,
        )

    val All: List<PaperPalette> = listOf(Cream, Vellum, Newsprint, Night)

    /** Unknown or absent keys resolve to Cream, the first-run default. */
    fun fromKey(key: String?): PaperPalette = All.firstOrNull { it.key == key } ?: Cream
}

object EditorialAccents {
    val Oxblood = EditorialAccent("oxblood", "Oxblood", Color(0xFF7A2E1D), Color(0xFFE9C8B8))
    val Forest = EditorialAccent("forest", "Forest", Color(0xFF2F5A3B), Color(0xFFCDE0CE))
    val Slate = EditorialAccent("slate", "Slate", Color(0xFF37475A), Color(0xFFCBD3DC))
    val Ink = EditorialAccent("ink", "Ink", Color(0xFF22201A), Color(0xFFD8D2C2))

    val All: List<EditorialAccent> = listOf(Oxblood, Forest, Slate, Ink)

    /** Unknown or absent keys resolve to Oxblood, the prototype default. */
    fun fromKey(key: String?): EditorialAccent = All.firstOrNull { it.key == key } ?: Oxblood
}
