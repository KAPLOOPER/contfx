package dev.continuum.fx.ui

import dev.continuum.fx.ui.bench.BenchField
import dev.continuum.fx.ui.bench.BenchGrid
import dev.continuum.fx.ui.bench.BenchRow
import dev.continuum.fx.ui.bench.BenchSection
import dev.continuum.fx.ui.components.InkSwitch
import dev.continuum.fx.ui.components.MasterSwitch
import androidx.compose.runtime.saveable.rememberSaveable
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.setup.ShizukuState
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.ChoiceRow
import dev.continuum.fx.ui.components.Reveal
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.InkSlider
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.PowerButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Txt
import dev.continuum.fx.volume.AppVolumes
import dev.continuum.fx.volume.Booster
import dev.continuum.fx.volume.BoostPolicy
import dev.continuum.fx.volume.Level
import dev.continuum.fx.volume.VolStream
import dev.continuum.fx.volume.VolumeHelper
import dev.continuum.fx.volume.VolumeModel
import dev.continuum.fx.volume.VolumePrefs
import dev.continuum.fx.volume.VolumeSettings
import dev.continuum.fx.volume.VolumeStatus
import dev.continuum.fx.volume.ui.streamIcon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun volumeOn(state: UiState, prefs: VolumePrefs) = prefs.enabled && state.privileges.volumeService

