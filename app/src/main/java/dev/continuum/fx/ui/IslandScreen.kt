package dev.continuum.fx.ui

import android.os.Build
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalView
import dev.continuum.fx.island.ui.InkFeel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.island.CameraLocator
import dev.continuum.fx.island.IslandPrefs
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.IslandSettings
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
import kotlin.math.abs

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
            PillButton("Code", { IslandRepository.demo("code") }, icon = R.drawable.ic_copy, height = 38.dp)
            PillButton("Alarm", { IslandRepository.demo("alarm") }, icon = R.drawable.ic_alarm, height = 38.dp)
            PillButton("Event", { IslandRepository.demo("event") }, icon = R.drawable.ic_event, height = 38.dp)
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
            ToggleRow(
                "One-time codes",
                "Sign-in codes from messages and emails drip down large, with Copy, and Continuum Keyboard offers them in its strip. Kept for five minutes, in memory only.",
                prefs.codes,
            ) {
                actions.updateIsland { it.copy(codes = !it.codes) }
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
            val eventsOn = prefs.events && state.privileges.calendar
            ToggleRow(
                "Calendar events",
                if (prefs.events && !state.privileges.calendar) "Needs calendar access. Tap to allow it."
                else "Your next event from 15 minutes before, counting down, with Join for video calls.",
                eventsOn,
            ) {
                when {
                    eventsOn -> actions.updateIsland { it.copy(events = false) }
                    state.privileges.calendar -> actions.updateIsland { it.copy(events = true) }
                    else -> actions.requestCalendar()
                }
            }
            ToggleRow("Downloads and live updates", "Progress, directions, deliveries and rides while they're happening.", prefs.live) {
                actions.updateIsland { it.copy(live = !it.live) }
            }
            ToggleRow("Sound mode", "Ring, vibrate or silent, for a moment when you switch.", prefs.sound) {
                actions.updateIsland { it.copy(sound = !it.sound) }
            }
            ToggleRow("Headphones", "Headphones, speakers and the car as they connect.", prefs.headphones) {
                actions.updateIsland { it.copy(headphones = !it.headphones) }
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
            ToggleRow(
                "Battery",
                if (prefs.batteryRing) "Your charge as a ring round the camera, so it takes no room: green while charging, red when low. Tap the island for details."
                else "An ink battery on the far right with your charge inside it. Tap the island for details.",
                prefs.alwaysBattery,
            ) {
                actions.updateIsland { it.copy(alwaysBattery = !it.alwaysBattery) }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("Ring round the camera", prefs.batteryRing, onClick = { actions.updateIsland { it.copy(batteryRing = true) } }, on = Surface.Card)
                Chip("Ink battery", !prefs.batteryRing, onClick = { actions.updateIsland { it.copy(batteryRing = false) } }, on = Surface.Card)
            }
        }
        Gap(28.dp)

        PlacementSection(prefs, actions)
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

/**
 * Where the island sits and how big it is. It starts fitted to the camera; move it, fit it to the
 * lens, or resize it, and it shows itself as you go so you can line it up.
 */
@Composable
private fun PlacementSection(prefs: IslandPrefs, actions: AppActions) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val detected = remember {
        try {
            CameraLocator.locate(context, context.getSystemService(WindowManager::class.java)).detected
        } catch (_: Exception) {
            true
        }
    }
    fun place(transform: (IslandPrefs) -> IslandPrefs) {
        actions.updateIsland(transform)
        IslandRepository.placing.value = System.currentTimeMillis()
    }
    /** A slider's 0..1 into [lo]..[hi], in [step]s, catching on [rest] when close to it. */
    fun value(t: Float, lo: Float, hi: Float, step: Float, rest: Float): Float {
        val v = lo + t * (hi - lo)
        if (abs(v - rest) < (hi - lo) * 0.025f) return rest
        return (Math.round(v / step) * step).coerceIn(lo, hi)
    }
    fun frac(v: Float, lo: Float, hi: Float) = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
    fun dpText(v: Float) = if (v % 1f == 0f) "${v.toInt()} dp" else "$v dp"
    fun pct(v: Float) = "${Math.round(v * 100)}%"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt("Placement", 21.sp, p.text, bold = true, modifier = Modifier.weight(1f))
        if (prefs.placed) PillButton("Reset", { place { it.unplaced() } }, height = 36.dp)
    }
    Gap(2.dp)
    Txt(
        if (detected) "The island starts fitted to your camera. Move it, fit it to the lens or resize it here; it shows itself while you do."
        else "No camera cutout was found, so the island sits at the top centre. Move it over your camera here; it shows itself while you do.",
        15.sp, p.sub,
    )
    Gap(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(p.surface)
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 12.dp),
    ) {
        val y = prefs.offsetY
        SliderField(
            "Up and down",
            when {
                y == 0f -> "On the camera"
                y > 0f -> "${dpText(y)} lower"
                else -> "${dpText(-y)} higher"
            },
            frac(y, IslandSettings.PLACE_Y_MIN, IslandSettings.PLACE_Y_MAX),
        ) { t -> place { it.copy(offsetY = value(t, IslandSettings.PLACE_Y_MIN, IslandSettings.PLACE_Y_MAX, 0.5f, 0f)) } }
        val x = prefs.offsetX
        SliderField(
            "Left and right",
            when {
                x == 0f -> "On the camera"
                x > 0f -> "${dpText(x)} right"
                else -> "${dpText(-x)} left"
            },
            frac(x, IslandSettings.PLACE_X_MIN, IslandSettings.PLACE_X_MAX),
        ) { t -> place { it.copy(offsetX = value(t, IslandSettings.PLACE_X_MIN, IslandSettings.PLACE_X_MAX, 0.5f, 0f)) } }
        val c = prefs.cameraSize
        SliderField(
            "Camera size",
            when {
                c == 0f -> "As found"
                c > 0f -> "+${dpText(c)}"
                else -> "−${dpText(-c)}"
            },
            frac(c, IslandSettings.CAMERA_MIN, IslandSettings.CAMERA_MAX),
        ) { t -> place { it.copy(cameraSize = value(t, IslandSettings.CAMERA_MIN, IslandSettings.CAMERA_MAX, 0.25f, 0f)) } }
        Txt("Grow or shrink this until the ink, and the battery ring, hug your lens.", 13.sp, p.sub)
        Gap(8.dp)
        SliderField("Size", pct(prefs.scale), frac(prefs.scale, IslandSettings.SCALE_MIN, IslandSettings.SCALE_MAX)) { t ->
            place { it.copy(scale = value(t, IslandSettings.SCALE_MIN, IslandSettings.SCALE_MAX, 0.01f, 1f)) }
        }
        SliderField("Height", pct(prefs.height), frac(prefs.height, IslandSettings.HEIGHT_MIN, IslandSettings.HEIGHT_MAX)) { t ->
            place { it.copy(height = value(t, IslandSettings.HEIGHT_MIN, IslandSettings.HEIGHT_MAX, 0.01f, 1f)) }
        }
        SliderField(
            "Width",
            if (prefs.width == 0f) "As wide as it needs" else "+${dpText(prefs.width)} each side",
            frac(prefs.width, IslandSettings.WIDTH_MIN, IslandSettings.WIDTH_MAX),
        ) { t -> place { it.copy(width = value(t, IslandSettings.WIDTH_MIN, IslandSettings.WIDTH_MAX, 1f, 0f)) } }
        Txt("Size scales everything together; height and width shape the pill around what's in it.", 13.sp, p.sub)
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
    val view = LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = source, indication = null, role = Role.Switch) {
                InkFeel.toggle(view, !on)
                onToggle()
            }
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
