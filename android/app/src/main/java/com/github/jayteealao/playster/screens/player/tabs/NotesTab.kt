package com.github.jayteealao.playster.screens.player.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.screens.player.PlayerNote
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.EditorialEmptyNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialRule
import com.github.jayteealao.playster.ui.editorial.components.EditorialTextAction
import com.github.jayteealao.playster.ui.editorial.components.Kicker

/**
 * The Notes tab: a composer that stamps a note at the current playback position,
 * then the volume's existing notes newest-first. Creation is this slice's new
 * write side (a saved note flows straight into both the Player and Playlist
 * Notes tabs via the untouched read listener); an empty list still teaches what
 * notes are for. The composer uses a bare editorial text field — no Material
 * TextField (the design bans the vocabulary).
 */
@Composable
fun NotesTab(
    notes: List<PlayerNote>,
    currentTimeLabel: String,
    onCreateNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.testTag("player-notes")) {
        NoteComposer(currentTimeLabel = currentTimeLabel, onCreateNote = onCreateNote)
        Spacer(Modifier.height(14.dp))
        if (notes.isEmpty()) {
            EditorialEmptyNotice(
                title = "No notes yet.",
                teaches =
                    "Save a thought at the moment it lands — it'll be pinned to that " +
                        "timestamp and gathered here and on the volume.",
                actionLabel = "Note this moment",
                onAction = {},
                modifier = Modifier.testTag("player-notes-empty"),
            )
        } else {
            Kicker(text = "${notes.size} notes · this episode")
            Spacer(Modifier.height(8.dp))
            notes.forEach { note -> NoteRow(note) }
        }
    }
}

@Composable
private fun NoteComposer(
    currentTimeLabel: String,
    onCreateNote: (String) -> Unit,
) {
    val tokens = LocalEditorialTokens.current
    var draft by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        Kicker(text = "Note at $currentTimeLabel", accent = true)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(tokens.palette.paperDeep)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = "Write what this moment made you think…",
                    style = tokens.type.deck.copy(fontStyle = FontStyle.Italic),
                    color = tokens.palette.inkFaint,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = tokens.type.body.copy(color = tokens.palette.ink),
                cursorBrush = SolidColor(tokens.accent.color),
                modifier = Modifier.fillMaxWidth().testTag("player-note-input"),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorialTextAction(
                text = "Save note",
                icon = EditorialIcons.Check,
                enabled = draft.isNotBlank(),
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        onCreateNote(text)
                        draft = ""
                    }
                },
                modifier = Modifier.testTag("player-note-save"),
            )
        }
        EditorialRule(color = tokens.palette.ruleFaint)
    }
}

@Composable
private fun NoteRow(note: PlayerNote) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(text = note.timestampLabel, style = tokens.type.kicker, color = tokens.accent.color)
        Spacer(Modifier.height(4.dp))
        Text(text = note.text, style = tokens.type.body, color = tokens.palette.ink)
    }
}
