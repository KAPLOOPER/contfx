package dev.continuum.fx.island.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import dev.continuum.fx.R
import dev.continuum.fx.island.IslandPrefs
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.audio.OutputDevice
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.island.AlarmRing
import dev.continuum.fx.island.CallInfo
import dev.continuum.fx.island.CameraSpot
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.IslandSettings
import dev.continuum.fx.island.NoteInfo
import dev.continuum.fx.island.Upcoming
import dev.continuum.fx.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Kind { Call, Media, Timer, Battery, Alarm, Event, Live, Sound, Output }

private data class Mode(val kind: Kind?, val expanded: Boolean, val time: Boolean, val battery: Boolean)

private sealed interface Drip {
    val key: String

    data class Ring(val call: CallInfo) : Drip {
        override val key get() = call.key
    }

    data class Peek(val note: NoteInfo) : Drip {
        override val key get() = note.key
    }

    /** An alarm, or a finished timer, ringing now. */
    data class Alarm(val ring: AlarmRing) : Drip {
        override val key get() = ring.key
    }

    /** A drop pulled out of the island by hand: recent notifications. */
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
 * The Continuum island. Everything is one body of ink that starts as the camera:
 * the island grows out of it, a second activity pinches off as a bud, alerts drip
 * down from it, and you can pull a drop out of it by hand. Nothing appears or
 * vanishes; ink only separates or merges.
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

    // The always-on clock: checked every second, but only changes once a minute, so it
    // is right again the moment the screen wakes.
    val context = LocalContext.current
    val alwaysTime = prefs.alwaysTime
    val clock by produceState(System.currentTimeMillis(), alwaysTime) {
        value = System.currentTimeMillis()
        while (alwaysTime) {
            delay(1_000)
            val t = System.currentTimeMillis()
            if (t / 60_000 != value / 60_000) value = t
        }
    }
    val timeLabel = if (alwaysTime) clockText(context, clock) else null
    val hoursLabel = if (alwaysTime) clockText(context, clock, hoursOnly = true) else null
    val minutesLabel = if (alwaysTime) minutesText(clock) else null
    val dateLabel = if (alwaysTime) dateText(clock) else null

