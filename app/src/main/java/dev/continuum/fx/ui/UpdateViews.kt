package dev.continuum.fx.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import dev.continuum.fx.update.Release
import dev.continuum.fx.update.UpdateState

/** An ink card at the top of every tab while there's a newer build to install. */
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
    val p = LocalPalette.current
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
    Seeded(visible = current != null, corner = 28.dp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) {
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
    val notes = release.notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(4).joinToString("\n")
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(Motion.flow())
            .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(p.inkTonal), contentAlignment = Alignment.Center) {
                Image(
                    painterResource(R.drawable.ic_import),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(p.onInk),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Txt(title(release), 18.sp, p.onInk, bold = true, maxLines = 1)
                Txt(subtitle, 13.sp, p.onInkSub, maxLines = 2)
            }
            if (update is UpdateState.Available || update is UpdateState.Failed) {
                IconBubble(R.drawable.ic_chevron_down, "Later", onClick = onDismiss, on = Surface.Ink)
            }
        }
        if (notes.isNotEmpty()) {
            Gap(10.dp)
            Txt(notes, 14.sp, p.onInkSub, maxLines = 5)
        }
        Gap(14.dp)
        Column(Modifier.padding(end = 8.dp)) {
            when (update) {
                is UpdateState.Available ->
                    PillButton(actionLabel, onAction, icon = R.drawable.ic_import, primary = true, on = Surface.Ink)
                is UpdateState.Downloading -> {
                    val shown by animateFloatAsState(update.progress, Motion.flow(), label = "updateProgress")
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(p.inkTonal)) {
                        Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(p.onInk))
                    }
                    Gap(8.dp)
                    Txt("Downloading · ${(update.progress * 100).toInt()}%", 14.sp, p.onInk, maxLines = 1)
                }
                is UpdateState.Installing ->
                    Txt(installingNote, 14.sp, p.onInk)
                is UpdateState.NeedsPermission -> {
                    Txt("Android needs your OK first: allow Continuum FX to install apps, then come back.", 14.sp, p.onInk)
                    Gap(12.dp)
                    PillButton("Allow", onAllow, primary = true, on = Surface.Ink)
                }
                is UpdateState.Failed -> {
                    Txt(update.message, 14.sp, p.onInk)
                    Gap(12.dp)
                    PillButton("Try again", onAction, on = Surface.Ink)
                }
                else -> Unit
            }
        }
    }
}

/** The build you're on, with a manual check, at the bottom of every tab. */
@Composable
internal fun UpdateFooter(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val label = when (state.update) {
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "Up to date"
        else -> "Check for updates"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt("Continuum FX · build ${state.build}", 14.sp, p.sub, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        PillButton(label, actions::checkForUpdates, height = 38.dp)
    }
}
