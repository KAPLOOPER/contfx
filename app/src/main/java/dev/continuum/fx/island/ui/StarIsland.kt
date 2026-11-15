/*
 * The Continuum island, printed in Eidolon.
 *
 * Its base is a black four-point star set on the camera, and a sliver traced round the star is the
 * battery: clockwise from the top tip as far as it is charged, green while it fills (a spark runs
 * along it), red when it runs low. The star is never still for no reason and never moves without
 * one: it grows out of the lens when the island starts, breathes while music plays, pulses while a
 * call rings, and turns a quarter (closing like a shutter as it goes, so its tips clear the sliver)
 * whenever there's news.
 *
 * Whatever is live rides out from the star along a slim square-cut band, its wings: what you're
 * listening to or talking on to one side, what's counting down or coming up to the other, and the
 * time wherever you keep it (at the far end of the bar, on the band, or split either side of the
 * star with the star as its colon). A moment's news (a song starting, the sound mode, headphones,
 * the battery, the date) runs out along the same band, the star between its two halves like a
 * full stop.
 *
 * Anything with more to say hangs from the star on two threads as a black slip, and swings as it
 * lands: a notification as a short ticket, a ringing call with its two answers, and whatever you
 * tap as its full card. Pull the star down and a thread runs out with it; let go past the mark and
 * your recent notifications hang there. Push a slip up to put it away, or slide it aside to clear
 * it.
 */

package dev.continuum.fx.island.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.audio.OutputDevice
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.island.AlarmRing
import dev.continuum.fx.island.CallInfo
import dev.continuum.fx.island.CameraSpot
import dev.continuum.fx.island.IslandPrefs
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.IslandSettings
import dev.continuum.fx.island.LiveInfo
import dev.continuum.fx.island.MediaInfo
import dev.continuum.fx.island.NoteInfo
import dev.continuum.fx.island.TimerInfo
import dev.continuum.fx.island.Upcoming
import dev.continuum.fx.ui.eidolon.GroteskTight
import dev.continuum.fx.ui.eidolon.LocalEidolonColors
import dev.continuum.fx.ui.eidolon.Star
import dev.continuum.fx.ui.eidolon.VoidColors
import dev.continuum.fx.ui.theme.EidolonFaces
import dev.continuum.fx.ui.theme.LocalFaces
import dev.continuum.fx.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What can be opened as a card. */
enum class Kind { Call, Media, Timer, Battery, Alarm, Event, Live }

/** What hangs from the star. */
private sealed interface Slip {
    val key: String

    /** Something you opened: a tap anywhere else folds it away. */
    val opened: Boolean get() = false

    /** Whether sliding it aside clears it, and pushing it up puts it away. */
    val dismissable: Boolean get() = true

    data class Ring(val call: CallInfo) : Slip {
        override val key get() = "ring:" + call.key
        override val dismissable get() = false
    }

    data class Alarm(val ring: AlarmRing) : Slip {
        override val key get() = "alarm:" + ring.key
        override val dismissable get() = false
    }

    data class Peek(val note: NoteInfo) : Slip {
        override val key get() = "peek:" + note.key + ":" + note.postedAt
    }

    data class Note(val note: NoteInfo) : Slip {
        override val key get() = "note:" + note.key
        override val opened get() = true
    }

    data class Card(val kind: Kind) : Slip {
        override val key get() = "card:" + kind.name
        override val opened get() = true
    }

    data object Recent : Slip {
        override val key get() = "recent"
        override val opened get() = true
    }
}

/** A moment's news, run out along the band: [left] before the star, [right] after it. */
private data class Words(
    val key: String,
    val left: String,
    val right: String,
    val rightColor: Color,
    @DrawableRes val icon: Int? = null,
    val opens: Kind? = null,
)

/**
 * The island held still for a drawing of it (the specimens in the app's previews): the star already
 * grown, whatever hangs already landed, and [open], [pulled], [noteOpen] or [date] showing as if
 * just tapped. Never set on the phone.
 */
internal data class IslandStill(
    val open: Kind? = null,
    val pulled: Boolean = false,
    val noteOpen: Boolean = false,
    val date: Boolean = false,
)

internal val LocalIslandStill = staticCompositionLocalOf<IslandStill?> { null }

/** Keeps the last non-null value, so what's leaving can still be drawn as it goes. */
private class Last<T>(var value: T? = null)

@Composable
private fun <T : Any> rememberLast(current: T?): T? {
    val holder = remember { Last<T>() }
    if (current != null) holder.value = current
    return holder.value
}

/**
 * The island as you've placed it: moved from the camera, fitted to its lens, and everything in it
 * scaled together (drawn at a larger or smaller density, so every measure follows).
 */
@Composable
fun IslandSurface(
    spotState: State<CameraSpot?>,
    outsideTaps: IntState,
    onBounds: (List<android.graphics.Rect>, Boolean) -> Unit,
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
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalFaces provides EidolonFaces,
        LocalEidolonColors provides VoidColors,
    ) {
        StarIsland(placedState, outsideTaps, onBounds)
    }
}

