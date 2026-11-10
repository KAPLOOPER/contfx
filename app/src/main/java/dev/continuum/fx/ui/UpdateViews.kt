package dev.continuum.fx.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.ActionRow
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import dev.continuum.fx.update.Release
import dev.continuum.fx.update.UpdateState
import dev.continuum.fx.update.changesOf
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.bench.BenchSection
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import dev.continuum.fx.ui.components.squash

/**
 * News of a newer build: ink, like the dock, so it reads as the app speaking rather than as a
 * setting. It drips from the title's drop and spreads into a card where it sits, and gathers back
 * into the drop when it goes; on the dark ground it keeps the dock's edge. Inside, the dock's own
 * grammar: what it is in bold, one quiet line, and the moves under it.
 */
@Composable
internal fun UpdateCard(
    update: UpdateState,
    title: (Release) -> String,
    subtitle: String,
    actionLabel: String,
    installingNote: String,
    onAction: () -> Unit,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = when (update) {
        is UpdateState.Available -> update.release
        is UpdateState.Downloading -> update.release
        is UpdateState.Installing -> update.release
        is UpdateState.NeedsPermission -> update.release
        is UpdateState.Failed -> update.release
        else -> null
    }
    // Kept while the card gathers itself back into the title's drop.
    val release = rememberLastOf(current)
    val lastUpdate = rememberLastOf(update.takeIf { current != null }) ?: update
    Seeded(visible = current != null, corner = 36.dp, edge = true, modifier = Modifier.padding(top = 14.dp, bottom = 18.dp)) {
        if (release != null) UpdateCardBody(lastUpdate, release, title, subtitle, actionLabel, installingNote, onAction, onAllow, onDismiss)
    }
}

