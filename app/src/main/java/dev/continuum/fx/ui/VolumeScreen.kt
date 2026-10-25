package dev.continuum.fx.ui

import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.setup.ShizukuState
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.InkSlider
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.PowerButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
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

private fun volumeOn(state: UiState, prefs: VolumePrefs) = prefs.enabled && state.privileges.volumeService

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
        val status = when {
            !state.privileges.volumeService -> "Off · finish setup to start"
            !prefs.enabled -> "Off · the volume keys open Android's panel"
            serviceOn -> "On · your volume keys open the ink panel"
            else -> "On · starting"
        }
        Txt(status, 17.sp, if (volumeOn(state, prefs)) p.text else p.sub, maxLines = 2)
        Gap(22.dp)

        Txt("Try it", 21.sp, p.text, bold = true)
        Gap(2.dp)
        Txt(
            if (serviceOn) "Press a volume key, or show the panel from here." else "Finish setup and the volume keys open the panel.",
            15.sp, p.sub,
        )
        Gap(12.dp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Show the panel", { actions.showVolumePanel(false) }, icon = R.drawable.ic_volume, height = 38.dp)
            PillButton("Opened out", { actions.showVolumePanel(true) }, icon = R.drawable.ic_tune, height = 38.dp)
        }
        Gap(28.dp)

        Txt("Levels", 21.sp, p.text, bold = true)
        Gap(10.dp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 8.dp)) {
            VolStream.entries.forEach { s ->
                val lv = snap[s]
                val muted = s == VolStream.Notification && snap.ringer != AudioManager.RINGER_MODE_NORMAL
                StreamControl(s, lv, snap.ringer, muted, if (s == VolStream.Media && boost > 0) BoostPolicy.percent(boost) else null)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("Ring", snap.ringer == AudioManager.RINGER_MODE_NORMAL, onClick = { VolumeModel.setRinger(AudioManager.RINGER_MODE_NORMAL) }, on = Surface.Card)
                Chip("Vibrate", snap.ringer == AudioManager.RINGER_MODE_VIBRATE, onClick = { VolumeModel.setRinger(AudioManager.RINGER_MODE_VIBRATE) }, on = Surface.Card)
                Chip("Silent", snap.ringer == AudioManager.RINGER_MODE_SILENT, onClick = { VolumeModel.setRinger(AudioManager.RINGER_MODE_SILENT) }, on = Surface.Card)
            }
        }
        val problem by VolumeModel.problem.collectAsState()
        problem?.let {
            Gap(8.dp)
            Txt(it, 14.sp, p.sub)
        }
        Gap(28.dp)

        Txt("Apps", 21.sp, p.text, bold = true)
        Gap(2.dp)
        Txt(
            when {
                !helper -> "Each app can have its own volume, with Shizuku. Set it up from the dock."
                apps.isEmpty() -> "Nothing's playing. Apps show up here while they make sound."
                else -> "Each app's own volume, kept while it's open" + if (prefs.rememberApps) " and for next time." else "."
            },
            15.sp, p.sub,
        )
        if (helper && apps.isNotEmpty()) {
            Gap(10.dp)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 8.dp)) {
                // Each row belongs to its app, not to its place in the list.
                apps.forEach { a ->
                    key(a.pkg) { AppControl(a.pkg, a.label, a.volume) { v -> AppVolumes.setVolume(a.pkg, v) } }
                }
            }
        }
        Gap(28.dp)

        PlaysAlongside(helper, known, actions)
        Gap(28.dp)

        Txt("Panel", 21.sp, p.text, bold = true)
        Gap(10.dp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 6.dp)) {
            ToggleRow("Replace Android's panel", "The volume keys open the ink panel. Off hands them straight back to Android.", prefs.enabled) {
                actions.updateVolume { it.copy(enabled = !it.enabled) }
            }
            ToggleRow("Open with every stream", "The first press opens the panel out, with every stream and app.", prefs.openExpanded) {
                actions.updateVolume { it.copy(openExpanded = !it.openExpanded) }
            }
            ToggleRow("Feel each step", "A light tick on every step, from the keys or your finger.", prefs.haptics) {
                actions.updateVolume { it.copy(haptics = !it.haptics) }
            }
            ToggleRow("Camera keeps its shutter", "In the camera app, the volume keys take pictures as usual.", prefs.cameraKeys) {
                actions.updateVolume { it.copy(cameraKeys = !it.cameraKeys) }
            }
            ToggleRow("Remember each app's volume", "An app you've turned down starts there next time. Back to full forgets it.", prefs.rememberApps) {
                actions.updateVolume { it.copy(rememberApps = !it.rememberApps) }
            }
            Txt("Side", 15.sp, p.sub, modifier = Modifier.padding(start = 18.dp, top = 10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("Right", !prefs.left, onClick = { actions.updateVolume { it.copy(side = VolumePrefs.SIDE_RIGHT) } }, on = Surface.Card)
                Chip("Left", prefs.left, onClick = { actions.updateVolume { it.copy(side = VolumePrefs.SIDE_LEFT) } }, on = Surface.Card)
            }
            Txt("Height", 15.sp, p.sub, modifier = Modifier.padding(start = 18.dp, top = 6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("Higher", prefs.height == VolumePrefs.HEIGHT_HIGHER, onClick = { actions.updateVolume { it.copy(height = VolumePrefs.HEIGHT_HIGHER) } }, on = Surface.Card)
                Chip("Middle", prefs.height == VolumePrefs.HEIGHT_MIDDLE, onClick = { actions.updateVolume { it.copy(height = VolumePrefs.HEIGHT_MIDDLE) } }, on = Surface.Card)
                Chip("Lower", prefs.height == VolumePrefs.HEIGHT_LOWER, onClick = { actions.updateVolume { it.copy(height = VolumePrefs.HEIGHT_LOWER) } }, on = Surface.Card)
            }
        }
        Gap(28.dp)

        Txt("Boost", 21.sp, p.text, bold = true)
        Gap(10.dp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 6.dp)) {
            ToggleRow(
                "Go past 100%",
                "At full media volume, press volume up twice to go louder in 5% steps; volume down brings it back. " +
                    "With the EQ processing, its limiter keeps it clean; otherwise it needs Shizuku. It can distort, and very loud sound can harm your hearing.",
                prefs.boost,
            ) { actions.updateVolume { it.copy(boost = !it.boost) } }
            if (prefs.boost) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(2, 4, 7, 10).forEach { n ->
                        Chip(
                            "${BoostPolicy.percent(n)}% · +${BoostPolicy.db(n).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} dB",
                            prefs.boostSteps == n,
                            onClick = { actions.updateVolume { it.copy(boostSteps = n) } },
                            on = Surface.Card,
                        )
                    }
                }
            }
        }
    }
}

