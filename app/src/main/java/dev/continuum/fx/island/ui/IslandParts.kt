package dev.continuum.fx.island.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import dev.continuum.fx.ui.theme.Libron
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.continuum.fx.R
import dev.continuum.fx.island.AlarmInfo
import dev.continuum.fx.island.AlarmRing
import dev.continuum.fx.island.BatteryInfo
import dev.continuum.fx.island.CallInfo
import dev.continuum.fx.island.EventInfo
import dev.continuum.fx.island.LiveInfo
import dev.continuum.fx.island.NoteAction
import dev.continuum.fx.island.SoundMode
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.MediaInfo
import dev.continuum.fx.island.NoteInfo
import dev.continuum.fx.island.TimerInfo
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.components.squash
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

// ---- Formatting ----

internal fun clock(ms: Long): String {
    val negative = ms < 0
    val total = abs(ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val body = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    return if (negative) "−$body" else body
}

internal fun timerText(t: TimerInfo, now: Long): String =
    if (t.countDown) {
        val left = t.base - now
        clock(if (left > 0) left + 999 else left)
    } else {
        clock(now - t.base)
    }

internal fun callText(c: CallInfo, now: Long): String =
    c.startedAt?.let { clock((now - it).coerceAtLeast(0)) } ?: "Call"

// ---- Small pieces ----

@Composable
internal fun Glyph(@DrawableRes icon: Int, tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Image(painterResource(icon), contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/**
 * The playing indicator: three drops of the track's colour that bob and fuse like
 * liquid. At rest they settle into one calm pill. Content brings the colour; the
 * island stays black.
 */
@Composable
internal fun InkPulse(color: Color, playing: Boolean, modifier: Modifier = Modifier, width: Dp = 24.dp, height: Dp = 20.dp) {
    val level by animateFloatAsState(if (playing) 1f else 0f, Motion.flow(), label = "pulseLevel")
    val phase by loopPhase(playing || level > 0.01f, 1700, "pulsePhase")
    val density = LocalDensity.current
    val goo = remember(density) {
        if (Build.VERSION.SDK_INT >= 31) Goo.effect(with(density) { 2.4.dp.toPx() }) else null
    }
    Canvas(modifier.size(width, height).graphicsLayer { renderEffect = goo }) {
        val tau = 2f * PI.toFloat()
        val n = 3
        val step = size.width / (n + 0.8f)
        val r0 = size.height * 0.19f
        for (i in 0 until n) {
            // Whole-number multiples of the phase only, so the loop has no seam.
            val bob = sin(tau * phase + i * 2.1f) * size.height * 0.2f * level
            val swell = 1f + 0.25f * level * sin(tau * 2f * phase + i * 1.7f)
            val drift = sin(tau * phase + i * 0.9f) * step * 0.12f * level
            drawCircle(
                color = color,
                radius = r0 * swell,
                center = Offset(step * (i + 0.9f) + drift, size.height / 2f + bob),
            )
        }
    }
}

@Composable
internal fun Art(art: Bitmap?, accent: Color, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    val image = remember(art) { art?.asImageBitmap() }
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(shape))
    } else {
        Box(modifier.size(size).clip(shape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_music, accent, size * 0.45f)
        }
    }
}

@Composable
internal fun Avatar(photo: Bitmap?, name: String, size: Dp) {
    val image = remember(photo) { photo?.asImageBitmap() }
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        Box(Modifier.size(size).clip(CircleShape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
            if (initials.isNotEmpty() && initials.first().isLetter()) {
                Txt(initials, (size.value * 0.36f).sp, InkColors.on, bold = true)
            } else {
                Glyph(R.drawable.ic_call, InkColors.on, size * 0.42f)
            }
        }
    }
}

@Composable
private fun NoteIcon(n: NoteInfo, size: Dp) {
    val context = LocalContext.current
    val large = remember(n.key, n.largeIcon) { n.largeIcon?.asImageBitmap() }
    val small = remember(n.key) {
        try {
            n.smallIcon?.loadDrawable(context)?.toBitmap(96, 96)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    when {
        large != null -> Image(large, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
        else -> Box(Modifier.size(size).clip(CircleShape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
            if (small != null) {
                Image(small, contentDescription = null, modifier = Modifier.size(size * 0.5f), colorFilter = ColorFilter.tint(InkColors.on))
            } else {
                Glyph(R.drawable.ic_island, InkColors.on, size * 0.5f)
            }
        }
    }
}

/** A solid round button on ink, with the Continuum squash. */
@Composable
internal fun RoundButton(
    @DrawableRes icon: Int,
    description: String,
    bg: Color,
    fg: Color,
    size: Dp,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .squash(source)
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, fg, size * 0.42f)
    }
}

/** A flat pill on ink with any fill, for End, Open and notification actions. */
@Composable
internal fun InkPill(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .squash(source, pill = true)
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Glyph(icon, fg, 18.dp)
        Txt(text, 15.sp, fg, maxLines = 1)
    }
}

// ---- Compact island: content either side of the camera ----

internal const val BATTERY_W = 34f
internal const val PILL_INNER = 4f
internal const val PILL_GAP = 8f

/** One thing in the pill. Widths are measured by the island so nothing gets squeezed. */
internal sealed interface PillItem {
    data class Label(val text: String, val size: Float, val color: Color, val bold: Boolean) : PillItem
    data object Art : PillItem
    data object Pulse : PillItem
    data class Icon(@DrawableRes val res: Int, val tint: Color) : PillItem
    /** The ink battery. */
    data object Cell : PillItem
    /** Pushes what follows to the far end of its side. */
    data object Flex : PillItem
    /** An app's own small icon, tinted to the ink. */
    data class AppIcon(val icon: android.graphics.drawable.Icon?, val tint: Color) : PillItem
    /** A ring of progress; negative spins while the app can't say how far along it is. */
    data class Ring(val progress: Float, val color: Color) : PillItem
}

/** What sits either side of the camera, and how wide each side is in pixels. */
internal data class PillLayout(val left: List<PillItem>, val right: List<PillItem>, val side: Float)

internal fun clockText(context: Context, at: Long, hoursOnly: Boolean = false): String {
    val h24 = DateFormat.is24HourFormat(context)
    val pattern = when {
        hoursOnly && h24 -> "H"
        hoursOnly -> "h"
        h24 -> "H:mm"
        else -> "h:mm"
    }
    return Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
}

internal fun minutesText(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("mm"))

internal fun dateText(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))

/** The pill: whatever is live either side of the camera, with the always-on time and battery. */
@Composable
internal fun PillContent(layout: PillLayout, side: Dp, media: MediaInfo?, battery: BatteryInfo?, height: Dp) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        PillRow(layout.left, leading = true, side = side, media = media, battery = battery, height = height)
        Spacer(Modifier.weight(1f))
        PillRow(layout.right, leading = false, side = side, media = media, battery = battery, height = height)
    }
}