@Composable
private fun StarIsland(
    spotState: State<CameraSpot?>,
    outsideTaps: IntState,
    onBounds: (List<android.graphics.Rect>, Boolean) -> Unit,
) {
    val spot = spotState.value ?: return
    val still = LocalIslandStill.current
    val density = LocalDensity.current
    val dp = density.density
    fun px(v: Float) = v * dp
    fun Float.toDp() = (this / dp).dp

    val view = LocalView.current
    val context = LocalContext.current
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
    val placingAt by IslandRepository.placing.collectAsState()

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

    // While you're placing it, the island shows the time and the battery to line them up by.
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

    // The clock: checked every second but changed once a minute, so it is right the moment the
    // screen wakes.
    val clockAt by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            val t = System.currentTimeMillis()
            if (t / 60_000 != value / 60_000) value = t
        }
    }

    // A one-second clock, running only while something on the island counts.
    val ticking = timer != null || call?.ringing == false || nextAlarm != null
    val now by produceState(System.currentTimeMillis(), ticking) {
        value = System.currentTimeMillis()
        while (ticking) {
            delay(1000L - System.currentTimeMillis() % 1000L)
            value = System.currentTimeMillis()
        }
    }

    var expanded by remember { mutableStateOf(still?.open) }
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

    // The next alarm rides the band for the last 12 hours before it (or always, if you asked).
    fun alarmDue(at: Long?): Boolean {
        val left = at?.let { it - System.currentTimeMillis() }
        return left != null && left > 0 && (prefs.alarmAlways || left <= 12 * 3_600_000L)
    }
    val alarmShown by produceState(alarmDue(nextAlarm?.at), nextAlarm, prefs.alarmAlways) {
        while (true) {
            val a = nextAlarm
            val left = a?.let { it.at - System.currentTimeMillis() }
            value = left != null && left > 0 && (prefs.alarmAlways || left <= 12 * 3_600_000L)
            if (a == null) break
            delay(60_000)
        }
    }

    // The next event from a quarter of an hour before it, counting down by the minute.
    val eventState by produceState(
        nextEvent?.let { e -> System.currentTimeMillis().let { t -> Upcoming.shows(e, t) to Upcoming.label(e, t) } },
        nextEvent,
    ) {
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

    // A new song says its name for a moment, then rides the band as its art.
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
    var dateShowing by remember { mutableStateOf(still?.date == true) }
    LaunchedEffect(dateShowing) {
        if (dateShowing) {
            delay(2_600)
            dateShowing = false
        }
    }

    val ongoing = call?.takeIf { !it.ringing }
    val ringing = call?.takeIf { it.ringing }
    // One wing carries what you're listening to or talking on, the other what's counting or coming up.
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

    var pulledOpen by remember { mutableStateOf(still?.pulled == true) }
    var peek by remember { mutableStateOf(if (still != null) note else null) }
    var noteOpen by remember { mutableStateOf(if (still?.noteOpen == true) note else null) }
    // The open card follows its notification as it updates.
    val noteOpenNow = noteOpen?.let { o -> recent.firstOrNull { it.key == o.key } ?: note?.takeIf { it.key == o.key } ?: o }

    // A tap elsewhere, or being left alone, folds everything back up.
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
            delay(12_000)
            noteOpen = null
        }
    }
    LaunchedEffect(pulledOpen, touchTick) {
        if (pulledOpen) {
            delay(12_000)
            pulledOpen = false
        }
    }
    // Something ringing takes the star: whatever hung from it is drawn back up.
    LaunchedEffect(ringing?.key, alarmRinging?.key) {
        if (ringing != null || alarmRinging != null) {
            pulledOpen = false
            noteOpen = null
            expanded = null
        }
    }

    // A new notification hangs for a few seconds; one with buttons, or a code, for longer.
    LaunchedEffect(note?.key, note?.postedAt, touchTick) {
        val n = note
        if (n == null) {
            peek = null
            return@LaunchedEffect
        }
        peek = n
        delay(if (n.code != null && prefs.codes) 15_000 else if (n.actions.isEmpty()) 5_000 else 8_000)
        peek = null
        delay(700)
        if (IslandRepository.note.value?.key == n.key && noteOpen?.key != n.key) IslandRepository.note.value = null
    }

    // ---- What runs out along the band, and what hangs from the star ----

    val words: Words? = outputShowing?.let { Words("output:" + it.key, outputLabel(it.name), "connected", Isle.charge, R.drawable.ic_headphones) }
        ?: soundRaw?.takeIf { soundShowing }?.let {
            Words("sound:" + it.at, "Sound", soundLabel(it.mode), if (it.mode == 0) Isle.signal else Isle.on, soundIcon(it.mode))
        }
        ?: batteryData?.takeIf { battery != null && batteryShowing }?.let { b ->
            Words("battery:$batteryFlash", "${b.level}%", batteryWord(b), sliverColor(b), if (b.charging) R.drawable.ic_bolt else null, Kind.Battery)
        }
        ?: (if (dateShowing) Words("date", weekday(clockAt), dayMonth(clockAt), Isle.soft) else null)
        ?: media?.takeIf { songShowing && leftKind == Kind.Media && open != Kind.Media }?.let {
            Words("song:" + it.title, it.title, it.artist, Isle.soft, null, Kind.Media)
        }
    val lastWords = rememberLast(words)

    val slip: Slip? = ringing?.let { Slip.Ring(it) }
        ?: alarmRinging?.let { Slip.Alarm(it) }
        ?: (if (pulledOpen) Slip.Recent else null)
        ?: noteOpenNow?.let { Slip.Note(it) }
        ?: open?.let { Slip.Card(it) }
        ?: peek?.takeIf { noteOpen?.key != it.key }?.let { Slip.Peek(it) }
    val shownSlip = rememberLast(slip)

    // ---- Geometry, in window pixels ----

    val sw = spot.screenWidth.toFloat()
    val cx = spot.cx
    val cy = spot.cy
    val camR = spot.radius
    val barH = max(max(spot.bar, cy + camR + px(4f)), px(24f))
    // The sliver keeps a hair's gap from the star, on a black track of its own so it reads on any wallpaper.
    val gap = px(1.3f)
    val trackW = px(3.4f)
    val sliverW = px(1.8f)
    val haloReach = gap + trackW
    // As large as covers the lens well, within the bar, the sliver still on the screen where it can be.
    val ry = max(min((camR * 2.25f).coerceIn(px(8.5f), px(14f)), cy - px(0.5f) - haloReach), camR * 1.85f)
    val rx = ry * 1.16f
    val coreR = camR + px(0.8f)
    val bandH = min(
        (ry * 1.6f).coerceIn(px(15f), px(19f)),
        min((barH - cy) * 2f - px(1f), cy * 2f - px(1f)),
    ).coerceAtLeast(px(12f))
    val margin = px(12f)
    // A camera at the left of the screen has no room beside it there: its usual left wing goes right.
    val flip = cx < sw * 0.32f
    val slipTop = barH + px(12f)
    val pivot = Offset(cx, cy + ry * 0.55f)

    val haloP by animateFloatAsState(if (showBattery) 1f else 0f, Motion.flow(), label = "haloP")
    val inner = rx + haloReach * haloP.coerceIn(0f, 1f) + px(5f)

    // ---- Time ----

    val wordsOn = words != null
    val timeLabel = clockText(context, clockAt)
    val outsideTime = showTime && prefs.timeSpot == IslandPrefs.TIME_OUTSIDE && !wordsOn
    val insideTime = showTime && prefs.timeSpot == IslandPrefs.TIME_INSIDE && !wordsOn
    val splitTime = showTime && prefs.timeSpot == IslandPrefs.TIME_SPLIT && !wordsOn

    // ---- Touch and bounds, gathered without recomposing ----

    val timeRect = remember { mutableStateOf<Rect?>(null) }
    val leftRect = remember { mutableStateOf<Rect?>(null) }
    val rightRect = remember { mutableStateOf<Rect?>(null) }
    val slipRect = remember { mutableStateOf<Rect?>(null) }
    var timeTagW by remember { mutableIntStateOf(0) }

    val tagRoom = if (outsideTime) timeTagW + px(8f) else 0f
    val leftMax = (cx - inner - margin - (if (!flip) tagRoom else 0f)).coerceAtLeast(0f)
    val rightMax = (sw - cx - inner - margin - (if (flip) tagRoom else 0f)).coerceAtLeast(0f)

    // ---- Motion ----

    // The star grows out of the lens as the island starts, turning into place.
    val bloom = remember { Animatable(if (still != null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (still != null) return@LaunchedEffect
        delay(120)
        bloom.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 110f))
    }
    // A quarter turn for each piece of news.
    var turnsWanted by remember { mutableIntStateOf(0) }
    val turns = remember { Animatable(0f) }
    LaunchedEffect(turnsWanted) { turns.animateTo(turnsWanted * 90f, spring(dampingRatio = 0.62f, stiffness = 150f)) }
    LaunchedEffect(words?.key) { if (words != null) turnsWanted += 1 }
    val newsKey = (slip as? Slip.Peek)?.key ?: (slip as? Slip.Ring)?.key ?: (slip as? Slip.Alarm)?.key
    LaunchedEffect(newsKey) { if (newsKey != null) turnsWanted += 1 }

    val playing = media?.playing == true && leftKind == Kind.Media
    val breathAmp by animateFloatAsState(if (playing && slip == null) 1f else 0f, Motion.settle(), label = "breath")
    val breath by loopPhase(playing || breathAmp > 0.01f, 3_600, "starBreath")
    val ringingNow = ringing != null || alarmRinging != null
    val pulseAmp by animateFloatAsState(if (ringingNow) 1f else 0f, Motion.settle(), label = "pulse")
    val pulse by loopPhase(ringingNow || pulseAmp > 0.01f, 1_050, "starPulse")
    val lift by animateFloatAsState(if (slip?.opened == true) 1.08f else 1f, Motion.flow(), label = "lift")

    // The battery: drawn to its level, swept up from empty when you plug in.
    val levelNow = (lastBattery?.level ?: 0) / 100f
    val level = remember { Animatable(levelNow) }
    val charging = batteryData?.charging == true
    var wasCharging by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(charging, levelNow) {
        val before = wasCharging
        wasCharging = charging
        if (charging && before == false) {
            level.snapTo(0f)
            level.animateTo(levelNow, tween(1_300, easing = FastOutSlowInEasing))
        } else {
            level.animateTo(levelNow, Motion.flow())
        }
    }
    val sparkRunning = charging && batteryData?.full != true && showBattery
    val spark by loopPhase(sparkRunning, 1_900, "spark")
    val lowBlink by loopPhase(showBattery && (batteryData?.level ?: 100) <= 10 && !charging, 1_600, "lowBlink")
    val waiting by loopPhase(!listening, 9_000, "waiting")
    val placeP by animateFloatAsState(if (placing) 1f else 0f, Motion.flow(), label = "placeP")

    // The pull: a thread out of the star, with a star for its weight.
    val pullRaw = remember { Animatable(0f) }
    val maxPull = px(150f)
    val pullMark = px(64f)
    fun pullShown(raw: Float) = maxPull * (1f - exp(-raw / maxPull))
    var armed by remember { mutableStateOf(false) }

    // The slip: dropped from the star, swung, and slid or pushed away by hand.
    val drop = remember { Animatable(if (still != null && slip != null) 1f else 0f) }
    val swing = remember { Animatable(0f) }
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    var slipSize by remember { mutableStateOf(IntSize.Zero) }
    var swingSign by remember { mutableStateOf(1f) }
    LaunchedEffect(slip != null) {
        if (slip != null) {
            drop.animateTo(1f, spring(dampingRatio = 0.56f, stiffness = 230f))
        } else {
            drop.animateTo(0f, spring(dampingRatio = 1f, stiffness = 420f))
            dragX.snapTo(0f)
            dragY.snapTo(0f)
        }
    }
    LaunchedEffect(slip?.key) {
        if (slip != null && still == null) {
            dragX.snapTo(0f)
            dragY.snapTo(0f)
            swingSign = -swingSign
            // Landing, it swings on its threads and comes to rest.
            swing.animateTo(0f, spring(dampingRatio = 0.16f, stiffness = 62f), initialVelocity = 46f * swingSign)
        }
    }
    val slipVisible = slip != null || drop.value > 0.002f

    val slipWidth = when (shownSlip) {
        is Slip.Peek -> min(sw - px(28f), px(340f))
        is Slip.Ring, is Slip.Alarm -> min(sw - px(24f), px(360f))
        else -> min(sw - px(20f), px(384f))
    }
    val slipW by animateDpAsState(slipWidth.toDp(), Motion.flow(), label = "slipW")
    fun slipX(w: Int): Float = (cx - w / 2f).coerceIn(px(10f), max(px(10f), sw - w - px(10f)))

    // ---- What a touch does ----

    fun touch() {
        touchTick += 1
    }

    fun foldAll() {
        expanded = null
        pulledOpen = false
        noteOpen = null
    }

    fun openKind(k: Kind?) {
        if (k == null) return
        InkFeel.land(view)
        pulledOpen = false
        noteOpen = null
        expanded = if (expanded == k) null else k
        touch()
    }

    fun tapStar() {
        when {
            slip?.opened == true -> foldAll()
            slip is Slip.Peek -> noteOpen = slip.note
            words?.opens != null -> openKind(words.opens)
            leftKind != null -> openKind(leftKind)
            rightKind != null -> openKind(rightKind)
            batteryData != null -> openKind(Kind.Battery)
            else -> dateShowing = true
        }
        touch()
    }

    fun tapWing(right: Boolean) {
        val kinds = if (right != flip) rightKind else leftKind
        when {
            words?.opens != null -> openKind(words.opens)
            kinds != null -> openKind(kinds)
            else -> dateShowing = true
        }
    }

    fun tapSlip() {
        when (val s = shownSlip) {
            is Slip.Peek -> {
                InkFeel.land(view)
                noteOpen = s.note
            }
            is Slip.Note -> {
                IslandRepository.openNote(s.note)
                noteOpen = null
            }
            is Slip.Card -> when (s.kind) {
                Kind.Media -> IslandRepository.openMedia().also { expanded = null }
                Kind.Call -> IslandRepository.send(lastCall?.open).also { expanded = null }
                Kind.Timer -> IslandRepository.send(lastTimer?.open).also { expanded = null }
                Kind.Alarm -> IslandRepository.openAlarm().also { expanded = null }
                Kind.Event -> lastEvent?.let { IslandRepository.openEvent(context, it) }.also { expanded = null }
                Kind.Live -> IslandRepository.send(lastLive?.open).also { expanded = null }
                Kind.Battery -> expanded = null
            }
            is Slip.Alarm -> IslandRepository.send(s.ring.open)
            is Slip.Ring -> IslandRepository.send(s.call.open)
            is Slip.Recent, null -> Unit
        }
        touch()
    }

    /** Slid aside: cleared, where clearing means something; otherwise put away. */
    fun clearSlip() {
        when (val s = shownSlip) {
            is Slip.Peek -> IslandRepository.dismissNote(s.note)
            is Slip.Note -> {
                if (s.note.clearable) IslandRepository.dismissNote(s.note)
                noteOpen = null
            }
            is Slip.Card -> expanded = null
            is Slip.Recent -> pulledOpen = false
            else -> Unit
        }
    }

    /** Pushed up: put away, still in the shade and the pulled list. */
    fun tuckSlip() {
        when (val s = shownSlip) {
            is Slip.Peek -> IslandRepository.tuckNote(s.note)
            is Slip.Note -> noteOpen = null
            is Slip.Card -> expanded = null
            is Slip.Recent -> pulledOpen = false
            else -> Unit
        }
    }

    val slipActs by rememberUpdatedState(Triple(::tapSlip, ::clearSlip, ::tuckSlip))
    val starTap by rememberUpdatedState(::tapStar)
    val dismissable by rememberUpdatedState(shownSlip?.dismissable == true)
    val pullAllowed by rememberUpdatedState(prefs.pull && slip !is Slip.Ring && slip !is Slip.Alarm)

    // ---- Reporting where the island can be touched ----

    val wantsOutside by rememberUpdatedState(slip?.opened == true)
    val starRect = Rect(cx - rx - px(14f), 0f, cx + rx + px(14f), max(barH, cy + ry + px(12f)))
    val starRectState by rememberUpdatedState(starRect)
    val slipShowing by rememberUpdatedState(slipVisible)
    val bounds by rememberUpdatedState(onBounds)
    LaunchedEffect(Unit) {
        snapshotFlow {
            val grid = 8f
            fun Rect.toAndroid() = android.graphics.Rect(
                (floor(left / grid) * grid).toInt(), (floor(top / grid) * grid).toInt(),
                (ceil(right / grid) * grid).toInt(), (ceil(bottom / grid) * grid).toInt(),
            )
            val rects = buildList {
                add(starRectState.toAndroid())
                timeRect.value?.let { add(it.toAndroid()) }
                leftRect.value?.let { add(it.toAndroid()) }
                rightRect.value?.let { add(it.toAndroid()) }
                if (slipShowing) slipRect.value?.let { add(it.toAndroid()) }
            }
            rects to wantsOutside
        }.distinctUntilChanged().collect { (rects, outside) -> bounds(rects, outside) }
    }

    // ---- Drawing ----

    val halo = remember(rx, ry, gap, trackW) { StarHalo(rx, ry, Star.SOFT, gap + trackW / 2f) }
    val starPath = remember { Path() }
    val tracePath = remember { Path() }
    val weightPath = remember { Path() }

    Box(Modifier.fillMaxSize()) {
        // The wings, under the star, each running out from it.
        val bandTop = (cy - bandH / 2f).roundToInt()
        val leftPresence by animateFloatAsState(
            if (wordsOn || (if (flip) splitTime else (leftKind != null || insideTime || splitTime))) 1f else 0f,
            Motion.flow(), label = "leftPresence",
        )
        val rightPresence by animateFloatAsState(
            if (wordsOn || splitTime || rightKind != null || (flip && (leftKind != null || insideTime))) 1f else 0f,
            Motion.flow(), label = "rightPresence",
        )
        val wingTap = remember { MutableInteractionSource() }

        // Left wing.
        Box(Modifier.fillMaxWidth().offset { IntOffset(0, bandTop) }) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = (sw - (cx - inner)).coerceAtLeast(0f).toDp())
                    .widthIn(max = leftMax.toDp())
                    .height(bandH.toDp())
                    .drawBehind { wingBand(leftPresence, inner, px(8f), px(4f), toLeft = true) }
                    .onGloballyPositioned { c -> leftRect.value = if (leftPresence > 0.5f) c.boundsInRoot().inflate(px(4f)) else null }
                    .clickable(interactionSource = wingTap, indication = null, role = Role.Button) { tapWing(right = false) }
                    .padding(start = (px(8f) * leftPresence).toDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Piece(wordsOn, fromEnd = true) {
                    lastWords?.let { w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            w.icon?.let { Glyph(it, w.rightColor.takeIf { c -> c != Isle.soft } ?: Isle.on, 12.dp); Spacer(Modifier.width(5.dp)) }
                            IsleText(w.left, 12.5.sp, Isle.on, weight = FontWeight.SemiBold)
                        }
                    }
                }
                if (!flip) {
                    Piece(!wordsOn && leftKind == Kind.Call, fromEnd = true) { lastCall?.let { CallPiece(it, now) } }
                    Piece(!wordsOn && leftKind == Kind.Media, fromEnd = true) { lastMedia?.let { MediaPiece(it) } }
                    Piece(insideTime, fromEnd = true) { TimePiece(timeLabel) }
                }
                Piece(splitTime, fromEnd = true) { TimePiece(clockText(context, clockAt, hoursOnly = true)) }
            }
        }

        // Right wing.
        Box(Modifier.fillMaxWidth().offset { IntOffset(0, bandTop) }) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = (cx + inner).toDp())
                    .widthIn(max = rightMax.toDp())
                    .height(bandH.toDp())
                    .drawBehind { wingBand(rightPresence, inner, px(8f), px(4f), toLeft = false) }
                    .onGloballyPositioned { c -> rightRect.value = if (rightPresence > 0.5f) c.boundsInRoot().inflate(px(4f)) else null }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tapWing(right = true) }
                    .padding(end = (px(8f) * rightPresence).toDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Piece(splitTime, fromEnd = false) { TimePiece(minutesText(clockAt)) }
                if (flip) Piece(insideTime, fromEnd = false) { TimePiece(timeLabel) }
                Piece(wordsOn, fromEnd = false) {
                    lastWords?.let { IsleText(it.right, 12.5.sp, it.rightColor, weight = FontWeight.Medium) }
                }
                Piece(!wordsOn && rightKind == Kind.Timer, fromEnd = false) { lastTimer?.let { TimerPiece(it, now) } }
                Piece(!wordsOn && rightKind == Kind.Live, fromEnd = false) { lastLive?.let { LivePiece(it) } }
                Piece(!wordsOn && rightKind == Kind.Event, fromEnd = false) { EventPiece(lastEventLabel) }
                Piece(!wordsOn && rightKind == Kind.Alarm, fromEnd = false) { lastAlarm?.let { AlarmPiece(clockText(context, it.at)) } }
                if (flip) {
                    Piece(!wordsOn && leftKind == Kind.Call, fromEnd = false) { lastCall?.let { CallPiece(it, now) } }
                    Piece(!wordsOn && leftKind == Kind.Media, fromEnd = false) { lastMedia?.let { MediaPiece(it) } }
                }
            }
        }

        // The time on a tag of its own at the far end of the bar.
        Box(Modifier.fillMaxWidth().offset { IntOffset(0, bandTop) }) {
            AnimatedVisibility(
                outsideTime,
                modifier = Modifier.align(if (flip) Alignment.TopEnd else Alignment.TopStart).padding(horizontal = margin.toDp()),
                enter = fadeIn(tween(220, 80)) + expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 380f), expandFrom = if (flip) Alignment.End else Alignment.Start),
                exit = fadeOut(tween(120)) + shrinkHorizontally(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = if (flip) Alignment.End else Alignment.Start),
            ) {
                Box(
                    Modifier
                        .height(bandH.toDp())
                        .onSizeChanged { timeTagW = it.width }
                        .onGloballyPositioned { c -> timeRect.value = c.boundsInRoot().inflate(px(4f)) }
                        .background(Isle.ink, TagShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                            dateShowing = true
                            touch()
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { TimePiece(timeLabel) }
            }
            LaunchedEffect(outsideTime) { if (!outsideTime) timeRect.value = null }
        }

        // The star, its sliver, and every thread.
        Canvas(Modifier.fillMaxSize()) {
            val b = bloom.value
            val breathe = 1f + 0.035f * breathAmp * sin(2f * PI.toFloat() * breath)
            val beat = 1f + 0.085f * pulseAmp * sin(PI.toFloat() * pulse).let { it * it }
            val grow = b * breathe * beat * lift
            val turn = turns.value
            val frac = ((turn % 90f) + 90f) % 90f / 90f
            // Turning, the star closes like a shutter so its tips clear the sliver, and opens again.
            val wink = 1f - 0.44f * sin(PI.toFloat() * frac)
            val pull = pullShown(pullRaw.value)
            val pullK = (pull / maxPull).coerceIn(0f, 1f)
            val center = Offset(cx, cy)

            // The pull's thread and weight, from under the star.
            if (pull > 0.5f) {
                val end = Offset(cx, cy + ry + pull)
                thread(Offset(cx, cy + ry * 0.4f), end)
                val wr = px(6f) + px(2.5f) * pullK
                drawPath(Star.path(end + Offset(0f, wr * 0.6f), wr, wr * 1.25f, Star.KEEN, rotation = pullK * 180f, into = weightPath), Isle.ink)
                if (armed) drawCircle(Isle.lit, px(1.8f), end + Offset(0f, wr * 0.6f))
            }

            // The slip's threads, to its top corners as it hangs and swings.
            if (slipVisible && slipSize.width > 0) {
                val w = slipSize.width.toFloat()
                val sx = slipX(slipSize.width)
                val d = drop.value
                val s = 0.08f + 0.92f * d
                val theta = swing.value * (PI.toFloat() / 180f)
                fun hung(p: Offset): Offset {
                    val vx = (p.x - pivot.x) * s
                    val vy = (p.y - pivot.y) * s
                    return Offset(
                        pivot.x + vx * cos(theta) - vy * sin(theta) + dragX.value,
                        pivot.y + vx * sin(theta) + vy * cos(theta) + dragY.value,
                    )
                }
                val a = hung(Offset(sx + w * 0.2f, slipTop + px(1f)))
                val c = hung(Offset(sx + w * 0.8f, slipTop + px(1f)))
                val alpha = (d * 2.2f).coerceIn(0f, 1f)
                thread(pivot, a, alpha)
                thread(pivot, c, alpha)
            }

            // The lens's own black, then the star over it.
            drawCircle(Isle.ink, coreR * min(1f, b * 1.6f), center)
            rotate(turn - 90f * (1f - b), pivot = center) {
                drawPath(Star.path(center, rx * grow * wink, ry * grow * wink * (1f + pullK * 0.16f), Star.SOFT, into = starPath), Isle.ink)
            }

            // The battery, traced round the star.
            if (haloP > 0.01f) {
                val p = haloP.coerceIn(0f, 1f)
                scale(b * breathe * beat * lift, pivot = center) {
                    drawPath(halo.trace(0f, 1f, cx, cy, tracePath), Isle.ink.copy(alpha = p), style = Stroke(trackW, join = StrokeJoin.Round))
                    drawPath(halo.trace(0f, 1f, cx, cy, tracePath), Color.White.copy(alpha = 0.13f * p), style = Stroke(px(0.8f), join = StrokeJoin.Round))
                    val lv = level.value.coerceIn(0f, 1f) * p
                    if (lv > 0.001f) {
                        val blink = if (lowBlink > 0f) 0.55f + 0.45f * cos(2f * PI.toFloat() * lowBlink) else 1f
                        drawPath(
                            halo.trace(0f, lv, cx, cy, tracePath), sliverColor(batteryData).copy(alpha = blink),
                            style = Stroke(sliverW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                    if (sparkRunning && lv > 0.03f) {
                        val head = lv * spark
                        drawPath(
                            halo.trace((head - 0.07f).coerceAtLeast(0f), head, cx, cy, tracePath), Color.White.copy(alpha = 0.92f),
                            style = Stroke(sliverW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                        drawCircle(Color.White, sliverW * 0.75f, center + halo.point(head))
                    }
                }
            }

            // Waiting for notification access: a broken ring turns slowly round it all.
            if (!listening && !slipVisible) {
                val r = ry * 1.08f + haloReach + px(3.5f)
                rotate(waiting * 360f, pivot = center) {
                    for (i in 0 until 12) {
                        drawArc(
                            Isle.ink, i * 30f, 14f, false, Offset(cx - r, cy - r), androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                            style = Stroke(px(2.2f), cap = StrokeCap.Round),
                        )
                    }
                }
            }

            // Placing it: register marks on the camera, so the star can be lined up on the lens.
            if (placeP > 0.01f) {
                val a = placeP.coerceIn(0f, 1f)
                val reach = rx + haloReach + px(14f)
                val near = rx + haloReach + px(4f)
                val hair = px(1f)
                listOf(-1f, 1f).forEach { sgn ->
                    drawLine(Isle.lit.copy(alpha = a), Offset(cx + sgn * near, cy), Offset(cx + sgn * reach, cy), hair)
                    drawLine(Isle.lit.copy(alpha = a), Offset(cx, cy + sgn * (ry + haloReach + px(3f))), Offset(cx, cy + sgn * (ry + haloReach + px(10f))), hair)
                }
                drawCircle(Isle.lit.copy(alpha = a), camR, center, style = Stroke(hair))
            }
        }

        // The star's own touch: tap it, or pull it down.
        Box(
            Modifier
                .offset { IntOffset(starRect.left.roundToInt(), starRect.top.roundToInt()) }
                .size(starRect.width.toDp(), starRect.height.toDp())
                .semantics {
                    contentDescription = "Island"
                    onClick(label = "Open") { starTap(); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var total = Offset.Zero
                        var pulling = false
                        var moved = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    if (pulling) {
                                        val shown = pullShown(pullRaw.value)
                                        if (shown >= pullMark) {
                                            InkFeel.land(view)
                                            expanded = null
                                            noteOpen = null
                                            pulledOpen = true
                                            touchTick += 1
                                        }
                                        armed = false
                                        scope.launch { pullRaw.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 300f)) }
                                    } else if (!moved) {
                                        starTap()
                                    }
                                    break
                                }
                                total += change.positionChange()
                                if (!moved && total.getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                    pulling = pullAllowed && total.y > 0 && abs(total.y) > abs(total.x)
                                }
                                if (pulling) {
                                    change.consume()
                                    val raw = total.y.coerceAtLeast(0f)
                                    scope.launch { pullRaw.snapTo(raw) }
                                    val nowArmed = pullShown(raw) >= pullMark
                                    if (nowArmed != armed) {
                                        armed = nowArmed
                                        if (nowArmed) InkFeel.split(view) else InkFeel.merge(view)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            armed = false
                            scope.launch { pullRaw.animateTo(0f, Motion.snap()) }
                            throw e
                        }
                    }
                },
        )

        // The slip.
        if (slipVisible && shownSlip != null) {
            Box(
                Modifier
                    .offset { IntOffset(slipX(slipSize.width).roundToInt(), slipTop.roundToInt()) }
                    .width(slipW)
                    .onSizeChanged { slipSize = it }
                    .onGloballyPositioned { c ->
                        val r = c.boundsInRoot()
                        // Room for the swing, and for the threads above it.
                        slipRect.value = Rect(r.left - px(14f), barH, r.right + px(14f), r.bottom + px(14f))
                    }
                    .graphicsLayer {
                        val w = size.width.coerceAtLeast(1f)
                        val h = size.height.coerceAtLeast(1f)
                        val sx = slipX(size.width.toInt())
                        transformOrigin = TransformOrigin((pivot.x - sx) / w, (pivot.y - slipTop) / h)
                        val d = drop.value
                        val s = 0.08f + 0.92f * d
                        scaleX = s
                        scaleY = s
                        alpha = (d * 2.2f).coerceIn(0f, 1f)
                        rotationZ = swing.value
                        translationX = dragX.value
                        translationY = dragY.value
                    }
                    .background(Isle.slip, SlipShape)
                    .border(1.dp, Isle.rule, SlipShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            touchTick += 1
                            var total = Offset.Zero
                            // 0 not yet moving, 1 sideways, 2 up and down, -1 a child took it.
                            var axis = 0
                            var released = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed()) {
                                        released = true
                                        val w = size.width.toFloat()
                                        when (axis) {
                                            0 -> if (!change.isConsumed) slipActs.first()
                                            1 -> {
                                                if (dismissable && abs(total.x) > w * 0.3f) {
                                                    InkFeel.split(view)
                                                    val dir = if (total.x > 0) 1f else -1f
                                                    scope.launch {
                                                        launch { swing.animateTo(dir * 14f, spring(stiffness = 500f)) }
                                                        dragX.animateTo(dir * sw, spring(dampingRatio = 1f, stiffness = 520f))
                                                        slipActs.second()
                                                    }
                                                } else {
                                                    scope.launch { dragX.animateTo(0f, Motion.snap()) }
                                                    scope.launch { swing.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 90f)) }
                                                }
                                            }
                                            2 -> {
                                                if (dismissable && total.y < -px(34f)) {
                                                    InkFeel.merge(view)
                                                    slipActs.third()
                                                } else {
                                                    scope.launch { dragY.animateTo(0f, Motion.snap()) }
                                                }
                                            }
                                        }
                                        break
                                    }
                                    if (axis == 0 && change.isConsumed) axis = -1
                                    if (axis == -1) continue
                                    total += change.positionChange()
                                    if (axis == 0 && total.getDistance() > viewConfiguration.touchSlop) {
                                        axis = if (abs(total.x) > abs(total.y)) 1 else 2
                                    }
                                    if (axis > 0) {
                                        change.consume()
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        if (axis == 1) {
                                            val x = if (dismissable) total.x else total.x * 0.25f
                                            scope.launch { dragX.snapTo(x) }
                                            scope.launch { swing.snapTo((x / w * 16f).coerceIn(-16f, 16f)) }
                                        } else {
                                            val y = if (total.y < 0 && dismissable) total.y else total.y * 0.22f
                                            scope.launch { dragY.snapTo(y) }
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                if (!released && axis > 0) {
                                    scope.launch { dragX.animateTo(0f, Motion.snap()) }
                                    scope.launch { dragY.animateTo(0f, Motion.snap()) }
                                    scope.launch { swing.animateTo(0f, Motion.snap()) }
                                }
                                throw e
                            }
                        }
                    },
            ) {
                AnimatedContent(
                    targetState = shownSlip,
                    contentKey = { it.key },
                    transitionSpec = {
                        (fadeIn(tween(220, 90)) togetherWith fadeOut(tween(90)))
                            .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.86f, stiffness = 420f) })
                    },
                    label = "slip",
                ) { s ->
                    when (s) {
                        is Slip.Ring -> RingTicket(s.call)
                        is Slip.Alarm -> AlarmRingTicket(s.ring)
                        is Slip.Peek -> PeekTicket(s.note, prefs.codes)
                        is Slip.Note -> NoteCard(s.note, prefs.codes) { noteOpen = null }
                        is Slip.Recent -> RecentCard(recent, batteryData) { pulledOpen = false }
                        is Slip.Card -> when (s.kind) {
                            Kind.Media -> lastMedia?.let { MediaCard(it) }
                            Kind.Call -> lastCall?.let { CallCard(it, now) { expanded = null } }
                            Kind.Timer -> lastTimer?.let { TimerCard(it, now) { expanded = null } }
                            Kind.Battery -> lastBattery?.let { BatteryCard(it) }
                            Kind.Alarm -> lastAlarm?.let { AlarmCard(it, now) { expanded = null } }
                            Kind.Event -> lastEvent?.let { EventCard(it, lastEventLabel) { expanded = null } }
                            Kind.Live -> lastLive?.let { LiveCard(it) { expanded = null } }
                        }
                    }
                }
            }
        }
        LaunchedEffect(slipVisible) { if (!slipVisible) slipRect.value = null }
    }
}

