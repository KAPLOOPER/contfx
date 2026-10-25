package dev.continuum.fx.volume.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.audio.OutputDevice
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.island.ui.Glyph
import dev.continuum.fx.island.ui.InkColors
import dev.continuum.fx.island.ui.InkPill
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import dev.continuum.fx.volume.AppAudio
import dev.continuum.fx.volume.AppVolumes
import dev.continuum.fx.volume.BoostPolicy
import dev.continuum.fx.volume.Level
import dev.continuum.fx.volume.VolStream
import dev.continuum.fx.volume.VolumeModel
import dev.continuum.fx.volume.VolumeSnapshot
import kotlin.math.roundToInt

/** The panel opened out: where sound is going, the ringer, every stream and every app playing. */
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
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Volume", 20.sp, InkColors.on, bold = true, maxLines = 1)
            Spacer(Modifier.weight(1f))
            output?.let { o ->
                OutputChip(o) {
                    onTouch()
                    if (showOutputSwitcher(context)) onDone()
                }
                Spacer(Modifier.width(6.dp))
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(InkColors.tonal)
                    .semantics { contentDescription = "Volume settings" }
                    .pointerInput(Unit) { detectTapGestures { open() } },
                contentAlignment = Alignment.Center,
            ) { Glyph(R.drawable.ic_setup, InkColors.on, 20.dp) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Triple(AudioManager.RINGER_MODE_NORMAL, "Ring", R.drawable.ic_bell),
                Triple(AudioManager.RINGER_MODE_VIBRATE, "Vibrate", R.drawable.ic_vibrate),
                Triple(AudioManager.RINGER_MODE_SILENT, "Silent", R.drawable.ic_bell_off),
            ).forEach { (mode, label, icon) ->
                val on = snap.ringer == mode
                InkPill(label, if (on) InkColors.on else InkColors.tonal, if (on) InkColors.ink else InkColors.on, Modifier.weight(1f), icon = icon) {
                    onTouch()
                    VolumeModel.setRinger(mode)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
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
                valueColor = if (boosted) InkColors.live else InkColors.sub,
                enabled = enabled,
                haptics = haptics,
                onTouch = onTouch,
            ) { f -> VolumeModel.set(s, lv.indexAt(f)) }
        }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Txt("Apps", 13.sp, InkColors.sub, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            val iconPx = with(LocalDensity.current) { 22.dp.roundToPx() }
            // Each row belongs to its app, not to its place in the list.
            apps.forEach { a -> key(a.pkg) {
                val bmp = remember(a.pkg) { AppVolumes.icon(a.pkg, iconPx) }
                LevelRow(
                    label = a.label,
                    icon = R.drawable.ic_volume,
                    bitmap = bmp,
                    fraction = a.volume,
                    steps = null,
                    value = "${(a.volume * 100f).roundToInt()}%",
                    valueColor = InkColors.sub,
                    enabled = true,
                    haptics = haptics,
                    onTouch = onTouch,
                ) { f -> AppVolumes.setVolume(a.pkg, f) }
            } }
        }
        problem?.let {
            Spacer(Modifier.height(8.dp))
            Txt(it, 13.sp, InkColors.sub)
        }
    }
}

/** One row: a liquid slider with the stream's (or app's) name inside, and its level beside. */
@Composable
private fun LevelRow(
    label: String,
    @DrawableRes icon: Int,
    bitmap: Bitmap?,
    fraction: Float,
    steps: Int?,
    value: String,
    valueColor: Color,
    enabled: Boolean,
    haptics: Boolean,
    onTouch: () -> Unit,
    onChange: (Float) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(22.dp).clip(CircleShape))
            } else {
                Glyph(icon, InkColors.on, 20.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        LiquidSlider(label, fraction, steps, enabled, haptics, onTouch, onChange, Modifier.weight(1f).height(38.dp))
        Txt(value, 13.sp, valueColor, modifier = Modifier.width(48.dp), maxLines = 1, align = TextAlign.End)
    }
}

/**
 * A horizontal slider of ink: the level is a pill of liquid that glides to where you touch,
 * snapping to the stream's own steps. The name sits inside, dark over the liquid, light past it.
 */
@Composable
private fun LiquidSlider(
    label: String,
    fraction: Float,
    steps: Int?,
    enabled: Boolean,
    haptics: Boolean,
    onTouch: () -> Unit,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown by animateFloatAsState(dragged ?: fraction, Motion.flow(), label = "level")
    val change by rememberUpdatedState(onChange)
    val touched by rememberUpdatedState(onTouch)
    val level by rememberUpdatedState(fraction)
    val feel by rememberUpdatedState(haptics)
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 13.sp, fontFamily = Libron)
    val layout = remember(label) { measurer.measure(label, style, maxLines = 1) }

    fun snapped(f: Float): Float = if (steps == null || steps <= 0) f.coerceIn(0f, 1f) else (f * steps).roundToInt().toFloat() / steps

    fun move(x: Float, width: Float) {
        val f = snapped(x / width)
        val before = dragged ?: level
        if (f != before) {
            dragged = f
            change(f)
            if (feel && steps != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Canvas(
        modifier
            .clip(RoundedCornerShape(50))
            .semantics { contentDescription = "$label volume" }
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(steps) {
                        detectHorizontalDragGestures(
                            onDragStart = { pos ->
                                touched()
                                dragged = level
                                move(pos.x, size.width.toFloat())
                            },
                            onDragEnd = { dragged = null },
                            onDragCancel = { dragged = null },
                        ) { ch, _ ->
                            ch.consume()
                            touched()
                            move(ch.position.x, size.width.toFloat())
                        }
                    }
                    .pointerInput(steps) {
                        detectTapGestures { pos ->
                            touched()
                            move(pos.x, size.width.toFloat())
                            dragged = null
                        }
                    },
            ),
    ) {
        val h = size.height
        drawRoundRect(InkColors.tonal, cornerRadius = CornerRadius(h / 2f))
        val fillW = (shown.coerceIn(0f, 1f) * size.width).coerceAtLeast(if (shown > 0.001f) h else 0f)
        if (fillW > 0f) drawRoundRect(InkColors.on, size = Size(fillW, h), cornerRadius = CornerRadius(h / 2f))
        val top = (h - layout.size.height) / 2f
        val x = 14.dp.toPx()
        clipRect(right = fillW) { drawText(layout, color = InkColors.ink, topLeft = Offset(x, top)) }
        clipRect(left = fillW) { drawText(layout, color = InkColors.on, topLeft = Offset(x, top)) }
    }
}

/** Where sound is going, as a chip; tap for Android's output picker where there is one. */
@Composable
private fun OutputChip(o: OutputDevice, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(InkColors.tonal)
            .pointerInput(Unit) { detectTapGestures { tap() } }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (o.kind == OutputDevice.Kind.Speaker) R.drawable.ic_speaker else R.drawable.ic_headphones, InkColors.on, 16.dp)
        Spacer(Modifier.width(6.dp))
        Txt(if (o.name.length > 16) o.name.take(15).trimEnd() + "…" else o.name, 13.sp, InkColors.on, maxLines = 1)
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
