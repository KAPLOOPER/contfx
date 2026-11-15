/*
 * What hangs from the star: a black slip on two threads, cut square at its corners like the
 * Eidolon tags, printed in the negative (paper-white ink, the one lit ultramarine, the one red
 * mark). A notification or a ringing call hangs as a short ticket; anything you open hangs as
 * its full card. Every card here is only the slip's content: the island hangs it, swings it and
 * takes it back.
 */

package dev.continuum.fx.island.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.continuum.fx.R
import dev.continuum.fx.island.AlarmInfo
import dev.continuum.fx.island.AlarmRing
import dev.continuum.fx.island.BatteryInfo
import dev.continuum.fx.island.CallInfo
import dev.continuum.fx.island.EventInfo
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.LiveInfo
import dev.continuum.fx.island.MediaInfo
import dev.continuum.fx.island.NoteAction
import dev.continuum.fx.island.NoteInfo
import dev.continuum.fx.island.OneTimeCodes
import dev.continuum.fx.island.TimerInfo
import dev.continuum.fx.ui.components.squash
import dev.continuum.fx.ui.eidolon.Grotesk
import dev.continuum.fx.ui.eidolon.GroteskTight
import dev.continuum.fx.ui.eidolon.GroteskWide
import dev.continuum.fx.ui.eidolon.Star
import dev.continuum.fx.ui.eidolon.StarShape
import dev.continuum.fx.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// ---- The island's inks: Eidolon's Void print, on pure black so the star meets the camera ----

internal object Isle {
    /** The star, the band, the threads: the camera's own black. */
    val ink = Color(0xFF000000)

    /** The slip: a hair off black, so a card reads as paper hung below the star, not a hole. */
    val slip = Color(0xFF060608)

    /** A recess in the slip: an icon's well, a button's ground. */
    val well = Color(0xFF17171D)

    val on = Color(0xFFF4F4F0)
    val soft = Color(0xFFA4A4A8)
    val faint = Color(0xFF6C6C72)
    val rule = Color(0x29F4F4F0)
    val ruleStrong = Color(0x66F4F4F0)

    /** Ultramarine as it prints on the dark: the lit state of anything. */
    val lit = Color(0xFFA3ACFF)
    val onLit = Color(0xFF05053A)

    /** The poster's ultramarine at full strength, for the halftone grounds. */
    val deep = Color(0xFF1512D9)

    /** The one red mark: whatever needs you. */
    val signal = Color(0xFFFF2B33)

    /** Charge: the battery filling, a call connected. */
    val charge = Color(0xFF97C459)
    val onCharge = Color(0xFF173404)
}

/** The slip's cut: corners clipped square, as every tag and window in Eidolon is. */
internal val SlipShape = CutCornerShape(7.dp)

/** A tag's and a button's smaller cut. */
internal val TagShape = CutCornerShape(4.dp)

// ---- Type ----

/** One line (or [maxLines]) of the island's grotesque, cut off with an ellipsis. */
@Composable
internal fun IsleText(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    face: FontFamily = Grotesk,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    tracking: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    figures: Boolean = false,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = face,
            letterSpacing = tracking,
            textAlign = align,
            lineHeight = lineHeight,
            fontFeatureSettings = if (figures) "tnum" else null,
        ),
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
        maxLines = maxLines,
    )
}

/** The small capitals every Eidolon label is set in: wide, tracked, figures in columns. */
@Composable
internal fun IsleNote(text: String, color: Color = Isle.soft, modifier: Modifier = Modifier) {
    val caps = text.uppercase()
    BasicText(
        caps,
        modifier.semantics { contentDescription = text },
        style = TextStyle(
            color = color,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GroteskWide,
            letterSpacing = 0.95.sp,
            fontFeatureSettings = "tnum, case",
        ),
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
    )
}

// ---- Pieces of a slip ----

/** A slip's first line: a printed star, what this is, and on the right whatever else it says. */
@Composable
internal fun SlipHeader(label: String, trailing: String? = null, mark: Color = Isle.on, trailingColor: Color = Isle.soft) {
    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
        PrintedStar(7.dp, mark)
        Spacer(Modifier.width(7.dp))
        IsleNote(label, Isle.soft, Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            IsleNote(trailing, trailingColor)
        }
    }
}

