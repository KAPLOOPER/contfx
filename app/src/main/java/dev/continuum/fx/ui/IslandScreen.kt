package dev.continuum.fx.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.heightIn
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
import dev.continuum.fx.ui.components.ChoiceRow
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.OnOffDot
import dev.continuum.fx.ui.components.InkSection
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.PowerButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Txt
import kotlin.math.abs

internal fun islandOn(state: UiState) = state.island.enabled && state.privileges.islandReady

/** How many kinds of thing the island can show. */
private const val ISLAND_SOURCES = 11

@Composable
internal fun IslandContent(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val prefs = state.island
    var showMore by rememberSaveable { mutableStateOf(false) }
    var aboutInk by rememberSaveable { mutableStateOf(false) }
    Column {
        // What the island shows, said as a sentence, with its switch beside it.
        val on = islandOn(state)
        val shown = buildList {
            if (prefs.media) add("Music")
            if (prefs.calls) add("calls")
            if (prefs.timers) add("timers")
            if (prefs.notifications) add("notifications")
            if (prefs.codes) add("codes")
            if (prefs.alarms) add("alarms")
            if (prefs.events && state.privileges.calendar) add("events")
            if (prefs.live) add("live updates")
            if (prefs.sound) add("sound mode")
            if (prefs.headphones) add("headphones")
            if (prefs.battery) add("charging")
        }.mapIndexed { i, w -> if (i == 0) w.replaceFirstChar { it.uppercase() } else w }
        Kicker(if (on) "In the island" else "Island off", if (on) "${shown.size} of $ISLAND_SOURCES" else null)
        Gap(6.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            HeroLine(
                when {
                    !state.privileges.islandReady -> "Finish setup to start."
                    !prefs.enabled -> "Switched off."
                    shown.isEmpty() -> "Nothing chosen to show."
                    else -> inWords(shown)
                },
                if (on && shown.isNotEmpty()) p.text else p.sub,
                Modifier.weight(1f).padding(end = 12.dp),
            )
            Box(Modifier.padding(top = 2.dp)) {
                OnOffDot(prefs.enabled && state.privileges.islandReady, R.drawable.ic_check, "The island", actions::toggleIsland)
            }
        }
        val waitingFor = when {
            !on -> null
            state.islandLive && !state.islandFed -> "Waiting for notification access"
            !state.islandLive -> "Starting"
            else -> null
        }
        if (waitingFor != null) {
            Gap(10.dp)
            dev.continuum.fx.ui.theme.Note(waitingFor, p.sub)
        }
        Gap(22.dp)

        // Samples, folded into one line until you want them.
        var trying by rememberSaveable { mutableStateOf(false) }
        val trySource = remember { MutableInteractionSource() }
        InkSection(trying, header = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clickable(interactionSource = trySource, indication = null, role = Role.Button) { trying = !trying },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("Try it", 17.sp, p.text, maxLines = 1, modifier = Modifier.weight(1f))
                ValueNote(if (state.islandLive) "12 samples" else "after setup")
            }
        }) {
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
            PillButton("Missed call", { IslandRepository.demo("missed") }, icon = R.drawable.ic_call, height = 38.dp)
            PillButton("Alarm", { IslandRepository.demo("alarm") }, icon = R.drawable.ic_alarm, height = 38.dp)
            PillButton("Event", { IslandRepository.demo("event") }, icon = R.drawable.ic_event, height = 38.dp)
            PillButton("Download", { IslandRepository.demo("download") }, height = 38.dp)
            PillButton("Sound mode", { IslandRepository.demo("sound") }, icon = R.drawable.ic_bell, height = 38.dp)
            PillButton("Charging", { IslandRepository.demo("battery") }, icon = R.drawable.ic_bolt, height = 38.dp)
            PillButton("Clear", { IslandRepository.demo("clear") }, height = 38.dp)
        }
        Gap(14.dp)
        }

        SectionLabel("Show in the island", index = 1)
        Gap(4.dp)
        Column(Modifier.fillMaxWidth()) {
            ToggleRow("Music and video", "Artwork and bars; tap it for controls", prefs.media) {
                actions.updateIsland { it.copy(media = !it.media) }
            }
            ToggleRow("Calls", "Drip down to answer, then a timer", prefs.calls) {
                actions.updateIsland { it.copy(calls = !it.calls) }
            }
            ToggleRow("Timers", "Countdowns and stopwatches", prefs.timers) {
                actions.updateIsland { it.copy(timers = !it.timers) }
            }
            ToggleRow(
                "Notifications",
                "Drip down with their buttons, Reply too",
                prefs.notifications,
            ) {
                actions.updateIsland { it.copy(notifications = !it.notifications) }
            }
            ToggleRow(
                "One-time codes",
                "Large, with Copy, and in the keyboard",
                prefs.codes,
            ) {
                actions.updateIsland { it.copy(codes = !it.codes) }
            }
            ToggleRow("Alarms", "Your next alarm, and Snooze and Stop", prefs.alarms) {
                actions.updateIsland { it.copy(alarms = !it.alarms) }
            }
            if (prefs.alarms) {
                ChoiceRow(Modifier.padding(bottom = 10.dp)) {
                    Chip("12 hours before", !prefs.alarmAlways, onClick = { actions.updateIsland { it.copy(alarmAlways = false) } }, on = Surface.Card)
                    Chip("Whenever one is set", prefs.alarmAlways, onClick = { actions.updateIsland { it.copy(alarmAlways = true) } }, on = Surface.Card)
                }
            }
            MoreSection(showMore, { showMore = !showMore }) {
            val eventsOn = prefs.events && state.privileges.calendar
            ToggleRow(
                "Calendar events",
                if (prefs.events && !state.privileges.calendar) "Needs calendar access: tap to allow"
                else "From 15 minutes before, with Join",
                eventsOn,
                always = prefs.events && !state.privileges.calendar,
            ) {
                when {
                    eventsOn -> actions.updateIsland { it.copy(events = false) }
                    state.privileges.calendar -> actions.updateIsland { it.copy(events = true) }
                    else -> actions.requestCalendar()
                }
            }
            ToggleRow("Downloads and live updates", "Progress, directions, deliveries, rides", prefs.live) {
                actions.updateIsland { it.copy(live = !it.live) }
            }
            ToggleRow("Sound mode", "Ring, vibrate or silent as you switch", prefs.sound) {
                actions.updateIsland { it.copy(sound = !it.sound) }
            }
            ToggleRow("Headphones", "Headphones, speakers and the car", prefs.headphones) {
                actions.updateIsland { it.copy(headphones = !it.headphones) }
            }
            ToggleRow("Charging and battery", "Plugging in, running low, fully charged", prefs.battery) {
                actions.updateIsland { it.copy(battery = !it.battery) }
            }
            ToggleRow("Pull a drop", "Hold and drag down for recent ones", prefs.pull) {
                actions.updateIsland { it.copy(pull = !it.pull) }
            }
            }
        }

        PopupsSection(state, actions)

        SectionLabel("In the bar", index = 3)
        Gap(4.dp)
        Column(Modifier.fillMaxWidth()) {
            ToggleRow("Time", "A drop at the left of the status bar; tap it for the date", prefs.alwaysTime) {
                actions.updateIsland { it.copy(alwaysTime = !it.alwaysTime) }
            }
            ToggleRow("Battery", "A drop at the right, as full as the battery; ink runs into it while charging", prefs.alwaysBattery) {
                actions.updateIsland { it.copy(alwaysBattery = !it.alwaysBattery) }
            }
        }

        PlacementSection(prefs, actions)
        Gap(20.dp)

        MoreSection(aboutInk, { aboutInk = !aboutInk }, label = "How the ink moves") { Txt(
            "The island is the status bar, and the top edge of the screen is where its ink comes from. The time, the camera and the " +
                "battery hang from it as drops, and whatever starts (a song, a call, a timer) hangs as its own drop beside the camera. " +
                "News spreads the camera's drop along the edge into one line: who, the camera, then what. Nothing hangs below the bar " +
                "unless you ask: tap a drop and it falls and lands as its card; push it up and it's drawn back into the edge. Hold the " +
                "camera and drag down to pull out your recent notifications. Ink never appears or vanishes: it leaves the edge and returns to it.",
            15.sp, p.sub,
        ) }
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
    var sizing by rememberSaveable { mutableStateOf(false) }
    fun pct(v: Float) = "${Math.round(v * 100)}%"

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Placement", modifier = Modifier.weight(1f), index = 4)
        if (prefs.placed) PillButton("Reset", { place { it.unplaced() } }, height = 36.dp)
    }
    // Said only when it isn't simply fitted to the camera.
    if (!detected) {
        Txt("No camera cutout was found, so the island sits at the top centre. Move it over your camera here.", 14.sp, p.sub)
    }
    Gap(4.dp)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
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
        MoreSection(sizing, { sizing = !sizing }, label = "Size and shape") {
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
        Txt("Grow or shrink this until the camera's drop hugs your lens.", 13.sp, p.sub)
        Gap(8.dp)
        SliderField("Size", pct(prefs.scale), frac(prefs.scale, IslandSettings.SCALE_MIN, IslandSettings.SCALE_MAX)) { t ->
            place { it.copy(scale = value(t, IslandSettings.SCALE_MIN, IslandSettings.SCALE_MAX, 0.01f, 1f)) }
        }
        Txt("Size scales every drop together.", 13.sp, p.sub)
        }
    }
}