// ---- The band ----

/**
 * One wing's half of the band: from the star's centre out to the wing's far end, cut square at
 * that end. [presence] draws it back into the star as the wing empties.
 */
private fun DrawScope.wingBand(presence: Float, inner: Float, pad: Float, cut: Float, toLeft: Boolean) {
    val p = presence.coerceIn(0f, 1.2f)
    if (p < 0.005f) return
    val h = size.height
    val path = Path()
    if (toLeft) {
        val r = size.width + inner
        val l = lerpF(r, 0f, p) - pad * (p - 1f).coerceAtLeast(0f)
        val c = min(cut, (r - l) / 2f)
        path.moveTo(l + c, 0f)
        path.lineTo(r, 0f)
        path.lineTo(r, h)
        path.lineTo(l + c, h)
        path.lineTo(l, h - c)
        path.lineTo(l, c)
        path.close()
    } else {
        val l = -inner
        val r = lerpF(l, size.width, p) + pad * (p - 1f).coerceAtLeast(0f)
        val c = min(cut, (r - l) / 2f)
        path.moveTo(l, 0f)
        path.lineTo(r - c, 0f)
        path.lineTo(r, c)
        path.lineTo(r, h - c)
        path.lineTo(r - c, h)
        path.lineTo(l, h)
        path.close()
    }
    drawPath(path, Isle.ink)
}