@Composable
private fun PillRow(items: List<PillItem>, leading: Boolean, side: Dp, media: MediaInfo?, battery: BatteryInfo?, height: Dp) {
    val outerItem = if (leading) items.firstOrNull() else items.lastOrNull()
    val outer = if (outerItem == PillItem.Art) 6.dp else 14.dp
    val flex = PillItem.Flex in items
    Row(
        Modifier
            .width(side)
            .fillMaxHeight()
            .padding(start = if (leading) outer else PILL_INNER.dp, end = if (leading) PILL_INNER.dp else outer),
        horizontalArrangement = if (flex || leading) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0 && item != PillItem.Flex && items[i - 1] != PillItem.Flex) Spacer(Modifier.width(PILL_GAP.dp))
            when (item) {
                is PillItem.Label -> Txt(item.text, item.size.sp, item.color, bold = item.bold, maxLines = 1)
                PillItem.Art -> if (media != null) Art(media.art, Color(media.accent), height - 12.dp, CircleShape)
                PillItem.Pulse -> if (media != null) InkPulse(Color(media.accent), media.playing)
                is PillItem.Icon -> Glyph(item.res, item.tint, 18.dp)
                PillItem.Cell -> if (battery != null) InkBattery(battery)
                PillItem.Flex -> Spacer(Modifier.weight(1f))
                is PillItem.AppIcon -> AppGlyph(item.icon, item.tint, 18.dp)
                is PillItem.Ring -> InkRing(item.progress, item.color, 18.dp)
            }
        }
    }
}

/** The bud: a second live activity, pinched off to the side of the island. */
@Composable
internal fun BudContent(
    kind: Kind,
    media: MediaInfo?,
    call: CallInfo?,
    timer: TimerInfo?,
    battery: BatteryInfo?,
    now: Long,
    alarm: AlarmInfo? = null,
    live: LiveInfo? = null,
    sound: SoundMode? = null,
    eventLabel: String = "",
    ringBattery: Boolean = false,
) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (kind) {
            Kind.Media -> if (media != null) InkPulse(Color(media.accent), media.playing)
            Kind.Call -> if (call != null) {
                Glyph(R.drawable.ic_call, InkColors.live, 14.dp)
                Txt(callText(call, now), 13.sp, InkColors.live, bold = true, maxLines = 1)
            }
            Kind.Timer -> if (timer != null) Txt(timerText(timer, now), 13.sp, InkColors.on, bold = true, maxLines = 1)
            Kind.Battery -> if (battery != null) {
                if (ringBattery) Txt("${battery.level}%", 13.sp, batteryColor(battery), bold = true, maxLines = 1) else InkBattery(battery)
            }
            Kind.Alarm -> if (alarm != null) {
                Glyph(R.drawable.ic_alarm, InkColors.on, 14.dp)
                Txt(clockText(LocalContext.current, alarm.at), 13.sp, InkColors.on, bold = true, maxLines = 1)
            }
            Kind.Event -> {
                Glyph(R.drawable.ic_event, InkColors.on, 14.dp)
                Txt(eventLabel, 13.sp, eventColor(eventLabel), bold = true, maxLines = 1)
            }
            Kind.Live -> if (live != null) {
                if (live.progress != null) InkRing(live.progress, liveColor(live), 16.dp) else AppGlyph(live.smallIcon, InkColors.on, 14.dp)
                liveShort(live)?.let { Txt(it, 13.sp, InkColors.on, bold = true, maxLines = 1) }
            }
            Kind.Sound -> if (sound != null) Glyph(soundIcon(sound.mode), soundColor(sound.mode), 16.dp)
            Kind.Output -> Glyph(R.drawable.ic_headphones, InkColors.live, 16.dp)
        }
    }
}

// ---- Expanded island ----

