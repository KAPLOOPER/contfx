package dev.continuum.fx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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

private fun statusText(k: KeyboardStatus) = when {
    !k.installed -> "Not installed yet"
    !k.enabled -> "Installed · switch it on"
    !k.selected -> "On · not in use right now"
    else -> "In use · build ${k.build}"
}

/** The Keyboard tab: get Continuum Keyboard going, then jump into its settings. */
@Composable
internal fun KeyboardContent(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val k = state.keyboard
    Column {
        Txt(statusText(k), 17.sp, if (k.selected) p.text else p.sub, maxLines = 2)

        UpdateCard(
            update = k.update,
            title = { if (k.installed) "Keyboard build ${it.build} is ready" else "Continuum Keyboard" },
            subtitle = if (k.installed) "Your words, settings and layouts stay as they are."
            else "Build ${(k.update as? UpdateState.Available)?.release?.build ?: ""} · Continuum's own keyboard".trim(),
            actionLabel = if (k.installed) "Update now" else "Install",
            installingNote = "Installing Continuum Keyboard. Android may ask you to confirm.",
            onAction = actions::installKeyboard,
            onAllow = actions::allowInstalls,
            onDismiss = actions::dismissKeyboardUpdate,
        )
        Gap(18.dp)

        if (!k.selected) {
            Txt("Set it up", 21.sp, p.text, bold = true)
            Gap(10.dp)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 6.dp)) {
                SetupStep(1, k.installed, "Install it", "Continuum Keyboard is its own app, installed and kept up to date from here.") {
                    PillButton("Install", actions::installKeyboard, icon = R.drawable.ic_import, primary = true, on = Surface.Card)
                }
                SetupStep(2, k.enabled, "Switch it on", "Turn on Continuum Keyboard in Android's keyboard list.") {
                    PillButton("Open keyboard list", actions::enableKeyboard, on = Surface.Card)
                }
                SetupStep(3, k.selected, "Make it your keyboard", "Pick it as the keyboard you type with.") {
                    PillButton("Choose keyboard", actions::chooseKeyboard, on = Surface.Card)
                }
            }
            Gap(28.dp)
        }

        if (k.installed) {
            Txt("Settings", 21.sp, p.text, bold = true)
            Gap(10.dp)
            val pages = listOf(
                "look" to "Look",
                "typing" to "Typing",
                "swipe" to "Swiping",
                "clipboard" to "Clipboard",
                "words" to "Learned words",
                "about" to "About",
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (page, label) ->
                            PillButton(label, { actions.openKeyboardSettings(page) }, Modifier.weight(1f))
                        }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            Gap(24.dp)
        }

        Txt(
            "Continuum Keyboard is its own app, kept up to date from here. Swipe typing, corrections, clipboard history and scan text all work on your phone; the keyboard has no internet access.",
            13.sp, p.sub,
        )
    }
}

@Composable
private fun SetupStep(number: Int, done: Boolean, title: String, body: String, action: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (done) p.live else p.ground),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Image(painterResource(R.drawable.ic_check), contentDescription = "Done", modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(p.onLive))
            } else {
                Txt("$number", 15.sp, p.text, bold = true)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Txt(title, 17.sp, p.text, bold = true)
            Txt(body, 14.sp, p.sub)
            if (!done) {
                Gap(10.dp)
                action()
            }
        }
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
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Txt("Continuum Keyboard", 18.sp, p.onInk, bold = true, maxLines = 1)
            Txt(statusText(k), 14.sp, p.onInkSub, maxLines = 1)
        }
        if (k.installed) {
            IconBubble(R.drawable.ic_setup, "Keyboard settings", onClick = { actions.openKeyboardSettings(null) }, on = Surface.Ink)
        }
    }
}