/** One stream: its name and level, then − and + either side of a slider. */
@Composable
private fun StreamControl(s: VolStream, lv: Level, ringer: Int, muted: Boolean, boostPercent: Int?) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(streamIcon(s, lv.index == lv.min, ringer)),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(if (muted) p.sub else p.text),
            )
            Spacer(Modifier.width(8.dp))
            Txt(s.label, 17.sp, if (muted) p.sub else p.text, modifier = Modifier.weight(1f), maxLines = 1)
            // The percentage and the device's own step, so 73% is plainly step 11 of 15.
            Txt(
                boostPercent?.let { "$it% · boosted" } ?: "${lv.percent}% · ${lv.index - lv.min}/${lv.steps}",
                14.sp, if (boostPercent != null) p.live else p.sub, maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatBubble(R.drawable.ic_volume_low, "Down") { VolumeModel.step(s, -1) }
            InkSlider(
                value = lv.fraction,
                onChange = { f -> VolumeModel.set(s, lv.indexAt(f)) },
                track = p.ground,
                fill = if (muted) p.sub else p.text,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            RepeatBubble(R.drawable.ic_plus, "Up") { VolumeModel.step(s, 1) }
        }
    }
}

@Composable
private fun AppControl(pkg: String, label: String, volume: Float, onChange: (Float) -> Unit) {
    val p = LocalPalette.current
    val iconPx = with(LocalDensity.current) { 28.dp.roundToPx() }
    val icon = remember(pkg) { AppVolumes.icon(pkg, iconPx) }
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
                Spacer(Modifier.width(10.dp))
            }
            Txt(label, 17.sp, p.text, modifier = Modifier.weight(1f), maxLines = 1)
            Txt("${(volume * 100f).toInt()}%", 14.sp, p.sub, maxLines = 1)
        }
        InkSlider(value = volume, onChange = onChange, track = p.ground, fill = p.text, modifier = Modifier.fillMaxWidth())
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
    Txt("Play alongside", 21.sp, p.text, bold = true)
    Gap(2.dp)
    Txt(
        "Normally, an app that starts playing pauses the one before. Switch an app on and it plays alongside: starting it doesn't " +
            "pause anything, and nothing else starting pauses it. Calls and alarms still pause it, and directions still lower it. " +
            "While any app is on, the helper looks after audio focus for the phone.",
        15.sp, p.sub,
    )
    val list = (alongside.toList() + known).distinct()
    when {
        !helper -> {
            Gap(6.dp)
            Txt(
                if (alongside.isEmpty()) "Needs Shizuku: set it up from the dock."
                else "Waiting for Shizuku: until it's back, apps pause each other as usual.",
                15.sp, p.sub,
            )
        }
        list.isEmpty() -> {
            Gap(6.dp)
            Txt("Apps show up here once they've played something.", 15.sp, p.sub)
        }
        else -> {
            problem?.let {
                Gap(6.dp)
                Txt(it, 15.sp, p.danger)
            }
            Gap(10.dp)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 6.dp)) {
                list.forEach { pkg ->
                    val on = pkg in alongside
                    ToggleRow(AppVolumes.label(pkg), if (on) "Plays alongside" else "Pauses when another starts", on) {
                        actions.setPlaysAlongside(pkg, !on)
                    }
                }
            }
        }
    }
}

