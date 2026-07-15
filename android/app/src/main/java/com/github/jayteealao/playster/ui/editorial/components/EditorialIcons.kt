package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The editorial stroke-icon set, ported path-for-path from the design
 * prototype's icon sheet (design-handoff/playster/project/chrome.jsx):
 * 24 viewport, 1.7 stroke weight, round caps and joins. Material icon
 * vectors do not match this weight/geometry and would fail the pixel gate.
 *
 * This file is the single extension point for editorial icons: this slice
 * ports only the subset the component library consumes; each screen slice
 * ports its own additions from the same prototype sheet.
 *
 * Vectors are built in a warm near-black (never pure #000) so an untinted
 * render stays inside the palette discipline; components always tint with
 * a token color via [androidx.compose.material3.Icon]'s tint.
 */
object EditorialIcons {
    val Play: ImageVector by lazy {
        filledIcon("editorial.play", "M8 5v14l11-7z")
    }

    val PlayFilled: ImageVector by lazy {
        filledIcon(
            "editorial.playFilled",
            "M7 4.5v15a1 1 0 0 0 1.5.87l13-7.5a1 1 0 0 0 0-1.74l-13-7.5A1 1 0 0 0 7 4.5z",
        )
    }

    val Pause: ImageVector by lazy {
        filledIcon(
            "editorial.pause",
            "M7 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z",
            "M15 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z",
        )
    }

    val Search: ImageVector by lazy {
        strokeIcon(
            "editorial.search",
            "M11 5a6 6 0 1 0 0 12 6 6 0 0 0 0-12z",
            "M20 20l-4-4",
        )
    }

    val Back: ImageVector by lazy {
        strokeIcon("editorial.back", "M15 6l-6 6 6 6")
    }

    val List: ImageVector by lazy {
        strokeIcon("editorial.list", "M4 6h16M4 12h16M4 18h10")
    }

    val Kebab: ImageVector by lazy {
        // The prototype draws three r=1 filled circles that also carry the
        // 1.7 stroke; a filled circle at r + stroke/2 is the same mark.
        filledIcon(
            "editorial.kebab",
            kebabDot(5.0f),
            kebabDot(12.0f),
            kebabDot(19.0f),
        )
    }

    val Bookmark: ImageVector by lazy {
        strokeIcon("editorial.bookmark", "M6 4h12v17l-6-4-6 4z")
    }

    val Download: ImageVector by lazy {
        strokeIcon("editorial.download", "M12 3v12M7 10l5 5 5-5M5 21h14")
    }

    val Next: ImageVector by lazy {
        strokeIcon("editorial.next", "M9 6l6 6-6 6")
    }

    val Close: ImageVector by lazy {
        strokeIcon("editorial.close", "M6 6l12 12M18 6L6 18")
    }

    val Text: ImageVector by lazy {
        strokeIcon("editorial.text", "M5 5h14M9 5v14M5 12h6M5 19h8")
    }

    val Check: ImageVector by lazy {
        strokeIcon("editorial.check", "M5 12.5l4.5 4.5L20 7")
    }
}

/** The prototype's stroke weight — 1.7 in the 24-unit viewport. */
private const val STROKE_WIDTH = 1.7f
private const val VIEWPORT = 24f

/** Kebab dot radius: r=1 plus half the 1.7 stroke the prototype adds. */
private const val KEBAB_RADIUS = 1.85f

/** Warm near-black default; replaced by the component's token tint. */
private val IconInk = Color(0xFF22201A)

private fun kebabDot(centerY: Float): String {
    val r = KEBAB_RADIUS
    val d = r + r
    return "M12 ${centerY - r}a$r $r 0 1 0 0 ${d}a$r $r 0 1 0 0 ${-d}z"
}

private fun strokeIcon(
    name: String,
    vararg pathData: String,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply {
        for (data in pathData) {
            addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(IconInk),
                strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun filledIcon(
    name: String,
    vararg pathData: String,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply {
        for (data in pathData) {
            addPath(
                pathData = addPathNodes(data),
                fill = SolidColor(IconInk),
            )
        }
    }.build()