/** A thread: black, with a lit hair down its middle so it shows on black too. */
private fun DrawScope.thread(from: Offset, to: Offset, alpha: Float = 1f) {
    drawLine(Isle.ink.copy(alpha = alpha), from, to, 2.2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.26f * alpha), from, to, 0.7.dp.toPx(), cap = StrokeCap.Round)
}

/** A piece of a wing, growing out from the star's side as it arrives and drawn back as it goes. */
@Composable
private fun Piece(visible: Boolean, fromEnd: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(200, 90)) + expandHorizontally(
            spring(dampingRatio = 0.78f, stiffness = 360f),
            expandFrom = if (fromEnd) Alignment.End else Alignment.Start,
        ),
        exit = fadeOut(tween(90)) + shrinkHorizontally(
            spring(dampingRatio = 1f, stiffness = 520f),
            shrinkTowards = if (fromEnd) Alignment.End else Alignment.Start,
        ),
    ) {
        Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun TimePiece(text: String) {
    IsleText(text, 13.5.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight, figures = true)
}

/** What's playing: its art in a small turning star, and three bars of its colour. */
@Composable
private fun MediaPiece(m: MediaInfo) {
    val turn by rememberTurn(m.playing, 24f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StarWindow(m.art, 16.dp, turn = turn, seed = m.title.hashCode())
        Spacer(Modifier.width(6.dp))
        PlayingBars(Color(lifted(m.accent)), m.playing)
    }
}

@Composable
private fun PlayingBars(color: Color, playing: Boolean) {
    val level by animateFloatAsState(if (playing) 1f else 0f, Motion.flow(), label = "barsLevel")
    val phase by loopPhase(playing || level > 0.01f, 1_250, "bars")
    Canvas(Modifier.size(13.dp, 11.dp)) {
        val w = size.width / 5f
        for (i in 0 until 3) {
            val swing = (0.5f + 0.5f * sin(2f * PI.toFloat() * phase + i * 2.2f)) * level
            val h = size.height * (0.28f + 0.72f * swing)
            val x = w * (i * 2f) + w / 2f
            drawLine(color, Offset(x, size.height), Offset(x, size.height - h), w, cap = StrokeCap.Butt)
        }
    }
}

@Composable
private fun CallPiece(c: CallInfo, now: Long) {
    val beat by loopPhase(true, 1_600, "callBeat")
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrintedStar(8.dp, Isle.charge, rotation = beat * 90f)
        Spacer(Modifier.width(5.dp))
        IsleText(callText(c, now), 12.5.sp, Isle.charge, weight = FontWeight.SemiBold, face = GroteskTight, figures = true)
    }
}