@Composable
internal fun ExpandedMedia(m: MediaInfo, band: Dp) {
    val accent = Color(m.accent)
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(band), verticalAlignment = Alignment.CenterVertically) {
            Txt(if (m.playing) "Now playing" else "Paused", 12.sp, InkColors.sub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            InkPulse(accent, m.playing)
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(
                m.art, accent, 56.dp, RoundedCornerShape(16.dp),
                Modifier.pointerInput(m.packageName) { detectTapGestures { IslandRepository.openMedia() } },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(m.title, 17.sp, InkColors.on, bold = true, maxLines = 1)
                Txt(m.artist, 14.sp, InkColors.sub, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        InkScrubber(m, accent)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkSkipButton(forward = false, description = "Previous") { IslandRepository.previous() }
            InkPlayButton(m.playing) { IslandRepository.playPause() }
            InkSkipButton(forward = true, description = "Next") { IslandRepository.next() }
        }
    }
}

@Composable
private fun rememberGoo(radius: Dp): androidx.compose.ui.graphics.RenderEffect? {
    val density = LocalDensity.current
    return remember(density, radius) {
        if (Build.VERSION.SDK_INT >= 31) Goo.effect(with(density) { radius.toPx() }) else null
    }
}

/**
 * The seek bar as liquid: the played part is a line of the track's colour ending in a
 * drop that the goo fuses to it. It flows in when the player opens, glides every frame
 * while playing, slides to where you tap, and swells under your finger while dragging.
 */
@Composable
private fun InkScrubber(m: MediaInfo, accent: Color) {
    val duration = m.durationMs
    val scope = rememberCoroutineScope()
    val live = produceState(m.positionNow(), m) {
        value = m.positionNow()
        if (m.playing) {
            while (true) {
                withFrameMillis { }
                value = m.positionNow()
            }
        }
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableStateOf<Float?>(null) }
    val seekAnim = remember { Animatable(0f) }
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 140f)) }
    val grab by animateFloatAsState(if (drag != null) 1f else 0f, Motion.flow(), label = "scrubGrab")
    val playingLevel by animateFloatAsState(if (m.playing) 1f else 0f, Motion.flow(), label = "scrubPlaying")
    val breath = rememberInfiniteTransition(label = "scrubBreath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "scrubBreathPhase",
    )
    val goo = rememberGoo(2.6.dp)

    fun liveFraction() = if (duration > 0) (live.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
    fun shownFraction(): Float = drag ?: (if (pending != null) seekAnim.value else liveFraction() * intro.value)
    fun seek(f: Float) {
        if (duration <= 0) return
        val from = shownFraction()
        IslandRepository.seekTo((f * duration).toLong())
        scope.launch {
            seekAnim.snapTo(from)
            pending = f
            drag = null
            seekAnim.animateTo(f, Motion.flow())
            delay(900)
            if (pending == f) pending = null
        }
    }
    val shownSeconds by remember(duration) {
        derivedStateOf {
            val f = drag ?: pending
            (if (f != null && duration > 0) (f * duration).toLong() else live.value) / 1000
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { seek(it) } },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f)) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val th = 6.dp.toPx()
                drawRoundRect(
                    InkColors.tonal,
                    topLeft = Offset(0f, (size.height - th) / 2f),
                    size = Size(size.width, th),
                    cornerRadius = CornerRadius(th / 2f),
                )
            }
            Canvas(Modifier.fillMaxSize().graphicsLayer { renderEffect = goo }) {
                val f = shownFraction().coerceIn(0f, 1f)
                val cy = size.height / 2f
                val th = lerpF(6.dp.toPx(), 9.dp.toPx(), grab)
                val swell = 1f + 0.07f * playingLevel * sin(2f * PI.toFloat() * breath.value)
                val bulb = lerpF(5.5.dp.toPx(), 10.dp.toPx(), grab) * swell
                val x = size.width * f
                if (x > 0f) {
                    drawRoundRect(accent, topLeft = Offset(0f, cy - th / 2f), size = Size(x, th), cornerRadius = CornerRadius(th / 2f))
                }
                drawCircle(accent, radius = bulb, center = Offset(x.coerceIn(bulb, size.width - bulb), cy))
            }
        }
        Row(Modifier.fillMaxWidth().height(16.dp)) {
            Txt(clock(shownSeconds * 1000), 11.sp, InkColors.sub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (duration > 0) Txt(clock(-(duration - shownSeconds * 1000).coerceAtLeast(0)), 11.sp, InkColors.sub, maxLines = 1)
        }
    }
}

/** Play and pause as one drop of ink: the two bars melt together into the triangle and back. */
@Composable
private fun InkPlayButton(playing: Boolean, size: Dp = 60.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val morph by animateFloatAsState(if (playing) 0f else 1f, spring(dampingRatio = 0.62f, stiffness = 320f), label = "playMorph")
    val goo = rememberGoo(2.2.dp)
    Box(
        Modifier
            .squash(source)
            .size(size)
            .clip(CircleShape)
            .background(InkColors.on)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = if (playing) "Pause" else "Play",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.5f).graphicsLayer { renderEffect = goo }) {
            val u = this.size.width / 24f
            val t = morph.coerceIn(0f, 1.1f)
            val bars = (1f - t).coerceIn(0f, 1f)
            // Through the goo, fading alpha makes a shape melt away rather than shrink to a dot.
            if (bars > 0.02f) {
                val barW = 3.8f * u * (0.45f + 0.55f * bars)
                val barH = 14f * u * (0.6f + 0.4f * bars)
                val off = 3.6f * u * bars
                for (sign in listOf(-1f, 1f)) {
                    drawRoundRect(
                        InkColors.ink,
                        topLeft = Offset(12f * u + sign * off - barW / 2f, 12f * u - barH / 2f),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW / 2f),
                        alpha = bars.pow(0.6f),
                    )
                }
            }
            if (t > 0.02f) {
                val k = 0.55f + 0.45f * t
                val cx = 13f * u
                val cy = 12f * u
                val tri = Path().apply {
                    moveTo(cx - 4.6f * u * k, cy - 7f * u * k)
                    lineTo(cx + 6.6f * u * k, cy)
                    lineTo(cx - 4.6f * u * k, cy + 7f * u * k)
                    close()
                }
                drawPath(tri, InkColors.ink, alpha = t.coerceAtMost(1f).pow(0.6f))
            }
        }
    }
}

