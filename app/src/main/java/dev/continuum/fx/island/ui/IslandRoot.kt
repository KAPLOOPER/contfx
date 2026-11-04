package dev.continuum.fx.island.ui

import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.audio.OutputDevice
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.island.AlarmRing
import dev.continuum.fx.island.BatteryInfo
import dev.continuum.fx.island.CallInfo
import dev.continuum.fx.island.CameraSpot
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.IslandSettings
import dev.continuum.fx.island.NoteInfo
import dev.continuum.fx.island.Upcoming
import dev.continuum.fx.ui.components.squash
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Kind { Call, Media, Timer, Battery, Alarm, Event, Live, Sound, Output }

/**
 * What crosses the status bar as one line, the camera its full stop: who or what before it,
 * the rest after. The camera's drop spreads along the top edge to carry it.
 */
private sealed interface Line {
    val key: String

    data class Ring(val call: CallInfo) : Line {
        override val key get() = "ring:" + call.key
    }

    /** An alarm, or a finished timer, ringing now. */
    data class Alarm(val ring: AlarmRing) : Line {
        override val key get() = "alarm:" + ring.key
    }

    data class Peek(val note: NoteInfo) : Line {
        override val key get() = "note:" + note.key + ":" + note.postedAt
    }

    /** A moment's news: a song starting, headphones connecting, the battery, the date. */
    data class Words(override val key: String, val left: String, val right: String, val rightColor: Color, val opens: Kind? = null) : Line
}

/** What falls from the bar and lands below it: only ever what you opened. */
private sealed interface Drip {
    val key: String

    /** A drop you tapped: its card. */
    data class Open(val kind: Kind) : Drip {
        override val key get() = "open:" + kind.name
    }

    /** A notification you tapped in the bar. */
    data class Note(val note: NoteInfo) : Drip {
        override val key get() = "note:" + note.key
    }

    /** A drop pulled out by hand: recent notifications. */
    data object Pulled : Drip {
        override val key get() = "pulled"
    }
}

/** Keeps the last non-null value, so content can fade out after its source has gone. */
private class Last<T>(var value: T? = null)

@Composable
private fun <T : Any> rememberLast(current: T?): T? {
    val holder = remember { Last<T>() }
    if (current != null) holder.value = current
    return holder.value
}

/**
 * The island as you've placed it: moved from the camera, sized to its lens, and everything in it
 * scaled together (by drawing it at a larger or smaller density, so every measure follows).
 */