    // A one-second clock, running only while something on the island counts.
    val ticking = timer != null || call?.ringing == false || media?.playing == false
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
            delay(4_000)
            batteryShowing = false
        }
    }

    // The next alarm sits in the island for the last 12 hours before it (or always, if you
    // asked), checked once a minute so it turns up on time.
    val alarmShown by produceState(false, nextAlarm, prefs.alarmAlways) {
        while (true) {
            val a = nextAlarm
            val left = a?.let { it.at - System.currentTimeMillis() }
            value = left != null && left > 0 && (prefs.alarmAlways || left <= 12 * 3_600_000L)
            if (a == null) break
            delay(60_000)
        }
    }

    // The next event sits in the island from a quarter of an hour before it, counting down
    // by the minute; the clock here moves only when what it says would change.
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
            // While it's showing, the countdown turns over on each minute before the start.
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

    val ongoing = call?.takeIf { !it.ringing }
    val ringing = call?.takeIf { it.ringing }
    val kinds = buildList {
        if (outputShowing != null) add(Kind.Output)
        if (soundRaw != null && soundShowing) add(Kind.Sound)
        if (batteryData != null && ((battery != null && batteryShowing) || expanded == Kind.Battery)) add(Kind.Battery)
        if (ongoing != null) add(Kind.Call)
        if (mediaShown) add(Kind.Media)
        if (timer != null) add(Kind.Timer)
        if (live != null) add(Kind.Live)
        if (nextEvent != null && (eventShown || expanded == Kind.Event)) add(Kind.Event)
        if (nextAlarm != null && (alarmShown || expanded == Kind.Alarm)) add(Kind.Alarm)
    }
    val open = expanded?.takeIf { it in kinds }
    val primary = open ?: kinds.firstOrNull()
    val secondary = if (open == null) kinds.getOrNull(1) else null

    val lastMedia = rememberLast(media)
    val lastCall = rememberLast(ongoing)
    val lastTimer = rememberLast(timer)
    val lastBattery = rememberLast(batteryData)
    val lastAlarm = rememberLast(nextAlarm)
    val lastEvent = rememberLast(nextEvent)
    val lastEventLabel = rememberLast(eventLabel).orEmpty()
    val lastLive = rememberLast(live)
    val lastSound = rememberLast(soundRaw)
    val lastOutput = rememberLast(outputShowing)

    // Always-on extras share the pill with whatever is live.
    val ambientBattery = if (prefs.alwaysBattery && primary != Kind.Battery) batteryData else null
    val ambient = alwaysTime || ambientBattery != null
    val pillShown = primary != null || ambient
    val lastSecondary = rememberLast(secondary)

    // ---- The pulled drop ----

    var pullPx by remember { mutableFloatStateOf(-1f) }
    var pulledOpen by remember { mutableStateOf(false) }
    var dripFromPull by remember { mutableStateOf(false) }
    var pullIndex by remember { mutableIntStateOf(0) }
    val pullSpread = remember { Animatable(0f) }

    // Outside taps and inactivity fold everything back up.
    val taps = outsideTaps.intValue
    LaunchedEffect(taps) {
        if (taps > 0) {
            expanded = null
            pulledOpen = false
        }
    }
    LaunchedEffect(open, touchTick) {
        if (open != null) {
            delay(if (open == Kind.Media) 12_000 else 8_000)
            expanded = null
        }
    }
    LaunchedEffect(pulledOpen, pullIndex) {
        if (pulledOpen) {
            delay(10_000)
            pulledOpen = false
        }
    }
    LaunchedEffect(ringing?.key, alarmRinging?.key) { if (ringing != null || alarmRinging != null) pulledOpen = false }

    // Notification peeks drip down for a few seconds, then merge back up.
    // Notifications with buttons stay a little longer, and touching one keeps it there.
    var peek by remember { mutableStateOf<NoteInfo?>(null) }
    var peekTouch by remember { mutableIntStateOf(0) }
    LaunchedEffect(note?.key, note?.postedAt, peekTouch) {
        val n = note
        if (n == null) {
            peek = null
            return@LaunchedEffect
        }
        peek = n
        delay(if (n.actions.isEmpty()) 5_000 else 8_000)
        peek = null
        delay(700)
        if (IslandRepository.note.value?.key == n.key) IslandRepository.note.value = null
    }

    val drip: Drip? = ringing?.let { Drip.Ring(it) }
        ?: alarmRinging?.let { Drip.Alarm(it) }
        ?: (if (pulledOpen) Drip.Pulled else null)
        ?: peek?.let { Drip.Peek(it) }
    val lastDrip = rememberLast(drip)
    val dripVisible = drip != null

    // ---- Geometry, in window pixels ----

    val sw = spot.screenWidth.toFloat()
    val band = max(spot.radius * 2f + px(12f), px(34f))
    val top = (spot.cy - band / 2f).coerceAtLeast(px(2f))
    val camSlot = if (spot.detected) spot.radius * 2f + px(14f) else px(18f)
    val cardW = min(sw - px(24f), px(380f))
    val idleR = spot.radius * 0.85f
    val gap = px(12f)
    val tearGap = px(40f)

    val timerLabel = timer?.let { timerText(it, now) } ?: ""
    val callLabel = ongoing?.let { callText(it, now) } ?: ""
    // The pill is laid out as items either side of the camera, measured for real so
    // nothing is squeezed or cut off.
    val measurer = rememberTextMeasurer()
    fun textW(t: PillItem.Label): Float = measurer.measure(
        AnnotatedString(t.text),
        style = TextStyle(fontFamily = Libron, fontSize = t.size.sp, fontWeight = if (t.bold) FontWeight.Bold else FontWeight.Normal),
    ).size.width + px(1f)
    fun itemW(it: PillItem): Float = when (it) {
        is PillItem.Label -> textW(it)
        PillItem.Art -> band - px(12f)
        PillItem.Pulse -> px(24f)
        is PillItem.Icon -> px(18f)
        PillItem.Cell -> px(BATTERY_W)
        PillItem.Flex -> 0f
        is PillItem.AppIcon -> px(18f)
        is PillItem.Ring -> px(18f)
    }
    fun rowW(items: List<PillItem>, leading: Boolean): Float {
        if (items.isEmpty()) return px(14f)
        val outer = if ((if (leading) items.first() else items.last()) == PillItem.Art) px(6f) else px(14f)
        var w = outer + px(PILL_INNER) + items.sumOf { itemW(it).toDouble() }.toFloat()
        for (i in 1 until items.size) if (items[i] != PillItem.Flex && items[i - 1] != PillItem.Flex) w += px(PILL_GAP)
        if (PillItem.Flex in items) w += px(PILL_GAP)
        return w
    }
    val batteryStatus = batteryData?.let { b ->
        when {
            b.full -> PillItem.Label("Charged", 13f, InkColors.live, true)
            b.charging && b.wireless -> PillItem.Label("Wireless", 13f, InkColors.live, true)
            b.charging -> PillItem.Icon(R.drawable.ic_bolt, InkColors.live)
            b.low -> PillItem.Label("Low", 13f, InkColors.end, true)
            else -> PillItem.Label("${b.level}%", 13f, InkColors.on, true)
        }
    }
    val alarmLabel = lastAlarm?.let { clockText(context, it.at) } ?: ""
    val liveLabel = lastLive?.let { liveShort(it) }
    val soundMode = lastSound?.mode ?: 2
    fun leftOf(k: Kind): List<PillItem> = when (k) {
        Kind.Media -> listOf(PillItem.Art)
        Kind.Call -> listOf(PillItem.Icon(R.drawable.ic_call, InkColors.live))
        Kind.Timer -> listOf(PillItem.Icon(R.drawable.ic_timer, InkColors.on))
        Kind.Battery -> listOf(PillItem.Cell)
        Kind.Alarm -> listOf(PillItem.Icon(R.drawable.ic_alarm, InkColors.on))
        Kind.Event -> listOf(PillItem.Icon(R.drawable.ic_event, InkColors.on))
        Kind.Live -> listOf(PillItem.AppIcon(lastLive?.smallIcon, InkColors.on))
        Kind.Sound -> listOf(PillItem.Icon(soundIcon(soundMode), soundColor(soundMode)))
        Kind.Output -> listOf(PillItem.Icon(R.drawable.ic_headphones, InkColors.live))
    }
    fun rightOf(k: Kind): List<PillItem> = when (k) {
        Kind.Media -> listOf(PillItem.Pulse)
        Kind.Call -> listOf(PillItem.Label(callLabel, 14f, InkColors.live, true))
        Kind.Timer -> listOf(PillItem.Label(timerLabel, 14f, InkColors.on, true))
        Kind.Battery -> listOfNotNull(batteryStatus)
        Kind.Alarm -> listOf(PillItem.Label(alarmLabel, 14f, InkColors.on, true))
        Kind.Event -> listOf(PillItem.Label(lastEventLabel, 14f, eventColor(lastEventLabel), true))
        Kind.Live -> {
            val l = lastLive
            val bits = mutableListOf<PillItem>()
            if (liveLabel != null) bits += PillItem.Label(liveLabel, 14f, InkColors.on, true)
            if (l?.progress != null) bits += PillItem.Ring(l.progress, liveColor(l))
            bits
        }
        Kind.Sound -> listOf(PillItem.Label(soundLabel(soundMode), 14f, soundColor(soundMode), true))
        Kind.Output -> listOf(PillItem.Label(outputLabel(lastOutput?.name.orEmpty()), 14f, InkColors.on, true))
    }
    /** What sits on each side of the camera, for a live [k] (or none) plus the always-on extras. */
    fun pillLayout(k: Kind?, time: Boolean, batt: Boolean): PillLayout {
        val left = mutableListOf<PillItem>()
        val right = mutableListOf<PillItem>()
        val t = if (time) timeLabel else null
        val split = prefs.timeSpot == IslandPrefs.TIME_SPLIT && t != null
        if (k != null && split) {
            // Hours hug the camera on the left, minutes on the right. The live activity's
            // second half goes on whichever side keeps the pill balanced.
            val hours = PillItem.Label(hoursLabel.orEmpty(), 14f, InkColors.on, true)
            val minutes = PillItem.Label(minutesLabel.orEmpty(), 14f, InkColors.on, true)
            val cell = if (batt) listOf<PillItem>(PillItem.Cell) else emptyList()
            val apart = PillLayout(
                leftOf(k) + PillItem.Flex + hours,
                listOf(minutes, PillItem.Flex) + rightOf(k) + cell,
                0f,
            )
            val together = PillLayout(
                leftOf(k) + rightOf(k) + PillItem.Flex + hours,
                listOf(minutes, PillItem.Flex) + cell,
                0f,
            )
            return listOf(apart, together)
                .map { it.copy(side = max(rowW(it.left, true), rowW(it.right, false))) }
                .minBy { it.side }
        }
        if (k != null) {
            when {
                t != null && prefs.timeSpot == IslandPrefs.TIME_INSIDE -> { left += leftOf(k); left += PillItem.Label(t, 14f, InkColors.on, true) }
                t != null -> { left += PillItem.Label(t, 14f, InkColors.on, true); left += leftOf(k) }
                else -> left += leftOf(k)
            }
            right += rightOf(k)
            if (batt) right += PillItem.Cell
        } else {
            when {
                split -> {
                    left += PillItem.Flex; left += PillItem.Label(hoursLabel.orEmpty(), 14f, InkColors.on, true)
                    right += PillItem.Label(minutesLabel.orEmpty(), 14f, InkColors.on, true); right += PillItem.Flex
                    if (batt) right += PillItem.Cell
                }
                t != null -> {
                    left += PillItem.Label(t, 14f, InkColors.on, true)
                    if (batt) right += PillItem.Cell else dateLabel?.let { right += PillItem.Label(it, 13f, InkColors.sub, false) }
                }
                batt -> {
                    if (batteryData?.charging == true) left += PillItem.Icon(R.drawable.ic_bolt, InkColors.live)
                    right += PillItem.Cell
                }
            }
        }
        return PillLayout(left, right, max(rowW(left, true), rowW(right, false)))
    }
    fun budWidthOf(k: Kind): Float = when (k) {
        Kind.Media -> band
        Kind.Call -> px(10f + 14f + 6f + 10f) + textW(PillItem.Label(callLabel, 13f, InkColors.live, true))
        Kind.Timer -> px(20f) + textW(PillItem.Label(timerLabel, 13f, InkColors.on, true))
        Kind.Battery -> px(BATTERY_W + 20f)
        Kind.Alarm -> px(20f + 14f + 6f) + textW(PillItem.Label(alarmLabel, 13f, InkColors.on, true))
        Kind.Event -> px(20f + 14f + 6f) + textW(PillItem.Label(lastEventLabel, 13f, InkColors.on, true))
        Kind.Live -> px(20f + 16f) + (liveLabel?.let { px(6f) + textW(PillItem.Label(it, 13f, InkColors.on, true)) } ?: 0f)
        Kind.Sound -> px(20f + 16f)
        Kind.Output -> px(20f + 16f)
    }
    fun expandedHeight(k: Kind): Float = band + px(
        when (k) {
            Kind.Media -> 198f
            Kind.Call -> 138f
            Kind.Timer -> 132f
            Kind.Battery -> 112f
            Kind.Alarm -> 140f
            Kind.Event -> 140f
            Kind.Live -> if (lastLive?.progress != null) 150f else 130f
            Kind.Sound, Kind.Output -> 0f
        },
    )
    fun centerFor(w: Float): Float {
        val margin = px(8f)
        return if (w >= sw - margin * 2f) sw / 2f else spot.cx.coerceIn(margin + w / 2f, sw - margin - w / 2f)
    }

    val mode = Mode(primary, open != null, alwaysTime && open == null, ambientBattery != null && open == null)
    val tW: Float
    val tH: Float
    val tTop: Float
    val tR: Float
    when {
        primary == null && !ambient -> {
            tW = idleR * 2f; tH = idleR * 2f; tTop = spot.cy - idleR; tR = idleR
        }
        open != null -> {
            tW = cardW; tH = expandedHeight(open); tTop = top; tR = px(30f)
        }
        else -> {
            tW = camSlot + pillLayout(primary, mode.time, mode.battery).side * 2f; tH = band; tTop = top; tR = band / 2f
        }
    }
    val mW by animateFloatAsState(tW, Motion.flow(), label = "mainW")
    val mH by animateFloatAsState(tH, Motion.flow(), label = "mainH")
    val mTop by animateFloatAsState(tTop, Motion.flow(), label = "mainTop")
    val mR by animateFloatAsState(tR, Motion.flow(), label = "mainR")

    fun mainRect(): Rect {
        val c = centerFor(mW)
        return Rect(c - mW / 2f, mTop, c + mW / 2f, mTop + mH)
    }
    fun mainRadius(r: Rect) = mR.coerceIn(0f, min(r.width, r.height) / 2f)
    val mainTarget = Rect(centerFor(tW) - tW / 2f, tTop, centerFor(tW) + tW / 2f, tTop + tH)

    // The bud pinches off the side with a fling, then settles a short gap away.
    val budKind = lastSecondary
    val budW = budKind?.let { budWidthOf(it) } ?: band
    val budWNow by animateFloatAsState(budW, Motion.flow(), label = "budW")
    val budRight = mainTarget.right + gap + budW <= sw - px(8f)
    val budP = remember { Animatable(0f) }
    val budOut = secondary != null
    LaunchedEffect(budOut) {
        if (budOut) {
            budP.animateTo(1.4f, tween(180, easing = LinearOutSlowInEasing))
            budP.animateTo(1f, Motion.flow())
        } else {
            budP.animateTo(0f, Motion.flow())
        }
    }
    fun budRect(m: Rect): Rect {
        val p = budP.value.coerceAtLeast(0f)
        val pc = p.coerceAtMost(1f)
        val s = min(min(m.height, m.width), band)
        val w = lerpF(s, budWNow, pc)
        val h = lerpF(s, band, pc)
        val cy = m.center.y
        return if (budRight) {
            val left = lerpF(m.right - s, m.right + gap, p)
            Rect(left, cy - h / 2f, left + w, cy + h / 2f)
        } else {
            val right = lerpF(m.left + s, m.left - gap, p)
            Rect(right - w, cy - h / 2f, right, cy + h / 2f)
        }
    }
    val budTarget = if (budRight) {
        Rect(mainTarget.right + gap, mainTarget.center.y - band / 2f, mainTarget.right + gap + budW, mainTarget.center.y + band / 2f)
    } else {
        Rect(mainTarget.left - gap - budW, mainTarget.center.y - band / 2f, mainTarget.left - gap, mainTarget.center.y + band / 2f)
    }

    // Drips fall from the island, overshoot, and spring back to hang just below it.
    // A pulled drop follows the finger instead, and spreads into a card once it tears free.
    val dripW = cardW
    // Drops are as tall as what they hold: buttons, a list, or a single line.
    val dripHTarget = px(
        when (val d = lastDrip) {
            is Drip.Peek -> noteDripHeight(d.note)
            is Drip.Alarm -> 140f
            Drip.Pulled -> pulledHeight(recent.size)
            else -> 84f
        },
    )
    val dripH by animateFloatAsState(dripHTarget, Motion.flow(), label = "dripH")
    // Swiping a notification sideways: it leans away on a neck that thins and snaps.
    val dripDx = remember { Animatable(0f) }
    val sideLatch = remember { NeckLatch() }
    val dripP = remember { Animatable(0f) }
    LaunchedEffect(dripVisible) {
        if (dripVisible) {
            if (!dripFromPull) {
                dripP.animateTo(1.5f, tween(260, easing = LinearOutSlowInEasing))
                dripP.animateTo(1f, Motion.flow())
            }
        } else {
            dripFromPull = false
            dripP.animateTo(0f, Motion.flow())
        }
    }
    fun dropSize(m: Rect) = min(min(m.height, m.width), band)
    fun dripSpread(q: Float): Float =
        if (dripFromPull) pullSpread.value.coerceIn(0f, 1f) else q.coerceIn(0f, 1f).let { it * it * it }
    fun dripRect(m: Rect): Rect {
        val s = dropSize(m)
        val pulling = pullPx >= 0f
        val q = if (pulling) pullPx / (gap + s) else dripP.value.coerceAtLeast(0f)
        // Falls as a drop first, then spreads into a card as it lands.
        val spread = dripSpread(q)
        // A drop on a stretching neck grows a little long and thin.
        val stretch = if (pulling) ((pullPx - s) / tearGap).coerceIn(0f, 1f) else 0f
        val w = lerpF(s, dripW, spread) * (1f - 0.14f * stretch)
        val h = lerpF(s, dripH, spread) * (1f + 0.16f * stretch)
        val t = lerpF(m.bottom - s, m.bottom + gap, q)
        val cx = lerpF(m.center.x, centerFor(dripW), spread) + dripDx.value
        return Rect(cx - w / 2f, t, cx + w / 2f, t + h)
    }
    fun dripRadius(r: Rect): Float {
        val spread = dripSpread(if (pullPx >= 0f) 0f else dripP.value)
        return lerpF(min(r.width, r.height) / 2f, px(28f), spread).coerceAtMost(min(r.width, r.height) / 2f)
    }
    val dripTarget = Rect(centerFor(dripW) - dripW / 2f, mainTarget.bottom + gap, centerFor(dripW) + dripW / 2f, mainTarget.bottom + gap + max(dripH, dripHTarget))

    // ---- Pulling a drop out by hand ----

    val budLatch = remember { NeckLatch() }
    val dripLatch = remember { NeckLatch() }

    val canPull = prefs.pull && drip == null && open == null
    val canPullNow by rememberUpdatedState(canPull)
    val tapTarget by rememberUpdatedState((primary ?: if (ambientBattery != null) Kind.Battery else null)?.takeIf { it != Kind.Sound && it != Kind.Output })

    fun haptic(strong: Boolean) {
        val kind = when {
            strong && Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
            strong -> HapticFeedbackConstants.VIRTUAL_KEY
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(kind)
    }

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
        val s = dropSize(mainRect())
        val q = pullPx / (gap + s)
        dripLatch.joined = false
        haptic(strong = true)
        pullIndex = 0
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
        val s = dropSize(mainRect())
        if (pullPx - s >= tearAt(s)) tear()
    }

    fun releasePull() {
        if (pullPx < 0f || pulledOpen) return
        val q = pullPx / (gap + dropSize(mainRect()))
        scope.launch {
            dripP.snapTo(q)
            pullPx = -1f
            dripP.animateTo(0f, Motion.flow())
            if (!pulledOpen) dripFromPull = false
        }
    }

    // ---- Touch area: only where the island is, so the rest of the screen stays usable ----

    val idleGrab = Rect(spot.cx - px(30f), 0f, spot.cx + px(30f), spot.cy + idleR + px(14f))
    val grabRect: Rect? = when {
        open != null -> null
        pillShown -> mainTarget
        prefs.pull -> idleGrab
        else -> null
    }
    val touchRect: android.graphics.Rect? = run {
        var u: Rect? = if (pillShown) mainTarget else grabRect
        if (budOut) u = u?.let { unionOf(it, budTarget) } ?: budTarget
        if (dripVisible) u = u?.let { unionOf(it, dripTarget) } ?: unionOf(mainTarget, dripTarget)
        u?.let { android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.roundToInt(), it.bottom.roundToInt()) }
    }
    val wantsOutside = open != null || pulledOpen
    LaunchedEffect(touchRect, wantsOutside) { bounds(touchRect, wantsOutside) }

    // ---- The ink ----

    val goo = remember(dp) { if (Build.VERSION.SDK_INT >= 31) Goo.effect(px(7f)) else null }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { renderEffect = goo }) {
            val m = mainRect()
            drawRoundRect(InkColors.ink, m.topLeft, m.size, CornerRadius(mainRadius(m)))
            if (budP.value > 0.002f) {
                val b = budRect(m)
                drawRoundRect(InkColors.ink, b.topLeft, b.size, CornerRadius(min(b.width, b.height) / 2f))
                if (budRight) {
                    neckAcross(m, b, budLatch, px(24f), px(2f), InkColors.ink)
                } else {
                    neckAcross(b, m, budLatch, px(24f), px(2f), InkColors.ink)
                }
            }
            if (dripP.value > 0.002f || pullPx > 0f) {
                val d = dripRect(m)
                drawRoundRect(InkColors.ink, d.topLeft, d.size, CornerRadius(dripRadius(d)))
                val reach = if (dripFromPull && !pulledOpen) tearGap else px(26f)
                val lean = dripDx.value
                if (abs(lean) < 0.5f) {
                    sideLatch.joined = true
                    neckDown(m, d, m.center.x, dripLatch, reach, px(2f), px(26f), InkColors.ink)
                } else {
                    // Leaning away: the neck thins as the drop goes, and once it snaps it stays apart.
                    val side = (1f - abs(lean) / px(90f)).coerceIn(0f, 1f)
                    if (side <= 0f) sideLatch.joined = false
                    if (sideLatch.joined) {
                        val gapNow = (d.top - m.bottom).coerceAtLeast(0f)
                        val rest = (1f - gapNow / reach).coerceIn(0f, 1f).pow(1.5f)
                        neckSlant(m, d, m.center.x, m.center.x + lean, rest * side.pow(1.5f), px(26f), InkColors.ink)
                    }
                }
            }
        }

        // ---- Content on the ink, clipped to the ink as it moves ----

        Crossfade(targetState = mode, modifier = Modifier.fillMaxSize(), animationSpec = tween(220), label = "islandContent") { md ->
            val k = md.kind
            if (k == null && !md.time && !md.battery) return@Crossfade
            val layout = pillLayout(k, md.time, md.battery)
            val w = if (md.expanded && k != null) cardW else camSlot + layout.side * 2f
            val h = if (md.expanded && k != null) expandedHeight(k) else band
            val left = centerFor(w) - w / 2f
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(toDp(w), toDp(h))
                        .drawWithContent {
                            val r = mainRect()
                            val local = r.translate(-left, -top)
                            val path = Path().apply { addRoundRect(RoundRect(local, CornerRadius(mainRadius(r)))) }
                            clipPath(path) { this@drawWithContent.drawContent() }
                        }
                        .then(
                            if (md.expanded) {
                                Modifier
                                    .pointerInput(md) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            touchTick++
                                        }
                                    }
                                    .pointerInput(md) { detectTapGestures { expanded = null } }
                                    .pointerInput(md) {
                                        var dy = 0f
                                        detectVerticalDragGestures(
                                            onDragStart = { dy = 0f },
                                            onDragEnd = { if (dy < -px(24f)) expanded = null },
                                        ) { change, amount ->
                                            change.consume()
                                            dy += amount
                                        }
                                    }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    if (md.expanded && k != null) {
                        when (k) {
                            Kind.Media -> lastMedia?.let { ExpandedMedia(it, toDp(band)) }
                            Kind.Call -> lastCall?.let { ExpandedCall(it, now, toDp(band)) }
                            Kind.Timer -> lastTimer?.let { ExpandedTimer(it, now, toDp(band)) }
                            Kind.Battery -> lastBattery?.let { ExpandedBattery(it, toDp(band)) }
                            Kind.Alarm -> lastAlarm?.let { ExpandedAlarm(it, System.currentTimeMillis(), toDp(band)) }
                            Kind.Event -> lastEvent?.let { ExpandedEvent(it, lastEventLabel, toDp(band)) }
                            Kind.Live -> lastLive?.let { ExpandedLive(it, toDp(band)) }
                            Kind.Sound, Kind.Output -> Unit
                        }
                    } else {
                        PillContent(
                            layout = layout,
                            side = toDp(layout.side),
                            media = lastMedia,
                            battery = lastBattery,
                            height = toDp(band),
                        )
                    }
                }
            }
        }

        // Grabbing the island: tap to open it, hold and drag down to pull a drop out.
        if (grabRect != null) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .offset { IntOffset(grabRect.left.roundToInt(), grabRect.top.roundToInt()) }
                        .size(toDp(grabRect.width), toDp(grabRect.height))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { tapTarget?.let { expanded = it } },
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
        }

        // The bud's content fades in once it has pinched off.
        val budAlpha by animateFloatAsState(
            if (budOut) 1f else 0f,
            if (budOut) tween(240, delayMillis = 140) else tween(110),
            label = "budAlpha",
        )
        if (budKind != null && budAlpha > 0.01f) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .offset { IntOffset(budTarget.left.roundToInt(), budTarget.top.roundToInt()) }
                        .size(toDp(budTarget.width), toDp(budTarget.height))
                        .graphicsLayer { alpha = budAlpha }
                        .drawWithContent {
                            val b = budRect(mainRect())
                            val local = b.translate(-budTarget.left, -budTarget.top)
                            val path = Path().apply { addRoundRect(RoundRect(local, CornerRadius(min(b.width, b.height) / 2f))) }
                            clipPath(path) { this@drawWithContent.drawContent() }
                        }
                        .pointerInput(budKind) { detectTapGestures { expanded = budKind } },
                ) {
                    BudContent(budKind, lastMedia, lastCall, lastTimer, lastBattery, now, lastAlarm, lastLive, lastSound, lastEventLabel)
                }
            }
        }

        // The drip's content: an incoming call, a ringing alarm, a notification, or the pulled drop.
        val dripAlpha by animateFloatAsState(
            if (dripVisible) 1f else 0f,
            if (dripVisible) tween(240, delayMillis = 160) else tween(120),
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
                        peek = null
                        IslandRepository.dismissNote(n)
                    } else {
                        dripDx.animateTo(0f, Motion.flow())
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .offset { IntOffset(dripTarget.left.roundToInt(), dripTarget.top.roundToInt()) }
                        .size(toDp(dripTarget.width), toDp(dripTarget.height))
                        .graphicsLayer { alpha = dripAlpha }
                        .drawWithContent {
                            val d = dripRect(mainRect())
                            val local = d.translate(-dripTarget.left, -dripTarget.top)
                            val path = Path().apply { addRoundRect(RoundRect(local, CornerRadius(dripRadius(d)))) }
                            clipPath(path) { this@drawWithContent.drawContent() }
                        }
                        .pointerInput(shownDrip.key) {
                            // Touching a notification keeps it out for longer.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                if (shownDrip is Drip.Peek) peekTouch++
                            }
                        }
                        .pointerInput(shownDrip.key) {
                            if (shownDrip == Drip.Pulled) return@pointerInput
                            detectTapGestures {
                                when (shownDrip) {
                                    is Drip.Ring -> IslandRepository.send(shownDrip.call.open)
                                    is Drip.Alarm -> IslandRepository.send(shownDrip.ring.open)
                                    is Drip.Peek -> {
                                        peek = null
                                        IslandRepository.openNote(shownDrip.note)
                                    }
                                    Drip.Pulled -> Unit
                                }
                            }
                        }
                        .pointerInput(shownDrip.key) {
                            // Up tucks a notification back into the island (it stays in the shade),
                            // or sends the pulled drop home.
                            var dy = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dy = 0f },
                                onDragEnd = {
                                    if (dy < -px(20f)) {
                                        when (shownDrip) {
                                            is Drip.Peek -> {
                                                peek = null
                                                IslandRepository.tuckNote(shownDrip.note)
                                            }
                                            Drip.Pulled -> pulledOpen = false
                                            is Drip.Ring, is Drip.Alarm -> Unit
                                        }
                                    }
                                },
                            ) { change, amount ->
                                change.consume()
                                dy += amount
                            }
                        }
                        .pointerInput(shownDrip.key) {
                            if (shownDrip !is Drip.Peek) return@pointerInput
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
                        when (shownDrip) {
                            is Drip.Ring -> RingingDrip(shownDrip.call)
                            is Drip.Alarm -> AlarmRingDrip(shownDrip.ring)
                            is Drip.Peek -> NoteDrip(shownDrip.note)
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
}

private fun unionOf(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
