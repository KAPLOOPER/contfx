package dev.continuum.fx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.ui.bench.BenchRow
import dev.continuum.fx.ui.bench.BenchSection
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Palette
import dev.continuum.fx.ui.theme.Txt
import dev.continuum.fx.update.UpdateState

/** Where Continuum Keyboard stands on this phone. */
data class KeyboardStatus(
    /** The installed build, or 0 when it isn't installed. */
    val build: Int = 0,
    /** Switched on in Android's keyboard settings. */
    val enabled: Boolean = false,
    /** The keyboard in use right now. */
    val selected: Boolean = false,
    val update: UpdateState = UpdateState.Idle,
) {
    val installed: Boolean get() = build > 0
}

internal fun statusText(k: KeyboardStatus) = when {
    !k.installed -> setupSteps(0, 3)
    !k.enabled -> setupSteps(1, 3)
    !k.selected -> setupSteps(2, 3)
    else -> "In use · build ${k.build}"
}

/** The next step towards typing with it, or its settings once it's in use. */
internal fun nextKeyboardStep(k: KeyboardStatus, actions: AppActions) = when {
    !k.installed -> actions.installKeyboard()
    !k.enabled -> actions.enableKeyboard()
    !k.selected -> actions.chooseKeyboard()
    else -> actions.openKeyboardSettings(null)
}

/** The Keyboard tab: get Continuum Keyboard going, then jump into its settings. */
@Composable
internal fun KeyboardContent(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val k = state.keyboard
    Column {
        Kicker("Keyboard", if (k.installed) "Build ${k.build}" else null)
        Gap(6.dp)
        HeroLine(
            when {
                !k.installed -> "Not installed yet."
                !k.enabled -> "Installed, not switched on."
                !k.selected -> "On, but not in use."
                else -> "In use."
            },
            if (k.selected) p.text else p.sub,
        )
        Gap(10.dp)

        // The keyboard itself, drawn in ink on the ground — no card, no radius — its keys
        // darkening a step as the keyboard comes alive, the space bar filled once it's the
        // one you type with. TalkBack hears your standing from it too.
        KeyboardGlyph(k)
        Gap(12.dp)

        // News of a newer build, inline like everything else: one 48dp row — a drop, the
        // fact, the move — never a card.
        UpdateCard(
            update = k.update,
            title = { if (k.installed) "Keyboard build ${it.build}" else "Continuum Keyboard" },
            subtitle = if (k.installed) "Your words and settings stay."
            else "Build ${(k.update as? UpdateState.Available)?.release?.build ?: ""} · Continuum's own keyboard".trim(),
            actionLabel = if (k.installed) "Update now" else "Install",
            installingNote = "Installing Continuum Keyboard. Android may ask you to confirm.",
            onAction = actions::installKeyboard,
            onAllow = actions::allowInstalls,
            onDismiss = actions::dismissKeyboardUpdate,
        )

        if (!k.selected) {
            // Getting it going: rows like every other, each saying its state at its end, and
            // the move opened beneath it on the ink thread.
            BenchSection(1, "Set it up")
            var setupOpen by rememberSaveable { mutableStateOf<String?>(null) }
            fun openSetup(key: String) { setupOpen = if (setupOpen == key) null else key }
            BenchRow(
                "Install it",
                "Continuum Keyboard is its own app, installed and kept up to date from here.",
                on = k.installed,
                expanded = setupOpen == "install",
                onExpand = { openSetup("install") },
                trailing = { SetupState(k.installed) },
            ) {
                PillButton("Install", actions::installKeyboard, icon = R.drawable.ic_import, primary = true, height = 44.dp)
            }
            BenchRow(
                "Switch it on",
                "Turn on Continuum Keyboard in Android's keyboard list.",
                on = k.enabled,
                expanded = setupOpen == "enable",
                onExpand = { openSetup("enable") },
                trailing = { SetupState(k.enabled) },
            ) {
                PillButton("Open keyboard list", actions::enableKeyboard, height = 44.dp)
            }
            BenchRow(
                "Make it your keyboard",
                "Pick it as the keyboard you type with.",
                on = k.selected,
                expanded = setupOpen == "choose",
                onExpand = { openSetup("choose") },
                trailing = { SetupState(k.selected) },
            ) {
                PillButton("Choose keyboard", actions::chooseKeyboard, height = 44.dp)
            }
        }

        if (k.installed) {
            BenchSection(if (k.selected) 1 else 2, "Settings")
            val pages = listOf(
                Triple("look", "Look", "Ink or tint, and the keys' height"),
                Triple("typing", "Typing", "Corrections, suggestions, the number row"),
                Triple("swipe", "Swiping", "Trace a word, and swipe to delete"),
                Triple("clipboard", "Clipboard", "What you copied, kept and pinned"),
                Triple("words", "Learned words", "What it picked up from you"),
                Triple("about", "About", "Build number and licences"),
            )
            // Each opens a page of the keyboard's settings: rows that lead somewhere end in an arrow.
            Column {
                pages.forEach { (page, label, caption) -> SettingRow(label, caption) { actions.openKeyboardSettings(page) } }
            }
        }

        Gap(8.dp)
        // Everything it does happens on the phone.
        Note("Offline · no internet access", p.quiet)
    }
}

