package dev.continuum.fx.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import dev.continuum.fx.ui.bench.BenchField
import dev.continuum.fx.ui.bench.BenchGrid
import dev.continuum.fx.ui.bench.BenchHero
import dev.continuum.fx.ui.bench.BenchRow
import dev.continuum.fx.ui.bench.BenchSection
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.ChoiceRow
import dev.continuum.fx.ui.components.Reveal
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.InkSlider
import dev.continuum.fx.ui.components.InkSwitch
import dev.continuum.fx.ui.components.MasterSwitch
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.PowerButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Txt
import dev.continuum.fx.island.placementStep

internal fun islandOn(state: UiState) = state.island.enabled && state.privileges.islandReady

/** What the island is set to show, in words, the first with its capital: "Music, calls, timers…". */
internal fun islandShows(state: UiState): List<String> {
    val prefs = state.island
    return buildList {
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
}

/** How many kinds of thing the island can show. */
private const val ISLAND_SOURCES = 11

@Composable
internal fun IslandContent(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val prefs = state.island
    var aboutInk by rememberSaveable { mutableStateOf(false) }
    Column {
        // The kicker starts the sentence the line finishes: what the status bar shows. Off, it's
        // Android's own; on, the kinds of things the island draws there, counted against the
        // whole. The switch at the line's end is named under it ("Island"): the line says what
        // shows, so a bare switch beside "Android's own icons." would read as those being off.
        val on = islandOn(state)
        val shown = islandShows(state)
        Kicker("The status bar shows", if (on) "${shown.size} of $ISLAND_SOURCES" else null)
        Gap(6.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeroLine(
                when {
                    !on -> "Android's own icons."
                    shown.isEmpty() -> "Nothing chosen yet."
                    else -> inWords(shown)
                },
                p.text,
                Modifier.weight(1f).padding(end = 12.dp),
            )
            MasterSwitch(prefs.enabled && state.privileges.islandReady, "Island", actions::toggleIsland)
        }
        Gap(18.dp)

        // The island itself, in the page, drawn on the top of a phone's screen: lit where a screen
        // is, rounded at its top corners as a display is, and running on down out of the drawing
        // (it fades into the ground) rather than ending in an edge, so it reads as the phone and not
        // as a box on the page. The real island draws itself into it, and samples from Try it
        // play into it even before setup.
        BenchHero {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .drawBehind {
                        val fade = 30.dp.toPx().coerceAtMost(size.height * 0.6f)
                        drawRect(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to p.surface,
                                ((size.height - fade) / size.height).coerceIn(0f, 1f) to p.surface,
                                1f to p.surface.copy(alpha = 0f),
                            ),
                        )
                    }
                    .padding(bottom = 10.dp),
            ) { IslandPreview() }
        }
        // What it is, under the drawing: one look now, so a line says how it behaves.
        Gap(18.dp)
        Txt(
            "A black star on the camera, the battery traced round it in a fine sliver. What's live rides out " +
                "beside it on a slim band; news turns the star; anything with more to say hangs from it on two threads.",
            13.5.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL),
        )
        Gap(30.dp)
        var moreDemos by rememberSaveable { mutableStateOf(false) }
        // Samples of what the island shows, played into the preview above: one table, two
        // columns, the last cell the way to the rest.
        Note("Try it", p.sub, Modifier.padding(start = INK_RAIL))
        BenchGrid(2, Modifier.padding(start = INK_RAIL, top = 2.dp)) {
            PillButton("Music", { IslandRepository.demo("media") }, icon = R.drawable.ic_music)
            PillButton("Call", { IslandRepository.demo("call") }, icon = R.drawable.ic_call)
            PillButton("Timer", { IslandRepository.demo("timer") }, icon = R.drawable.ic_timer)
            PillButton("Notification", { IslandRepository.demo("note") }, icon = R.drawable.ic_notification)
            PillButton("Code", { IslandRepository.demo("code") }, icon = R.drawable.ic_copy)
            if (moreDemos) {
                PillButton("Missed call", { IslandRepository.demo("missed") }, icon = R.drawable.ic_call_end)
                PillButton("Alarm", { IslandRepository.demo("alarm") }, icon = R.drawable.ic_alarm)
                PillButton("Event", { IslandRepository.demo("event") }, icon = R.drawable.ic_event)
                PillButton("Download", { IslandRepository.demo("download") }, icon = R.drawable.ic_import)
                PillButton("Sound mode", { IslandRepository.demo("sound") }, icon = R.drawable.ic_bell)
                PillButton("Charging", { IslandRepository.demo("battery") }, icon = R.drawable.ic_bolt)
                PillButton("Clear", { IslandRepository.demo("clear") }, icon = R.drawable.ic_trash)
            }
            MoreSamples(moreDemos) { moreDemos = !moreDemos }
        }

        // Getting the island its two Android switches, as rows like every other: the state at the
        // row's end, the detail (the settings buttons, the restricted-settings note) opened
        // beneath it on the ink thread.
        BenchSection(1, "Set it up")
        var setupOpen by rememberSaveable { mutableStateOf<String?>(null) }
        fun openSetup(key: String) { setupOpen = if (setupOpen == key) null else key }
        BenchRow(
            "Draw the island",
            "Accessibility · draws over apps, reads nothing",
            on = state.privileges.islandOverlay,
            expanded = setupOpen == "draw",
            onExpand = { openSetup("draw") },
            trailing = { SetupState(state.privileges.islandOverlay) },
        ) {
            PillButton("Open accessibility settings", actions::openAccessibilitySettings, icon = R.drawable.ic_island)
            if (Build.VERSION.SDK_INT >= 33) {
                Gap(4.dp)
                Txt("Greyed out? Open App info, tap the menu in the corner and choose Allow restricted settings.", 14.sp, p.sub)
                Gap(4.dp)
                PillButton("App info", actions::openAppInfo, icon = R.drawable.ic_setup)
            }
        }
        BenchRow(
            "See what's live",
            "Notification access · music, calls, timers",
            on = state.privileges.islandListener,
            expanded = setupOpen == "listen",
            onExpand = { openSetup("listen") },
            trailing = { SetupState(state.privileges.islandListener) },
        ) {
            PillButton("Open notification access", actions::openListenerSettings, icon = R.drawable.ic_bell)
        }
        if (!state.privileges.islandReady) {
            // One way in, in the sheet: the two rows above say what each switch is, and the strip
            // says where to go. A third and fourth call to action on the page would just compete.
            BenchRow(
                "All of it at once",
                "Shizuku can flip both switches, and open them from a computer if it isn't running",
                on = false,
                onAct = { actions.setSetupOpen(true) },
                trailing = { Note("Set up", p.text) },
            )
        }

        PlacementSection(prefs, actions)

        // What the island does, in the groups it falls into: what it shows, what it speaks up
        // for, what it keeps in the bar, and what it steps aside for. Plain rows, the ink
        // in the margin carrying the state, and each rule saying how many of its group are on:
        // while the island is, as Effects' rules count only while the effects are on. Off, the
        // rows still show what's chosen; a count over them would say "on" of nothing showing.
        val eventsOn = prefs.events && state.privileges.calendar
        fun lit(vararg v: Boolean): String? = v.count { it }.let { if (it == 0 || !on) null else "$it on" }
        BenchSection(3, "Shows", trail = lit(prefs.media, prefs.calls, prefs.timers, prefs.notifications, prefs.codes, prefs.alarms, eventsOn, prefs.live, prefs.pull))
        IslandRow("Music and video", "Its art in a turning star; tap it for controls", prefs.media) {
            actions.updateIsland { it.copy(media = !it.media) }
        }
        IslandRow("Calls", "Hangs with Answer and Decline, then counts", prefs.calls) {
            actions.updateIsland { it.copy(calls = !it.calls) }
        }
        IslandRow("Timers", "Countdowns and stopwatches", prefs.timers) {
            actions.updateIsland { it.copy(timers = !it.timers) }
        }
        IslandRow("Notifications", "Hang as a ticket; tap for their buttons and Reply", prefs.notifications) {
            actions.updateIsland { it.copy(notifications = !it.notifications) }
        }
        IslandRow("One-time codes", "Large, with Copy, and in the keyboard", prefs.codes) {
            actions.updateIsland { it.copy(codes = !it.codes) }
        }
        IslandRow("Alarms", "Your next alarm, and Snooze and Stop", prefs.alarms) {
            actions.updateIsland { it.copy(alarms = !it.alarms) }
        }
        Reveal(prefs.alarms) {
            ChoiceRow(Modifier.padding(start = INK_RAIL, bottom = 10.dp)) {
                Chip("12 hours before", !prefs.alarmAlways, onClick = { actions.updateIsland { it.copy(alarmAlways = false) } })
                Chip("Always", prefs.alarmAlways, onClick = { actions.updateIsland { it.copy(alarmAlways = true) } })
            }
        }
        IslandRow(
            "Calendar events",
            if (prefs.events && !state.privileges.calendar) "Needs calendar access: tap to allow"
            else "From 15 minutes before, with Join",
            eventsOn,
            resting = prefs.events && !state.privileges.calendar,
        ) {
            when {
                eventsOn -> actions.updateIsland { it.copy(events = false) }
                state.privileges.calendar -> actions.updateIsland { it.copy(events = true) }
                else -> actions.requestCalendar()
            }
        }
        IslandRow("Downloads and live updates", "Progress, directions, deliveries, rides", prefs.live) {
            actions.updateIsland { it.copy(live = !it.live) }
        }
        IslandRow("Pull the thread", "Drag the star down for recent ones", prefs.pull) {
            actions.updateIsland { it.copy(pull = !it.pull) }
        }

        // What speaks up for a moment, then goes.
        BenchSection(4, "Speaks up", trail = lit(prefs.sound, prefs.headphones, prefs.battery))
        IslandRow("Sound mode", "Ring, vibrate or silent as you switch", prefs.sound) {
            actions.updateIsland { it.copy(sound = !it.sound) }
        }
        IslandRow("Headphones", "Headphones, speakers and the car", prefs.headphones) {
            actions.updateIsland { it.copy(headphones = !it.headphones) }
        }
        IslandRow("Charging and battery", "Plugging in, running low, fully charged", prefs.battery) {
            actions.updateIsland { it.copy(battery = !it.battery) }
        }

        // What the island keeps in the bar itself.
        BenchSection(5, "In the bar", trail = lit(prefs.alwaysTime, prefs.alwaysBattery))
        IslandRow("Time", "Tap it for the date", prefs.alwaysTime) {
            actions.updateIsland { it.copy(alwaysTime = !it.alwaysTime) }
        }
        Reveal(prefs.alwaysTime) {
            ChoiceRow(Modifier.padding(start = INK_RAIL, bottom = 10.dp)) {
                Chip("At the end", prefs.timeSpot == IslandPrefs.TIME_OUTSIDE, onClick = { actions.updateIsland { it.copy(timeSpot = IslandPrefs.TIME_OUTSIDE) } })
                Chip("By the star", prefs.timeSpot == IslandPrefs.TIME_INSIDE, onClick = { actions.updateIsland { it.copy(timeSpot = IslandPrefs.TIME_INSIDE) } })
                Chip("Either side", prefs.timeSpot == IslandPrefs.TIME_SPLIT, onClick = { actions.updateIsland { it.copy(timeSpot = IslandPrefs.TIME_SPLIT) } })
            }
        }
        IslandRow(
            "Battery",
            "A sliver round the star as far as it's charged; green and running while it charges",
            prefs.alwaysBattery,
        ) { actions.updateIsland { it.copy(alwaysBattery = !it.alwaysBattery) } }

        // And what the island steps aside for: Android's own pieces, silenced one by one.
        BenchSection(6, "Steps aside", trail = lit(prefs.hidePopups, prefs.hideStatusIcons))
        IslandRow(
            "Only the island's pop-ups",
            "No banners while the island shows them; the shade keeps everything",
            prefs.hidePopups,
        ) { actions.updateIsland { it.copy(hidePopups = !it.hidePopups) } }
        IslandRow(
            "Only the island's status bar",
            "Android's clock, battery and icons step aside while the island shows the time and battery",
            prefs.hideStatusIcons,
        ) { actions.updateIsland { it.copy(hideStatusIcons = !it.hideStatusIcons) } }
        Reveal(prefs.hidePopups && !prefs.notifications) {
            Txt(
                "Turn on Notifications above too, or nothing will show new messages.",
                14.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL, top = 4.dp),
            )
        }
        Reveal((prefs.hidePopups || prefs.hideStatusIcons) && !state.privileges.popups) {
            Column(Modifier.padding(start = INK_RAIL, top = 8.dp, bottom = 12.dp)) {
                Txt(
                    "Android needs one more permission before the island can step its own pieces aside. Grant it once:",
                    14.sp, p.text,
                )
                Gap(10.dp)
                val shizuku = when (state.privileges.shizuku) {
                    ShizukuState.READY -> "Allow with Shizuku"
                    ShizukuState.NEEDS_PERMISSION -> "Allow Shizuku"
                    ShizukuState.NOT_RUNNING -> "Use Shizuku"
                }
                PillButton(shizuku, actions::grantPopupsWithShizuku, icon = R.drawable.ic_bolt, primary = true)
                Gap(10.dp)
                Txt("Or from a computer:", 14.sp, p.sub)
                Txt(Privileges.popupsAdbCommand(LocalContext.current.packageName), 13.sp, p.text, mono = true)
            }
        }

        Gap(8.dp)
        MoreSection(aboutInk, { aboutInk = !aboutInk }, label = "How the star moves") { Txt(
            "The star sits on the camera and grows out of it when the island starts. The sliver round it is the battery, read " +
                "clockwise from the top tip like a clock face: green while it charges, with a spark running along it, and red when " +
                "it's low. Whatever starts (a song, a call, a timer) rides out beside the star on a slim band, and a moment's news " +
                "runs along the same band with the star between its halves. Each piece of news turns the star a quarter. Anything " +
                "with more to say hangs from the star on two threads and swings as it lands: tap it for its card, push it up to put " +
                "it away, slide it aside to clear it. Drag the star down and a thread runs out with it; let go past the mark for " +
                "your recent notifications.",
            15.sp, p.sub,
        ) }
    }
}