/**
 * Previous and next as ink: press and the triangle slides into the bar and fuses with
 * it, a drop of tonal ink swelling behind; let go and it springs back apart.
 */
@Composable
private fun InkSkipButton(forward: Boolean, description: String, size: Dp = 50.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val push = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        if (pressed) push.animateTo(1f, Motion.snap()) else push.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 420f))
    }
    val goo = rememberGoo(2.2.dp)
    Box(
        Modifier
            .size(size)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val p = push.value.coerceIn(0f, 1.2f)
            if (p > 0.01f) {
                drawCircle(InkColors.tonal, radius = this.size.minDimension / 2f * (0.35f + 0.65f * p.coerceAtMost(1f)), alpha = p.coerceAtMost(1f))
            }
        }
        Canvas(Modifier.size(26.dp).graphicsLayer { renderEffect = goo }) {
            val u = this.size.width / 24f
            val p = push.value
            scale(scaleX = if (forward) 1f else -1f, scaleY = 1f, pivot = center) {
                val shift = 3.2f * u * p
                val tri = Path().apply {
                    moveTo(4f * u + shift, 6f * u)
                    lineTo(14f * u + shift * 1.15f, 12f * u)
                    lineTo(4f * u + shift, 18f * u)
                    close()
                }
                drawPath(tri, InkColors.on)
                drawRoundRect(
                    InkColors.on,
                    topLeft = Offset(16.8f * u, 6f * u),
                    size = Size(3f * u, 12f * u),
                    cornerRadius = CornerRadius(1.5f * u),
                )
            }
        }
    }
}

@Composable
internal fun ExpandedCall(c: CallInfo, now: Long, band: Dp) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(band), verticalAlignment = Alignment.CenterVertically) {
            Txt(c.appName, 12.sp, InkColors.sub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Txt(callText(c, now), 13.sp, InkColors.live, bold = true, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.photo, c.caller, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(c.caller, 18.sp, InkColors.on, bold = true, maxLines = 1)
                Txt(if (c.ringing) "Incoming call" else "On a call", 14.sp, InkColors.sub, maxLines = 1)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkPill("End", InkColors.end, InkColors.onEnd, Modifier.weight(1f), icon = R.drawable.ic_call_end) { IslandRepository.endCall() }
            if (c.open != null) {
                InkPill("Open", InkColors.tonal, InkColors.on, Modifier.weight(1f)) { IslandRepository.send(c.open) }
            }
        }
    }
}

@Composable
internal fun ExpandedTimer(t: TimerInfo, now: Long, band: Dp) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(band), verticalAlignment = Alignment.CenterVertically) {
            Txt(t.label, 12.sp, InkColors.sub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Txt(t.appName, 12.sp, InkColors.sub, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
            Txt(timerText(t, now), 44.sp, InkColors.on, bold = true, maxLines = 1, align = TextAlign.Center)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            t.actions.take(2).forEach { a ->
                InkPill(a.title, InkColors.tonal, InkColors.on, Modifier.weight(1f)) { IslandRepository.timerAction(t, a) }
            }
            if (t.open != null) {
                InkPill("Open", InkColors.tonal, InkColors.on, Modifier.weight(1f)) { IslandRepository.send(t.open) }
            }
        }
    }
}

// ---- Drips: what hangs below the island ----

@Composable
internal fun RingingDrip(c: CallInfo) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.photo, c.caller, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(c.caller, 17.sp, InkColors.on, bold = true, maxLines = 1)
            Txt("Incoming call · ${c.appName}", 13.sp, InkColors.sub, maxLines = 1)
        }
        RoundButton(R.drawable.ic_call_end, "Decline", InkColors.end, InkColors.onEnd, 48.dp) { IslandRepository.endCall() }
        Spacer(Modifier.width(10.dp))
        RoundButton(R.drawable.ic_call, "Answer", InkColors.live, InkColors.onLive, 48.dp) { IslandRepository.answer() }
    }
}

@Composable
internal fun NoteDrip(n: NoteInfo) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteIcon(n, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(n.appName, 11.sp, InkColors.sub, maxLines = 1)
                if (n.title.isNotBlank()) Txt(n.title, 15.sp, InkColors.on, bold = true, maxLines = 1)
                if (n.text.isNotBlank()) Txt(n.text, 13.sp, InkColors.sub, maxLines = 1)
            }
        }
        if (n.actions.isNotEmpty()) NoteActions(n, Modifier.padding(start = 12.dp, end = 12.dp))
    }
}

// ---- Battery ----