/** A four-point star, printed small. */
@Composable
internal fun PrintedStar(size: Dp, color: Color, modifier: Modifier = Modifier, rotation: Float = 0f, pinch: Float = Star.SOFT) {
    val path = remember { Path() }
    Canvas(modifier.size(size)) {
        drawPath(Star.path(center, this.size.minDimension / 2f, pinch = pinch, rotation = rotation, into = path), color)
    }
}

/** The hairline rule between a slip's parts. */
@Composable
internal fun SlipRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Isle.rule))
}

internal enum class Tone { Plain, Lit, Signal, Charge }

/** A slip's button: cut square, ruled when plain, filled with the one colour that says what it does. */
@Composable
internal fun SlipButton(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Plain,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val (bg, fg) = when (tone) {
        Tone.Plain -> Isle.well to Isle.on
        Tone.Lit -> Isle.lit to Isle.onLit
        Tone.Signal -> Isle.signal to Color.White
        Tone.Charge -> Isle.charge to Isle.onCharge
    }
    Row(
        modifier
            .squash(source, pill = true)
            .height(42.dp)
            .background(bg, TagShape)
            .then(if (tone == Tone.Plain) Modifier.border(1.dp, Isle.rule, TagShape) else Modifier)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                InkFeel.land(view)
                onClick()
            }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Glyph(icon, fg, 16.dp)
        IsleText(text, 14.sp, fg, weight = FontWeight.SemiBold)
    }
}

/** Buttons shared out along the foot of a slip. */
@Composable
internal fun SlipActions(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A bare glyph you can press, for the transport either side of the play star. */
@Composable
private fun GlyphPress(@DrawableRes icon: Int, description: String, size: Dp = 48.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    Box(
        Modifier
            .squash(source)
            .size(size)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = description) {
                InkFeel.split(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, Isle.on, 24.dp) }
}

/** An angle that keeps turning at [degPerSecond] while [running], and holds where it stopped. */
@Composable
internal fun rememberTurn(running: Boolean, degPerSecond: Float): State<Float> {
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { t ->
                angle.floatValue = (angle.floatValue + (t - last) / 1e9f * degPerSecond) % 360f
                last = t
            }
        }
    }
    return angle
}

/**
 * A halftone plate: ultramarine with a screen of lit dots swelling toward one corner, as the
 * poster's sky prints. Stands in for artwork and photos the app didn't give.
 */
private fun Modifier.halftone(seed: Int): Modifier = drawWithCache {
    val pitch = 3.2.dp.toPx()
    val cols = (size.width / pitch).toInt() + 2
    val rows = (size.height / pitch).toInt() + 2
    val turn = (seed and 3) * 0.5f
    onDrawBehind {
        drawRect(Isle.deep)
        for (j in 0 until rows) {
            for (i in 0 until cols) {
                val x = (i + if (j % 2 == 0) 0f else 0.5f) * pitch
                val y = j * pitch
                val u = x / size.width
                val v = y / size.height
                // A slow fall of light from one corner, rippled so it reads as cloud, not a ramp.
                val light = (0.95f - 0.75f * (if (turn < 0.75f) v else 1f - v) - 0.25f * u +
                    0.12f * sin((u * 5.3f + v * 3.1f + seed * 0.37f) * PI.toFloat())).coerceIn(0f, 1f)
                val r = pitch * 0.5f * light
                if (r > 0.25f) drawCircle(Isle.lit, r, Offset(x, y))
            }
        }
    }
}

/** A picture in a star-shaped window; a halftone plate when there is no picture. */
@Composable
internal fun StarWindow(image: Bitmap?, size: Dp, modifier: Modifier = Modifier, turn: Float = 0f, seed: Int = 0, pinch: Float = Star.CIRCULAR) {
    val bitmap = remember(image) { image?.asImageBitmap() }
    val shape = remember(pinch) { StarShape(pinch) }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                rotationZ = turn
                this.shape = shape
                clip = true
            },
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().halftone(seed))
        }
    }
}

/** Someone's photo in a star, or their initials printed on a halftone star. */
@Composable
private fun Caller(photo: Bitmap?, name: String, size: Dp) {
    Box(contentAlignment = Alignment.Center) {
        StarWindow(photo, size, seed = name.hashCode(), pinch = Star.SOFT)
        if (photo == null) {
            val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
            if (initials.isNotEmpty() && initials.first().isLetter()) {
                IsleText(initials, (size.value * 0.27f).sp, Isle.on, weight = FontWeight.Bold, face = GroteskWide)
            } else {
                Glyph(R.drawable.ic_call, Isle.on, size * 0.3f)
            }
        }
    }
}

