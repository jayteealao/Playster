package com.github.jayteealao.playster.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.BodyText
import com.github.jayteealao.playster.ui.editorial.components.Dateline
import com.github.jayteealao.playster.ui.editorial.components.Deck
import com.github.jayteealao.playster.ui.editorial.components.DisplayTitle
import com.github.jayteealao.playster.ui.editorial.components.DropcapBody
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBarAction
import com.github.jayteealao.playster.ui.editorial.components.EditorialBottomNav
import com.github.jayteealao.playster.ui.editorial.components.EditorialChip
import com.github.jayteealao.playster.ui.editorial.components.EditorialEmptyNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialErrorNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialLoadingNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialPillButton
import com.github.jayteealao.playster.ui.editorial.components.EditorialProgressBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialQuotaNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialRule
import com.github.jayteealao.playster.ui.editorial.components.EditorialTabs
import com.github.jayteealao.playster.ui.editorial.components.EditorialTextAction
import com.github.jayteealao.playster.ui.editorial.components.EpisodeRow
import com.github.jayteealao.playster.ui.editorial.components.Folio
import com.github.jayteealao.playster.ui.editorial.components.Kicker
import com.github.jayteealao.playster.ui.editorial.components.MiniPlayerPill
import com.github.jayteealao.playster.ui.editorial.components.Ordinal
import com.github.jayteealao.playster.ui.editorial.components.PullQuote
import com.github.jayteealao.playster.ui.editorial.components.ShelfRow

/**
 * Debug-only component galleries — the Roborazzi golden subjects and the
 * emulator eyeball surface for the editorial component library. Four themed
 * pages render every primitive with sample content quoted verbatim from
 * the design prototype's content file (editorial/data.jsx), so the goldens
 * stay directly comparable against the mock idioms.
 */
enum class ComponentGalleryPage(val key: String) {
    TYPOGRAPHY("typography"),
    LISTS("lists"),
    CHROME("chrome"),
    GAP_STATES("gapstates"),
    ;

    companion object {
        fun fromKey(key: String?): ComponentGalleryPage = entries.firstOrNull { it.key == key } ?: TYPOGRAPHY
    }
}

@Composable
fun ComponentGallery(page: ComponentGalleryPage) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(LocalEditorialTokens.current.palette.paper)
                .testTag("component-gallery-${page.key}")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        when (page) {
            ComponentGalleryPage.TYPOGRAPHY -> TypographyGallery()
            ComponentGalleryPage.LISTS -> ListsAndControlsGallery()
            ComponentGalleryPage.CHROME -> ChromeGallery()
            ComponentGalleryPage.GAP_STATES -> GapStatesGallery()
        }
    }
}

@Composable
private fun TypographyGallery() {
    val tokens = LocalEditorialTokens.current
    Kicker("Issue 38 · Tuesday")
    Gap(4)
    Kicker("43h unread", accent = true)
    Gap(12)
    DisplayTitle(
        text =
            buildAnnotatedString {
                append("Today's\n")
                withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic)) {
                    append("shelf.")
                }
            },
        sizeSp = 32.0,
    )
    Gap(8)
    DisplayTitle(GallerySample.FEATURED_TITLE, sizeSp = 22.0)
    Gap(8)
    DisplayTitle("Modern Type Practice", sizeSp = 19.0)
    Gap(8)
    Deck("Six playlists you started, three you forgot. A few minutes each.")
    Gap(6)
    Dateline("by Joey Banks · Recorded in Brooklyn, autumn 2024.")
    Gap(10)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Ordinal("01")
        Ordinal("02")
        Ordinal("09", sizeSp = 12.0, active = true)
    }
    Gap(14)
    DropcapBody(GallerySample.SUMMARY_TLDR)
    PullQuote(GallerySample.PULL_QUOTE)
    BodyText("Adoption is measured by lines deleted, not stories shipped.")
    Gap(14)
    EditorialRule()
    Gap(6)
    EditorialRule(color = tokens.palette.ruleFaint)
    Gap(6)
    EditorialRule(color = tokens.accent.color, thickness = 2.dp)
    Gap(14)
    Folio(left = "Ep. 9 · ${GallerySample.PLAYLIST_TITLE}", right = "23:14", topRule = true)
}

@Composable
private fun ListsAndControlsGallery() {
    val tokens = LocalEditorialTokens.current
    Kicker("The Shelf")
    Gap(4)
    ShelfRow(
        ordinal = 1,
        kicker = "Design · Vol. 01",
        title = GallerySample.PLAYLIST_TITLE,
        byline = "by Andy Allen · 24 videos · 8h 14m",
        onClick = {},
        progress = 0.55f,
    )
    ShelfRow(
        ordinal = 4,
        kicker = "Code · Vol. 04",
        title = "Frontend Architecture",
        byline = "by Theo Browne · 9 videos · 3h 19m",
        onClick = {},
        // progress 0.0 — at or below the 2% threshold the consumer omits
        // the bar entirely (the mock's `p.progress > 0.02` guard).
        progress = null,
    )
    ShelfRow(
        ordinal = 6,
        kicker = "Inbox · A folder",
        title = "Weekly, Saved",
        byline = "by You · 6 videos · 1h 48m",
        onClick = {},
        progress = 0.10f,
    )
    Gap(16)
    Kicker("Episodes")
    Gap(4)
    EpisodeRow(
        position = 1,
        title = "Why design systems plateau",
        duration = "12:08",
        onClick = {},
        watched = true,
    )
    EpisodeRow(
        position = 9,
        title = GallerySample.FEATURED_TITLE,
        duration = "23:14",
        onClick = {},
        playing = true,
    )
    EpisodeRow(
        position = 10,
        title = "When to fork the system",
        duration = "15:47",
        onClick = {},
    )
    Gap(16)
    Kicker("Recently searched")
    Gap(8)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GallerySample.RECENT_SEARCHES.take(3).forEach { EditorialChip(it, onClick = {}) }
    }
    Gap(6)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GallerySample.RECENT_SEARCHES.drop(3).forEach { EditorialChip(it, onClick = {}) }
    }
    Gap(16)
    Kicker("Progress")
    Gap(8)
    EditorialProgressBar(progress = 0.55f)
    Gap(8)
    EditorialProgressBar(progress = 0.9f, fill = tokens.accent.color)
    Gap(8)
    EditorialProgressBar(progress = 0.55f, showScrubDot = true)
    Gap(8)
    // Both sides of the consumer-side 2% rule: 3% renders, 1% is omitted.
    EditorialProgressBar(progress = 0.03f)
    Gap(6)
    Dateline("at 1%, the consumer omits the bar entirely")
    Gap(16)
    EditorialTabs(
        tabs = listOf("Episodes", "Summary", "Notes"),
        selectedIndex = 0,
        onSelect = {},
    )
}