@Composable
fun IslandSurface(
    spotState: State<CameraSpot?>,
    outsideTaps: IntState,
    onBounds: (android.graphics.Rect?, Boolean) -> Unit,
) {
    val prefs by IslandSettings.state.collectAsState()
    val base = LocalDensity.current
    val raw = spotState.value
    val placed = remember(raw, prefs.offsetX, prefs.offsetY, prefs.cameraSize, base.density) {
        raw?.let { s ->
            val d = base.density
            val lens = (s.radius + prefs.cameraSize * d).coerceIn(4f * d, 30f * d)
            s.copy(
                cx = (s.cx + prefs.offsetX * d).coerceIn(lens, s.screenWidth - lens),
                cy = (s.cy + prefs.offsetY * d).coerceAtLeast(lens * 0.5f),
                radius = lens,
            )
        }
    }
    val placedState = rememberUpdatedState(placed)
    val scaled = remember(base, prefs.scale) { Density(base.density * prefs.scale, base.fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled) {
        IslandRoot(placedState, outsideTaps, onBounds)
    }
}

/**
 * A drop hanging from the top edge of the screen: where it meets the edge the ink spreads into a
 * meniscus, then narrows to a neck, then swells into its [body]. The edge is where the ink comes
 * from, and where it goes back.
 */
private fun DrawScope.hangDrop(body: Rect, x: Float, attach: Float, latch: NeckLatch, edge: Float) {
    if (body.width < 0.5f || body.height < 0.5f) return
    val top = Rect(x - attach * 1.8f, -attach * 4f, x + attach * 1.8f, edge)
    drawRoundRect(InkColors.ink, top.topLeft, top.size, CornerRadius(attach * 0.6f))
    neckDown(top, body, x, latch, reach = attach * 5f, touch = 0f, maxHalf = attach, color = InkColors.ink)
    drawRoundRect(InkColors.ink, body.topLeft, body.size, CornerRadius(min(body.width, body.height) / 2f))
}

/** A body of [w] by [h] centred on ([x], [y]), drawn up into the edge as [p] goes to 0. */
private fun grown(x: Float, y: Float, w: Float, h: Float, p: Float, edge: Float, narrow: Float): Rect {
    val q = p.coerceIn(0f, 1.2f)
    val ww = lerpF(min(w, narrow), w, q.coerceAtMost(1f))
    val hh = h * q.coerceAtMost(1f)
    val cy = lerpF(edge, y, q)
    return Rect(x - ww / 2f, cy - hh / 2f, x + ww / 2f, cy + hh / 2f)
}

/**
 * The Continuum island. It lives in the status bar and carries the time and the battery itself,
 * each a drop of ink hanging from the top edge of the screen, the camera another. Whatever
 * starts (a song, a call, a timer) hangs as its own drop beside the camera. News spreads the
 * camera's drop along the edge into one line. Only what you open falls out of the bar, and
 * lands; when you're done it's drawn back up. Nothing appears or vanishes: ink only leaves the
 * edge and returns to it.
 */
@Composable
fun IslandRoot(
    spotState: State<CameraSpot?>,
    outsideTaps: IntState,
    onBounds: (android.graphics.Rect?, Boolean) -> Unit,
) {
    val spot = spotState.value ?: return
    val density = LocalDensity.current
    val dp = density.density
    fun px(v: Float) = v * dp
    fun toDp(v: Float): Dp = (v / dp).dp

    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs by IslandSettings.state.collectAsState()
    val mediaRaw by IslandRepository.media.collectAsState()
    val callRaw by IslandRepository.call.collectAsState()
    val timerRaw by IslandRepository.timer.collectAsState()
    val noteRaw by IslandRepository.note.collectAsState()
    val recent by IslandRepository.recent.collectAsState()
    val batteryReal by IslandRepository.battery.collectAsState()
    val batteryDemo by IslandRepository.batteryDemo.collectAsState()
    val batteryFlash by IslandRepository.batteryFlash.collectAsState()
    val alarmReal by IslandRepository.alarm.collectAsState()
    val alarmDemo by IslandRepository.alarmDemo.collectAsState()
    val alarmRingRaw by IslandRepository.alarmRing.collectAsState()
    val eventReal by IslandRepository.event.collectAsState()
    val eventDemo by IslandRepository.eventDemo.collectAsState()
    val liveRaw by IslandRepository.live.collectAsState()
    val soundRaw by IslandRepository.sound.collectAsState()
    val listening by IslandRepository.listenerActive.collectAsState()
    val output by OutputWatcher.current.collectAsState()
    val bounds by rememberUpdatedState(onBounds)

    // ---- What to show ----

    val media = mediaRaw?.takeIf { prefs.media }
    val call = callRaw?.takeIf { prefs.calls }
    val timer = timerRaw?.takeIf { prefs.timers }
    val note = noteRaw?.takeIf { prefs.notifications }
    val batteryData = batteryDemo ?: batteryReal
    val battery = batteryData?.takeIf { prefs.battery }
    val nextAlarm = (alarmDemo ?: alarmReal)?.takeIf { prefs.alarms }
    val alarmRinging = alarmRingRaw?.takeIf { prefs.alarms }
    val nextEvent = eventDemo ?: eventReal?.takeIf { prefs.events }
    val live = liveRaw?.takeIf { prefs.live }

    val context = LocalContext.current
    // While you're placing it, the island shows itself with the time and the battery to line up by.
    val placingAt by IslandRepository.placing.collectAsState()
    var placing by remember { mutableStateOf(false) }
    LaunchedEffect(placingAt) {
        if (placingAt > 0 && System.currentTimeMillis() - placingAt < 3_000) {
            placing = true
            delay(3_000)
            placing = false
        }
    }
    val showTime = prefs.alwaysTime || placing
    val showBattery = (prefs.alwaysBattery || placing) && batteryData != null
    // The clock: checked every second, but only changes once a minute, so it is right again the
    // moment the screen wakes.
    val clock by produceState(System.currentTimeMillis(), showTime) {
        value = System.currentTimeMillis()
        while (showTime) {
            delay(1_000)
            val t = System.currentTimeMillis()
            if (t / 60_000 != value / 60_000) value = t
        }
    }
    val timeLabel = clockText(context, clock)

    // A one-second clock, running only while something on the island counts.
    val ticking = timer != null || call?.ringing == false
    val now by produceState(System.currentTimeMillis(), ticking) {
        value = System.currentTimeMillis()
        while (ticking) {
            delay(1000L - System.currentTimeMillis() % 1000L)
            value = System.currentTimeMillis()
        }
    }

    var expanded by remember { mutableStateOf<Kind?>(null) }
    var touchTick by remember { mutableIntStateOf(0) }

    var pausedAt by remember { mutableStateOf<Long?>(null) }
    val mediaPlaying = media?.playing
    LaunchedEffect(mediaPlaying) {
        pausedAt = if (mediaPlaying == false) System.currentTimeMillis() else null
    }
    val mediaShown = media != null &&
        (media.playing || expanded == Kind.Media || (pausedAt?.let { now - it < 30_000 } ?: true))

    // Plugging in (by cable or wireless), running low or reaching full speaks up for a moment.
    var batteryShowing by remember { mutableStateOf(false) }
    LaunchedEffect(batteryFlash) {
        if (batteryFlash > 0 && System.currentTimeMillis() - batteryFlash < 5_000) {
            batteryShowing = true
            delay(3_200)
            batteryShowing = false
        }
    }

    // The next alarm hangs in the bar for the last 12 hours before it (or always, if you asked),
    // checked once a minute so it turns up on time.
    val alarmShown by produceState(false, nextAlarm, prefs.alarmAlways) {
        while (true) {
            val a = nextAlarm
            val left = a?.let { it.at - System.currentTimeMillis() }
            value = left != null && left > 0 && (prefs.alarmAlways || left <= 12 * 3_600_000L)
            if (a == null) break
            delay(60_000)
        }
    }

    // The next event from a quarter of an hour before it, counting down by the minute.
    val eventState by produceState<Pair<Boolean, String>?>(null, nextEvent) {
        while (true) {
            val e = nextEvent
            if (e == null) {
                value = null
                break
            }
            val t = System.currentTimeMillis()
            val shown = Upcoming.shows(e, t)
            val next = shown to Upcoming.label(e, t)
            if (next != value) value = next
            val change = Upcoming.nextChange(e, t)
            val minute = e.begin - ((e.begin - t) / 60_000L) * 60_000L
            val tick = if (minute > t) minute else minute + 60_000L
            val wake = if (shown) minOf(change, tick) else change
            delay((wake - t).coerceIn(250L, 60_000L))
        }
    }
    val eventShown = eventState?.first == true
    val eventLabel = eventState?.second

    // Ring, vibrate or silent speaks up for a moment when you switch.
    var soundShowing by remember { mutableStateOf(false) }
    val soundAt = soundRaw?.at ?: 0L
    LaunchedEffect(soundAt) {
        if (prefs.sound && soundAt > 0 && System.currentTimeMillis() - soundAt < 3_000) {
            soundShowing = true
            delay(2_400)
            soundShowing = false
        }
    }

    // Headphones, a speaker or the car connecting says so for a moment (not at start-up).
    val lastOutputKey = remember { Last<String>() }
    var outputShowing by remember { mutableStateOf<OutputDevice?>(null) }
    LaunchedEffect(output?.key) {
        val o = output
        val before = lastOutputKey.value
        lastOutputKey.value = o?.key ?: ""
        if (prefs.headphones && o != null && before != null && before != o.key && o.kind != OutputDevice.Kind.Speaker) {
            outputShowing = o
            delay(3_000)
            outputShowing = null
        } else if (o?.kind == OutputDevice.Kind.Speaker) {
            outputShowing = null
        }
    }

    // A new song says its name for a moment, then becomes a drop of its art.
    var songShowing by remember { mutableStateOf(false) }
    val songKey = media?.takeIf { it.playing }?.let { it.title + "\u0000" + it.artist }
    LaunchedEffect(songKey) {
        if (songKey != null && songKey.isNotBlank()) {
            songShowing = true
            delay(3_000)
        }
        songShowing = false
    }

    // Tapping the time: the date, for a moment.
    var dateShowing by remember { mutableStateOf(false) }
    LaunchedEffect(dateShowing) {
        if (dateShowing) {
            delay(2_600)
            dateShowing = false
        }
    }

    val ongoing = call?.takeIf { !it.ringing }
    val ringing = call?.takeIf { it.ringing }
    // Beside the camera: what you're listening to or talking on to its left, what's counting or
    // coming up to its right.
    val leftKind: Kind? = when {
        ongoing != null -> Kind.Call
        mediaShown -> Kind.Media
        else -> null
    }
    val rightKind: Kind? = when {
        timer != null -> Kind.Timer
        live != null -> Kind.Live
        nextEvent != null && eventShown -> Kind.Event
        nextAlarm != null && alarmShown -> Kind.Alarm
        else -> null
    }
    val openable = listOfNotNull(leftKind, rightKind, if (batteryData != null) Kind.Battery else null)
    val open = expanded?.takeIf { it in openable }

    val lastMedia = rememberLast(media)
    val lastCall = rememberLast(ongoing)
    val lastTimer = rememberLast(timer)
    val lastBattery = rememberLast(batteryData)
    val lastAlarm = rememberLast(nextAlarm)
    val lastEvent = rememberLast(nextEvent)
    val lastEventLabel = rememberLast(eventLabel).orEmpty()
    val lastLive = rememberLast(live)
    val lastLeft = rememberLast(leftKind)
    val lastRight = rememberLast(rightKind)

    // ---- The pulled drop ----

    var pullPx by remember { mutableFloatStateOf(-1f) }
    var pulledOpen by remember { mutableStateOf(false) }
    var dripFromPull by remember { mutableStateOf(false) }
    val pullSpread = remember { Animatable(0f) }

    // A notification in the bar, and the one you opened from it.
    var peek by remember { mutableStateOf<NoteInfo?>(null) }
    var peekTouch by remember { mutableIntStateOf(0) }
    var noteOpen by remember { mutableStateOf<NoteInfo?>(null) }

    // Outside taps and inactivity fold everything back up.
    val taps = outsideTaps.intValue
    LaunchedEffect(taps) {
        if (taps > 0) {
            expanded = null
            pulledOpen = false
            noteOpen = null
        }
    }
    LaunchedEffect(open, touchTick) {
        if (open != null) {
            delay(if (open == Kind.Media) 12_000 else 8_000)
            expanded = null
        }
    }
    LaunchedEffect(noteOpen?.key, touchTick) {
        if (noteOpen != null) {
            delay(10_000)
            noteOpen = null
        }
    }
    LaunchedEffect(pulledOpen, touchTick) {
        if (pulledOpen) {
            delay(10_000)
            pulledOpen = false
        }
    }
    // Something ringing takes the bar: anything open is drawn back up.
    LaunchedEffect(ringing?.key, alarmRinging?.key) {
        if (ringing != null || alarmRinging != null) {
            pulledOpen = false
            noteOpen = null
            expanded = null
        }
    }

    // New notifications cross the bar for a few seconds; ones with buttons, and codes, for longer.
    LaunchedEffect(note?.key, note?.postedAt, peekTouch) {
        val n = note
        if (n == null) {
            peek = null
            return@LaunchedEffect
        }
        peek = n
        delay(if (n.code != null) 15_000 else if (n.actions.isEmpty()) 5_000 else 8_000)
        peek = null
        delay(700)
        if (IslandRepository.note.value?.key == n.key && noteOpen?.key != n.key) IslandRepository.note.value = null
    }

    // ---- What crosses the bar, and what falls from it ----

    val line: Line? = ringing?.let { Line.Ring(it) }
        ?: alarmRinging?.let { Line.Alarm(it) }
        ?: peek?.takeIf { noteOpen?.key != it.key }?.let { Line.Peek(it) }
        ?: outputShowing?.let { Line.Words("output:" + it.key, outputLabel(it.name), "connected", InkColors.live) }
        ?: soundRaw?.takeIf { soundShowing }?.let { Line.Words("sound:" + it.at, "Sound", soundLabel(it.mode), soundColor(it.mode)) }
        ?: batteryData?.takeIf { battery != null && batteryShowing }?.let { b ->
            Line.Words("battery:$batteryFlash", "${b.level}%", batteryWord(b), batteryColor(b), Kind.Battery)
        }
        ?: (if (dateShowing) Line.Words("date", weekday(clock), dayMonth(clock), InkColors.sub) else null)
        ?: media?.takeIf { songShowing && leftKind == Kind.Media && open != Kind.Media }?.let {
            Line.Words("song:" + it.title, it.title, it.artist, InkColors.sub, Kind.Media)
        }
    val lastLine = rememberLast(line)

    val drip: Drip? = (if (pulledOpen) Drip.Pulled else null)
        ?: noteOpen?.let { Drip.Note(it) }
        ?: open?.let { Drip.Open(it) }
    val lastDrip = rememberLast(drip)
    val dripVisible = drip != null

    // ---- Geometry, in window pixels ----

    val sw = spot.screenWidth.toFloat()
    val cx = spot.cx
    val cy = spot.cy
    val camR = spot.radius + px(1f)
    // The status bar: the ink keeps within it, unless you open something.
    val barH = max(max(spot.bar, cy + camR + px(4f)), px(24f))
    val barBottom = barH - px(1.5f)
    // A slim bar of ink along the top edge: everything hangs from it, so it all sits flush.
    val edge = px(4.5f)
    val margin = px(14f)
    val bodyH = min(px(26f), (barBottom - cy) * 2f - px(2f)).coerceAtLeast(camR * 2f - px(2f))
    val beside = px(9f)
    val cardW = min(sw - px(24f), px(380f))
    val landTop = barH + px(10f)
    val tearGap = px(40f)

    val measurer = rememberTextMeasurer()
    fun textW(text: String, size: Float, bold: Boolean): Float = measurer.measure(
        AnnotatedString(text),
        style = TextStyle(fontFamily = Libron, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    ).size.width.toFloat()

    // How each drop grows down from the edge and is drawn back into it.
    val timeP by animateFloatAsState(if (showTime) 1f else 0f, Motion.flow(), label = "timeP")
    val battP by animateFloatAsState(if (showBattery) 1f else 0f, Motion.flow(), label = "battP")
    val leftP by animateFloatAsState(if (leftKind != null) 1f else 0f, Motion.flow(), label = "leftP")
    val rightP by animateFloatAsState(if (rightKind != null) 1f else 0f, Motion.flow(), label = "rightP")
    val lineP by animateFloatAsState(if (line != null) 1f else 0f, Motion.flow(), label = "lineP")

    // The time, in a drop at the left of the bar (after the camera, if the camera is on the left).
    val timeWTarget = textW(timeLabel, 15f, true) + px(22f)
    val timeW by animateFloatAsState(timeWTarget, Motion.flow(), label = "timeW")
    // The battery, a drop as full as it is.
    val levelNow = (lastBattery?.level ?: 0) / 100f
    val battRTarget = px(9.5f) + px(4f) * levelNow
    val battR by animateFloatAsState(battRTarget, Motion.flow(), label = "battR")
    val battX = sw - margin - px(13.5f)
    val battY = min(cy + battR * 0.12f, barBottom - battR - px(1f))
    val cameraLeft = cx < sw * 0.3f
    val timeX = if (cameraLeft) battX - px(13.5f) - px(12f) - timeW / 2f else margin + timeW / 2f

    // What hangs beside the camera.
    val actR = min(px(12f), barBottom - cy - px(1f))
    val callLabel = lastCall?.let { callText(it, now) } ?: ""
    val rightLabel: String = when (lastRight) {
        Kind.Timer -> lastTimer?.let { timerText(it, now) } ?: ""
        Kind.Live -> lastLive?.let { liveShort(it) ?: it.progress?.takeIf { p -> p >= 0f }?.let { p -> "${(p * 100).roundToInt()}%" } } ?: ""
        Kind.Event -> lastEventLabel
        Kind.Alarm -> lastAlarm?.let { clockText(context, it.at) } ?: ""
        else -> ""
    }
    val glyphW = px(15f)
    val leftWTarget = when (lastLeft) {
        Kind.Media -> actR * 2f
        Kind.Call -> glyphW + px(5f) + textW(callLabel, 13f, true) + px(20f)
        else -> actR * 2f
    }
    val rightWTarget = glyphW + (if (rightLabel.isNotEmpty()) px(5f) + textW(rightLabel, 13f, true) else 0f) + px(20f)
    val leftW by animateFloatAsState(leftWTarget, Motion.flow(), label = "leftW")
    val rightW by animateFloatAsState(rightWTarget, Motion.flow(), label = "rightW")
    val leftX = cx - camR - beside - leftW / 2f
    val rightX = cx + camR + beside + rightW / 2f

    val timeBody = grown(timeX, cy, timeW, bodyH, timeP, edge, px(14f))
    val battBody = grown(battX, battY, battR * 2f, battR * 2f, battP, edge, px(6f))
    val leftBody = grown(leftX, cy, leftW, if (lastLeft == Kind.Media) actR * 2f else bodyH - px(2f), leftP, edge, px(8f))
    val rightBody = grown(rightX, cy, rightW, bodyH - px(2f), rightP, edge, px(8f))
    val camBody = Rect(cx - camR, cy - camR, cx + camR, cy + camR)

    // The line: the camera's drop spread along the edge.
    val lineL = lerpF(cx - camR, margin * 0.5f, lineP)
    val lineR = lerpF(cx + camR, sw - margin * 0.5f, lineP)
    val lineBottom = lerpF(cy + camR, barBottom, lineP.coerceIn(0f, 1f))
    val lineRect = Rect(lineL, -px(24f), lineR, lineBottom)

    // ---- Falling and landing: only what you open ----

    fun sourceOf(d: Drip?): Rect = when (d) {
        is Drip.Open -> when (d.kind) {
            Kind.Battery -> battBody.takeIf { battP > 0.5f } ?: camBody
            lastLeft -> leftBody
            lastRight -> rightBody
            else -> camBody
        }
        else -> camBody
    }
    val dripHTarget = px(
        when (val d = lastDrip) {
            is Drip.Note -> noteDripHeight(d.note)
            Drip.Pulled -> pulledHeight(recent.size)
            is Drip.Open -> 40f + when (d.kind) {
                Kind.Media -> 198f
                Kind.Call -> 138f
                Kind.Timer -> 132f
                Kind.Battery -> 112f
                Kind.Alarm -> 140f
                Kind.Event -> 140f
                Kind.Live -> if (lastLive?.progress != null) 150f else 130f
                Kind.Sound, Kind.Output -> 60f
            }
            null -> 84f
        },
    )
    val dripH by animateFloatAsState(dripHTarget, Motion.flow(), label = "dripH")
    fun centerFor(w: Float): Float {
        val m = px(8f)
        return if (w >= sw - m * 2f) sw / 2f else cx.coerceIn(m + w / 2f, sw - m - w / 2f)
    }
    val dripDx = remember { Animatable(0f) }
    val sideLatch = remember { NeckLatch() }
    val dripP = remember { Animatable(0f) }
    // A drop you open falls as a drop, glints as it lands, then spreads into its card.
    val landSpread = remember { Animatable(0f) }
    val landGlint = remember { Animatable(1f) }
    LaunchedEffect(dripVisible) {
        if (dripVisible) {
            if (!dripFromPull) {
                landSpread.snapTo(0f)
                dripP.animateTo(1.12f, tween(260, easing = LinearOutSlowInEasing))
                launch {
                    landGlint.snapTo(0f)
                    landGlint.animateTo(1f, tween(420, easing = LinearEasing))
                }
                launch { dripP.animateTo(1f, Motion.flow()) }
                delay(150)
                landSpread.animateTo(1f, Motion.flow())
            }
        } else {
            dripFromPull = false
            dripP.animateTo(0f, Motion.flow())
        }
    }
    fun dripSpread(q: Float): Float =
        if (dripFromPull) pullSpread.value.coerceIn(0f, 1f)
        else min(landSpread.value, q.coerceIn(0f, 1f).let { it * it * it }).coerceIn(0f, 1f)
    fun dripRect(src: Rect): Rect {
        val s = min(src.width, src.height).coerceAtLeast(px(12f))
        val pulling = pullPx >= 0f
        val fall = (landTop - (src.bottom - s)).coerceAtLeast(1f)
        val q = if (pulling) pullPx / fall else dripP.value.coerceAtLeast(0f)
        val spread = dripSpread(q)
        val stretch = if (pulling) ((pullPx - s) / tearGap).coerceIn(0f, 1f) else 0f
        val w = lerpF(s, cardW, spread) * (1f - 0.14f * stretch)
        val h = lerpF(s, dripH, spread) * (1f + 0.16f * stretch)
        val t = lerpF(src.bottom - s, landTop, q)
        val x = lerpF(src.center.x, centerFor(cardW), spread) + dripDx.value
        return Rect(x - w / 2f, t, x + w / 2f, t + h)
    }
    fun dripRadius(r: Rect): Float {
        val spread = dripSpread(if (pullPx >= 0f) 0f else dripP.value)
        return lerpF(min(r.width, r.height) / 2f, px(28f), spread).coerceAtMost(min(r.width, r.height) / 2f)
    }
    val dripTarget = Rect(centerFor(cardW) - cardW / 2f, landTop, centerFor(cardW) + cardW / 2f, landTop + max(dripH, dripHTarget))

    // ---- Pulling a drop out by hand ----

    val dripLatch = remember { NeckLatch() }
    val latches = remember { List(6) { NeckLatch() } }
    val canPull = prefs.pull && drip == null && line == null
    val canPullNow by rememberUpdatedState(canPull)

    fun haptic(strong: Boolean) = if (strong) InkFeel.land(view) else InkFeel.detent(view)

    fun startPull() {
        dripFromPull = true
        pullPx = 0f
        scope.launch {
            pullSpread.snapTo(0f)
            dripP.snapTo(0f)
        }
    }

    // Tear when the neck has thinned to a thread, which is when the goo lets go of it too.
    fun tearAt(s: Float): Float {
        val half = min(s / 2f * 0.72f, px(26f)) * 0.8f
        val k = (px(2f) / half).coerceIn(0f, 1f)
        return tearGap * (1f - k.pow(1f / 1.5f))
    }

    fun tear() {
        val s = min(camBody.width, camBody.height)
        val q = pullPx / (landTop - (camBody.bottom - s)).coerceAtLeast(1f)
        dripLatch.joined = false
        InkFeel.split(view)
        pulledOpen = true
        scope.launch {
            dripP.snapTo(q)
            pullPx = -1f
            launch { pullSpread.animateTo(1f, Motion.flow()) }
            dripP.animateTo(1f, Motion.flow())
        }
    }

    fun movePull(fingerDy: Float) {
        if (pullPx < 0f || pulledOpen) return
        pullPx = (fingerDy * 0.8f).coerceAtLeast(0f)
        val s = min(camBody.width, camBody.height)
        if (pullPx - s >= tearAt(s)) tear()
    }

    fun releasePull() {
        if (pullPx < 0f || pulledOpen) return
        // Let go before it tore: it springs back into the bar, and that's felt.
        if (pullPx > px(6f)) InkFeel.merge(view)
        val s = min(camBody.width, camBody.height)
        val q = pullPx / (landTop - (camBody.bottom - s)).coerceAtLeast(1f)
        scope.launch {
            dripP.snapTo(q)
            pullPx = -1f
            dripP.animateTo(0f, Motion.flow())
            if (!pulledOpen) dripFromPull = false
        }
    }

    // ---- Touch: only what you can tap, so the rest of the bar still pulls down Android's shade ----

    fun inflate(r: Rect, by: Float) = Rect(r.left - by, 0f, r.right + by, max(r.bottom + by, barH))
    val grabRect: Rect? = if (line != null) null else run {
        var u: Rect = inflate(camBody, px(14f))
        if (leftKind != null) u = unionOf(u, inflate(leftBody, px(6f)))
        if (rightKind != null) u = unionOf(u, inflate(rightBody, px(6f)))
        // The time and the battery are left to Android, so the bar either side still pulls the shade.
        u
    }
    val lineTouch = if (line != null) Rect(margin * 0.5f, 0f, sw - margin * 0.5f, barH) else null
    val touchRect: android.graphics.Rect? = run {
        var u: Rect? = grabRect ?: lineTouch
        if (dripVisible) u = u?.let { unionOf(it, dripTarget) } ?: dripTarget
        u?.let { android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.roundToInt(), it.bottom.roundToInt()) }
    }
    val wantsOutside = dripVisible
    LaunchedEffect(touchRect, wantsOutside) { bounds(touchRect, wantsOutside) }

    // Tapping in the bar: whichever drop is under the finger.
    val tapAt by rememberUpdatedState<(Offset) -> Unit>({ p ->
        fun hits(r: Rect, slop: Float) = p.x >= r.left - slop && p.x <= r.right + slop && p.y <= max(r.bottom, barH) + slop
        when {
            leftKind != null && hits(leftBody, px(6f)) -> expanded = leftKind
            rightKind != null && hits(rightBody, px(6f)) -> expanded = rightKind
            showBattery && hits(battBody, px(6f)) -> expanded = Kind.Battery
            showTime && hits(timeBody, px(4f)) -> dateShowing = true
            hits(camBody, px(14f)) -> (leftKind ?: rightKind)?.let { expanded = it }
        }
        Unit
    })

    // ---- The charging drip: ink running down the battery's neck into it ----

    val charging = showBattery && lastBattery?.let { it.charging && !it.full } == true
    val chargeDrip = remember { Animatable(0f) }
    LaunchedEffect(charging) {
        while (charging) {
            chargeDrip.snapTo(0f)
            chargeDrip.animateTo(1f, tween(640, easing = LinearEasing))
            chargeDrip.snapTo(0f)
            delay(1_100)
        }
        chargeDrip.snapTo(0f)
    }

    // ---- The ink ----

    val goo = remember(dp) { if (Build.VERSION.SDK_INT >= 31) Goo.effect(px(3.6f), Shader.TileMode.CLAMP) else null }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { renderEffect = goo }) {
            // The bar along the edge, then the camera and everything else hanging from it.
            drawRect(InkColors.ink, Offset(-px(10f), -px(24f)), Size(sw + px(20f), px(24f) + edge))
            hangDrop(camBody, cx, px(8f), latches[0], edge)
            if (timeP > 0.01f) hangDrop(timeBody, timeX, px(8.5f) * timeP.coerceAtMost(1f), latches[1], edge)
            if (battP > 0.01f) {
                hangDrop(battBody, battX, px(6.5f) * battP.coerceAtMost(1f), latches[2], edge)
                val u = chargeDrip.value
                if (u > 0f) {
                    // A bead of ink running down the neck into the drop.
                    val y = lerpF(-px(4f), battBody.center.y - battBody.height * 0.2f, u)
                    drawCircle(InkColors.ink, px(3.4f) * (1f - 0.3f * u), Offset(battX, y))
                }
            }
            if (leftP > 0.01f) hangDrop(leftBody, leftX, px(6f) * leftP.coerceAtMost(1f), latches[3], edge)
            if (rightP > 0.01f) hangDrop(rightBody, rightX, px(6f) * rightP.coerceAtMost(1f), latches[4], edge)
            // News: the camera's drop spread along the edge.
            if (lineP > 0.01f) {
                drawRoundRect(InkColors.ink, lineRect.topLeft, lineRect.size, CornerRadius(min(px(16f), lineRect.height / 2f)))
            }
            // What you opened: falling from its drop, and landing.
            if (dripP.value > 0.002f || pullPx > 0f) {
                val src = sourceOf(lastDrip)
                val d = dripRect(src)
                drawRoundRect(InkColors.ink, d.topLeft, d.size, CornerRadius(dripRadius(d)))
                val reach = if (dripFromPull && !pulledOpen) tearGap else px(18f)
                val lean = dripDx.value
                if (abs(lean) < 0.5f) {
                    sideLatch.joined = true
                    neckDown(src, d, src.center.x, dripLatch, reach, px(2f), px(20f), InkColors.ink)
                } else {
                    // Leaning away: the neck thins as the drop goes, and once it snaps it stays apart.
                    val side = (1f - abs(lean) / px(90f)).coerceIn(0f, 1f)
                    if (side <= 0f) {
                        if (sideLatch.joined) InkFeel.split(view)
                        sideLatch.joined = false
                    }
                    if (sideLatch.joined) {
                        val gapNow = (d.top - src.bottom).coerceAtLeast(0f)
                        val rest = (1f - gapNow / reach).coerceIn(0f, 1f).pow(1.5f)
                        neckSlant(src, d, src.center.x, src.center.x + lean, rest * side.pow(1.5f), px(20f), InkColors.ink)
                    }
                }
            }
        }

        // The glint as a drop lands: pulled four ways for a moment, outside the goo so its
        // points stay sharp. Read only while drawing, so it redraws this layer alone.
        Canvas(Modifier.fillMaxSize()) {
            val u = landGlint.value
            if (u < 1f && !dripFromPull && dripP.value > 0.3f) {
                val src = sourceOf(lastDrip)
                val d = dripRect(src)
                val s = min(src.width, src.height).coerceAtLeast(px(12f))
                val e = sin(PI.toFloat() * u)
                drawPath(inkStar(d.center.x, d.top + s / 2f, s / 2f * (1f + 1.1f * e), 2f - 1.58f * e, u * 0.8f), InkColors.ink)
            }
        }

        // Waiting for notification access: a halo of ink floats over the camera.
        val waitingHalo = animateFloatAsState(if (listening) 0f else 1f, Motion.flow(), label = "halo")
        Canvas(Modifier.fillMaxSize()) {
            halo(Offset(spot.cx, spot.cy - spot.radius * 1.05f), spot.radius * 1.15f, spot.radius * 0.36f, waitingHalo.value, InkColors.on, px(1.4f))
        }

        // ---- What's on the drops ----

        val calm = (1f - lineP * 1.8f).coerceIn(0f, 1f)
        fun Modifier.at(r: Rect) = this
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .size(toDp(r.width), toDp(r.height))
        if (timeP > 0.6f) {
            Box(
                Modifier.at(timeBody).graphicsLayer { alpha = ((timeP - 0.6f) / 0.4f).coerceIn(0f, 1f) * calm },
                contentAlignment = Alignment.Center,
            ) { Txt(timeLabel, 15.sp, InkColors.on, bold = true, maxLines = 1) }
        }
        if (battP > 0.6f) {
            val b = lastBattery
            if (b != null) {
                Box(
                    Modifier.at(battBody).graphicsLayer { alpha = ((battP - 0.6f) / 0.4f).coerceIn(0f, 1f) * calm },
                    contentAlignment = Alignment.Center,
                ) { Note("${b.level}", if (b.charging || b.full) InkColors.live else if (b.low) InkColors.end else InkColors.on) }
            }
        }
        if (leftP > 0.6f) {
            Box(
                Modifier.at(leftBody).graphicsLayer { alpha = ((leftP - 0.6f) / 0.4f).coerceIn(0f, 1f) * calm },
                contentAlignment = Alignment.Center,
            ) {
                when (lastLeft) {
                    Kind.Media -> lastMedia?.let { m ->
                        // A drop of the song's art; paused, it dims.
                        Art(
                            m.art, Color(m.accent), toDp(leftBody.height * 0.82f), CircleShape,
                            Modifier.graphicsLayer { alpha = if (m.playing) 1f else 0.5f },
                        )
                    }
                    Kind.Call -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(R.drawable.ic_call, InkColors.live, 14.dp)
                        Spacer(Modifier.width(5.dp))
                        Txt(callLabel, 13.sp, InkColors.live, bold = true, maxLines = 1)
                    }
                    else -> Unit
                }
            }
        }
        if (rightP > 0.6f) {
            Box(
                Modifier.at(rightBody).graphicsLayer { alpha = ((rightP - 0.6f) / 0.4f).coerceIn(0f, 1f) * calm },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (lastRight) {
                        Kind.Timer -> Glyph(R.drawable.ic_timer, InkColors.on, 14.dp)
                        Kind.Live -> lastLive?.let { l ->
                            if (l.progress != null && liveShort(l) == null) InkRing(l.progress, liveColor(l), 14.dp, 2.dp)
                            else AppGlyph(l.smallIcon, InkColors.on, 14.dp)
                        }
                        Kind.Event -> Glyph(R.drawable.ic_event, InkColors.on, 14.dp)
                        Kind.Alarm -> Glyph(R.drawable.ic_alarm, InkColors.on, 14.dp)
                        else -> Unit
                    }
                    if (rightLabel.isNotEmpty()) {
                        Spacer(Modifier.width(5.dp))
                        Txt(
                            rightLabel, 13.sp,
                            if (lastRight == Kind.Event) eventColor(rightLabel) else InkColors.on,
                            bold = true, maxLines = 1,
                        )
                    }
                }
            }
        }

        // Tapping a drop opens it (it falls and lands); dragging down from the camera pulls out
        // your recent notifications.
        if (grabRect != null) {
            Box(
                Modifier
                    .offset { IntOffset(grabRect.left.roundToInt(), grabRect.top.roundToInt()) }
                    .size(toDp(grabRect.width), toDp(grabRect.height))
                    .pointerInput(grabRect.left, grabRect.top) {
                        val origin = grabRect.topLeft
                        detectTapGestures(
                            onTap = { o -> tapAt(o + origin) },
                            onLongPress = { if (canPullNow) haptic(strong = false) },
                        )
                    }
                    .pointerInput(Unit) {
                        var dy = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                dy = 0f
                                if (canPullNow) startPull()
                            },
                            onDragEnd = { releasePull() },
                            onDragCancel = { releasePull() },
                        ) { change, amount ->
                            if (pullPx >= 0f) {
                                change.consume()
                                dy += amount
                                movePull(dy)
                            }
                        }
                    },
            )
        }

        // ---- The line's words, either side of the camera ----

        val shownLine = lastLine
        if (shownLine != null && lineP > 0.02f) {
            val words = ((lineP - 0.75f) / 0.25f).coerceIn(0f, 1f)
            LineContent(
                line = shownLine,
                left = lineRect.left,
                right = lineRect.right,
                cx = cx,
                camR = camR,
                height = lineBottom,
                alpha = words,
                toDp = ::toDp,
                onTap = {
                    when (shownLine) {
                        is Line.Ring -> IslandRepository.send(shownLine.call.open)
                        is Line.Alarm -> {
                            if (shownLine.ring.actions.isEmpty()) IslandRepository.send(shownLine.ring.open)
                        }
                        is Line.Peek -> {
                            peekTouch++
                            noteOpen = shownLine.note
                        }
                        is Line.Words -> {
                            if (shownLine.key == "date") dateShowing = false
                            shownLine.opens?.let { expanded = it }
                        }
                    }
                    Unit
                },
                onUp = {
                    when (shownLine) {
                        is Line.Peek -> {
                            peek = null
                            IslandRepository.tuckNote(shownLine.note)
                        }
                        is Line.Words -> {
                            if (shownLine.key == "date") dateShowing = false
                            if (shownLine.key.startsWith("song:")) songShowing = false
                        }
                        else -> Unit
                    }
                    Unit
                },
            )
        }

        // ---- What you opened, landed below the bar ----

        val dripAlpha by animateFloatAsState(
            if (dripVisible) 1f else 0f,
            if (dripVisible) tween(240, delayMillis = 200) else tween(120),
            label = "dripAlpha",
        )
        val shownDrip = lastDrip
        if (shownDrip != null && dripAlpha > 0.01f) {
            val cardWidth = dripTarget.width
            // Sideways: the notification follows your finger; past a third of the way it flies
            // off and is cleared, otherwise it springs back and the neck joins up again.
            fun swipeEnd(n: NoteInfo) {
                val past = abs(dripDx.value) > cardWidth * 0.33f && n.clearable
                scope.launch {
                    if (past) {
                        haptic(strong = true)
                        dripDx.animateTo(if (dripDx.value > 0f) sw else -sw, tween(200))
                        dripP.snapTo(0f)
                        dripDx.snapTo(0f)
                        noteOpen = null
                        IslandRepository.dismissNote(n)
                    } else {
                        dripDx.animateTo(0f, Motion.flow())
                    }
                }
            }
            fun close() {
                when (shownDrip) {
                    is Drip.Open -> expanded = null
                    is Drip.Note -> noteOpen = null
                    Drip.Pulled -> pulledOpen = false
                }
            }
            Box(
                Modifier
                    .offset { IntOffset(dripTarget.left.roundToInt(), dripTarget.top.roundToInt()) }
                    .size(toDp(dripTarget.width), toDp(dripTarget.height))
                    .graphicsLayer { alpha = dripAlpha }
                    .drawWithContent {
                        val d = dripRect(sourceOf(lastDrip))
                        val local = d.translate(-dripTarget.left, -dripTarget.top)
                        val path = Path().apply { addRoundRect(RoundRect(local, CornerRadius(dripRadius(d)))) }
                        clipPath(path) { this@drawWithContent.drawContent() }
                    }
                    .pointerInput(shownDrip.key) {
                        // Touching what you opened keeps it out for longer.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            touchTick++
                        }
                    }
                    .pointerInput(shownDrip.key) {
                        if (shownDrip == Drip.Pulled) return@pointerInput
                        detectTapGestures {
                            when (shownDrip) {
                                is Drip.Note -> {
                                    noteOpen = null
                                    IslandRepository.openNote(shownDrip.note)
                                }
                                else -> close()
                            }
                        }
                    }
                    .pointerInput(shownDrip.key) {
                        // Up draws it back into the bar (a notification stays in the shade).
                        var dy = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dy = 0f },
                            onDragEnd = {
                                if (dy < -px(20f)) {
                                    if (shownDrip is Drip.Note) IslandRepository.tuckNote(shownDrip.note)
                                    close()
                                }
                            },
                        ) { change, amount ->
                            change.consume()
                            dy += amount
                        }
                    }
                    .pointerInput(shownDrip.key) {
                        if (shownDrip !is Drip.Note) return@pointerInput
                        val n = shownDrip.note
                        detectHorizontalDragGestures(
                            onDragEnd = { swipeEnd(n) },
                            onDragCancel = { scope.launch { dripDx.animateTo(0f, Motion.flow()) } },
                        ) { change, amount ->
                            change.consume()
                            val give = if (n.clearable) 1f else 0.25f
                            scope.launch { dripDx.snapTo(dripDx.value + amount * give) }
                        }
                    },
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = dripDx.value }) {
                    val band = 40.dp
                    when (shownDrip) {
                        is Drip.Note -> NoteDrip(shownDrip.note)
                        is Drip.Open -> when (shownDrip.kind) {
                            Kind.Media -> lastMedia?.let { ExpandedMedia(it, band) }
                            Kind.Call -> lastCall?.let { ExpandedCall(it, now, band) }
                            Kind.Timer -> lastTimer?.let { ExpandedTimer(it, now, band) }
                            Kind.Battery -> lastBattery?.let { ExpandedBattery(it, band) }
                            Kind.Alarm -> lastAlarm?.let { ExpandedAlarm(it, System.currentTimeMillis(), band) }
                            Kind.Event -> lastEvent?.let { ExpandedEvent(it, lastEventLabel, band) }
                            Kind.Live -> lastLive?.let { ExpandedLive(it, band) }
                            Kind.Sound, Kind.Output -> Unit
                        }
                        Drip.Pulled -> PulledList(
                            notes = recent,
                            battery = lastBattery,
                            alarm = nextAlarm,
                            onOpen = { n ->
                                pulledOpen = false
                                IslandRepository.openNote(n)
                            },
                            onClear = { n ->
                                haptic(strong = false)
                                IslandRepository.dismissNote(n)
                            },
                            onClearAll = {
                                haptic(strong = true)
                                IslandRepository.clearAll()
                                pulledOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One line across the bar, the camera its full stop: who or what to its left, the rest to its
 * right. A call has Decline and Answer at its ends; an alarm, its own buttons.
 */
@Composable
private fun LineContent(
    line: Line,
    left: Float,
    right: Float,
    cx: Float,
    camR: Float,
    height: Float,
    alpha: Float,
    toDp: (Float) -> Dp,
    onTap: () -> Unit,
    onUp: () -> Unit,
) {
    val density = LocalDensity.current
    fun px(v: Dp) = with(density) { v.toPx() }
    val button = toDp(min(height - px(8.dp), px(32.dp)))
    val buttonPx = px(button)
    val pad = px(6.dp)
    val gapX = camR + px(10.dp)
    // Ends taken by buttons, so the words keep clear of them.
    val (startUsed, endUsed) = when (line) {
        is Line.Ring -> (buttonPx + pad * 2f) to (buttonPx + pad * 2f)
        is Line.Alarm -> when {
            line.ring.actions.size >= 2 -> px(86.dp) to px(86.dp)
            line.ring.actions.size == 1 -> px(10.dp) to px(86.dp)
            else -> px(10.dp) to px(10.dp)
        }
        else -> px(12.dp) to px(12.dp)
    }
    val leftStart = left + startUsed
    val leftW = (cx - gapX - leftStart).coerceAtLeast(0f)
    val rightStart = cx + gapX
    val rightW = (right - endUsed - rightStart).coerceAtLeast(0f)

    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        // The line itself: tap to open what it's about, push up to send it away.
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), 0) }
                .size(toDp(right - left), toDp(height))
                .pointerInput(line.key) { detectTapGestures { onTap() } }
                .pointerInput(line.key) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onDragEnd = { if (dy < -px(12.dp)) onUp() },
                    ) { change, amount ->
                        change.consume()
                        dy += amount
                    }
                },
        )
        val leftText: String
        val rightText: String
        val rightColor: Color
        var rightIsWords = false
        when (line) {
            is Line.Ring -> {
                leftText = line.call.caller
                rightText = "calling"
                rightColor = InkColors.sub
            }
            is Line.Alarm -> {
                leftText = line.ring.title.ifBlank { line.ring.appName }
                rightText = line.ring.text.ifBlank { line.ring.appName }
                rightColor = InkColors.sub
            }
            is Line.Peek -> {
                val n = line.note
                leftText = n.title.ifBlank { n.appName }
                when {
                    n.code != null -> {
                        rightText = spacedCode(n.code)
                        rightColor = InkColors.on
                        rightIsWords = true
                    }
                    n.missed -> {
                        rightText = "missed call"
                        rightColor = InkColors.end
                    }
                    else -> {
                        rightText = n.text.ifBlank { n.appName }
                        rightColor = InkColors.sub
                        rightIsWords = true
                    }
                }
            }
            is Line.Words -> {
                leftText = line.left
                rightText = line.right
                rightColor = line.rightColor
            }
        }
        Box(
            Modifier
                .offset { IntOffset(leftStart.roundToInt(), 0) }
                .size(toDp(leftW), toDp(height)),
            contentAlignment = Alignment.CenterEnd,
        ) { Txt(leftText, 15.sp, InkColors.on, bold = true, maxLines = 1) }
        Box(
            Modifier
                .offset { IntOffset(rightStart.roundToInt(), 0) }
                .size(toDp(rightW), toDp(height)),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (rightIsWords) Txt(rightText, 14.sp, rightColor, maxLines = 1) else Note(rightText, rightColor)
        }
        // The ends.
        val top = ((height - buttonPx) / 2f).roundToInt()
        when (line) {
            is Line.Ring -> {
                Box(Modifier.offset { IntOffset((left + pad).roundToInt(), top) }) {
                    RoundButton(R.drawable.ic_call_end, "Decline", InkColors.end, InkColors.onEnd, button) { IslandRepository.endCall() }
                }
                Box(Modifier.offset { IntOffset((right - pad - buttonPx).roundToInt(), top) }) {
                    RoundButton(R.drawable.ic_call, "Answer", InkColors.live, InkColors.onLive, button) { IslandRepository.answer() }
                }
            }
            is Line.Alarm -> {
                val acts = line.ring.actions
                val first = acts.firstOrNull()?.takeIf { acts.size >= 2 }
                val last = acts.lastOrNull()
                if (first != null) {
                    Box(Modifier.offset { IntOffset((left + pad).roundToInt(), top) }) {
                        EndPill(first.title, InkColors.tonal, InkColors.on, px(74.dp), button, toDp) { IslandRepository.alarmAction(line.ring, first) }
                    }
                }
                if (last != null) {
                    Box(Modifier.offset { IntOffset((right - pad - px(74.dp)).roundToInt(), top) }) {
                        EndPill(last.title, InkColors.on, InkColors.ink, px(74.dp), button, toDp) { IslandRepository.alarmAction(line.ring, last) }
                    }
                }
            }
            else -> Unit
        }
    }
}

/** A small button at the end of the line. */
@Composable
private fun EndPill(text: String, bg: Color, fg: Color, w: Float, h: Dp, toDp: (Float) -> Dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .squash(source, pill = true)
            .width(toDp(w))
            .height(h)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, 13.sp, fg, bold = true, maxLines = 1) }
}

/** What the battery is doing, in a word. */
private fun batteryWord(b: BatteryInfo): String = when {
    b.full -> "charged"
    b.charging && b.wireless -> "charging wirelessly"
    b.charging -> "charging"
    b.low -> "low"
    else -> "${b.level}% left"
}

private fun weekday(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))

private fun dayMonth(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))

private fun unionOf(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