/** A notification's face: its picture in a star, or its app's mark in a square-cut well. */
@Composable
internal fun NoteFace(n: NoteInfo, size: Dp) {
    val context = LocalContext.current
    val large = n.largeIcon
    if (large != null) {
        StarWindow(large, size, pinch = Star.SOFT)
        return
    }
    val small = remember(n.key) {
        try {
            n.smallIcon?.loadDrawable(context)?.toBitmap(96, 96)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    Box(Modifier.size(size).background(Isle.well, TagShape), contentAlignment = Alignment.Center) {
        if (small != null) {
            Image(small, contentDescription = null, modifier = Modifier.size(size * 0.5f), colorFilter = ColorFilter.tint(Isle.on))
        } else {
            Glyph(R.drawable.ic_notification, Isle.on, size * 0.5f)
        }
    }
}

/** How far through a song is, a rule with a star riding it. Drag the star, or tap the rule, to seek. */
@Composable
private fun StarScrubber(m: MediaInfo) {
    val view = LocalView.current
    val duration = m.durationMs.coerceAtLeast(1L)
    val position by produceState(m.positionNow(), m) {
        while (true) {
            value = m.positionNow()
            delay(if (m.playing) 250L else 1_000L)
        }
    }
    var dragging by remember { mutableStateOf(false) }
    var dragAt by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragAt else (position.toFloat() / duration).coerceIn(0f, 1f)
    val grip by animateFloatAsState(if (dragging) 1f else 0f, Motion.snap(), label = "grip")
    val path = remember { Path() }
    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics { contentDescription = "Position ${clock((shown * duration).toLong())} of ${clock(duration)}" }
                .pointerInput(duration) {
                    detectTapGestures { at ->
                        InkFeel.split(view)
                        IslandRepository.seekTo((at.x / size.width).coerceIn(0f, 1f).times(duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { at ->
                            dragging = true
                            dragAt = (at.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            IslandRepository.seekTo((dragAt * duration).toLong())
                            InkFeel.land(view)
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    ) { change, dx ->
                        change.consume()
                        dragAt = (dragAt + dx / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val y = size.height / 2f
            val inset = 9.dp.toPx()
            val span = size.width - inset * 2f
            val x = inset + span * shown
            drawLine(Isle.rule, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
            drawLine(Isle.on, Offset(inset, y), Offset(x, y), 2.dp.toPx(), cap = StrokeCap.Square)
            val r = (6.5f + 3f * grip).dp.toPx()
            drawPath(Star.path(Offset(x, y), r, pinch = Star.KEEN, rotation = 45f * grip, into = path), Isle.on)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            IsleNote(clock((shown * duration).toLong()), Isle.soft)
            Spacer(Modifier.weight(1f))
            IsleNote("−" + clock(duration - (shown * duration).toLong()), Isle.soft)
        }
    }
}

/** Play and pause: a paper-white star you press, turning a quarter as it changes its mind. */
@Composable
private fun PlayStar(playing: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val turn by animateFloatAsState(if (playing) 45f else 0f, Motion.flow(), label = "playTurn")
    val path = remember { Path() }
    Box(
        Modifier
            .squash(source)
            .size(66.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = if (playing) "Pause" else "Play") {
                InkFeel.toggle(view, !playing)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawPath(Star.path(center, size.minDimension / 2f, pinch = Star.SOFT, rotation = turn, into = path), Isle.on)
        }
        Glyph(if (playing) R.drawable.ic_pause else R.drawable.ic_play, Isle.ink, 22.dp)
    }
}

// ---- The cards ----

/** What every slip's content sits in: its padding, its rhythm. */
@Composable
internal fun SlipBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp), content = content)
}

@Composable
internal fun MediaCard(m: MediaInfo) {
    val turn by rememberTurn(m.playing, 14f)
    SlipBody {
        SlipHeader(if (m.playing) "Now playing" else "Paused", mark = if (m.playing) Isle.lit else Isle.soft)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StarWindow(
                m.art, 68.dp, turn = turn, seed = m.title.hashCode(),
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { IslandRepository.openMedia() },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                IsleText(m.title.ifBlank { "Untitled" }, 18.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight, maxLines = 2, lineHeight = 21.sp)
                if (m.artist.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    IsleText(m.artist, 14.sp, Isle.soft)
                }
            }
        }
        if (m.durationMs > 0) {
            Spacer(Modifier.height(8.dp))
            StarScrubber(m)
        } else {
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            GlyphPress(R.drawable.ic_prev, "Previous") { IslandRepository.previous() }
            PlayStar(m.playing) { IslandRepository.playPause() }
            GlyphPress(R.drawable.ic_next, "Next") { IslandRepository.next() }
        }
    }
}

@Composable
internal fun CallCard(c: CallInfo, now: Long, onDone: () -> Unit) {
    SlipBody {
        SlipHeader("On a call", c.appName, mark = Isle.charge)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caller(c.photo, c.caller, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                IsleText(c.caller, 18.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight)
                IsleText(callText(c, now), 30.sp, Isle.charge, weight = FontWeight.Medium, face = GroteskTight, figures = true)
            }
        }
        Spacer(Modifier.height(14.dp))
        SlipActions {
            if (c.open != null) SlipButton("Open", Modifier.weight(1f)) { IslandRepository.send(c.open); onDone() }
            SlipButton("End", Modifier.weight(1f), Tone.Signal, R.drawable.ic_call_end) { IslandRepository.endCall() }
        }
    }
}

/** A call ringing: who, and the two answers, red to the left and lit to the right. */
@Composable
internal fun RingTicket(c: CallInfo) {
    SlipBody {
        SlipHeader("Incoming", c.appName, mark = Isle.signal)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caller(c.photo, c.caller, 46.dp)
            Spacer(Modifier.width(12.dp))
            IsleText(c.caller, 19.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        SlipActions {
            SlipButton("Decline", Modifier.weight(1f), Tone.Signal, R.drawable.ic_call_end) { IslandRepository.endCall() }
            SlipButton("Answer", Modifier.weight(1f), Tone.Charge, R.drawable.ic_call) { IslandRepository.answer() }
        }
    }
}

@Composable
internal fun TimerCard(t: TimerInfo, now: Long, onDone: () -> Unit) {
    val over = t.countDown && t.base - now < 0
    SlipBody {
        SlipHeader(if (t.countDown) "Timer" else "Stopwatch", t.appName, mark = Isle.lit)
        Spacer(Modifier.height(6.dp))
        IsleText(timerText(t, now), 50.sp, if (over) Isle.signal else Isle.on, weight = FontWeight.Medium, face = GroteskTight, figures = true)
        if (t.label.isNotBlank() && t.label != "Timer") IsleText(t.label, 14.sp, Isle.soft)
        Spacer(Modifier.height(12.dp))
        SlipActions {
            t.actions.take(3).forEachIndexed { i, a ->
                SlipButton(a.title, Modifier.weight(1f), if (i == 0) Tone.Lit else Tone.Plain) { IslandRepository.timerAction(t, a) }
            }
            if (t.actions.isEmpty() && t.open != null) SlipButton("Open", Modifier.weight(1f)) { IslandRepository.send(t.open); onDone() }
        }
    }
}

/** The colour the battery's sliver is drawn in. */
internal fun sliverColor(b: BatteryInfo?): Color = when {
    b == null -> Isle.on
    b.charging || b.full -> Isle.charge
    b.low -> Isle.signal
    else -> Isle.on
}

@Composable
internal fun BatteryCard(b: BatteryInfo) {
    val level by animateFloatAsState(b.level / 100f, Motion.flow(), label = "cardLevel")
    val spark by loopPhase(b.charging && !b.full, 1_900, "cardSpark")
    val halo = remember { mutableStateOf<StarHalo?>(null) }
    val path = remember { Path() }
    val star = remember { Path() }
    SlipBody {
        SlipHeader("Battery", batteryWord(b), mark = sliverColor(b), trailingColor = if (b.low) Isle.signal else Isle.soft)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The island's own star, large: the sliver round it is the charge, as in the bar.
            Canvas(Modifier.size(78.dp)) {
                val ry = size.height * 0.34f
                val rx = ry * 1.16f
                val gap = 4.dp.toPx()
                val h = halo.value ?: StarHalo(rx, ry, Star.SOFT, gap).also { halo.value = it }
                drawPath(Star.path(center, rx, ry, Star.SOFT, into = star), Isle.ink)
                drawPath(Star.path(center, rx, ry, Star.SOFT, into = star), Isle.ruleStrong, style = Stroke(1.dp.toPx()))
                drawPath(h.trace(0f, 1f, center.x, center.y, path), Isle.rule, style = Stroke(2.dp.toPx()))
                drawPath(
                    h.trace(0f, level, center.x, center.y, path), sliverColor(b),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                if (b.charging && !b.full && level > 0.02f) {
                    val head = level * spark
                    drawPath(
                        h.trace((head - 0.08f).coerceAtLeast(0f), head, center.x, center.y, path), Color.White.copy(alpha = 0.9f),
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                IsleText("${b.level}%", 44.sp, Isle.on, weight = FontWeight.Medium, face = GroteskTight, figures = true)
                val detail = when {
                    b.full -> "Fully charged"
                    b.charging && b.fullInMs != null && b.fullInMs > 0 -> "Full in ${duration(b.fullInMs)}"
                    b.charging && b.wireless -> "Charging wirelessly"
                    b.charging -> "Charging"
                    b.low -> "Running low: plug in soon"
                    else -> "On battery"
                }
                IsleText(detail, 14.sp, if (b.low) Isle.signal else Isle.soft)
            }
        }
    }
}

@Composable
internal fun AlarmCard(a: AlarmInfo, now: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    SlipBody {
        SlipHeader("Next alarm", alarmDay(a.at, now), mark = Isle.lit)
        Spacer(Modifier.height(4.dp))
        IsleText(alarmTime(context, a.at), 46.sp, Isle.on, weight = FontWeight.Medium, face = GroteskTight, figures = true)
        IsleText("In ${duration((a.at - now).coerceAtLeast(0))}", 14.sp, Isle.soft)
        if (a.show != null || a.demo) {
            Spacer(Modifier.height(12.dp))
            SlipActions { SlipButton("Open clock", Modifier.weight(1f), icon = R.drawable.ic_alarm) { IslandRepository.openAlarm(); onDone() } }
        }
    }
}

@Composable
internal fun EventCard(e: EventInfo, label: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val fmt = remember { DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm") }
    val span = Instant.ofEpochMilli(e.begin).atZone(zone).format(fmt) + " – " + Instant.ofEpochMilli(e.end).atZone(zone).format(fmt)
    SlipBody {
        SlipHeader("Up next", label, mark = if (label == "Now") Isle.charge else Isle.lit, trailingColor = if (label == "Now") Isle.charge else Isle.soft)
        Spacer(Modifier.height(8.dp))
        IsleText(e.title.ifBlank { "Event" }, 20.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight, maxLines = 2, lineHeight = 23.sp)
        Spacer(Modifier.height(2.dp))
        IsleText(listOfNotNull(span, e.location?.takeIf { it.isNotBlank() }).joinToString(" · "), 14.sp, Isle.soft)
        Spacer(Modifier.height(12.dp))
        SlipActions {
            if (e.joinUrl != null) SlipButton("Join", Modifier.weight(1f), Tone.Lit, R.drawable.ic_video) { IslandRepository.joinEvent(context, e); onDone() }
            SlipButton("Open", Modifier.weight(1f), icon = R.drawable.ic_event) { IslandRepository.openEvent(context, e); onDone() }
        }
    }
}

/** How far along something is: a rule with a star at its head, or a star running along it while the app can't say. */
@Composable
private fun StarProgress(progress: Float) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.flow(), label = "liveProgress")
    val run by loopPhase(progress < 0f, 1_400, "liveRun")
    val path = remember { Path() }
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val y = size.height / 2f
        val inset = 7.dp.toPx()
        val span = size.width - inset * 2f
        drawLine(Isle.rule, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
        if (progress < 0f) {
            val x = inset + span * run
            drawLine(Isle.lit, Offset((x - span * 0.18f).coerceAtLeast(inset), y), Offset(x, y), 2.dp.toPx())
            drawPath(Star.path(Offset(x, y), 5.5.dp.toPx(), pinch = Star.KEEN, rotation = run * 180f, into = path), Isle.lit)
        } else {
            val x = inset + span * shown
            drawLine(Isle.lit, Offset(inset, y), Offset(x, y), 2.dp.toPx())
            drawPath(Star.path(Offset(x, y), 5.5.dp.toPx(), pinch = Star.KEEN, into = path), Isle.lit)
        }
    }
}

@Composable
internal fun LiveCard(l: LiveInfo, onDone: () -> Unit) {
    SlipBody {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(l.smallIcon, Isle.on, 14.dp)
            Spacer(Modifier.width(7.dp))
            IsleNote(l.appName, Isle.soft, Modifier.weight(1f))
            liveShort(l)?.let { IsleNote(it, Isle.lit) }
        }
        Spacer(Modifier.height(8.dp))
        if (l.title.isNotBlank()) IsleText(l.title, 17.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight)
        if (l.text.isNotBlank()) IsleText(l.text, 14.sp, Isle.soft, maxLines = 2, lineHeight = 18.sp)
        if (l.progress != null) {
            Spacer(Modifier.height(6.dp))
            StarProgress(l.progress)
        }
        if (l.actions.isNotEmpty() || l.open != null) {
            Spacer(Modifier.height(10.dp))
            SlipActions {
                l.actions.take(2).forEach { a -> SlipButton(a.title, Modifier.weight(1f)) { IslandRepository.liveAction(l, a) } }
                if (l.open != null) SlipButton("Open", Modifier.weight(1f), Tone.Lit) { IslandRepository.send(l.open); onDone() }
            }
        }
    }
}

/** Copy, then Copied, in charge green, for a moment. */
@Composable
private fun CopyCode(code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    SlipButton(
        if (copied) "Copied" else "Copy",
        modifier,
        if (copied) Tone.Charge else Tone.Lit,
        if (copied) R.drawable.ic_check else R.drawable.ic_copy,
    ) {
        if (OneTimeCodes.copy(context, code)) {
            copied = true
            InkFeel.land(view)
        }
    }
}

/** A notification's line of who and when, with the red mark on a missed call. */
@Composable
private fun NoteHead(n: NoteInfo, now: Long) {
    SlipHeader(
        n.appName.ifBlank { "Notification" },
        ago(n.postedAt, now),
        mark = if (n.missed) Isle.signal else if (n.code != null) Isle.lit else Isle.on,
    )
}

/** A notification arriving: a short ticket. Tap it for its card; push it up to tuck it away, or aside to clear it. */
@Composable
internal fun PeekTicket(n: NoteInfo, codes: Boolean) {
    val now = remember(n.key, n.postedAt) { System.currentTimeMillis() }
    val code = n.code?.takeIf { codes }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NoteFace(n, 36.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IsleText(n.title.ifBlank { n.appName }, 15.sp, Isle.on, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    IsleNote(ago(n.postedAt, now), Isle.faint)
                }
                if (code == null && n.text.isNotBlank()) IsleText(n.text, 13.5.sp, if (n.missed) Isle.signal else Isle.soft)
                if (code != null) IsleText(n.appName, 13.5.sp, Isle.soft)
            }
        }
        if (code != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IsleText(spacedCode(code), 30.sp, Isle.on, weight = FontWeight.Medium, face = GroteskTight, figures = true, modifier = Modifier.weight(1f))
                CopyCode(code)
            }
        }
    }
}

/** A notification opened: all of what it says, its code, and its own buttons. */
@Composable
internal fun NoteCard(n: NoteInfo, codes: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val now = remember(n.key, n.postedAt) { System.currentTimeMillis() }
    val code = n.code?.takeIf { codes }
    SlipBody {
        NoteHead(n, now)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            NoteFace(n, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (n.title.isNotBlank()) IsleText(n.title, 17.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight, maxLines = 2)
                if (n.text.isNotBlank()) IsleText(n.text, 14.5.sp, if (n.missed) Isle.signal else Isle.soft, maxLines = 7, lineHeight = 19.sp)
            }
        }
        if (code != null) {
            Spacer(Modifier.height(12.dp))
            SlipRule()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IsleText(spacedCode(code), 36.sp, Isle.on, weight = FontWeight.Medium, face = GroteskTight, figures = true, modifier = Modifier.weight(1f))
                CopyCode(code)
            }
        }
        val buttons = n.actions.take(3)
        if (buttons.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SlipActions {
                buttons.forEach { a ->
                    SlipButton(a.title, Modifier.weight(1f), if (a.isReply) Tone.Lit else Tone.Plain, if (a.isReply) R.drawable.ic_reply else null) {
                        IslandRepository.noteAction(context, n, a)
                        onDone()
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SlipActions {
            SlipButton("Open", Modifier.weight(1f)) { IslandRepository.openNote(n); onDone() }
            if (n.clearable) SlipButton("Clear", Modifier.weight(1f), icon = R.drawable.ic_close) { IslandRepository.dismissNote(n); onDone() }
        }
    }
}

/** An alarm, or a finished timer, ringing: its name and the clock's own buttons, the last of them lit. */
@Composable
internal fun AlarmRingTicket(r: AlarmRing) {
    SlipBody {
        SlipHeader(r.title.ifBlank { "Alarm" }, r.appName, mark = Isle.signal)
        Spacer(Modifier.height(4.dp))
        if (r.text.isNotBlank()) IsleText(r.text, 34.sp, Isle.on, weight = FontWeight.Medium, face = GroteskTight, figures = true)
        Spacer(Modifier.height(10.dp))
        SlipActions {
            val acts: List<NoteAction> = r.actions.take(3)
            acts.forEachIndexed { i, a ->
                SlipButton(a.title, Modifier.weight(1f), if (i == acts.lastIndex) Tone.Lit else Tone.Plain) { IslandRepository.alarmAction(r, a) }
            }
            if (acts.isEmpty()) SlipButton("Open", Modifier.weight(1f), Tone.Lit) { IslandRepository.send(r.open) }
        }
    }
}

/** One recent notification: tap it to open it; slide it aside to clear it. */
@Composable
private fun RecentRow(n: NoteInfo, now: Long, onOpen: (NoteInfo) -> Unit, onClear: (NoteInfo) -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val slide = remember(n.key) { Animatable(0f) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(slide.value.roundToInt(), 0) }
                .graphicsLayer { alpha = (1f - abs(slide.value) / (size.width * 0.8f)).coerceIn(0f, 1f) }
                .pointerInput(n.key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(slide.value) > size.width * 0.3f && n.clearable) {
                                    InkFeel.split(view)
                                    slide.animateTo(if (slide.value > 0) size.width.toFloat() else -size.width.toFloat(), spring(stiffness = 900f))
                                    onClear(n)
                                } else {
                                    slide.animateTo(0f, Motion.snap())
                                }
                            }
                        },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { slide.snapTo(slide.value + dx) }
                    }
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(n) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteFace(n, 32.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                IsleText(n.title.ifBlank { n.appName }, 14.5.sp, Isle.on, weight = FontWeight.SemiBold)
                if (n.text.isNotBlank()) IsleText(n.text, 13.sp, if (n.missed) Isle.signal else Isle.soft)
            }
            Spacer(Modifier.width(8.dp))
            IsleNote(ago(n.postedAt, now), Isle.faint)
        }
    }
}

/** What the thread pulled out of the star: your recent notifications, or word that there are none. */
@Composable
internal fun RecentCard(notes: List<NoteInfo>, battery: BatteryInfo?, onDone: () -> Unit) {
    val now = remember(notes) { System.currentTimeMillis() }
    SlipBody {
        SlipHeader("Recent", if (notes.isEmpty()) null else "${notes.size}", mark = Isle.lit)
        Spacer(Modifier.height(4.dp))
        if (notes.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            IsleText("All caught up", 20.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight)
            IsleText(
                battery?.let { "Battery ${it.level}%" + if (it.charging) " · charging" else "" } ?: "No notifications",
                14.sp, Isle.soft,
            )
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                notes.forEachIndexed { i, n ->
                    if (i > 0) SlipRule()
                    key(n.key) {
                        RecentRow(n, now, onOpen = { IslandRepository.openNote(it); onDone() }, onClear = { IslandRepository.dismissNote(it) })
                    }
                }
            }
            if (notes.any { it.clearable }) {
                Spacer(Modifier.height(8.dp))
                SlipActions { SlipButton("Clear all", Modifier.weight(1f), icon = R.drawable.ic_trash) { IslandRepository.clearAll(); onDone() } }
            }
        }
    }
}