internal fun batteryColor(b: BatteryInfo): Color = when {
    b.charging || b.full -> InkColors.live
    b.level <= 10 -> InkColors.end
    b.low -> InkColors.end
    else -> InkColors.on
}

private fun duration(ms: Long): String {
    val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

/**
 * The ink battery: a flat capsule filled with liquid charge, its edge a soft meniscus
 * that ripples while charging, with a droplet for the terminal. The percentage sits
 * inside, black over the charge and white over the empty part, so it reads at a glance.
 * Green when charging, red when low, white otherwise.
 */
@Composable
internal fun InkBattery(
    b: BatteryInfo,
    modifier: Modifier = Modifier,
    width: Dp = BATTERY_W.dp,
    height: Dp = 18.dp,
    showNumber: Boolean = true,
) {
    val fill = batteryColor(b)
    val onFill = if (fill == InkColors.end) InkColors.onEnd else InkColors.ink
    val level by animateFloatAsState(b.level / 100f, Motion.flow(), label = "batteryLevel")
    val wave by animateFloatAsState(if (b.charging && !b.full) 1f else 0f, Motion.flow(), label = "batteryRipple")
    val phase by loopPhase((b.charging && !b.full) || wave > 0.01f, 1400, "batteryPhase")
    val measurer = rememberTextMeasurer()
    val number = remember(b.level, height) {
        measurer.measure(
            AnnotatedString("${b.level}"),
            style = TextStyle(fontFamily = Libron, fontWeight = FontWeight.Bold, fontSize = (height.value * 0.6f).sp),
        )
    }
    Canvas(modifier.size(width, height)) {
        val tau = 2f * PI.toFloat()
        val h = this.size.height
        val nub = h * 0.2f
        val gapX = h * 0.12f
        val bodyW = this.size.width - nub * 2f - gapX
        val body = Path().apply { addRoundRect(RoundRect(0f, 0f, bodyW, h, CornerRadius(h / 2f))) }
        drawPath(body, InkColors.tonal)
        drawCircle(if (b.full) fill else InkColors.tonal, radius = nub, center = Offset(bodyW + gapX + nub, h / 2f))

        val edge = bodyW * level.coerceIn(0f, 1f)
        val bulge = h * 0.14f
        val amp = h * 0.09f * wave
        val liquid = Path().apply {
            moveTo(0f, 0f)
            val steps = 16
            for (k in 0..steps) {
                val f = k / steps.toFloat()
                lineTo(edge + bulge * sin(PI.toFloat() * f) - bulge * 0.5f + sin(tau * (phase + f)) * amp, h * f)
            }
            lineTo(0f, h)
            close()
        }
        clipPath(body) { drawPath(liquid, fill) }

        if (showNumber) {
            val tl = Offset((bodyW - number.size.width) / 2f, (h - number.size.height) / 2f)
            clipPath(body) {
                clipPath(liquid) { drawText(number, color = onFill, topLeft = tl) }
                clipPath(liquid, clipOp = ClipOp.Difference) { drawText(number, color = InkColors.on, topLeft = tl) }
            }
        }
    }
}

/**
 * An endlessly repeating 0..1 phase that runs only while [running]. Stopped, nothing asks for a
 * new frame, so a still island isn't redrawn at all.
 */
@Composable
internal fun loopPhase(running: Boolean, periodMs: Int, label: String): androidx.compose.runtime.State<Float> {
    if (!running) return remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = label).animateFloat(
        0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = label,
    )
}

/** The ring's gap from the lens and its thickness, in dp. */
internal const val RING_GAP = 2f
internal const val RING_STROKE = 2.5f

/** How far past the lens the ring reaches, in dp. */
internal const val RING_REACH = RING_GAP + RING_STROKE

/**
 * The battery as a ring of liquid round the camera, so it takes no room in the island at all.
 * It fills clockwise from the top with your charge, on a dark track: green while charging (a
 * glint runs along it), red when low, white otherwise. Plugging in sweeps
 * it up from empty to where it stands. [cx], [cy] and [lens] (the camera's radius) are in pixels.
 */
