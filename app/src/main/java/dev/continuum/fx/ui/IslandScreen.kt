package dev.continuum.fx.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.setup.Privileges
import dev.continuum.fx.setup.ShizukuState
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.OnOffDot
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.PowerButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Txt

private fun islandOn(state: UiState) = state.island.enabled && state.privileges.islandReady

@Composable
private fun IslandStatusLine(state: UiState) {
    val p = LocalPalette.current
    val text = when {
        !state.privileges.islandReady -> "Off · finish setup to start"
        !state.island.enabled -> "Off"
        state.islandLive && state.islandFed -> "On · around your camera"
        state.islandLive -> "On · waiting for notification access"
        else -> "On · starting"
    }
    Txt(text, 17.sp, if (islandOn(state)) p.text else p.sub, maxLines = 2)
}

@Composable
internal fun IslandContent(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val prefs = state.island
    Column {
        IslandStatusLine(state)
        Gap(22.dp)

        Txt("Try it", 21.sp, p.text, bold = true)
        Gap(2.dp)
        Txt(
            if (state.islandLive) "Send a sample to the island and watch the ink move."
            else "Finish setup and switch the island on to try it.",
            15.sp, p.sub,
        )
        Gap(12.dp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton("Music", { IslandRepository.demo("media") }, icon = R.drawable.ic_music, height = 38.dp)
            PillButton("Call", { IslandRepository.demo("call") }, icon = R.drawable.ic_call, height = 38.dp)
            PillButton("Timer", { IslandRepository.demo("timer") }, icon = R.drawable.ic_timer, height = 38.dp)
            PillButton("Notification", { IslandRepository.demo("note") }, icon = R.drawable.ic_island, height = 38.dp)
            PillButton("Charging", { IslandRepository.demo("battery") }, icon = R.drawable.ic_bolt, height = 38.dp)
            PillButton("Clear", { IslandRepository.demo("clear") }, height = 38.dp)
        }
        Gap(28.dp)

        Txt("Show in the island", 21.sp, p.text, bold = true)
        Gap(10.dp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 6.dp)) {
            ToggleRow("Music and video", "Artwork and playing bars; open it for controls.", prefs.media) {
                actions.updateIsland { it.copy(media = !it.media) }
            }
            ToggleRow("Calls", "Incoming calls drip down to answer; ongoing calls keep a timer.", prefs.calls) {
                actions.updateIsland { it.copy(calls = !it.calls) }
            }
            ToggleRow("Timers", "Countdowns and stopwatches from your clock app.", prefs.timers) {
                actions.updateIsland { it.copy(timers = !it.timers) }
            }
            ToggleRow("Notifications", "New messages drip down for a few seconds. Swipe up to dismiss.", prefs.notifications) {
                actions.updateIsland { it.copy(notifications = !it.notifications) }
            }
            ToggleRow("Charging and battery", "Plugging in, wireless charging, running low and fully charged.", prefs.battery) {
                actions.updateIsland { it.copy(battery = !it.battery) }
            }
            ToggleRow("Pull a drop", "Hold the island and drag down to pull out your recent notifications.", prefs.pull) {
                actions.updateIsland { it.copy(pull = !it.pull) }
            }
        }
        Gap(28.dp)

        Txt("Always on", 21.sp, p.text, bold = true)
        Gap(2.dp)
        Txt("Kept in the island all the time, beside music, calls and timers.", 15.sp, p.sub)
        Gap(10.dp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 6.dp)) {
            ToggleRow("Time", "On the left of the island. With nothing else on, the date sits opposite.", prefs.alwaysTime) {
                actions.updateIsland { it.copy(alwaysTime = !it.alwaysTime) }
            }
            ToggleRow("Battery", "On the right, in a liquid battery that ripples while charging. Tap the island for details.", prefs.alwaysBattery) {
                actions.updateIsland { it.copy(alwaysBattery = !it.alwaysBattery) }
            }
        }
        Gap(28.dp)

        Txt("How it moves", 21.sp, p.text, bold = true)
        Gap(4.dp)
        Txt(
            "The island is one piece of ink that starts as your camera. It stretches into a pill for what's live, " +
                "pinches off a second bubble when two things are running, and drips alerts down from the camera. " +
                "Hold it and drag down to pull a drop out on a stretchy neck: let go early and it springs back, " +
                "pull past the snap and it tears free as your recent notifications. Swipe the card sideways for more, " +
                "up to send it back. Nothing pops in or out: ink only separates and merges.",
            15.sp, p.sub,
        )
    }
}

