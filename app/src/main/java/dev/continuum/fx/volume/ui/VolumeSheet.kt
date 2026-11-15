package dev.continuum.fx.volume.ui

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.service.notification.NotificationListenerService
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.audio.OutputDevice
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.ui.Glyph
import dev.continuum.fx.island.ui.GlyphTab
import dev.continuum.fx.island.ui.Kanji
import dev.continuum.fx.island.ui.Lines
import dev.continuum.fx.island.ui.LocalPlateSky
import dev.continuum.fx.island.ui.Osd
import dev.continuum.fx.island.ui.PlateActions
import dev.continuum.fx.island.ui.PlateButton
import dev.continuum.fx.island.ui.Press
import dev.continuum.fx.island.ui.UsesSky
import dev.continuum.fx.island.ui.drawPlateSky
import dev.continuum.fx.ui.eidolon.Eidolon
import dev.continuum.fx.island.ui.IsleText
import dev.continuum.fx.ui.eidolon.GroteskTight
import dev.continuum.fx.ui.eidolon.Star
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.volume.AppAudio
import dev.continuum.fx.volume.AppVolumes
import dev.continuum.fx.volume.BoostPolicy
import dev.continuum.fx.volume.VolStream
import dev.continuum.fx.volume.VolumeModel
import dev.continuum.fx.volume.VolumeSettings
import dev.continuum.fx.volume.VolumeSnapshot
import kotlin.math.roundToInt

/**
 * The panel opened out, as a poster: what it is and how it stands in its corner, the ringer and
 * the other switches as boxes (the chosen one a window), then every stream and every app playing,
 * each a rule whose measured length is cut through to the sky, a star at its end.
 */
