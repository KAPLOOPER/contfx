package dev.continuum.fx.island.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.continuum.fx.R
import dev.continuum.fx.island.CallInfo
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
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
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

/** Playing bars in the track's own colour: content brings the colour, the ink stays black. */
@Composable
internal fun Bars(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "barsPhase",
    )
    val level by animateFloatAsState(if (playing) 1f else 0f, Motion.flow(), label = "barsLevel")
    Canvas(modifier.size(18.dp, 14.dp)) {
        val n = 4
        val bw = size.width / (n * 2 - 1)
        for (i in 0 until n) {
            val wave = ((sin((phase + i * 0.29f) * 2f * PI.toFloat()) + 1f) / 2f)
            val shaped = 0.35f + 0.65f * ((wave + (i % 2) * 0.35f) % 1f)
            val h = size.height * (0.22f + 0.78f * shaped * level)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2 * bw, (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f),
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

@Composable
internal fun CompactContent(kind: Kind, side: Dp, media: MediaInfo?, call: CallInfo?, timer: TimerInfo?, now: Long, height: Dp) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(side).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            when (kind) {
                Kind.Media -> if (media != null) {
                    Art(media.art, Color(media.accent), height - 12.dp, CircleShape, Modifier.padding(start = 6.dp))
                }
                Kind.Call -> Glyph(R.drawable.ic_call, InkColors.live, 18.dp, Modifier.padding(start = 14.dp))
                Kind.Timer -> Glyph(R.drawable.ic_timer, InkColors.on, 18.dp, Modifier.padding(start = 14.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(side).fillMaxHeight().padding(end = 14.dp), contentAlignment = Alignment.CenterEnd) {
            when (kind) {
                Kind.Media -> if (media != null) Bars(Color(media.accent), media.playing)
                Kind.Call -> if (call != null) Txt(callText(call, now), 14.sp, InkColors.live, bold = true, maxLines = 1)
                Kind.Timer -> if (timer != null) Txt(timerText(timer, now), 14.sp, InkColors.on, bold = true, maxLines = 1)
            }
        }
    }
}

/** The bud: a second live activity, pinched off to the side of the island. */
@Composable
internal fun BudContent(kind: Kind, media: MediaInfo?, call: CallInfo?, timer: TimerInfo?, now: Long) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (kind) {
            Kind.Media -> if (media != null) Bars(Color(media.accent), media.playing)
            Kind.Call -> if (call != null) {
                Glyph(R.drawable.ic_call, InkColors.live, 14.dp)
                Txt(callText(call, now), 13.sp, InkColors.live, bold = true, maxLines = 1)
            }
            Kind.Timer -> if (timer != null) Txt(timerText(timer, now), 13.sp, InkColors.on, bold = true, maxLines = 1)
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
            Bars(accent, m.playing)
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
        Spacer(Modifier.height(14.dp))
        Scrubber(m)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(R.drawable.ic_prev, "Previous", onClick = { IslandRepository.previous() }, on = Surface.Ink, size = 48.dp)
            RoundButton(
                if (m.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (m.playing) "Pause" else "Play",
                InkColors.on, InkColors.ink, 56.dp,
            ) { IslandRepository.playPause() }
            IconBubble(R.drawable.ic_next, "Next", onClick = { IslandRepository.next() }, on = Surface.Ink, size = 48.dp)
        }
    }
}

@Composable
private fun Scrubber(m: MediaInfo) {
    val position by produceState(m.positionNow(), m) {
        while (true) {
            value = m.positionNow()
            delay(250)
        }
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    val duration = m.durationMs
    val fraction = drag ?: if (duration > 0) (position.toFloat() / duration) else 0f
    val thickness by animateDpAsState(if (drag != null) 10.dp else 6.dp, Motion.flow(), label = "scrubThickness")
    val shown = if (drag != null && duration > 0) (drag!! * duration).toLong() else position
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            drag?.let { IslandRepository.seekTo((it * duration).toLong()) }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    detectTapGestures { o -> IslandRepository.seekTo(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val f = fraction.coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(thickness)) {
                drawRoundRect(InkColors.tonal, size = size, cornerRadius = CornerRadius(size.height / 2f))
                drawRoundRect(InkColors.on, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(size.height / 2f))
            }
        }
        Row(Modifier.fillMaxWidth().height(16.dp)) {
            Txt(clock(shown), 11.sp, InkColors.sub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (duration > 0) Txt(clock(-(duration - shown).coerceAtLeast(0)), 11.sp, InkColors.sub, maxLines = 1)
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
    Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
}
