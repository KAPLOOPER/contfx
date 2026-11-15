/*
 * What hangs from the eye: small posters.
 *
 * Each is composed the way the sheets it comes from are. The calm ones are printed on paper like
 * the app's own poster: a star-shaped window bleeding off the sheet with the sky (or the album's
 * art, put through the same halftone) behind it, the number that matters set huge and turned
 * down the page's edge, a mark in kanji, a record sleeve's barcode for anything that has a
 * length, and a camera screen's small bracketed readouts for the rest. The ones that need you
 * now (a call ringing, an alarm) are printed on black instead: a pale figure out of the dark, a
 * title in wide capitals, and the one red star with its long tail coming down onto it.
 */

package dev.continuum.fx.island.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import dev.continuum.fx.island.NoteInfo
import dev.continuum.fx.island.OneTimeCodes
import dev.continuum.fx.island.TimerInfo
import dev.continuum.fx.ui.eidolon.Eidolon
import dev.continuum.fx.ui.eidolon.EidolonColors
import dev.continuum.fx.ui.eidolon.GroteskTight
import dev.continuum.fx.ui.eidolon.GroteskWide
import dev.continuum.fx.ui.eidolon.LocalEidolonColors
import dev.continuum.fx.ui.eidolon.Star
import dev.continuum.fx.ui.eidolon.VoidColors
import dev.continuum.fx.ui.eidolon.tailedStar
import dev.continuum.fx.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** The black sheets' inks: the negative, on pure black. */
internal val BlackSheet: EidolonColors = VoidColors.copy(paper = Color.Black, slip = Color.Black, slipRaised = Color(0xFF0D0D11))

/** A sheet of the print, [height] tall when it's composed to a fixed size; black for what needs you now. */
@Composable
internal fun Poster(height: Dp? = null, black: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val colors = if (black) BlackSheet else Eidolon.colors
    CompositionLocalProvider(LocalEidolonColors provides colors) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (height != null) Modifier.height(height) else Modifier)
                .sheet(colors.slip),
            content = content,
        )
    }
}

/** A sheet set as a column, with the print's margins. */
@Composable
internal fun PosterColumn(black: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Poster(black = black) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), content = content)
    }
}

