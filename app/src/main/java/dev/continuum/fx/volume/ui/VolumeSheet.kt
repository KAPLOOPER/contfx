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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.audio.OutputDevice
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.ui.Glyph
import dev.continuum.fx.island.ui.Isle
import dev.continuum.fx.island.ui.IsleNote
import dev.continuum.fx.island.ui.IsleText
import dev.continuum.fx.island.ui.PrintedStar
import dev.continuum.fx.island.ui.TagShape
import dev.continuum.fx.ui.components.squash
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
 * The panel opened out, as an Eidolon slip: where sound is going, the ringer, Do not disturb and
 * going past 100%, then every stream and every app playing, each a rule with a star riding it.
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
    val open by rememberUpdatedState(openApp)
    val output by OutputWatcher.current.collectAsState()
    val problem by VolumeModel.problem.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // The slip's head: a printed star, what it is, and on the right the ringer's state.
        Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
            PrintedStar(7.dp, Isle.lit)
            Spacer(Modifier.width(7.dp))
            IsleNote("Sound", Isle.soft, Modifier.weight(1f))
            IsleNote(ringerWord(snap.ringer), if (snap.ringer == AudioManager.RINGER_MODE_SILENT) Isle.signal else Isle.soft)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            IsleText("Volume", 24.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight, modifier = Modifier.weight(1f))
            output?.let { o ->
                OutputTag(o) {
                    onTouch()
                    if (showOutputSwitcher(context)) onDone()
                }
                Spacer(Modifier.width(6.dp))
            }
            Box(
                Modifier
                    .size(40.dp)
                    .background(Isle.well, TagShape)
                    .border(1.dp, Isle.rule, TagShape)
                    .semantics { contentDescription = "Volume settings" }
                    .pointerInput(Unit) { detectTapGestures { open() } },
                contentAlignment = Alignment.Center,
            ) { Glyph(R.drawable.ic_setup, Isle.on, 20.dp) }
        }
        Spacer(Modifier.height(12.dp))
        // The ringer: three cells, the chosen one lit.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Triple(AudioManager.RINGER_MODE_NORMAL, "Ring", R.drawable.ic_bell),
                Triple(AudioManager.RINGER_MODE_VIBRATE, "Vibrate", R.drawable.ic_vibrate),
                Triple(AudioManager.RINGER_MODE_SILENT, "Silent", R.drawable.ic_bell_off),
            ).forEach { (mode, label, icon) ->
                Cell(label, icon, snap.ringer == mode, Modifier.weight(1f)) {
                    onTouch()
                    VolumeModel.setRinger(mode)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // The other sound switches: Do not disturb (through the island's notification access) and
        // going past 100%.
        val prefs by VolumeSettings.state.collectAsState()
        val nm = remember { context.getSystemService(NotificationManager::class.java) }
        var quiet by remember {
            mutableStateOf((nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) >= NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val listener = IslandRepository.listener
            if (listener != null) {
                Cell("Do not disturb", R.drawable.ic_bell_off, quiet, Modifier.weight(1f)) {
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
            Cell("Past 100%", R.drawable.ic_volume, prefs.boost, Modifier.weight(1f), lit = Isle.charge, onLit = Isle.onCharge) {
                onTouch()
                VolumeSettings.update { it.copy(boost = !it.boost) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Isle.rule))
        Spacer(Modifier.height(4.dp))
        for (s in VolStream.entries) {
            val lv = snap[s]
            // Notifications can't sound while the ringer is on vibrate or silent.
            val enabled = s != VolStream.Notification || snap.ringer == AudioManager.RINGER_MODE_NORMAL
            val boosted = s == VolStream.Media && boostSteps > 0
            LevelRow(
                label = s.label,
                icon = streamIcon(s, lv.index == lv.min, snap.ringer),
                bitmap = null,
                fraction = lv.fraction,
                steps = lv.steps,
                value = if (boosted) "${BoostPolicy.percent(boostSteps)}%" else "${lv.percent}%",
                valueColor = if (boosted) Isle.charge else Isle.soft,
                color = if (s == VolStream.Call) Isle.charge else Isle.on,
                enabled = enabled,
                haptics = haptics,
                onTouch = onTouch,
            ) { f -> VolumeModel.set(s, lv.indexAt(f)) }
        }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrintedStar(6.dp, Isle.soft)
                Spacer(Modifier.width(7.dp))
                IsleNote("Apps playing", Isle.soft)
            }
            Spacer(Modifier.height(2.dp))
            val iconPx = with(LocalDensity.current) { 22.dp.roundToPx() }
            // Each row belongs to its app, not to its place in the list.
            apps.forEach { a ->
                key(a.pkg) {
                    val bmp = remember(a.pkg) { AppVolumes.icon(a.pkg, iconPx) }
                    LevelRow(
                        label = a.label,
                        icon = R.drawable.ic_volume,
                        bitmap = bmp,
                        fraction = a.volume,
                        steps = null,
                        value = "${(a.volume * 100f).roundToInt()}%",
                        valueColor = Isle.soft,
                        color = Isle.lit,
                        enabled = true,
                        haptics = haptics,
                        onTouch = onTouch,
                    ) { f -> AppVolumes.setVolume(a.pkg, f) }
                }
            }
        }
        problem?.let {
            Spacer(Modifier.height(8.dp))
            IsleText(it, 13.sp, Isle.soft, maxLines = 3)
        }
    }
}

private fun ringerWord(mode: Int): String = when (mode) {
    AudioManager.RINGER_MODE_SILENT -> "Silent"
    AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
    else -> "Ring"
}

/** A choice cell, cut square: ruled when off, filled with its one colour when on. */
@Composable
private fun Cell(
    label: String,
    @DrawableRes icon: Int,
    on: Boolean,
    modifier: Modifier = Modifier,
    lit: Color = Isle.lit,
    onLit: Color = Isle.onLit,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val fg = if (on) onLit else Isle.on
    Row(
        modifier
            .squash(source, pill = true)
            .height(42.dp)
            .background(if (on) lit else Isle.well, TagShape)
            .then(if (on) Modifier else Modifier.border(1.dp, Isle.rule, TagShape))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, fg, 16.dp)
        IsleText(label, 13.5.sp, fg, weight = FontWeight.SemiBold)
    }
}

/** One row: the stream's (or app's) mark in a well, its name over a rule you drag, its level at the end. */
@Composable
private fun LevelRow(
    label: String,
    @DrawableRes icon: Int,
    bitmap: Bitmap?,
    fraction: Float,
    steps: Int?,
    value: String,
    valueColor: Color,
    color: Color,
    enabled: Boolean,
    haptics: Boolean,
    onTouch: () -> Unit,
    onChange: (Float) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(Isle.well, TagShape), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(22.dp).clip(TagShape))
            } else {
                Glyph(icon, Isle.on, 18.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IsleText(label, 13.5.sp, Isle.on, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IsleText(value, 12.5.sp, valueColor, weight = FontWeight.SemiBold, face = GroteskTight, figures = true, align = TextAlign.End)
            }
            StarRule(label, fraction, steps, color, enabled, haptics, onTouch, onChange)
        }
    }
}

