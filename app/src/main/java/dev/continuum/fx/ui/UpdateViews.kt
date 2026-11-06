package dev.continuum.fx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Txt
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.update.Release
import dev.continuum.fx.update.UpdateState
import dev.continuum.fx.update.changesOf
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * News of a newer build, inline like everything else: no card, no filled surface. One 48dp row —
 * a 5dp drop of ink, the fact at 16.5sp, and the move in the note face at the far right. Tapping
 * the row opens the release notes under it, plain indented text on a 1dp thread of ink, as every
 * opened block is.
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
    @Suppress("UNUSED_PARAMETER") onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val current = when (update) {
        is UpdateState.Available -> update.release
        is UpdateState.Downloading -> update.release
        is UpdateState.Installing -> update.release
        is UpdateState.NeedsPermission -> update.release
        is UpdateState.Failed -> update.release
        else -> null
    }
    if (current == null) return
    var notes by rememberSaveable { mutableStateOf(false) }
    val changes = current.changes.ifEmpty { changesOf(current.build, current.notes) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 12.dp)
            .animateContentSize(Motion.flow()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (notes) "Hide what's new" else "Show what's new",
                ) { notes = !notes },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The row's drop of ink: this needs you.
            Box(Modifier.size(5.dp).clip(CircleShape).background(p.text))
            Spacer(Modifier.width(14.dp))
            Txt(title(current), 16.5.sp, p.text, maxLines = 1, modifier = Modifier.weight(1f).padding(end = 12.dp))
            val move: Pair<String, (() -> Unit)>? = when (update) {
                is UpdateState.Available -> actionLabel to onAction
                is UpdateState.Failed -> "Try again" to onAction
                is UpdateState.NeedsPermission -> "Allow" to onAllow
                else -> null
            }
            if (move != null) {
                dev.continuum.fx.ui.theme.Note(
                    move.first,
                    p.text,
                    Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) { move.second() }
                        .padding(start = 12.dp, top = 15.dp, bottom = 15.dp),
                )
            }
        }
        // What's under the row, threaded like every opened block: 16dp in, 1dp of ink down its left.
        // For a fresh release this is the release notes, and it only comes out when asked for.
        if (update !is UpdateState.Available || notes) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(p.text, Offset(0.5.dp.toPx(), 0f), Offset(0.5.dp.toPx(), size.height), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Square)
                    }
                    .padding(start = 16.dp, top = 2.dp, bottom = 12.dp),
            ) {
                when (update) {
                    is UpdateState.Available -> {
                        changes.take(MAX_CHANGES).forEach { c ->
                            Note(listOfNotNull(c.area, "build ${c.build}").joinToString(" · "), p.quiet)
                            Spacer(Modifier.height(3.dp))
                            Txt(c.headline, 16.sp, p.text)
                            if (c.detail.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Txt(c.detail, 14.sp, p.sub)
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (changes.size > MAX_CHANGES) {
                            Txt("And ${changes.size - MAX_CHANGES} more before those.", 14.sp, p.sub)
                        }
                        if (changes.isEmpty()) {
                            Txt(subtitle, 14.sp, p.sub)
                        }
                    }
                    is UpdateState.Downloading -> {
                        val shown = update.progress.toFloat().coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line)) {
                            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(p.text))
                        }
                        Spacer(Modifier.height(8.dp))
                        Note("Downloading · ${(update.progress * 100).toInt()}%", p.sub)
                    }
                    is UpdateState.Installing -> Txt(installingNote, 14.sp, p.sub)
                    is UpdateState.NeedsPermission -> Txt(
                        "Android needs your OK first: allow Continuum Kit to install apps, then come back.",
                        14.sp, p.text,
                    )
                    is UpdateState.Failed -> Txt(update.message, 14.sp, p.sub)
                    else -> Unit
                }
            }
        }
    }
}

/** How many builds' changes the row lists before saying how many more there are. */
private const val MAX_CHANGES = 8

/** The build you're on, with a manual check, at the bottom of every tab. */
@Composable
internal fun UpdateFooter(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val label = when (state.update) {
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "Up to date"
        else -> "Check for updates"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Note("Continuum Kit · build ${state.build}", p.quiet, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .height(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = actions::checkForUpdates,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Note(label, p.sub)
        }
    }
}