/** The few lines a poster says small in its corner, one under another. */
@Composable
internal fun Lines(vararg lines: String?, modifier: Modifier = Modifier, soft: Int = 2) {
    val colors = Eidolon.colors
    Column(modifier) {
        lines.filterNotNull().filter { it.isNotBlank() }.forEachIndexed { i, line ->
            IsleText(
                line, 14.sp, if (i >= soft) colors.inkSoft else colors.ink,
                weight = FontWeight.Medium, face = GroteskTight, lineHeight = 15.sp, tracking = (-0.1).sp,
            )
        }
    }
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

/** Draws this only where [inside] (a path in this node's own space) is: a second pass of type, knocked out over a window. */
private fun Modifier.onlyInside(inside: DrawScope.(Path) -> Unit): Modifier = drawWithContent {
    val path = Path()
    inside(path)
    clipPath(path) { this@drawWithContent.drawContent() }
}

/**
 * Type that crosses a window: [content] is set twice over the whole sheet, in ink on the paper
 * and in the window's white where the sky is behind it, as the app's masthead is.
 */
@Composable
private fun BoxScope.Knockout(window: DrawScope.(Path) -> Unit, content: @Composable BoxScope.(Color, Boolean) -> Unit) {
    val colors = Eidolon.colors
    Box(Modifier.matchParentSize()) { content(colors.ink, false) }
    Box(Modifier.matchParentSize().onlyInside(window)) { content(colors.onWindow, true) }
}

/** A picture (or the sky) in a small star: a face for a notification, a caller, what's playing. */
@Composable
internal fun StarWindow(image: Bitmap?, size: Dp, modifier: Modifier = Modifier, turn: Float = 0f, ghost: Boolean = false, pinch: Float = Star.SOFT) {
    PrintWindow(image, modifier.size(size).graphicsLayer { rotationZ = turn }, ghost = ghost, cells = 56, fine = true) { path ->
        Star.path(center, this.size.width / 2f, this.size.height / 2f, pinch, into = path)
    }
}

/** A notification's face: its picture through the plate in a star, or its app's mark over a star of sky. */
@Composable
internal fun NoteFace(n: NoteInfo, size: Dp) {
    val context = LocalContext.current
    val colors = Eidolon.colors
    val small = remember(n.key) {
        if (n.largeIcon != null) null else try {
            n.smallIcon?.loadDrawable(context)?.toBitmap(96, 96)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    Box(contentAlignment = Alignment.Center) {
        StarWindow(n.largeIcon, size)
        if (n.largeIcon == null) {
            if (small != null) {
                Image(small, contentDescription = null, modifier = Modifier.size(size * 0.36f), colorFilter = ColorFilter.tint(colors.onWindow))
            } else {
                Glyph(R.drawable.ic_notification, colors.onWindow, size * 0.36f)
            }
        }
    }
}

/** The colour the battery's sliver is printed in: ink, the lit ultramarine while it fills, the one red when it's low. */
internal fun sliverColor(b: BatteryInfo?, colors: EidolonColors): Color = when {
    b == null -> colors.ink
    b.charging || b.full -> colors.ultramarine
    b.low -> colors.signal
    else -> colors.ink
}

// ---------------------------------------------------------------------------------------------
// Music

/** Where the song has got to, set huge and turned down the page's edge, as the poster sets its hour. */
@Composable
private fun ElapsedDownThePage(m: MediaInfo, modifier: Modifier = Modifier) {
    val position by produceState(m.positionNow(), m) {
        while (true) {
            value = m.positionNow()
            delay(if (m.playing) 250L else 1_000L)
        }
    }
    Numerals(clock(position), 60.sp, Eidolon.colors.ink, modifier.downThePage())
}

/**
 * A record sleeve's barcode for the length of the song: printed in ink as far as it has played,
 * pale beyond, with the red star's tail down through it where it is now. Drag or tap to seek.
 */
@Composable
private fun BarcodeScrubber(m: MediaInfo, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val colors = Eidolon.colors
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
    val needle = remember { Path() }
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
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
            val top = 14.dp.toPx()
            barcode(Rect(0f, top, size.width, size.height), m.title.hashCode() * 31 + m.artist.hashCode(), shown, colors.ink, colors.ink.copy(alpha = 0.2f))
            val x = size.width * shown
            val arm = (6f + 2.5f * grip).dp.toPx()
            drawPath(tailedStar(Offset(x, arm + 1.dp.toPx()), arm, arm, size.height - arm, needle), colors.signal)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Osd("FILE: %04d / %s".format(abs(m.title.hashCode()) % 10_000, clock(duration)), colors.inkSoft, Modifier.weight(1f))
            Osd("−" + clock(duration - (shown * duration).toLong()), colors.inkSoft)
        }
    }
}

@Composable
internal fun MediaCard(m: MediaInfo) {
    val colors = Eidolon.colors
    Poster(height = if (m.durationMs > 0) 286.dp else 228.dp) {
        // The window: a star bleeding off the sheet's top corner, the album's art through the plate.
        PrintWindow(m.art, Modifier.fillMaxSize().clipToBounds()) { path ->
            Star.path(Offset(size.width - 104.dp.toPx(), 84.dp.toPx()), 172.dp.toPx(), 112.dp.toPx(), Star.KEEN, into = path)
        }
        Lines(
            m.title.ifBlank { "untitled" }, m.artist, if (m.playing) "now playing" else "paused",
            modifier = Modifier.padding(start = 14.dp, top = 12.dp).widthIn(max = 150.dp),
        )
        ElapsedDownThePage(m, Modifier.align(Alignment.TopStart).offset(x = 6.dp, y = 76.dp))
        Kanji("音楽", 17.sp, colors.ink, Modifier.align(Alignment.TopStart).offset(x = 78.dp, y = 150.dp), stacked = true)
        // The transport: three tabs of paper pasted over the window's foot.
        Row(
            Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = 146.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            GlyphTab(R.drawable.ic_prev, "Previous", size = 42.dp) { IslandRepository.previous() }
            GlyphTab(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, if (m.playing) "Pause" else "Play", size = 54.dp, lit = true) { IslandRepository.playPause() }
            GlyphTab(R.drawable.ic_next, "Next", size = 42.dp) { IslandRepository.next() }
        }
        if (m.durationMs > 0) {
            BarcodeScrubber(m, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Calls and alarms: the black sheets

/** The red star with its long tail, coming down the sheet from the top: what needs you. */
private fun DrawScope.redStar(at: Offset, arm: Float, tail: Float, color: Color, path: Path) {
    drawPath(tailedStar(at, arm, arm * 1.15f, tail, path), color)
}

/** A title the way the black poster sets it: wide capitals, two lines, the second begun with an ampersand. */
@Composable
private fun BlackTitle(first: String, second: String, modifier: Modifier = Modifier) {
    val colors = Eidolon.colors
    Column(modifier) {
        IsleText(first.uppercase(), 19.sp, colors.ink, weight = FontWeight.Medium, face = GroteskWide, tracking = 0.2.sp, lineHeight = 20.sp)
        IsleText("& " + second.uppercase(), 19.sp, colors.ink, weight = FontWeight.Medium, face = GroteskWide, tracking = 0.2.sp, lineHeight = 20.sp)
    }
}

/** A call ringing: who, out of the dark, under the red star; decline to the left, answer to the right. */
@Composable
internal fun RingTicket(c: CallInfo) {
    Poster(height = 252.dp, black = true) {
        val colors = Eidolon.colors
        val star = remember { Path() }
        // The figure: their photo as a pale ghost, or a star of light where it would be.
        PrintWindow(c.photo, Modifier.fillMaxSize().clipToBounds(), ghost = true) { path ->
            Star.path(Offset(size.width * 0.66f, 100.dp.toPx()), 104.dp.toPx(), 118.dp.toPx(), 0.3f, into = path)
        }
        Canvas(Modifier.fillMaxSize()) {
            redStar(Offset(size.width * 0.66f, 58.dp.toPx()), 17.dp.toPx(), 104.dp.toPx(), colors.signal, star)
        }
        BlackTitle("Incoming", "call", Modifier.padding(start = 14.dp, top = 12.dp))
        Osd("${c.appName.uppercase()} // LINE_01", colors.inkSoft, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 14.dp))
        Column(Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 84.dp).widthIn(max = 190.dp)) {
            IsleText(c.caller, 26.sp, colors.ink, weight = FontWeight.Medium, face = GroteskTight, maxLines = 2, lineHeight = 27.sp)
            Spacer(Modifier.height(6.dp))
            Osd("ringing\n// not\nanswered", colors.inkSoft, maxLines = 3, lineHeight = 12.sp)
        }
        PlateActions(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
            PlateButton("Decline", Modifier.weight(1f), Press.Signal, R.drawable.ic_call_end) { IslandRepository.endCall() }
            PlateButton("Answer", Modifier.weight(1f), Press.Solid, R.drawable.ic_call) { IslandRepository.answer() }
        }
    }
}

/** An alarm, or a finished timer, ringing: its hour huge on black, under the red star, with the clock's own buttons. */
@Composable
internal fun AlarmRingTicket(r: AlarmRing) {
    Poster(height = 236.dp, black = true) {
        val colors = Eidolon.colors
        val star = remember { Path() }
        PrintWindow(null, Modifier.fillMaxSize().clipToBounds(), ghost = true) { path ->
            Star.path(Offset(size.width * 0.72f, 96.dp.toPx()), 96.dp.toPx(), 110.dp.toPx(), 0.3f, into = path)
        }
        Canvas(Modifier.fillMaxSize()) {
            redStar(Offset(size.width * 0.72f, 54.dp.toPx()), 17.dp.toPx(), 100.dp.toPx(), colors.signal, star)
        }
        BlackTitle(r.title.ifBlank { "Alarm" }, "now", Modifier.padding(start = 14.dp, top = 12.dp).widthIn(max = 200.dp))
        Osd("${r.appName.uppercase()} // WAKE", colors.inkSoft, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 14.dp))
        if (r.text.isNotBlank()) {
            Numerals(r.text, 58.sp, colors.ink, Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 84.dp))
        }
        PlateActions(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
            val acts = r.actions.take(3)
            acts.forEachIndexed { i, a ->
                PlateButton(a.title, Modifier.weight(1f), if (i == acts.lastIndex) Press.Solid else Press.Rule) { IslandRepository.alarmAction(r, a) }
            }
            if (acts.isEmpty()) PlateButton("Open", Modifier.weight(1f), Press.Solid) { IslandRepository.send(r.open) }
        }
    }
}

@Composable
internal fun CallCard(c: CallInfo, now: Long, onDone: () -> Unit) {
    val colors = Eidolon.colors
    Poster(height = 212.dp) {
        PrintWindow(c.photo, Modifier.fillMaxSize().clipToBounds()) { path ->
            Star.path(Offset(size.width - 84.dp.toPx(), 78.dp.toPx()), 132.dp.toPx(), 108.dp.toPx(), Star.KEEN, into = path)
        }
        Lines(c.caller, "on a call", c.appName.lowercase(), modifier = Modifier.padding(start = 14.dp, top = 12.dp).widthIn(max = 170.dp), soft = 1)
        Numerals(callText(c, now), 64.sp, colors.ink, Modifier.align(Alignment.TopStart).offset(x = 12.dp, y = 76.dp))
        Kanji("通話", 17.sp, colors.ink, Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp), stacked = true)
        PlateActions(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
            if (c.open != null) PlateButton("Open", Modifier.weight(1f)) { IslandRepository.send(c.open); onDone() }
            PlateButton("End", Modifier.weight(1f), Press.Signal, R.drawable.ic_call_end) { IslandRepository.endCall() }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Timers, the battery, alarms, events, things under way

@Composable
internal fun TimerCard(t: TimerInfo, now: Long, onDone: () -> Unit) {
    val colors = Eidolon.colors
    val over = t.countDown && t.base - now < 0
    val text = timerText(t, now)
    val window: DrawScope.(Path) -> Unit = { path ->
        Star.path(Offset(size.width - 92.dp.toPx(), 70.dp.toPx()), 168.dp.toPx(), 86.dp.toPx(), Star.KEEN, into = path)
    }
    Poster(height = 214.dp) {
        PrintWindow(null, Modifier.fillMaxSize().clipToBounds(), shape = window)
        // The count and the sheet's mark, in ink on the paper and in white where they cross the window.
        Knockout(window) { color, over2 ->
            Lines(if (t.countDown) "timer" else "stopwatch", t.label.takeIf { it.isNotBlank() && it != "Timer" }, t.appName.lowercase(), modifier = Modifier.padding(start = 14.dp, top = 12.dp), soft = if (over2) 9 else 1)
            Numerals(text, 92.sp, if (over && !over2) colors.signal else color, Modifier.padding(start = 10.dp, top = 54.dp), overWindow = over2)
            Kanji("時計", 17.sp, color, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp), stacked = true)
        }
        PlateActions(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
            t.actions.take(3).forEachIndexed { i, a ->
                PlateButton(a.title, Modifier.weight(1f), if (i == 0) Press.Solid else Press.Rule) { IslandRepository.timerAction(t, a) }
            }
            if (t.actions.isEmpty() && t.open != null) PlateButton("Open", Modifier.weight(1f)) { IslandRepository.send(t.open); onDone() }
        }
    }
}

@Composable
internal fun BatteryCard(b: BatteryInfo) {
    val colors = Eidolon.colors
    val level by animateFloatAsState(b.level / 100f, Motion.flow(), label = "cardLevel")
    val spark by loopPhase(b.charging && !b.full, 2_100, "cardSpark")
    val turn by rememberTurn(b.charging && !b.full, 9f)
    val halo = remember { mutableStateOf<Pair<StarHalo, StarHalo>?>(null) }
    val path = remember { Path() }
    val scratch = remember { Path() }
    Poster(height = 204.dp) {
        // The island's own emblem, large: the eye in its star, the charge traced round it.
        Canvas(Modifier.align(Alignment.TopStart).offset(x = 2.dp, y = 22.dp).size(168.dp)) {
            val ry = size.height * 0.3f
            val rx = ry * 1.34f
            val gap = 8.dp.toPx()
            val (line, mount) = halo.value ?: (StarHalo(rx, ry, Star.SOFT, gap) to StarHalo(rx, ry, Star.SOFT, gap * 2f)).also { halo.value = it }
            drawPath(mount.trace(0f, 1f, center.x, center.y, path), colors.slipRaised)
            drawPath(mount.trace(0f, 1f, center.x, center.y, path), colors.ink.copy(alpha = 0.18f), style = Stroke(1f))
            drawPath(line.trace(0f, 1f, center.x, center.y, path), colors.ink.copy(alpha = 0.2f), style = Stroke(1.dp.toPx()))
            drawPath(
                line.trace(0f, level, center.x, center.y, path), sliverColor(b, colors),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            if (b.charging && !b.full && level > 0.02f) {
                val at = line.point(level * spark)
                drawPath(Star.path(Offset(center.x + at.x, center.y + at.y), 6.dp.toPx(), pinch = Star.KEEN, rotation = spark * 180f, into = scratch), colors.gilt)
            }
            starEye(center, rx, ry, ry * 0.24f, colors, EyeLook(turn = turn, gaze = Offset(0.6f, 0.2f)), scratch)
        }
        Kanji("電池", 17.sp, colors.ink, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp), stacked = true)
        Column(Modifier.align(Alignment.TopStart).padding(start = 178.dp, top = 30.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Numerals("${b.level}", 92.sp, colors.ink)
                IsleText("%", 22.sp, colors.ink, Modifier.padding(start = 2.dp, bottom = 14.dp), weight = FontWeight.Medium, face = GroteskTight)
            }
            val detail = when {
                b.full -> "fully charged"
                b.charging && b.fullInMs != null && b.fullInMs > 0 -> "full in ${duration(b.fullInMs)}"
                b.charging -> "filling"
                b.low -> "plug in soon"
                else -> "on battery"
            }
            Lines("battery", batteryWord(b), detail, soft = 1)
        }
    }
}

@Composable
internal fun AlarmCard(a: AlarmInfo, now: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val day = alarmDay(a.at, now)
    val window: DrawScope.(Path) -> Unit = { path ->
        Star.path(Offset(size.width - 92.dp.toPx(), 70.dp.toPx()), 168.dp.toPx(), 86.dp.toPx(), Star.KEEN, into = path)
    }
    val time = alarmTime(context, a.at)
    val button = a.show != null || a.demo
    Poster(height = if (button) 214.dp else 160.dp) {
        PrintWindow(null, Modifier.fillMaxSize().clipToBounds(), shape = window)
        Knockout(window) { color, over ->
            Lines("next alarm", day.lowercase(), "in ${duration((a.at - now).coerceAtLeast(0))}", modifier = Modifier.padding(start = 14.dp, top = 12.dp), soft = if (over) 9 else 1)
            Numerals(time, 72.sp, color, Modifier.padding(start = 10.dp, top = 70.dp), overWindow = over)
            Kanji(if (day == "Tomorrow") "明日" else "起時", 17.sp, color, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp), stacked = true)
        }
        if (button) {
            PlateActions(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
                PlateButton("Open clock", Modifier.weight(1f), icon = R.drawable.ic_alarm) { IslandRepository.openAlarm(); onDone() }
            }
        }
    }
}

@Composable
internal fun EventCard(e: EventInfo, label: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val colors = Eidolon.colors
    val zone = ZoneId.systemDefault()
    val fmt = remember { DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm") }
    val begins = Instant.ofEpochMilli(e.begin).atZone(zone).format(fmt)
    val ends = Instant.ofEpochMilli(e.end).atZone(zone).format(fmt)
    val window: DrawScope.(Path) -> Unit = { path ->
        Star.path(Offset(size.width - 70.dp.toPx(), 56.dp.toPx()), 130.dp.toPx(), 92.dp.toPx(), Star.KEEN, into = path)
    }
    Poster(height = 226.dp) {
        PrintWindow(null, Modifier.fillMaxSize().clipToBounds(), shape = window)
        Knockout(window) { color, over ->
            Lines("up next", label.lowercase(), modifier = Modifier.padding(start = 14.dp, top = 12.dp).widthIn(max = 200.dp), soft = if (over) 9 else 1)
            Kanji("予定", 17.sp, color, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp), stacked = true)
        }
        // The hour it starts, down the page's edge; what it is beside it.
        Numerals(begins, 54.sp, colors.ink, Modifier.align(Alignment.TopStart).offset(x = 6.dp, y = 52.dp).downThePage())
        Column(Modifier.align(Alignment.TopStart).padding(start = 72.dp, top = 76.dp, end = 96.dp)) {
            IsleText(e.title.ifBlank { "Event" }, 22.sp, colors.ink, weight = FontWeight.SemiBold, face = GroteskTight, maxLines = 2, lineHeight = 23.sp)
            Spacer(Modifier.height(4.dp))
            Osd("[ $begins – $ends ]" + (e.location?.takeIf { it.isNotBlank() }?.let { " " + it.lowercase() } ?: ""), colors.inkSoft)
        }
        PlateActions(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 72.dp, end = 14.dp, bottom = 14.dp)) {
            if (e.joinUrl != null) PlateButton("Join", Modifier.weight(1f), Press.Window, R.drawable.ic_video) { IslandRepository.joinEvent(context, e); onDone() }
            PlateButton("Open", Modifier.weight(1f)) { IslandRepository.openEvent(context, e); onDone() }
        }
    }
}