@Composable
private fun ChromeGallery() {
    Kicker("App bar")
    Gap(8)
    EditorialAppBar(
        kicker = "Now playing",
        left = {
            EditorialAppBarAction(
                icon = EditorialIcons.Back,
                contentDescription = "Back",
                onClick = {},
            )
        },
        right = {
            EditorialAppBarAction(
                icon = EditorialIcons.Kebab,
                contentDescription = "More options",
                onClick = {},
            )
        },
    )
    Gap(16)
    Kicker("Bottom navigation")
    Gap(8)
    EditorialBottomNav(
        items = listOf("Reading", "Library", "Search", "You"),
        selectedIndex = 0,
        onSelect = {},
    )
    Gap(16)
    Kicker("Mini player")
    Gap(8)
    MiniPlayerPill(
        title = GallerySample.FEATURED_TITLE,
        position = "12:47",
        playing = true,
        onClick = {},
        onPlayPause = {},
    )
    Gap(10)
    MiniPlayerPill(
        title = GallerySample.FEATURED_TITLE,
        position = "12:47",
        playing = false,
        onClick = {},
        onPlayPause = {},
    )
    Gap(16)
    Kicker("Actions")
    Gap(8)
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        EditorialPillButton(
            text = "Continue · Ep 9",
            onClick = {},
            icon = EditorialIcons.Play,
        )
        EditorialTextAction(
            text = "Download",
            onClick = {},
            icon = EditorialIcons.Download,
        )
    }
    Gap(10)
    EditorialPillButton(
        text = "Continue · Ep 9",
        onClick = {},
        icon = EditorialIcons.Play,
        enabled = false,
    )
    Gap(16)
    Kicker("Icon set")
    Gap(8)
    IconSpecimenRow()
}

@Composable
private fun GapStatesGallery() {
    Kicker("Loading")
    Gap(8)
    EditorialLoadingNotice(label = "Fetching the shelf — a moment.")
    Gap(16)
    Kicker("Empty")
    Gap(8)
    EditorialEmptyNotice(
        title = "Nothing on the shelf yet.",
        teaches = "Playlists you save will appear here, ordered by last opened.",
        actionLabel = "Find a playlist",
        onAction = {},
    )
    Gap(16)
    Kicker("Error")
    Gap(8)
    EditorialErrorNotice(
        message = "The transcript didn't arrive — Playster couldn't reach the library.",
        actionLabel = "Try again",
        onAction = {},
    )
    Gap(16)
    Kicker("Quota")
    Gap(8)
    EditorialQuotaNotice(
        message = "You've reached today's summary quota. It resets at midnight.",
    )
}

@Composable
private fun IconSpecimenRow() {
    val tokens = LocalEditorialTokens.current
    val icons =
        listOf(
            EditorialIcons.Play,
            EditorialIcons.PlayFilled,
            EditorialIcons.Pause,
            EditorialIcons.Search,
            EditorialIcons.Back,
            EditorialIcons.List,
            EditorialIcons.Kebab,
        )
    val icons2 =
        listOf(
            EditorialIcons.Bookmark,
            EditorialIcons.Download,
            EditorialIcons.Next,
            EditorialIcons.Close,
            EditorialIcons.Text,
            EditorialIcons.Check,
        )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icons.forEach { IconSwatch(it, tokens.palette.ink) }
    }
    Gap(8)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icons2.forEach { IconSwatch(it, tokens.palette.inkSoft) }
    }
}

@Composable
private fun IconSwatch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun Gap(heightDp: Int) {
    Box(Modifier.size(width = 0.dp, height = heightDp.dp))
}

/**
 * Sample strings quoted verbatim from the design prototype's content file
 * (design-handoff/playster/project/editorial/data.jsx) so gallery goldens
 * stay comparable against the mock's own idioms. Debug sourceset only —
 * none of this ships in release.
 */
object GallerySample {
    const val FEATURED_TITLE = "The Future of Design Systems"
    const val PLAYLIST_TITLE = "Designing Better Interfaces"
    const val PULL_QUOTE = "We shipped four hundred components and nobody used them."
    const val SUMMARY_TLDR =
        "Design systems plateau when they’re treated as a library instead of a contract. " +
            "The next decade belongs to runtime systems — tokens that resolve at render time, " +
            "components that negotiate with their host, and adoption measured in deleted code, " +
            "not added components."
    val RECENT_SEARCHES = listOf("design tokens", "figma variants", "storybook adoption", "andy allen", "a11y")
}