@Composable
private fun UpdateCardBody(
    update: UpdateState,
    release: Release,
    title: (Release) -> String,
    subtitle: String,
    actionLabel: String,
    installingNote: String,
    onAction: () -> Unit,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var whatsNew by rememberSaveable { mutableStateOf(false) }
    val changes = release.changes.ifEmpty { changesOf(release.build, release.notes) }
    Column(
        Modifier
            .fillMaxWidth()
            // Its notes run to a few screens, a long way for the ink to go: it settles rather than
            // springing past (see [Motion.settle]).
            .animateContentSize(Motion.settle())
            .padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 12.dp),
    ) {
        // No round mark beside the words: on the ink a round bubble is a control (the dock's power,
        // its keyboard, this card's Later), and a decorative one that only repeated "Update now"'s
        // glyph read as a button that did nothing. The words start on the ledger's axis instead,
        // 26dp in (the page's margin plus this card's, the same as the rail).
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp)) {
                Txt(title(release), 18.sp, p.onInk, bold = true, maxLines = 2)
                // How far behind this build is, then what's kept: said whole, over two lines if
                // it needs them, rather than cut off.
                val behind = when {
                    changes.size > 1 -> "${changes.size} builds since yours. "
                    else -> ""
                }
                Txt(behind + subtitle, 14.sp, p.onInkSub, maxLines = 3, modifier = Modifier.padding(top = 3.dp))
            }
            if (update is UpdateState.Available || update is UpdateState.Failed) {
                // Later: the card gathers back into the title's drop until the next check.
                IconBubble(R.drawable.ic_close, "Later", onClick = onDismiss, on = Surface.Ink)
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
        Gap(8.dp)
        // The moves hang under the words, on their edge.
        Column(Modifier.padding(start = 8.dp, end = 12.dp)) {
            when (update) {
                is UpdateState.Available -> ActionRow {
                    PillButton(actionLabel, onAction, icon = R.drawable.ic_import, primary = true, on = Surface.Ink)
                    // Opens in place, so it says so the way every opening word does: a chevron
                    // after the words, turning over once open.
                    if (changes.isNotEmpty()) WhatsNew(whatsNew) { whatsNew = !whatsNew }
                }
                is UpdateState.Downloading -> {
                    val shown by animateFloatAsState(update.progress, Motion.flow(), label = "updateProgress")
                    Gap(6.dp)
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(p.inkTonal)) {
                        Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(p.onInk))
                    }
                    Gap(8.dp)
                    Txt("Downloading · ${(update.progress * 100).toInt()}%", 14.sp, p.onInk, maxLines = 1)
                    Gap(6.dp)
                }
                is UpdateState.Installing -> {
                    Txt(installingNote, 14.sp, p.onInk)
                    Gap(6.dp)
                }
                is UpdateState.NeedsPermission -> {
                    Txt("Android needs your OK first: allow Continuum Kit to install apps, then come back.", 14.sp, p.onInk)
                    Gap(4.dp)
                    PillButton("Allow", onAllow, icon = R.drawable.ic_import, primary = true, on = Surface.Ink)
                }
                is UpdateState.Failed -> {
                    Txt(update.message, 14.sp, p.onInk)
                    Gap(4.dp)
                    PillButton("Try again", onAction, icon = R.drawable.ic_refresh, on = Surface.Ink)
                }
                else -> Unit
            }
        }
        // What's new, opened under the words that open it: each change since your build, in a
        // line, with its paragraphs under it, whole. A build's notes come with blank lines
        // between paragraphs, a whole line's height, which is as far apart as two builds; set as
        // separate paragraphs a short step apart, a build's notes hold together and the step to
        // the next build is plainly the bigger one.
        // The ink opens first and the words come into it (they'd otherwise be there at full
        // strength while the card is still growing past them); closing, they go first.
        val notesIn by animateFloatAsState(if (whatsNew) 1f else 0f, tween(220, delayMillis = 60), label = "whatsNew")
        if (whatsNew && changes.isNotEmpty()) {
            Column(Modifier.padding(start = 8.dp, end = 12.dp).graphicsLayer { alpha = notesIn }) {
                changes.take(MAX_CHANGES).forEachIndexed { i, c ->
                    Gap(if (i == 0) 12.dp else CHANGE_GAP)
                    Note(listOfNotNull(c.area, "build ${c.build}").joinToString(" · "), p.onInkSub)
                    Gap(4.dp)
                    Txt(c.headline, 16.sp, p.onInk, bold = true)
                    c.detail.split(PARAGRAPH).map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { j, para ->
                        Gap(if (j == 0) 4.dp else 12.dp)
                        Txt(para, 14.sp, p.onInkSub, maxLines = 40)
                    }
                }
                if (changes.size > MAX_CHANGES) {
                    Gap(CHANGE_GAP)
                    Txt("And ${changes.size - MAX_CHANGES} more before those.", 14.sp, p.onInkSub)
                }
                Gap(8.dp)
            }
        }
    }
}

/** The card's "What's new": words that open what's below them in place, a chevron after them. */
@Composable
private fun WhatsNew(open: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .squash(source, pill = true)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = if (open) "Hide what's new" else "Show what's new",
                onClick = onToggle,
            )
            .opens(open, onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpensName("What's new", open, p.onInk, size = 16.sp, chevronColor = p.onInkSub)
    }
}

/** How many builds' changes the card lists before saying how many more there are. */
private const val MAX_CHANGES = 8

/** Between one build's notes and the next, against 12dp between paragraphs of one build's. */
private val CHANGE_GAP = 28.dp

private val PARAGRAPH = Regex("\\n\\s*\\n")

/** The build you're on, with a manual check, at the bottom of every tab. */
@Composable
internal fun UpdateFooter(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val label = when (state.update) {
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "Up to date"
        else -> "Check for updates"
    }
    Column {
        BenchSection(null, "Continuum Kit", trail = "Build ${state.build}")
        Row(
            Modifier.fillMaxWidth().padding(start = INK_RAIL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(label, actions::checkForUpdates, icon = R.drawable.ic_refresh)
        }
    }
}