/** A round − or + that keeps stepping while held, quicker the longer you hold. */
@Composable
private fun RepeatBubble(icon: Int, description: String, onStep: () -> Unit) {
    val p = LocalPalette.current
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(p.ground)
            .semantics { contentDescription = description }
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
    val subtitle = when {
        !state.privileges.volumeService -> "Tap to set up"
        !prefs.enabled -> "Android's own panel"
        else -> "Volume keys open the ink panel"
    }
    Row(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PowerButton(on, R.drawable.ic_volume, actions::toggleVolume, what = "the volume panel")
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Txt(if (on) "Volume panel on" else "Volume panel off", 18.sp, p.onInk, bold = true, maxLines = 1)
            Txt(subtitle, 14.sp, p.onInkSub, maxLines = 1)
        }
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
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Set up volume", 24.sp, p.onInk, bold = true, modifier = Modifier.weight(1f))
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
                PillButton(shizukuLabel, actions::grantVolumeWithShizuku, primary = true, on = Surface.Ink)
                PillButton("Open accessibility settings", actions::openAccessibilitySettings, on = Surface.Ink)
                if (Build.VERSION.SDK_INT >= 33) {
                    Txt("If the switch is greyed out, open App info, tap the menu in the corner and choose Allow restricted settings.", 14.sp, p.onInkSub)
                    PillButton("App info", actions::openAppInfo, on = Surface.Ink)
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
                actions::connectVolumeHelper, primary = priv.volumeService, on = Surface.Ink,
            )
        }
        StepRow(
            3, priv.islandListener, "Leave casting alone",
            "Notification access (the island uses it too) lets the panel see when something is cast, so the keys move that device instead.",
            optional = true,
        ) {
            PillButton("Open notification access", actions::openListenerSettings, on = Surface.Ink)
        }
    }
}