/** The last cell of the samples' table: opens the rest in place, so the chevron follows the words. */
@Composable
private fun MoreSamples(open: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = if (open) "Show fewer samples" else "Show every sample",
                onClick = onToggle,
            )
            .opens(open, onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Under the other cells' words, not their icons: past a button's 18dp icon and the 8dp after it.
        Spacer(Modifier.width(18.dp + 8.dp))
        OpensName(if (open) "Fewer" else "Every one", open, p.sub, size = 16.sp)
    }
}

/** An appearance row of the island's: its caption says what it does, the ink and the switch carry the state. */
@Composable
private fun IslandRow(label: String, caption: String, on: Boolean, resting: Boolean = false, onToggle: () -> Unit) {
    BenchRow(label, caption, on = on, resting = resting, onToggle = onToggle, switchDescription = label)
}

/** A setup row's state: a drop of ink beside "On" when done, "Needs you" when it isn't. */
@Composable
internal fun SetupState(done: Boolean) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp)) {
            if (done) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(p.text))
            }
        }
        Spacer(Modifier.width(6.dp))
        Note(if (done) "On" else "Needs you", if (done) p.sub else p.danger)
    }
}

/**
 * Where the island sits and how big it is: fields under the section's rule, each its own slider
 * with the value in the note face at its end. It starts fitted to the camera; move it, fit it to
 * the lens, or resize it, and it shows itself as you go so you can line it up.
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
    fun dpText(v: Float) = if (v % 1f == 0f) "${v.toInt()} dp" else "$v dp"
    fun pct(v: Float) = "${Math.round(v * 100)}%"
    fun adjustment(v: Float) = if (v == 0f) "Automatic" else "${if (v > 0f) "+" else ""}${dpText(v)}"

    BenchSection(2, "Placement")
    if (!detected) {
        Txt("No camera cutout was found, so the island sits at the top centre. Move it over your camera here.", 14.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL))
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlacementControl("Up and down", prefs.offsetY, IslandSettings.PLACE_Y_MIN, IslandSettings.PLACE_Y_MAX,
            0.25f, dpText(prefs.offsetY), "Negative moves up; positive moves down.") { v -> place { it.copy(offsetY = v) } }
        PlacementControl("Left and right", prefs.offsetX, IslandSettings.PLACE_X_MIN, IslandSettings.PLACE_X_MAX,
            0.25f, dpText(prefs.offsetX), "Negative moves left; positive moves right.") { v -> place { it.copy(offsetX = v) } }
        PlacementControl("Camera size", prefs.cameraSize, IslandSettings.CAMERA_MIN, IslandSettings.CAMERA_MAX,
            0.25f, adjustment(prefs.cameraSize), "Fit the black centre to your lens, then adjust the star's points below.") { v -> place { it.copy(cameraSize = v) } }
        PlacementControl("Star width", prefs.starWidth, IslandSettings.STAR_MIN, IslandSettings.STAR_MAX,
            0.01f, pct(prefs.starWidth), "Move the side points without resizing the bar or its text.") { v -> place { it.copy(starWidth = v) } }
        PlacementControl("Star height", prefs.starHeight, IslandSettings.STAR_MIN, IslandSettings.STAR_MAX,
            0.01f, pct(prefs.starHeight), "Shorten the top and bottom points to fit the screen edge.") { v -> place { it.copy(starHeight = v) } }
        PlacementControl("Bar width", prefs.barWidth, IslandSettings.BAR_WIDTH_MIN, IslandSettings.BAR_WIDTH_MAX,
            0.25f, adjustment(prefs.barWidth), "Extra space at each end. The bar still grows to fit its content.") { v -> place { it.copy(barWidth = v) } }
        PlacementControl("Bar height", prefs.barHeight, IslandSettings.BAR_HEIGHT_MIN, IslandSettings.BAR_HEIGHT_MAX,
            0.25f, adjustment(prefs.barHeight), "Change the band's thickness without resizing the star.") { v -> place { it.copy(barHeight = v) } }
        PlacementControl("Overall size", prefs.scale, IslandSettings.SCALE_MIN, IslandSettings.SCALE_MAX,
            0.01f, pct(prefs.scale), "Scale the island's text, spacing and cards together.") { v -> place { it.copy(scale = v) } }
    }
    Reveal(prefs.placed) {
        Gap(4.dp)
        PillButton("Reset", { place { it.unplaced() } }, icon = R.drawable.ic_undo, modifier = Modifier.padding(start = INK_RAIL))
    }
}

/** A wide slider for coarse movement, with exact steps that do not snap back to the default. */
@Composable
private fun PlacementControl(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    label: String,
    note: String,
    onChange: (Float) -> Unit,
) {
    BenchField(
        title, label, note = note,
        leading = {
            IconBubble(R.drawable.ic_minus, "Decrease $title", {
                onChange(placementStep(value - step, min, max, step))
            })
        },
        trailing = {
            IconBubble(R.drawable.ic_plus, "Increase $title", {
                onChange(placementStep(value + step, min, max, step))
            })
        },
    ) {
        InkSlider(
            value = ((value - min) / (max - min)).coerceIn(0f, 1f),
            label = "$title: $label",
            onChange = { onChange(placementStep(min + it * (max - min), min, max, step)) },
        )
    }
}