/** How far along something is, as a sleeve's barcode with the red star at its head; the star runs it while the app can't say. */
@Composable
private fun BarcodeProgress(progress: Float, seed: Int) {
    val colors = Eidolon.colors
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.flow(), label = "liveProgress")
    val run by loopPhase(progress < 0f, 1_600, "liveRun")
    val needle = remember { Path() }
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val at = if (progress < 0f) run else shown
        barcode(Rect(0f, 12.dp.toPx(), size.width, size.height), seed, if (progress < 0f) 0f else shown, colors.ink, colors.ink.copy(alpha = 0.2f))
        val arm = 5.5.dp.toPx()
        drawPath(tailedStar(Offset(size.width * at, arm + 1.dp.toPx()), arm, arm, size.height - arm, needle), colors.signal)
    }
}

@Composable
internal fun LiveCard(l: LiveInfo, onDone: () -> Unit) {
    val colors = Eidolon.colors
    PosterColumn {
        Row(verticalAlignment = Alignment.Top) {
            Lines(l.appName.lowercase(), l.title, modifier = Modifier.weight(1f), soft = 9)
            val p = l.progress
            if (p != null && p >= 0f) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Numerals("${(p * 100).toInt()}", 54.sp, colors.ink)
                    IsleText("%", 16.sp, colors.ink, Modifier.padding(bottom = 8.dp), weight = FontWeight.Medium, face = GroteskTight)
                }
            } else {
                liveShort(l)?.let { Numerals(it, 30.sp, colors.ink) }
            }
        }
        if (l.text.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Osd(l.text, colors.inkSoft, maxLines = 2, lineHeight = 14.sp)
        }
        if (l.progress != null) {
            Spacer(Modifier.height(8.dp))
            BarcodeProgress(l.progress, l.key.hashCode())
        }
        if (l.actions.isNotEmpty() || l.open != null) {
            Spacer(Modifier.height(12.dp))
            PlateActions(Modifier.fillMaxWidth()) {
                l.actions.take(2).forEach { a -> PlateButton(a.title, Modifier.weight(1f)) { IslandRepository.liveAction(l, a) } }
                if (l.open != null) PlateButton("Open", Modifier.weight(1f), Press.Window) { IslandRepository.send(l.open); onDone() }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Notifications

/** Copy, then Copied, for a moment. */
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
    PlateButton(
        if (copied) "Copied" else "Copy",
        modifier,
        if (copied) Press.Rule else Press.Window,
        if (copied) R.drawable.ic_check else R.drawable.ic_copy,
    ) {
        if (OneTimeCodes.copy(context, code)) {
            copied = true
            InkFeel.land(view)
        }
    }
}

/** A notification arriving: a ticket. Tap it for its sheet; push it up to tuck it away, or aside to clear it. */
@Composable
internal fun PeekTicket(n: NoteInfo, codes: Boolean) {
    val colors = Eidolon.colors
    val now = remember(n.key, n.postedAt) { System.currentTimeMillis() }
    val code = n.code?.takeIf { codes }
    Poster {
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoteFace(n, 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (n.missed) {
                            PrintedStar(11.dp, colors.signal)
                            Spacer(Modifier.width(5.dp))
                        }
                        IsleText(n.title.ifBlank { n.appName }, 16.sp, colors.ink, weight = FontWeight.SemiBold, face = GroteskTight)
                    }
                    if (code == null && n.text.isNotBlank()) IsleText(n.text, 13.5.sp, if (n.missed) colors.signal else colors.inkSoft)
                    if (code != null) IsleText(n.appName.lowercase(), 13.5.sp, colors.inkSoft)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Kanji("通知", 11.sp, colors.inkSoft)
                    Osd("[${ago(n.postedAt, now).padStart(4)}]", colors.inkFaint, size = 9.sp)
                }
            }
            if (code != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Numerals(spacedCode(code), 44.sp, colors.ink, Modifier.weight(1f))
                    CopyCode(code)
                }
            }
        }
    }
}