@Composable
internal fun CameraRing(b: BatteryInfo?, cx: Float, cy: Float, lens: Float, visible: Boolean, flashAt: Long) {
    val alpha by animateFloatAsState(
        if (visible && b != null) 1f else 0f,
        tween(if (visible) 320 else 180),
        label = "ringAlpha",
    )
    if (b == null || alpha < 0.01f) return
    val level by animateFloatAsState(b.level / 100f, Motion.flow(), label = "ringLevel")
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(flashAt) {
        if (flashAt > 0 && System.currentTimeMillis() - flashAt < 5_000) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    val charging = b.charging && !b.full
    // Only while charging does anything move, so the rest of the time the ring is drawn once and left.
    val glint by loopPhase(charging, 1800, "ringGlint")
    val color = batteryColor(b)
    Canvas(Modifier.fillMaxSize()) {
        val stroke = RING_STROKE.dp.toPx()
        val r = lens + RING_GAP.dp.toPx() + stroke / 2f
        val topLeft = Offset(cx - r, cy - r)
        val box = Size(r * 2f, r * 2f)
        drawCircle(InkColors.tonal.copy(alpha = alpha), r, Offset(cx, cy), style = Stroke(stroke))
        val arc = 360f * level.coerceIn(0f, 1f) * sweep.value
        if (arc > 0.5f) {
            drawArc(color.copy(alpha = alpha), -90f, arc, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
            if (charging && arc > 40f) {
                val g = 24f
                drawArc(
                    Color.White.copy(alpha = 0.5f * alpha), -90f + (arc - g) * glint, g, false, topLeft, box,
                    style = Stroke(stroke * 0.55f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
internal fun ExpandedBattery(b: BatteryInfo, band: Dp) {
    val source = when {
        b.wireless && b.plugged != 0 -> "Wireless"
        b.plugged != 0 -> "Cable"
        else -> "On battery"
    }
    val detail = when {
        b.full -> "Fully charged"
        b.charging && b.fullInMs != null -> "Full in ${duration(b.fullInMs)}"
        b.charging && b.wireless -> "Charging wirelessly"
        b.charging -> "Charging"
        b.low -> "Low battery"
        else -> "${b.level}% left"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(band), verticalAlignment = Alignment.CenterVertically) {
            Txt("Battery", 12.sp, InkColors.sub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Txt(source, 12.sp, InkColors.sub, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${b.level}%", 44.sp, batteryColor(b), bold = true, maxLines = 1)
            Spacer(Modifier.weight(1f))
            InkBattery(b, width = 112.dp, height = 46.dp, showNumber = false)
        }
        Spacer(Modifier.height(8.dp))
        Txt(detail, 14.sp, InkColors.sub, maxLines = 1)
    }
}

// ---- The pulled drop: recent notifications, one at a time ----

private fun ago(at: Long, now: Long): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}

@Composable
internal fun PulledCard(notes: List<NoteInfo>, index: Int, battery: BatteryInfo?) {
    val now = remember(notes, index) { System.currentTimeMillis() }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (notes.isEmpty()) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_island, InkColors.on, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt("All caught up", 15.sp, InkColors.on, bold = true, maxLines = 1)
                Txt(
                    battery?.let { "Battery ${it.level}%" + if (it.charging) " · charging" else "" } ?: "No new notifications",
                    13.sp, InkColors.sub, maxLines = 1,
                )
            }
            if (battery != null) InkBattery(battery)
        } else {
            val i = index.mod(notes.size)
            val n = notes[i]
            NoteIcon(n, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("${n.appName} · ${ago(n.postedAt, now)}", 11.sp, InkColors.sub, maxLines = 1, modifier = Modifier.weight(1f))
                    if (notes.size > 1) Txt("${i + 1} of ${notes.size}", 11.sp, InkColors.sub, maxLines = 1)
                }
                if (n.title.isNotBlank()) Txt(n.title, 15.sp, InkColors.on, bold = true, maxLines = 1)
                if (n.text.isNotBlank()) Txt(n.text, 13.sp, InkColors.sub, maxLines = 1)
            }
        }
    }
}


// ---- App icons and progress ----

/** An app's small icon from its notification, tinted to the ink. */
@Composable
internal fun AppGlyph(icon: android.graphics.drawable.Icon?, tint: Color, size: Dp) {
    val context = LocalContext.current
    val image = remember(icon) {
        try {
            icon?.loadDrawable(context)?.toBitmap(72, 72)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (image != null) {
        Image(image, contentDescription = null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))
    } else {
        Glyph(R.drawable.ic_island, tint, size)
    }
}

/**
 * Progress as a ring of liquid filling round a dark track; while the app can't say how
 * far along it is, a short drop runs round it instead.
 */
@Composable
internal fun InkRing(progress: Float, color: Color, size: Dp, stroke: Dp = 3.dp) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.flow(), label = "ring")
    val spin by loopPhase(progress < 0f, 1100, "ringTurn")
    val turn = spin * 360f
    Canvas(Modifier.size(size)) {
        val w = stroke.toPx()
        val inset = w / 2f
        val arcSize = Size(this.size.width - w, this.size.height - w)
        val style = androidx.compose.ui.graphics.drawscope.Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(InkColors.tonal, 0f, 360f, false, Offset(inset, inset), arcSize, style = style)
        if (progress < 0f) {
            drawArc(color, turn, 80f, false, Offset(inset, inset), arcSize, style = style)
        } else if (shown > 0.005f) {
            drawArc(color, -90f, 360f * shown, false, Offset(inset, inset), arcSize, style = style)
        }
    }
}

/** A flat bar of liquid progress for the open island. */
@Composable
internal fun InkBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.flow(), label = "bar")
    val run by loopPhase(progress < 0f, 1300, "barRun")
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val h = this.size.height
        drawRoundRect(InkColors.tonal, Offset.Zero, this.size, CornerRadius(h / 2f))
        if (progress < 0f) {
            val w = this.size.width * 0.28f
            val x = (this.size.width + w) * run - w
            clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, this@Canvas.size.width, h, CornerRadius(h / 2f))) }) {
                drawRoundRect(color, Offset(x, 0f), Size(w, h), CornerRadius(h / 2f))
            }
        } else {
            drawRoundRect(color, Offset.Zero, Size(this.size.width * shown, h), CornerRadius(h / 2f))
        }
    }
}

internal fun liveColor(l: LiveInfo): Color = l.accent?.let { Color(brighten(it)) } ?: InkColors.live

/** An app colour, lifted enough to read on black ink. */
private fun brighten(color: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color, hsv)
    hsv[2] = maxOf(hsv[2], 0.75f)
    return android.graphics.Color.HSVToColor(hsv)
}

