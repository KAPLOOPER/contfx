package dev.continuum.fx.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
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
    !k.installed -> setupSteps(0, 3)
    !k.enabled -> setupSteps(1, 3)
    !k.selected -> setupSteps(2, 3)
    else -> "In use · build ${k.build}"
}

/** The next step towards typing with it, or its settings once it's in use. */
private fun nextKeyboardStep(k: KeyboardStatus, actions: AppActions) = when {
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
        Gap(4.dp)

        if (!k.selected) {
            SectionLabel("Set it up", index = 1)
            Gap(4.dp)
            Column(Modifier.fillMaxWidth()) {
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
        }

        if (k.installed) {
            SectionLabel("Settings", index = if (k.selected) 1 else 2)
            Gap(4.dp)
            val pages = listOf(
                "look" to "Look",
                "typing" to "Typing",
                "swipe" to "Swiping",
                "clipboard" to "Clipboard",
                "words" to "Learned words",
                "about" to "About",
            )
            // Each opens a page of the keyboard's settings: rows that lead somewhere end in an arrow.
            Column {
                pages.forEach { (page, label) ->
                    val source = remember(page) { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(interactionSource = source, indication = null, role = Role.Button) { actions.openKeyboardSettings(page) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(label, 17.sp, p.text, modifier = Modifier.weight(1f), maxLines = 1)
                        Chevron(null, p.sub)
                    }
                }
            }
            Gap(24.dp)
        }

        Gap(if (k.installed) 0.dp else 24.dp)
        // Everything it does happens on the phone.
        dev.continuum.fx.ui.theme.Note("Offline · no internet access", p.quiet)
    }
}

/** A numbered step of setting something up, on a page; done, its number turns to a tick. */
@Composable
internal fun SetupStep(number: Int, done: Boolean, title: String, body: String, action: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (done) p.primary else p.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Image(painterResource(R.drawable.ic_check), contentDescription = "Done", modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(p.onPrimary))
            } else {
                Txt("$number", 15.sp, p.text, bold = true)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Txt(title, 17.sp, p.text)
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