/** Android's own pop-ups: off while the island shows notifications, so you only see one. */
@Composable
private fun PopupsSection(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val prefs = state.island
    val priv = state.privileges
    val context = LocalContext.current
    SectionLabel("Android's own", index = 2)
    Gap(4.dp)
    Column(Modifier.fillMaxWidth()) {
        ToggleRow(
            "Only the island's pop-ups",
            "No banners while the island shows them; the shade keeps everything",
            prefs.hidePopups,
        ) { actions.updateIsland { it.copy(hidePopups = !it.hidePopups) } }
        ToggleRow(
            "Only the island's status bar",
            "Android's clock, battery and icons step aside while the island shows the time and battery",
            prefs.hideStatusIcons,
        ) { actions.updateIsland { it.copy(hideStatusIcons = !it.hideStatusIcons) } }
        if (prefs.hidePopups && !prefs.notifications) {
            Txt(
                "Turn on Notifications above too, or nothing will show new messages.",
                14.sp, p.sub, modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if ((prefs.hidePopups || prefs.hideStatusIcons) && !priv.popups) {
            Column(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                Txt(
                    "Android needs one more permission before the island can step its own pieces aside. Grant it once:",
                    14.sp, p.text,
                )
                Gap(10.dp)
                val label = when (priv.shizuku) {
                    ShizukuState.READY -> "Allow with Shizuku"
                    ShizukuState.NEEDS_PERMISSION -> "Allow Shizuku"
                    ShizukuState.NOT_RUNNING -> "Use Shizuku"
                }
                PillButton(label, actions::grantPopupsWithShizuku, primary = true)
                Gap(10.dp)
                Txt("Or from a computer:", 14.sp, p.sub)
                Txt(Privileges.popupsAdbCommand(context.packageName), 13.sp, p.text, mono = true)
            }
        }
    }
}

@Composable
internal fun ToggleRow(title: String, body: String, on: Boolean, always: Boolean = false, onToggle: () -> Unit) {
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
            .heightIn(min = if (on || always) 62.dp else 52.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Off, it's just its name, quietly; on (or when it needs you), the line under it says
        // what it's doing.
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Txt(title, 17.sp, if (on) p.text else p.sub, maxLines = 1)
            if (on || always) Txt(body, 14.sp, p.sub, maxLines = 2)
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
