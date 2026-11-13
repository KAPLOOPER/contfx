package dev.continuum.fx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.continuum.fx.R
import dev.continuum.fx.ui.bench.BenchRow
import dev.continuum.fx.ui.bench.BenchLinkRow
import dev.continuum.fx.ui.bench.BenchSection
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Palette
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
        // The kicker is the one exact fact that qualifies everything the line can say: whatever
        // its standing, the keyboard has no internet access. (The pivot already names the page;
        // the dock names the app and its build.)
        Kicker("Offline · no internet access")
        Gap(6.dp)
        HeroLine(
            when {
                !k.installed -> "Not installed yet."
                !k.enabled -> "Installed, not switched on."
                !k.selected -> "On, but not in use."
                else -> "In use."
            },
            p.text,
        )
        Gap(10.dp)

        // The keyboard itself, drawn in ink on the ground — no card, no radius — its keys
        // darkening a step as the keyboard comes alive, the space bar filled once it's the
        // one you type with. TalkBack hears your standing from it too.
        KeyboardGlyph(k)
        Gap(12.dp)

        // News of a newer build: the same ink card as the Kit's own, dripping from the title's
        // drop, so the app speaks about both builds the same way.
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
                PillButton("Install", actions::installKeyboard, icon = R.drawable.ic_import, primary = true)
            }
            BenchRow(
                "Switch it on",
                "Turn on Continuum Keyboard in Android's keyboard list.",
                on = k.enabled,
                expanded = setupOpen == "enable",
                onExpand = { openSetup("enable") },
                trailing = { SetupState(k.enabled) },
            ) {
                PillButton("Open keyboard list", actions::enableKeyboard, icon = R.drawable.ic_keyboard)
            }
            BenchRow(
                "Make it your keyboard",
                "Pick it as the keyboard you type with.",
                on = k.selected,
                expanded = setupOpen == "choose",
                onExpand = { openSetup("choose") },
                trailing = { SetupState(k.selected) },
            ) {
                PillButton("Choose keyboard", actions::chooseKeyboard, icon = R.drawable.ic_keyboard)
            }
        }

        if (k.installed) {
            BenchSection(if (k.selected) 1 else 2, "Settings")
            // The keyboard's settings, group by group in their own order, each caption saying
            // what's in it. Each goes to a page of its own, so each ends in an arrow.
            val pages = listOf(
                Triple("look", "Look", "Ink or tint, the keys' height"),
                Triple("typing", "Typing", "Corrections, capitals, sound and vibration"),
                Triple("swipe", "Swiping", "Trace a word, and swipe to delete"),
                Triple("clipboard", "Clipboard", "What you copied, kept and pinned"),
                Triple("passwords", "Passwords", "Logins and sign-in codes in the strip"),
                Triple("words", "Learned words", "What it picked up from you"),
                Triple("about", "About", "Build number and licences"),
            )
            Column {
                pages.forEach { (page, label, caption) -> BenchLinkRow(label, caption, onOpen = { actions.openKeyboardSettings(page) }) }
            }
        }
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
            // In use, the page's line says so; the dock says which build, and where its words go.
            "Continuum Keyboard", if (k.selected) "Build ${k.build} · tap for settings" else statusText(k),
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