/** A notification opened: who, large; what they said, as typed; its code; and its own buttons. */
@Composable
internal fun NoteCard(n: NoteInfo, codes: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val colors = Eidolon.colors
    val now = remember(n.key, n.postedAt) { System.currentTimeMillis() }
    val code = n.code?.takeIf { codes }
    PosterColumn {
        Row(verticalAlignment = Alignment.Top) {
            Lines(n.appName.lowercase().ifBlank { "notification" }, ago(n.postedAt, now).let { if (it == "now") "just now" else "$it ago" }, modifier = Modifier.weight(1f), soft = 1)
            if (n.missed) {
                PrintedStar(14.dp, colors.signal)
                Spacer(Modifier.width(8.dp))
            }
            Kanji("通知", 17.sp, colors.ink, stacked = true)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NoteFace(n, 52.dp)
            Spacer(Modifier.width(10.dp))
            if (n.title.isNotBlank()) IsleText(n.title, 25.sp, colors.ink, weight = FontWeight.SemiBold, face = GroteskTight, maxLines = 2, lineHeight = 26.sp)
        }
        if (n.text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            // What it says, as typed.
            Osd(n.text, if (n.missed) colors.signal else colors.ink, size = 13.sp, maxLines = 8, lineHeight = 18.sp)
        }
        if (code != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Numerals(spacedCode(code), 52.sp, colors.ink, Modifier.weight(1f))
                CopyCode(code)
            }
        }
        val buttons = n.actions.take(3)
        if (buttons.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PlateActions(Modifier.fillMaxWidth()) {
                buttons.forEach { a ->
                    PlateButton(a.title, Modifier.weight(1f), if (a.isReply) Press.Window else Press.Rule, if (a.isReply) R.drawable.ic_reply else null) {
                        IslandRepository.noteAction(context, n, a)
                        onDone()
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PlateActions(Modifier.fillMaxWidth()) {
            PlateButton("Open", Modifier.weight(1f)) { IslandRepository.openNote(n); onDone() }
            if (n.clearable) PlateButton("Clear", Modifier.weight(1f), icon = R.drawable.ic_close) { IslandRepository.dismissNote(n); onDone() }
        }
    }
}

/** One recent notification, a line of the list: tap it to open it; slide it aside to clear it. */
@Composable
private fun RecentRow(index: Int, n: NoteInfo, now: Long, onOpen: (NoteInfo) -> Unit, onClear: (NoteInfo) -> Unit) {
    val view = LocalView.current
    val colors = Eidolon.colors
    val scope = rememberCoroutineScope()
    val slide = remember(n.key) { Animatable(0f) }
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
        Osd("%02d".format(index + 1), colors.inkFaint, Modifier.width(22.dp))
        if (n.missed) {
            PrintedStar(10.dp, colors.signal)
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            IsleText(n.title.ifBlank { n.appName }, 15.5.sp, colors.ink, weight = FontWeight.SemiBold, face = GroteskTight)
            if (n.text.isNotBlank()) IsleText(n.text, 13.sp, if (n.missed) colors.signal else colors.inkSoft)
        }
        Spacer(Modifier.width(8.dp))
        Osd("[${ago(n.postedAt, now).padStart(4)}]", colors.inkFaint, size = 9.sp)
    }
}

/** What came out of the eye on the red star's tail: your recent notifications, set as a sleeve sets its tracks. */
@Composable
internal fun RecentCard(notes: List<NoteInfo>, battery: BatteryInfo?, onDone: () -> Unit) {
    val colors = Eidolon.colors
    val now = remember(notes) { System.currentTimeMillis() }
    PosterColumn {
        Row(verticalAlignment = Alignment.Top) {
            Lines("recent", if (notes.isEmpty()) "all caught up" else "${notes.size} waiting", modifier = Modifier.weight(1f), soft = 1)
            Osd("[%3d/%3d]".format(notes.size, IslandRepository.MAX_RECENT), colors.inkSoft, Modifier.padding(top = 3.dp, end = 10.dp))
            Kanji("履歴", 17.sp, colors.ink, stacked = true)
        }
        if (notes.isEmpty()) {
            Numerals("0", 64.sp, colors.ink)
            Osd(battery?.let { "battery ${it.level}%" + if (it.charging) " // charging" else "" } ?: "nothing waiting", colors.inkSoft)
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                notes.forEachIndexed { i, n ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(colors.ink.copy(alpha = 0.16f)) })
                    key(n.key) {
                        RecentRow(i, n, now, onOpen = { IslandRepository.openNote(it); onDone() }, onClear = { IslandRepository.dismissNote(it) })
                    }
                }
            }
            if (notes.any { it.clearable }) {
                Spacer(Modifier.height(8.dp))
                PlateActions(Modifier.fillMaxWidth()) {
                    PlateButton("Clear all", Modifier.weight(1f), icon = R.drawable.ic_trash) { IslandRepository.clearAll(); onDone() }
                }
            }
        }
    }
}