@Composable
internal fun VolumeContent(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val prefs by VolumeSettings.state.collectAsState()
    val snap by VolumeModel.state.collectAsState()
    val serviceOn by VolumeStatus.serviceOn.collectAsState()
    val helper by VolumeHelper.ready.collectAsState()
    val apps by AppVolumes.playing.collectAsState()
    val known by AppVolumes.known.collectAsState()
    val boost by Booster.steps.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // The page works whether or not the service is running: it reads and sets levels itself.
        VolumeModel.start(context)
        VolumeHelper.init(context)
        AppVolumes.start(context)
        Booster.init(context)
    }

    Column {
        // The kicker starts the sentence the line finishes: what the volume keys open. The
        // switch at the line's end is named under it ("Volume panel"), as Island's is: the line
        // says which panel opens, so a bare switch beside "Android's own panel." would read as
        // that panel being off.
        val on = volumeOn(state, prefs)
        Kicker("The volume keys open")
        Gap(6.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeroLine(if (on) "The ink panel." else "Android's own panel.", p.text, Modifier.weight(1f).padding(end = 12.dp))
            MasterSwitch(on, "Volume panel", actions::toggleVolume)
        }

        // Showing the panel from here, as Island's samples play into its drawing: a label over a
        // table of the two ways it opens. Before setup there's no panel to show, so both are
        // drawn faint and take no touch, and the label says when they will; the switch above
        // and the dock below are the way to setup.
        Gap(18.dp)
        Note(if (serviceOn) "Try it" else "Try it · after setup", p.sub, Modifier.padding(start = INK_RAIL))
        BenchGrid(2, Modifier.padding(start = INK_RAIL, top = 2.dp)) {
            PillButton("Show the panel", { actions.showVolumePanel(false) }, icon = R.drawable.ic_volume, enabled = serviceOn)
            PillButton("Opened out", { actions.showVolumePanel(true) }, icon = R.drawable.ic_tune, enabled = serviceOn)
        }

        // The five streams, each one field: name and value in the note face above, the ink under.
        BenchSection(1, "Levels")
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            VolStream.entries.forEach { s ->
                val lv = snap[s]
                val muted = s == VolStream.Notification && snap.ringer != AudioManager.RINGER_MODE_NORMAL
                StreamField(s, lv, snap.ringer, muted, if (s == VolStream.Media && boost > 0) BoostPolicy.percent(boost) else null)
            }
        }
        ChoiceRow(Modifier.padding(start = INK_RAIL, top = 10.dp, bottom = 6.dp), fill = false) {
            Chip("Ring", snap.ringer == AudioManager.RINGER_MODE_NORMAL, onClick = { VolumeModel.setRinger(AudioManager.RINGER_MODE_NORMAL) })
            Chip("Vibrate", snap.ringer == AudioManager.RINGER_MODE_VIBRATE, onClick = { VolumeModel.setRinger(AudioManager.RINGER_MODE_VIBRATE) })
            Chip("Silent", snap.ringer == AudioManager.RINGER_MODE_SILENT, onClick = { VolumeModel.setRinger(AudioManager.RINGER_MODE_SILENT) })
        }
        val problem by VolumeModel.problem.collectAsState()
        problem?.let {
            Gap(8.dp)
            Row(Modifier.padding(start = INK_RAIL), verticalAlignment = Alignment.CenterVertically) {
                dev.continuum.fx.island.ui.RedMark(12.dp)
                Spacer(Modifier.width(8.dp))
                Txt(it, 14.sp, p.text)
            }
        }

        // Each app its own level, and those that play alongside each other, all under one rule.
        BenchSection(2, "Apps")
        when {
            !helper -> Rest(
                "With Shizuku, each app can have its own volume and play alongside the others.",
                action = "Set up",
                icon = R.drawable.ic_setup,
                onAction = { actions.setSetupOpen(true) },
            )
            apps.isEmpty() -> Rest("Nothing's playing. Apps show up here while they make sound.")
            else -> Unit
        }
        if (helper && apps.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Each row belongs to its app, not to its place in the list.
                apps.forEach { a ->
                    key(a.pkg) { AppField(a.pkg, a.label, a.volume) { v -> AppVolumes.setVolume(a.pkg, v) } }
                }
            }
        }
        PlaysAlongside(helper, known, actions)

        // How the keys behave, as plain rows.
        BenchSection(3, "Panel")
        VolumeRow("Replace Android's panel", "The volume keys open the ink panel. Off hands them straight back to Android.", prefs.enabled) {
            actions.updateVolume { it.copy(enabled = !it.enabled) }
        }
        VolumeRow("Open with every stream", "The first press opens the panel out, with every stream and app.", prefs.openExpanded) {
            actions.updateVolume { it.copy(openExpanded = !it.openExpanded) }
        }
        VolumeRow("Feel each step", "A light tick on every step, from the keys or your finger.", prefs.haptics) {
            actions.updateVolume { it.copy(haptics = !it.haptics) }
        }
        VolumeRow("Camera keeps its shutter", "In the camera app, the volume keys take pictures as usual.", prefs.cameraKeys) {
            actions.updateVolume { it.copy(cameraKeys = !it.cameraKeys) }
        }
        VolumeRow("Remember each app's volume", "An app you've turned down starts there next time. Back to full forgets it.", prefs.rememberApps) {
            actions.updateVolume { it.copy(rememberApps = !it.rememberApps) }
        }
        Txt("Side", 15.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL, top = 10.dp))
        ChoiceRow(Modifier.padding(start = INK_RAIL, top = 6.dp, bottom = 6.dp), fill = false) {
            Chip("Right", !prefs.left, onClick = { actions.updateVolume { it.copy(side = VolumePrefs.SIDE_RIGHT) } })
            Chip("Left", prefs.left, onClick = { actions.updateVolume { it.copy(side = VolumePrefs.SIDE_LEFT) } })
        }
        Txt("Height", 15.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL, top = 6.dp))
        ChoiceRow(Modifier.padding(start = INK_RAIL, top = 6.dp, bottom = 6.dp), fill = false) {
            Chip("Higher", prefs.height == VolumePrefs.HEIGHT_HIGHER, onClick = { actions.updateVolume { it.copy(height = VolumePrefs.HEIGHT_HIGHER) } })
            Chip("Middle", prefs.height == VolumePrefs.HEIGHT_MIDDLE, onClick = { actions.updateVolume { it.copy(height = VolumePrefs.HEIGHT_MIDDLE) } })
            Chip("Lower", prefs.height == VolumePrefs.HEIGHT_LOWER, onClick = { actions.updateVolume { it.copy(height = VolumePrefs.HEIGHT_LOWER) } })
        }

        // Past 100%, as its own rule, with the warning said out whole under its row.
        BenchSection(4, "Boost")
        BenchRow("Go past 100%", null, on = prefs.boost, onToggle = { actions.updateVolume { it.copy(boost = !it.boost) } }, switchDescription = "Go past 100%")
        Reveal(prefs.boost) {
            Txt(
                "At full media volume, press volume up twice to go louder in 5% steps; volume down brings it back. " +
                    "With the EQ processing, its limiter keeps it clean; otherwise it needs Shizuku. It can distort, and very loud sound can harm your hearing.",
                14.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL, top = 2.dp),
            )
            ChoiceRow(Modifier.padding(start = INK_RAIL, top = 8.dp, bottom = 6.dp)) {
                listOf(2, 4, 7, 10).forEach { n ->
                    Chip(
                        "${BoostPolicy.percent(n)}% · +${BoostPolicy.db(n).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} dB",
                        prefs.boostSteps == n,
                        onClick = { actions.updateVolume { it.copy(boostSteps = n) } },
                    )
                }
            }
        }
    }
}