/** The keyboard drawn in ink, a hero's worth of it: three rows of keys and a space bar. */
@Composable
private fun KeyboardGlyph(k: KeyboardStatus) {
    val p = LocalPalette.current
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics { contentDescription = statusText(k) },
    ) {
        drawKeyboardGlyph(p, k)
    }
}

/** The drawing: the further the keyboard's come, a step more the ink runs; selected, the space fills. */
private fun DrawScope.drawKeyboardGlyph(p: Palette, k: KeyboardStatus) {
    // A drawing, not the content: the ink goes a step quieter than any row's would.
    val ink = when {
        k.selected -> p.sub
        k.enabled -> p.quiet
        k.installed -> p.quiet
        else -> p.line
    }
    val touch = 1.dp.toPx()
    val bend = CornerRadius(4.5.dp.toPx())
    val margin = 4.dp.toPx()
    val widths = arrayOf(
        floatArrayOf(1.3f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.3f),
        floatArrayOf(1.7f, 1f, 1f, 1f, 1f, 1f, 1f, 1.7f),
        floatArrayOf(2.2f, 1f, 1f, 1f, 1f, 1f, 2.2f),
    )
    val slot = (size.height - 4 * margin) / 4
    var y = margin / 2
    // Three rows of keys, then the space bar: its outline in ink, filled once it's in use.
    for (row in widths) {
        val key = (size.width - 2 * margin - (row.size - 1) * margin) / row.sum()
        var x = margin
        for (factor in row) {
            drawRoundRect(ink, Offset(x, y), Size(key * factor, slot), bend, style = Stroke(touch))
            x += key * factor + margin
        }
        y += slot + margin
    }
    drawRoundRect(ink, Offset(margin, y), Size(size.width - 2 * margin, slot), bend, style = Stroke(touch))
    if (k.selected) {
        val inset = touch * 2
        drawRoundRect(ink, Offset(margin + inset, y + inset), Size(size.width - 2 * margin - inset * 2, slot - inset * 2), bend)
    }
}

/** A row that leads to one of the keyboard's pages: 56dp of it, an arrow at its end. */
@Composable
private fun SettingRow(label: String, caption: String, onOpen: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onOpen,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Txt(label, 16.5.sp, p.text, maxLines = 1)
            Txt(caption, 13.sp, p.sub, maxLines = 1)
        }
        Chevron(null, p.quiet, 15.dp)
    }
}

@Composable
internal fun KeyboardDockBar(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val k = state.keyboard
    Row(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(if (k.selected) p.onInk else p.inkTonal),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_keyboard),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                colorFilter = ColorFilter.tint(if (k.selected) p.ink else p.onInk),
            )
        }
        DockText(
            "Continuum Keyboard", statusText(k),
            when {
                !k.installed -> "Install"
                !k.enabled -> "Open keyboard list"
                !k.selected -> "Choose keyboard"
                else -> "Keyboard settings"
            },
        ) { nextKeyboardStep(k, actions) }
        if (k.installed) {
            IconBubble(R.drawable.ic_setup, "Keyboard settings", onClick = { actions.openKeyboardSettings(null) }, on = Surface.Ink)
        }
    }
}