@Composable
internal fun IslandDockBar(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val prefs = state.island
    val on = islandOn(state)
    // The page says what the island shows; the dock says how it's doing: set up or not, and once
    // on, whether it's live yet.
    val subtitle = when {
        !state.privileges.islandReady -> setupSteps(listOf(state.privileges.islandOverlay, state.privileges.islandListener).count { it }, 2)
        !prefs.enabled -> "Set up and ready"
        !state.islandLive -> "Starting"
        !state.islandFed -> "Waiting for notification access"
        else -> "Live in the status bar"
    }
    Row(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PowerButton(on, R.drawable.ic_island, actions::toggleIsland, what = "the island")
        DockText(if (on) "Island on" else "Island off", subtitle, "Open island setup") { actions.setSetupOpen(true) }
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
            .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Set up the island", 22.sp, p.onInk, bold = true, modifier = Modifier.weight(1f))
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
            PillButton(label, actions::grantIslandWithShizuku, icon = R.drawable.ic_bolt, primary = true, on = Surface.Ink, modifier = Modifier.padding(start = INK_RAIL))
        }
        Gap(6.dp)

        StepRow(
            1, priv.islandOverlay, "Draw the island",
            "An accessibility service draws the island over apps and the lock screen. It doesn't read anything on screen.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Open accessibility settings", actions::openAccessibilitySettings, icon = R.drawable.ic_island, on = Surface.Ink)
                if (Build.VERSION.SDK_INT >= 33) {
                    Txt(
                        "If the switch is greyed out, open App info, tap the menu in the corner and choose Allow restricted settings.",
                        14.sp, p.onInkSub,
                    )
                    PillButton("App info", actions::openAppInfo, icon = R.drawable.ic_setup, on = Surface.Ink)
                }
            }
        }
        StepRow(
            2, priv.islandListener, "See what's live",
            "Notification access lets the island show music, calls, timers and new notifications.",
        ) {
            PillButton("Open notification access", actions::openListenerSettings, icon = R.drawable.ic_bell, on = Surface.Ink)
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
                icon = if (state.island.enabled) R.drawable.ic_check else R.drawable.ic_island,
                primary = true,
                on = Surface.Ink,
                modifier = Modifier.padding(start = INK_RAIL),
            )
        }
    }
}