@Composable
internal fun VolumeSheet(
    snap: VolumeSnapshot,
    apps: List<AppAudio>,
    boostSteps: Int,
    haptics: Boolean,
    onTouch: () -> Unit,
    onDone: () -> Unit,
    openApp: () -> Unit,
) {
    val context = LocalContext.current
    val colors = Eidolon.colors
    val open by rememberUpdatedState(openApp)
    val output by OutputWatcher.current.collectAsState()
    val problem by VolumeModel.problem.collectAsState()
    UsesSky()
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // The poster's corner: what this is, how the ringer stands, where the sound is going.
        Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.Top) {
            Lines("volume", ringerWord(snap.ringer), output?.name?.lowercase(), modifier = Modifier.weight(1f), soft = 1)
            output?.let { o ->
                GlyphTab(
                    if (o.kind == OutputDevice.Kind.Speaker) R.drawable.ic_speaker else R.drawable.ic_headphones,
                    "Where sound plays", size = 40.dp,
                ) {
                    onTouch()
                    if (showOutputSwitcher(context)) onDone()
                }
                Spacer(Modifier.width(6.dp))
            }
            GlyphTab(R.drawable.ic_setup, "Volume settings", size = 40.dp) { open() }
            Spacer(Modifier.width(10.dp))
            Kanji("音量", 17.sp, colors.ink, stacked = true)
        }
        Spacer(Modifier.height(10.dp))
        // The ringer: three boxes, the chosen one a window.
        PlateActions(Modifier.fillMaxWidth()) {
            listOf(
                Triple(AudioManager.RINGER_MODE_NORMAL, "Ring", R.drawable.ic_bell),
                Triple(AudioManager.RINGER_MODE_VIBRATE, "Vibrate", R.drawable.ic_vibrate),
                Triple(AudioManager.RINGER_MODE_SILENT, "Silent", R.drawable.ic_bell_off),
            ).forEach { (mode, label, icon) ->
                val on = snap.ringer == mode
                PlateButton(label, Modifier.weight(1f), if (!on) Press.Rule else if (mode == AudioManager.RINGER_MODE_SILENT) Press.Signal else Press.Window, icon, arrow = false) {
                    onTouch()
                    VolumeModel.setRinger(mode)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // The other sound switches: Do not disturb (through the island's notification access) and
        // going past 100%.
        val prefs by VolumeSettings.state.collectAsState()
        val nm = remember { context.getSystemService(NotificationManager::class.java) }
        var quiet by remember {
            mutableStateOf((nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) >= NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
        PlateActions(Modifier.fillMaxWidth()) {
            val listener = IslandRepository.listener
            if (listener != null) {
                PlateButton("Do not disturb", Modifier.weight(1f), if (quiet) Press.Window else Press.Rule, R.drawable.ic_bell_off) {
                    onTouch()
                    val next = !quiet
                    try {
                        listener.requestInterruptionFilter(
                            if (next) NotificationListenerService.INTERRUPTION_FILTER_PRIORITY else NotificationListenerService.INTERRUPTION_FILTER_ALL,
                        )
                        quiet = next
                    } catch (_: Exception) {
                    }
                }
            }
            PlateButton("Past 100%", Modifier.weight(1f), if (prefs.boost) Press.Signal else Press.Rule, R.drawable.ic_volume) {
                onTouch()
                VolumeSettings.update { it.copy(boost = !it.boost) }
            }
        }
        Spacer(Modifier.height(8.dp))
        VolStream.entries.forEachIndexed { i, s ->
            val lv = snap[s]
            // Notifications can't sound while the ringer is on vibrate or silent.
            val enabled = s != VolStream.Notification || snap.ringer == AudioManager.RINGER_MODE_NORMAL
            val boosted = s == VolStream.Media && boostSteps > 0
            LevelRow(
                index = i,
                label = s.label,
                icon = streamIcon(s, lv.index == lv.min, snap.ringer),
                bitmap = null,
                fraction = lv.fraction,
                steps = lv.steps,
                value = if (boosted) BoostPolicy.percent(boostSteps) else lv.percent,
                hot = boosted,
                enabled = enabled,
                haptics = haptics,
                onTouch = onTouch,
            ) { f -> VolumeModel.set(s, lv.indexAt(f)) }
        }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Lines("apps playing")
            val iconPx = with(LocalDensity.current) { 22.dp.roundToPx() }
            // Each row belongs to its app, not to its place in the list.
            apps.forEachIndexed { i, a ->
                key(a.pkg) {
                    val bmp = remember(a.pkg) { AppVolumes.icon(a.pkg, iconPx) }
                    LevelRow(
                        index = VolStream.entries.size + i,
                        label = a.label,
                        icon = R.drawable.ic_volume,
                        bitmap = bmp,
                        fraction = a.volume,
                        steps = null,
                        value = (a.volume * 100f).roundToInt(),
                        hot = false,
                        enabled = true,
                        haptics = haptics,
                        onTouch = onTouch,
                    ) { f -> AppVolumes.setVolume(a.pkg, f) }
                }
            }
        }
        problem?.let {
            Spacer(Modifier.height(8.dp))
            Osd(it.lowercase(), colors.inkSoft, maxLines = 3, lineHeight = 14.sp)
        }
    }
}

private fun ringerWord(mode: Int): String = when (mode) {
    AudioManager.RINGER_MODE_SILENT -> "silent"
    AudioManager.RINGER_MODE_VIBRATE -> "on vibrate"
    else -> "ringing"
}

/** One row of the list: its number, its name, its level in a camera's brackets, and the rule you drag. */
@Composable
private fun LevelRow(
    index: Int,
    label: String,
    @DrawableRes icon: Int,
    bitmap: Bitmap?,
    fraction: Float,
    steps: Int?,
    value: Int,
    hot: Boolean,
    enabled: Boolean,
    haptics: Boolean,
    onTouch: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val colors = Eidolon.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(22.dp))
            } else {
                Glyph(icon, colors.ink, 20.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Osd("%02d".format(index + 1), colors.inkFaint, Modifier.width(22.dp))
                IsleText(label.lowercase(), 14.sp, colors.ink, weight = FontWeight.Medium, face = GroteskTight, modifier = Modifier.weight(1f))
                Osd("[%4d%%]".format(value), if (hot) colors.signal else colors.inkSoft)
            }
            WindowRule(label, fraction, steps, hot, enabled, haptics, onTouch, onChange)
        }
    }
}