@Composable
private fun TimerPiece(t: TimerInfo, now: Long) {
    val over = t.countDown && t.base - now < 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(R.drawable.ic_timer, if (over) Isle.signal else Isle.lit, 12.dp)
        Spacer(Modifier.width(4.dp))
        IsleText(timerText(t, now), 12.5.sp, if (over) Isle.signal else Isle.lit, weight = FontWeight.SemiBold, face = GroteskTight, figures = true)
    }
}

@Composable
private fun LivePiece(l: LiveInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val p = l.progress
        if (p != null) MiniRing(p) else AppGlyph(l.smallIcon, Isle.on, 12.dp)
        liveShort(l)?.let {
            Spacer(Modifier.width(5.dp))
            IsleText(it, 12.5.sp, Isle.on, weight = FontWeight.SemiBold, face = GroteskTight, figures = true)
        }
    }
}

/** Progress as a fine ring, a star at its head; a short arc running round while the app can't say. */
@Composable
private fun MiniRing(progress: Float) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.flow(), label = "miniRing")
    val spin by loopPhase(progress < 0f, 1_100, "miniSpin")
    Canvas(Modifier.size(12.dp)) {
        val w = 1.6.dp.toPx()
        val inset = w / 2f
        val arc = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
        if (progress < 0f) {
            drawArc(Isle.lit, spin * 360f - 90f, 90f, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        } else {
            drawArc(Isle.lit, -90f, 360f * shown, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun EventPiece(label: String) {
    val color = if (label == "Now") Isle.charge else Isle.lit
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(R.drawable.ic_event, color, 12.dp)
        Spacer(Modifier.width(4.dp))
        IsleText(label, 12.5.sp, color, weight = FontWeight.SemiBold, face = GroteskTight, figures = true)
    }
}

@Composable
private fun AlarmPiece(at: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(R.drawable.ic_alarm, Isle.soft, 12.dp)
        Spacer(Modifier.width(4.dp))
        IsleText(at, 12.5.sp, Isle.soft, weight = FontWeight.SemiBold, face = GroteskTight, figures = true)
    }
}

/** An artwork's colour, lifted enough to read on black. */
private fun lifted(color: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color, hsv)
    hsv[2] = maxOf(hsv[2], 0.78f)
    return android.graphics.Color.HSVToColor(hsv)
}