/**
 * A slider printed as a rule: a hairline for the whole way, a firm line for the level, the
 * stream's steps ticked under it, and a star riding the end that swells while you hold it.
 */
@Composable
private fun StarRule(
    label: String,
    fraction: Float,
    steps: Int?,
    color: Color,
    enabled: Boolean,
    haptics: Boolean,
    onTouch: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val view = LocalView.current
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown by animateFloatAsState(dragged ?: fraction, Motion.flow(), label = "level")
    val grip by animateFloatAsState(if (dragged != null) 1f else 0f, Motion.snap(), label = "grip")
    val change by rememberUpdatedState(onChange)
    val touched by rememberUpdatedState(onTouch)
    val level by rememberUpdatedState(fraction)
    val feel by rememberUpdatedState(haptics)
    val path = remember { Path() }

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
            .height(24.dp)
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
        val y = size.height / 2f
        val span = size.width - inset * 2f
        val x = inset + span * shown.coerceIn(0f, 1f)
        drawLine(Isle.rule, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
        if (steps != null && steps in 2..40) {
            val tick = 3.dp.toPx()
            for (i in 1 until steps) {
                val tx = inset + span * i / steps
                drawLine(if (tx <= x) color.copy(alpha = 0.5f) else Isle.rule, Offset(tx, y + 2.dp.toPx()), Offset(tx, y + 2.dp.toPx() + tick), 1.dp.toPx())
            }
        }
        drawLine(color, Offset(inset, y), Offset(x, y), 2.dp.toPx(), cap = StrokeCap.Square)
        val r = (6f + 3f * grip).dp.toPx()
        drawPath(Star.path(Offset(x, y), r, pinch = Star.KEEN, rotation = 45f * grip, into = path), color)
    }
}

/** Where sound is going, as a ruled tag; tap for Android's output picker where there is one. */
@Composable
private fun OutputTag(o: OutputDevice, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Row(
        Modifier
            .height(40.dp)
            .background(Isle.well, TagShape)
            .border(1.dp, Isle.rule, TagShape)
            .pointerInput(Unit) { detectTapGestures { tap() } }
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (o.kind == OutputDevice.Kind.Speaker) R.drawable.ic_speaker else R.drawable.ic_headphones, Isle.lit, 16.dp)
        Spacer(Modifier.width(6.dp))
        IsleText(if (o.name.length > 16) o.name.take(15).trimEnd() + "…" else o.name, 13.sp, Isle.on, weight = FontWeight.Medium)
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
