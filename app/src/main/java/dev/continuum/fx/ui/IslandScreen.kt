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
import dev.continuum.fx.island.IslandPrefs
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.setup.Privileges
import dev.continuum.fx.setup.ShizukuState
import dev.continuum.fx.ui.components.Chip
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
            PillButton("Alarm", { IslandRepository.demo("alarm") }, icon = R.drawable.ic_alarm, height = 38.dp)
            PillButton("Download", { IslandRepository.demo("download") }, height = 38.dp)
            PillButton("Sound mode", { IslandRepository.demo("sound") }, icon = R.drawable.ic_bell, height = 38.dp)
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
            ToggleRow(
                "Notifications",
                "New messages drip down with their buttons, Reply included. Swipe sideways to clear, up to tuck away.",
                prefs.notifications,
            ) {
                actions.updateIsland { it.copy(notifications = !it.notifications) }
            }
            ToggleRow("Alarms", "Your next alarm, and alarms and timers as they ring, with Snooze and Stop.", prefs.alarms) {
                actions.updateIsland { it.copy(alarms = !it.alarms) }
            }
            if (prefs.alarms) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("12 hours before", !prefs.alarmAlways, onClick = { actions.updateIsland { it.copy(alarmAlways = false) } }, on = Surface.Card)
                    Chip("Whenever one is set", prefs.alarmAlways, onClick = { actions.updateIsland { it.copy(alarmAlways = true) } }, on = Surface.Card)
                }
            }
            ToggleRow("Downloads and live updates", "Progress, directions, deliveries and rides while they're happening.", prefs.live) {
                actions.updateIsland { it.copy(live = !it.live) }
            }
            ToggleRow("Sound mode", "Ring, vibrate or silent, for a moment when you switch.", prefs.sound) {
                actions.updateIsland { it.copy(sound = !it.sound) }
            }
            ToggleRow("Charging and battery", "Plugging in, wireless charging, running low and fully charged.", prefs.battery) {
                actions.updateIsland { it.copy(battery = !it.battery) }
            }
            ToggleRow("Pull a drop", "Hold the island and drag down for your recent notifications. Swipe one away to clear it.", prefs.pull) {
                actions.updateIsland { it.copy(pull = !it.pull) }
            }
        }
        Gap(28.dp)

        PopupsSection(state, actions)
        Gap(28.dp)

        Txt("Always on", 21.sp, p.text, bold = true)
        Gap(2.dp)
        Txt("Kept in the island all the time, beside music, calls and timers.", 15.sp, p.sub)
        Gap(10.dp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 6.dp)) {
            ToggleRow("Time", "Always in the island. With nothing else on, the date sits opposite.", prefs.alwaysTime) {
                actions.updateIsland { it.copy(alwaysTime = !it.alwaysTime) }
            }
            if (prefs.alwaysTime) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        IslandPrefs.TIME_OUTSIDE to "Left of the art",
                        IslandPrefs.TIME_INSIDE to "Right of the art",
                        IslandPrefs.TIME_SPLIT to "Around the camera",
                    ).forEach { (spot, label) ->
                        Chip(label, prefs.timeSpot == spot, onClick = { actions.updateIsland { it.copy(timeSpot = spot) } }, on = Surface.Card)
                    }
                }
            }
            ToggleRow("Battery", "An ink battery on the far right with your charge inside it. Tap the island for details.", prefs.alwaysBattery) {
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
                "pull past the snap and it tears free as your recent notifications. Swipe a notification sideways and it " +
                "leans away on a neck that thins and snaps; swipe up to send it back. Nothing pops in or out: ink only separates and merges.",
            15.sp, p.sub,
        )
    }
}

/** Android's own pop-ups: off while the island shows notifications, so you only see one. */
@Composable
private fun PopupsSection(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val prefs = state.island
    val priv = state.privileges
    val context = LocalContext.current
    Txt("Android's pop-ups", 21.sp, p.text, bold = true)
    Gap(2.dp)
    Txt("Stop the usual banner appearing as well as the island.", 15.sp, p.sub)
    Gap(10.dp)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 6.dp)) {
        ToggleRow(
            "Only the island",
            "Android's banners stay away while the island shows notifications. Calls ring in the island, " +
                "alarms open full screen, and everything still goes to your notification shade. " +
                "Banners come back whenever the island is off.",
            prefs.hidePopups,
        ) { actions.updateIsland { it.copy(hidePopups = !it.hidePopups) } }
        if (prefs.hidePopups && !prefs.notifications) {
            Txt(
                "Turn on Notifications above too, or nothing will show new messages.",
                14.sp, p.sub, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
            )
        }
        if (prefs.hidePopups && !priv.popups) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp)) {
                Txt(
                    "Android needs one more permission before the island can switch its banners off. Grant it once:",
                    14.sp, p.text,
                )
                Gap(10.dp)
                val label = when (priv.shizuku) {
                    ShizukuState.READY -> "Allow with Shizuku"
                    ShizukuState.NEEDS_PERMISSION -> "Allow Shizuku"
                    ShizukuState.NOT_RUNNING -> "Use Shizuku"
                }
                PillButton(label, actions::grantPopupsWithShizuku, primary = true, on = Surface.Card)
                Gap(10.dp)
                Txt("Or from a computer:", 14.sp, p.sub)
                Txt(Privileges.popupsAdbCommand(context.packageName), 13.sp, p.text, mono = true)
            }
        }
    }
}

@Composable
internal fun ToggleRow(title: String, body: String, on: Boolean, onToggle: () -> Unit) {
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