/** A panel row of the volume's: its caption says what it does, the ink and the switch carry the state. */
@Composable
private fun VolumeRow(label: String, caption: String, on: Boolean, onToggle: () -> Unit) {
    BenchRow(label, caption, on = on, onToggle = onToggle, switchDescription = label)
}

/** One stream as a field: its name and level in the note face, the ink under it — the stream's speaker mark at the slider's left, the + that keeps stepping at its right. */
@Composable
private fun StreamField(s: VolStream, lv: Level, ringer: Int, muted: Boolean, boostPercent: Int?) {
    val p = LocalPalette.current
    BenchField(
        label = s.label,
        value = boostPercent?.let { "$it% · boosted" } ?: "${lv.percent}% · ${lv.index - lv.min}/${lv.steps}",
        mark = {
            Image(
                painterResource(streamIcon(s, lv.index == lv.min, ringer)),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(if (muted) p.sub else p.text),
            )
        },
        leading = { RepeatBubble(R.drawable.ic_minus, "${s.label} down") { VolumeModel.step(s, -1) } },
        trailing = { RepeatBubble(R.drawable.ic_plus, "${s.label} up") { VolumeModel.step(s, 1) } },
        live = boostPercent != null,
        muted = muted,
    ) {
        InkSlider(
            value = lv.fraction,
            onChange = { f -> VolumeModel.set(s, lv.indexAt(f)) },
            label = boostPercent?.let { "$it%" } ?: "${lv.percent}%",
            muted = muted,
        )
    }
}

@Composable
private fun AppField(pkg: String, label: String, volume: Float, onChange: (Float) -> Unit) {
    val iconPx = with(LocalDensity.current) { 28.dp.roundToPx() }
    val icon = remember(pkg) { AppVolumes.icon(pkg, iconPx) }
    if (icon != null) {
        BenchField(
            label = label,
            value = "${(volume * 100f).toInt()}%",
            mark = {
                Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(20.dp).clip(CircleShape))
            },
        ) {
            InkSlider(value = volume, onChange = onChange, label = "${(volume * 100f).toInt()}%")
        }
    } else {
        BenchField(label = label, value = "${(volume * 100f).toInt()}%") {
            InkSlider(value = volume, onChange = onChange, label = "${(volume * 100f).toInt()}%")
        }
    }
}

/**
 * Apps that play alongside others. With any switched on, the helper looks after audio focus for
 * the phone, the way Android does, except that these neither pause others nor get paused.
 */
@Composable
private fun PlaysAlongside(helper: Boolean, known: List<String>, actions: AppActions) {
    val p = LocalPalette.current
    val alongside by AppVolumes.alongside.collectAsState()
    val problem by AppVolumes.alongsideProblem.collectAsState()
    var how by rememberSaveable { mutableStateOf(false) }
    val list = (alongside.toList() + known).distinct()
    when {
        !helper && alongside.isEmpty() -> Unit
        !helper -> {
            Gap(6.dp)
            Rest("Waiting for Shizuku: until it's back, apps pause each other as usual.", action = "Reconnect", icon = R.drawable.ic_refresh, onAction = actions::connectVolumeHelper)
        }
        list.isEmpty() -> {
            Gap(6.dp)
            Rest("Apps show up here once they've played something.")
        }
        else -> {
            problem?.let {
                Gap(8.dp)
                Row(Modifier.padding(start = INK_RAIL), verticalAlignment = Alignment.CenterVertically) {
                    dev.continuum.fx.island.ui.RedMark(13.dp)
                    Spacer(Modifier.width(8.dp))
                    Txt(it, 15.sp, p.text)
                }
            }
            Gap(4.dp)
            Column(Modifier.fillMaxWidth()) {
                list.forEach { pkg ->
                    val on = pkg in alongside
                    VolumeRow(AppVolumes.label(pkg), "Plays alongside", on) {
                        actions.setPlaysAlongside(pkg, !on)
                    }
                }
            }
        }
    }
    MoreSection(how, { how = !how }, label = "How it works") {
        Txt(
            "Normally, an app that starts playing pauses the one before. Switch an app on and it plays alongside: starting it doesn't " +
                "pause anything, and nothing else starting pauses it. Calls and alarms still pause it, and directions still lower it. " +
                "While any app is on, the helper looks after audio focus for the phone.",
            14.sp, p.sub, modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

/** A round + that keeps stepping while held, quicker the longer you hold. */
@Composable
private fun RepeatBubble(icon: Int, description: String, onStep: () -> Unit) {
    val p = LocalPalette.current
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier
            .size(48.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick(label = description) {
                    step()
                    true
                }
            }
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown()
                        step()
                        val job = launch {
                            delay(400)
                            var wait = 160L
                            while (true) {
                                step()
                                delay(wait)
                                wait = (wait - 15L).coerceAtLeast(50L)
                            }
                        }
                        waitForUpOrCancellation()
                        job.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), colorFilter = ColorFilter.tint(p.text))
    }
}