@Composable
private fun ToggleRow(title: String, body: String, on: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = source, indication = null, role = Role.Switch, onClick = onToggle)
            .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, 17.sp, p.text, maxLines = 1)
            Txt(body, 14.sp, p.sub)
        }
        OnOffDot(on, R.drawable.ic_check, title, onToggle)
    }
}

@Composable
internal fun IslandDockBar(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val prefs = state.island
    val on = islandOn(state)
    val sources = buildList {
        if (prefs.media) add("music")
        if (prefs.calls) add("calls")
        if (prefs.timers) add("timers")
        if (prefs.notifications) add("alerts")
        if (prefs.battery) add("charging")
    }
    val subtitle = when {
        !state.privileges.islandReady -> "Tap to set up"
        !prefs.enabled -> "Switched off"
        sources.isEmpty() -> "Nothing selected to show"
        else -> sources.joinToString(", ").replaceFirstChar { it.uppercase() }
    }
    Row(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PowerButton(on, R.drawable.ic_island, actions::toggleIsland, what = "the island")
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Txt(if (on) "Island on" else "Island off", 18.sp, p.onInk, bold = true, maxLines = 1)
            Txt(subtitle, 14.sp, p.onInkSub, maxLines = 1)
        }
        IconBubble(R.drawable.ic_setup, "Island setup", onClick = { actions.setSetupOpen(true) }, on = Surface.Ink)
    }
}

@Composable
internal fun IslandSetupSheet(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val priv = state.privileges
    val packageName = LocalContext.current.packageName
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Set up the island", 24.sp, p.onInk, bold = true, modifier = Modifier.weight(1f))
            IconBubble(R.drawable.ic_chevron_down, "Close setup", onClick = { actions.setSetupOpen(false) }, on = Surface.Ink)
        }
        Gap(4.dp)
        Txt(
            "Android needs two switches for the island. Shizuku can flip both at once, or you can do it in Settings.",
            15.sp, p.onInkSub,
        )
        if (!priv.islandReady) {
            Gap(14.dp)
            val label = when (priv.shizuku) {
                ShizukuState.READY -> "Switch on with Shizuku"
                ShizukuState.NEEDS_PERMISSION -> "Allow Shizuku"
                ShizukuState.NOT_RUNNING -> "Use Shizuku"
            }
            PillButton(label, actions::grantIslandWithShizuku, primary = true, on = Surface.Ink)
        }
        Gap(6.dp)

        StepRow(
            1, priv.islandOverlay, "Draw the island",
            "An accessibility service draws the island over apps and the lock screen. It doesn't read anything on screen.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Open accessibility settings", actions::openAccessibilitySettings, on = Surface.Ink)
                if (Build.VERSION.SDK_INT >= 33) {
                    Txt(
                        "If the switch is greyed out, open App info, tap the menu in the corner and choose Allow restricted settings.",
                        14.sp, p.onInkSub,
                    )
                    PillButton("App info", actions::openAppInfo, on = Surface.Ink)
                }
            }
        }
        StepRow(
            2, priv.islandListener, "See what's live",
            "Notification access lets the island show music, calls, timers and new notifications.",
        ) {
            PillButton("Open notification access", actions::openListenerSettings, on = Surface.Ink)
        }

        if (!priv.islandReady) {
            Gap(6.dp)
            Txt("From a computer with ADB, these unlock the settings and give notification access:", 14.sp, p.onInkSub)
            Gap(10.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(p.inkRaised)
                    .padding(14.dp),
            ) {
                Txt(Privileges.islandAdbCommands(packageName), 13.sp, p.onInk, mono = true)
            }
            Gap(10.dp)
            PillButton("Copy commands", actions::copyIslandCommands, icon = R.drawable.ic_copy, on = Surface.Ink)
            Gap(8.dp)
            Txt("Then switch on Continuum island in accessibility settings.", 14.sp, p.onInkSub)
        } else {
            Gap(14.dp)
            PillButton(
                if (state.island.enabled) "Done" else "Switch the island on",
                {
                    actions.setSetupOpen(false)
                    if (!state.island.enabled) actions.toggleIsland()
                },
                icon = R.drawable.ic_island,
                primary = true,
                on = Surface.Ink,
            )
        }
    }
}