/** What a live item shows in the pill. */
internal fun liveShort(l: LiveInfo): String? = when {
    l.short != null -> l.short
    l.progress != null && l.progress >= 0f -> "${(l.progress * 100).toInt()}%"
    l.progress != null -> null
    else -> l.title.takeIf { it.isNotBlank() }?.let { if (it.length > 14) it.take(13) + "…" else it }
}

// ---- Sound mode ----

@DrawableRes
internal fun soundIcon(mode: Int): Int = when (mode) {
    0 -> R.drawable.ic_bell_off
    1 -> R.drawable.ic_vibrate
    else -> R.drawable.ic_bell
}

internal fun soundLabel(mode: Int): String = when (mode) {
    0 -> "Silent"
    1 -> "Vibrate"
    else -> "Ring"
}

internal fun soundColor(mode: Int): Color = if (mode == 0) InkColors.end else InkColors.on

/** A device's name, short enough for the pill. */
internal fun outputLabel(name: String): String = if (name.length > 18) name.take(17).trimEnd() + "…" else name

// ---- Alarms ----

/** "Today", "Tomorrow" or the day's name, for an alarm time. */
internal fun alarmDay(at: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
    }
}

/** The alarm's time in the phone's own style, with AM/PM when it uses them. */
internal fun alarmTime(context: Context, at: Long): String =
    DateFormat.getTimeFormat(context).format(java.util.Date(at))

@Composable
internal fun ExpandedAlarm(a: AlarmInfo, now: Long, band: Dp) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(band), verticalAlignment = Alignment.CenterVertically) {
            Txt("Next alarm", 12.sp, InkColors.sub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Txt(alarmDay(a.at, now), 12.sp, InkColors.sub, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_alarm, InkColors.on, 24.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(alarmTime(context, a.at), 30.sp, InkColors.on, bold = true, maxLines = 1)
                Txt("Goes off in ${duration((a.at - now).coerceAtLeast(0))}", 14.sp, InkColors.sub, maxLines = 1)
            }
        }
        Spacer(Modifier.height(14.dp))
        InkPill("Open clock", InkColors.tonal, InkColors.on, Modifier.fillMaxWidth(), icon = R.drawable.ic_alarm) { IslandRepository.openAlarm() }
    }
}

// ---- Calendar ----

/** The countdown reads in the live green once the event is starting. */
internal fun eventColor(label: String): Color = if (label == "Now") InkColors.live else InkColors.on

/** The next event, opened: what and where, its time, and Join when it has a video call. */
@Composable
internal fun ExpandedEvent(e: EventInfo, label: String, band: Dp) {
    val context = LocalContext.current
    val starting = label == "Now"
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(band), verticalAlignment = Alignment.CenterVertically) {
            Txt("Next event", 12.sp, InkColors.sub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Txt("${alarmTime(context, e.begin)} – ${alarmTime(context, e.end)}", 12.sp, InkColors.sub, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_event, if (starting) InkColors.live else InkColors.on, 24.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(e.title, 18.sp, InkColors.on, bold = true, maxLines = 1)
                val status = if (starting) "Starting now" else "Starts $label"
                Txt(listOfNotNull(status, e.location).joinToString(" · "), 14.sp, if (starting) InkColors.live else InkColors.sub, maxLines = 1)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.joinUrl != null) {
                InkPill("Join", InkColors.on, InkColors.ink, Modifier.weight(1f), icon = R.drawable.ic_video) {
                    IslandRepository.joinEvent(context, e)
                }
                InkPill("Calendar", InkColors.tonal, InkColors.on, Modifier.weight(1f), icon = R.drawable.ic_event) {
                    IslandRepository.openEvent(context, e)
                }
            } else {
                InkPill("Open calendar", InkColors.tonal, InkColors.on, Modifier.fillMaxWidth(), icon = R.drawable.ic_event) {
                    IslandRepository.openEvent(context, e)
                }
            }
        }
    }
}

/** An alarm or finished timer ringing: what it is, with the clock's own Snooze and Stop. */
@Composable
internal fun AlarmRingDrip(r: AlarmRing) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_alarm, InkColors.on, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(r.appName, 11.sp, InkColors.sub, maxLines = 1)
                Txt(r.title, 16.sp, InkColors.on, bold = true, maxLines = 1)
                if (r.text.isNotBlank()) Txt(r.text, 13.sp, InkColors.sub, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            r.actions.forEachIndexed { i, a ->
                // The last button is the one that ends it (Stop, Dismiss): it gets the bright fill.
                val last = i == r.actions.lastIndex
                InkPill(a.title, if (last) InkColors.on else InkColors.tonal, if (last) InkColors.ink else InkColors.on, Modifier.weight(1f)) {
                    IslandRepository.alarmAction(r, a)
                }
            }
            if (r.actions.isEmpty()) {
                InkPill("Open", InkColors.on, InkColors.ink, Modifier.weight(1f)) { IslandRepository.send(r.open) }
            }
        }
    }
}

// ---- Live activities ----