@Composable
internal fun VolumeDockBar(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val prefs by VolumeSettings.state.collectAsState()
    val on = volumeOn(state, prefs)
    // The page says what the keys open; the dock says how it's doing.
    val subtitle = when {
        !state.privileges.volumeService -> setupSteps(0, 1)
        !prefs.enabled -> "Set up and ready"
        else -> "Press a volume key to see it"
    }
    Row(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PowerButton(on, R.drawable.ic_volume, actions::toggleVolume, what = "the volume panel")
        DockText(if (on) "Volume panel on" else "Volume panel off", subtitle, "Open volume setup") { actions.setSetupOpen(true) }
        IconBubble(R.drawable.ic_setup, "Volume setup", onClick = { actions.setSetupOpen(true) }, on = Surface.Ink)
    }
}

@Composable
internal fun VolumeSetupSheet(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val priv = state.privileges
    val helper by VolumeHelper.ready.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Set up volume", 22.sp, p.onInk, bold = true, modifier = Modifier.weight(1f))
            IconBubble(R.drawable.ic_chevron_down, "Close setup", onClick = { actions.setSetupOpen(false) }, on = Surface.Ink)
        }
        Gap(4.dp)
        Txt("One switch for the panel; Shizuku adds each app's own volume.", 15.sp, p.onInkSub)
        Gap(8.dp)
        val shizukuLabel = when (priv.shizuku) {
            ShizukuState.READY -> "Switch on with Shizuku"
            ShizukuState.NEEDS_PERMISSION -> "Allow Shizuku"
            ShizukuState.NOT_RUNNING -> "Use Shizuku"
        }
        StepRow(
            1, priv.volumeService, "Catch the volume keys",
            "An accessibility service takes the two volume keys before Android's panel does. It listens to nothing else and reads nothing on screen.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(shizukuLabel, actions::grantVolumeWithShizuku, icon = R.drawable.ic_bolt, primary = true, on = Surface.Ink)
                // Two ways to the same switch: said, so the second doesn't read as a next step.
                Txt("Or switch it on yourself:", 14.sp, p.onInkSub)
                PillButton("Open accessibility settings", actions::openAccessibilitySettings, icon = R.drawable.ic_volume, on = Surface.Ink)
                if (Build.VERSION.SDK_INT >= 33) {
                    Txt("If the switch is greyed out, open App info, tap the menu in the corner and choose Allow restricted settings.", 14.sp, p.onInkSub)
                    PillButton("App info", actions::openAppInfo, icon = R.drawable.ic_setup, on = Surface.Ink)
                }
            }
        }
        StepRow(
            2, helper, "Each app's own volume",
            "A small helper, run by Shizuku, sets each app's volume, lets apps play alongside each other, sets silent without Do Not Disturb and boosts without the EQ on.",
            optional = true,
        ) {
            PillButton(
                if (priv.shizuku == ShizukuState.READY) "Start the helper" else shizukuLabel,
                actions::connectVolumeHelper, icon = R.drawable.ic_bolt, primary = priv.volumeService, on = Surface.Ink,
            )
        }
        StepRow(
            3, priv.islandListener, "Leave casting alone",
            "Notification access (the island uses it too) lets the panel see when something is cast, so the keys move that device instead.",
            optional = true,
        ) {
            PillButton("Open notification access", actions::openListenerSettings, icon = R.drawable.ic_bell, on = Surface.Ink)
        }
    }
}