/**
 * A slider as the app prints them: a hairline for the whole way, and its measured length cut
 * through to the sky, with the stream's steps ticked under it and a star at its end that swells
 * and turns while you hold it.
 */
@Composable
private fun WindowRule(
    label: String,
    fraction: Float,
    steps: Int?,
    hot: Boolean,
    enabled: Boolean,
    haptics: Boolean,
    onTouch: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val view = LocalView.current
    val colors = Eidolon.colors
    val sky = LocalPlateSky.current
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown by animateFloatAsState(dragged ?: fraction, Motion.flow(), label = "level")
    val grip by animateFloatAsState(if (dragged != null) 1f else 0f, Motion.snap(), label = "grip")
    val change by rememberUpdatedState(onChange)
    val touched by rememberUpdatedState(onTouch)
    val level by rememberUpdatedState(fraction)
    val feel by rememberUpdatedState(haptics)
    val path = remember { Path() }
    val window = remember { Path() }

    fun snapped(f: Float): Float = if (steps == null || steps <= 0) f.coerceIn(0f, 1f) else (f * steps).roundToInt().toFloat() / steps

    fun move(x: Float, width: Float, inset: Float) {
        val f = snapped((x - inset) / (width - inset * 2f))
        val before = dragged ?: level
        if (f != before) {
            dragged = f
            change(f)
            if (feel && steps != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .semantics { contentDescription = "$label volume" }
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(steps) {
                        val inset = 8.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { pos ->
                                touched()
                                dragged = level
                                move(pos.x, size.width.toFloat(), inset)
                            },
                            onDragEnd = { dragged = null },
                            onDragCancel = { dragged = null },
                        ) { ch, _ ->
                            ch.consume()
                            touched()
                            move(ch.position.x, size.width.toFloat(), inset)
                        }
                    }
                    .pointerInput(steps) {
                        val inset = 8.dp.toPx()
                        detectTapGestures { pos ->
                            touched()
                            move(pos.x, size.width.toFloat(), inset)
                            dragged = null
                        }
                    },
            ),
    ) {
        val inset = 8.dp.toPx()
        val y = size.height / 2f - 1.dp.toPx()
        val span = size.width - inset * 2f
        val x = inset + span * shown.coerceIn(0f, 1f)
        val band = 7.dp.toPx()
        drawLine(colors.ink.copy(alpha = 0.3f), Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
        if (x > inset + 0.5f) {
            window.rewind()
            window.addRect(androidx.compose.ui.geometry.Rect(inset, y - band / 2f, x, y + band / 2f))
            when {
                hot -> drawPath(window, colors.signal)
                sky != null -> drawPlateSky(sky, window, Offset(-sky.size.width * 0.1f, -sky.size.height * 0.5f))
                else -> drawPath(window, colors.ultramarine)
            }
        }
        if (steps != null && steps in 2..40) {
            for (i in 0..steps) {
                val tx = inset + span * i / steps
                drawLine(colors.ink.copy(alpha = if (tx <= x) 0.7f else 0.3f), Offset(tx, y + band / 2f + 2.dp.toPx()), Offset(tx, y + band / 2f + (if (i % 5 == 0) 6f else 4f).dp.toPx()), 1.dp.toPx())
            }
        }
        val r = (7f + 3f * grip).dp.toPx()
        drawPath(Star.path(Offset(x, y), r, pinch = Star.KEEN, rotation = 45f * grip, into = path), if (hot) colors.signal else colors.ink)
    }
}

/** Android 14's own picker for where sound plays. False where there isn't one. */
private fun showOutputSwitcher(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    return try {
        MediaRouter2.getInstance(context).showSystemOutputSwitcher()
    } catch (_: Exception) {
        false
    }
}