@Composable
internal fun ExpandedLive(l: LiveInfo, band: Dp) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(band), verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(l.smallIcon, InkColors.sub, 14.dp)
            Spacer(Modifier.width(6.dp))
            Txt(l.appName, 12.sp, InkColors.sub, maxLines = 1, modifier = Modifier.weight(1f))
            liveShort(l)?.let { Txt(it, 12.sp, InkColors.on, bold = true, maxLines = 1) }
        }
        if (l.title.isNotBlank()) Txt(l.title, 17.sp, InkColors.on, bold = true, maxLines = 1)
        if (l.text.isNotBlank()) Txt(l.text, 14.sp, InkColors.sub, maxLines = 2)
        if (l.progress != null) {
            Spacer(Modifier.height(12.dp))
            InkBar(l.progress, liveColor(l))
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            l.actions.forEach { a ->
                InkPill(a.title, InkColors.tonal, InkColors.on, Modifier.weight(1f)) { IslandRepository.liveAction(l, a) }
            }
            if (l.open != null || l.demo) {
                InkPill("Open", InkColors.tonal, InkColors.on, Modifier.weight(1f)) { if (!l.demo) IslandRepository.send(l.open) }
            }
        }
    }
}

// ---- Notifications with their buttons ----

/** The buttons under a notification: Reply first, then the app's others. */
@Composable
internal fun NoteActions(n: NoteInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        n.actions.take(3).forEach { a ->
            InkPill(
                a.title, InkColors.tonal, InkColors.on, Modifier.weight(1f),
                icon = if (a.isReply) R.drawable.ic_reply else null,
            ) { IslandRepository.noteAction(context, n, a) }
        }
    }
}

/** How tall a notification drop is: taller when it has buttons. */
internal fun noteDripHeight(n: NoteInfo): Float = if (n.actions.isEmpty()) 84f else 140f

/**
 * The pulled drop: your recent notifications as a short list. Swipe one sideways to clear
 * it, tap to open; the next alarm and Clear all sit at the foot.
 */
@Composable
internal fun PulledList(
    notes: List<NoteInfo>,
    battery: BatteryInfo?,
    alarm: AlarmInfo?,
    onOpen: (NoteInfo) -> Unit,
    onClear: (NoteInfo) -> Unit,
    onClearAll: () -> Unit,
) {
    val now = remember(notes) { System.currentTimeMillis() }
    val context = LocalContext.current
    if (notes.isEmpty()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_island, InkColors.on, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt("All caught up", 15.sp, InkColors.on, bold = true, maxLines = 1)
                val bits = listOfNotNull(
                    alarm?.let { "Alarm ${alarmTime(context, it.at)} ${alarmDay(it.at, now).lowercase()}" },
                    battery?.let { "Battery ${it.level}%" + if (it.charging) " · charging" else "" },
                )
                Txt(bits.joinToString(" · ").ifEmpty { "No new notifications" }, 13.sp, InkColors.sub, maxLines = 1)
            }
            if (battery != null) InkBattery(battery)
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(top = 8.dp, bottom = 8.dp)) {
        notes.take(PULLED_ROWS).forEach { n ->
            androidx.compose.runtime.key(n.key) {
                SwipeRow(n, now, onOpen, onClear)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().height(PULLED_FOOT.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (alarm != null) {
                Glyph(R.drawable.ic_alarm, InkColors.sub, 14.dp)
                Spacer(Modifier.width(6.dp))
                Txt("${alarmTime(context, alarm.at)} ${alarmDay(alarm.at, now).lowercase()}", 12.sp, InkColors.sub, maxLines = 1)
            } else if (notes.size > PULLED_ROWS) {
                Txt("${notes.size - PULLED_ROWS} more in the shade", 12.sp, InkColors.sub, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (notes.any { it.clearable }) {
                InkPill("Clear all", InkColors.tonal, InkColors.on, icon = R.drawable.ic_trash, onClick = onClearAll)
            }
        }
    }
}

internal const val PULLED_ROWS = 4
internal const val PULLED_ROW = 62f
internal const val PULLED_FOOT = 52f

/** How tall the pulled drop is for [count] notifications. */
internal fun pulledHeight(count: Int): Float =
    if (count == 0) 84f else 16f + PULLED_ROW * minOf(count, PULLED_ROWS) + PULLED_FOOT

/** One notification in the pulled list: follows your finger sideways and clears past halfway. */
@Composable
private fun SwipeRow(n: NoteInfo, now: Long, onOpen: (NoteInfo) -> Unit, onClear: (NoteInfo) -> Unit) {
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(PULLED_ROW.dp)
            .pointerInput(n.key) { detectTapGestures { onOpen(n) } }
            .pointerInput(n.key) {
                val width = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val past = abs(dx.value) > width * 0.35f
                        scope.launch {
                            if (past && n.clearable) {
                                dx.animateTo(if (dx.value > 0) width * 1.2f else -width * 1.2f, tween(180))
                                onClear(n)
                            } else {
                                dx.animateTo(0f, Motion.flow())
                            }
                        }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f, Motion.flow()) } },
                ) { change, amount ->
                    change.consume()
                    // Notifications that can't be cleared only give a little.
                    val give = if (n.clearable) 1f else 0.25f
                    scope.launch { dx.snapTo(dx.value + amount * give) }
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = dx.value
                    alpha = 1f - (abs(dx.value) / (size.width * 0.9f)).coerceIn(0f, 0.9f)
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteIcon(n, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt("${n.appName} · ${ago(n.postedAt, now)}", 11.sp, InkColors.sub, maxLines = 1)
                if (n.title.isNotBlank()) Txt(n.title, 15.sp, InkColors.on, bold = true, maxLines = 1)
                if (n.text.isNotBlank()) Txt(n.text, 13.sp, InkColors.sub, maxLines = 1)
            }
        }
    }
}
