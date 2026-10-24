package dev.continuum.fx.island.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.continuum.fx.island.CallInfo
import dev.continuum.fx.island.CameraSpot
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.IslandSettings
import dev.continuum.fx.island.MediaInfo
import dev.continuum.fx.island.NoteInfo
import dev.continuum.fx.island.TimerInfo
import dev.continuum.fx.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Kind { Call, Media, Timer }

private data class Mode(val kind: Kind?, val expanded: Boolean)

private sealed interface Drip {
    val key: String

    data class Ring(val call: CallInfo) : Drip {
        override val key get() = call.key
    }

    data class Peek(val note: NoteInfo) : Drip {
        override val key get() = note.key
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
 * the island grows out of it, a second activity pinches off as a bud, and alerts
 * drip down from it. Nothing appears or vanishes; it separates or merges.
 */
@Composable
fun IslandRoot(spotState: State<CameraSpot?>, outsideTaps: IntState, onBounds: (android.graphics.Rect?) -> Unit) {
    val spot = spotState.value ?: return
    val density = LocalDensity.current
    val dp = density.density
    fun px(v: Float) = v * dp
    fun toDp(v: Float): Dp = (v / dp).dp

    val prefs by IslandSettings.state.collectAsState()
    val mediaRaw by IslandRepository.media.collectAsState()
    val callRaw by IslandRepository.call.collectAsState()
    val timerRaw by IslandRepository.timer.collectAsState()
    val noteRaw by IslandRepository.note.collectAsState()
    val bounds by rememberUpdatedState(onBounds)

    // ---- What to show ----

    val media = mediaRaw?.takeIf { prefs.media }
    val call = callRaw?.takeIf { prefs.calls }
    val timer = timerRaw?.takeIf { prefs.timers }
    val note = noteRaw?.takeIf { prefs.notifications }

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

    val ongoing = call?.takeIf { !it.ringing }
    val ringing = call?.takeIf { it.ringing }
    val kinds = buildList {
        if (ongoing != null) add(Kind.Call)
        if (mediaShown) add(Kind.Media)
        if (timer != null) add(Kind.Timer)
    }
    val open = expanded?.takeIf { it in kinds }
    val primary = open ?: kinds.firstOrNull()
    val secondary = if (open == null) kinds.getOrNull(1) else null

    val lastMedia = rememberLast(media)
    val lastCall = rememberLast(ongoing)
    val lastTimer = rememberLast(timer)
    val lastSecondary = rememberLast(secondary)

    // Outside taps and inactivity fold the island back up.
    val taps = outsideTaps.intValue
    LaunchedEffect(taps) { if (taps > 0) expanded = null }
    LaunchedEffect(open, touchTick) {
        if (open != null) {
            delay(if (open == Kind.Media) 12_000 else 8_000)
            expanded = null
        }
    }

    // Notification peeks drip down for a few seconds, then merge back up.
    var peek by remember { mutableStateOf<NoteInfo?>(null) }
    LaunchedEffect(note?.key, note?.postedAt) {
        val n = note
        if (n == null) {
            peek = null
            return@LaunchedEffect
        }
        peek = n
        delay(5_000)
        peek = null
        delay(700)
        if (IslandRepository.note.value?.key == n.key) IslandRepository.note.value = null
    }

    val drip: Drip? = ringing?.let { Drip.Ring(it) } ?: peek?.let { Drip.Peek(it) }
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

    val timerLabel = timer?.let { timerText(it, now) } ?: ""
    val callLabel = ongoing?.let { callText(it, now) } ?: ""
    fun sideOf(k: Kind): Float = when (k) {
        Kind.Media -> px(44f)
        Kind.Call -> px(if (callLabel.length > 5) 80f else 66f)
        Kind.Timer -> px(if (timerLabel.length > 5) 80f else 66f)
    }
    fun budWidthOf(k: Kind): Float = when (k) {
        Kind.Media -> band
        Kind.Call -> px(if (callLabel.length > 5) 96f else 82f)
        Kind.Timer -> px(if (timerLabel.length > 5) 88f else 70f)
    }
    fun expandedHeight(k: Kind): Float = band + px(
        when (k) {
            Kind.Media -> 192f
            Kind.Call -> 138f
            Kind.Timer -> 132f
        },
    )
    fun centerFor(w: Float): Float {
        val margin = px(8f)
        return if (w >= sw - margin * 2f) sw / 2f else spot.cx.coerceIn(margin + w / 2f, sw - margin - w / 2f)
    }

    val mode = Mode(primary, open != null)
    val tW: Float
    val tH: Float
    val tTop: Float
    val tR: Float
    when {
        primary == null -> {
            tW = idleR * 2f; tH = idleR * 2f; tTop = spot.cy - idleR; tR = idleR
        }
        open != null -> {
            tW = cardW; tH = expandedHeight(open); tTop = top; tR = px(30f)
        }
        else -> {
            tW = camSlot + sideOf(primary) * 2f; tH = band; tTop = top; tR = band / 2f
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
    val dripW = cardW
    val dripH = px(84f)
    val dripP = remember { Animatable(0f) }
    LaunchedEffect(dripVisible) {
        if (dripVisible) {
            dripP.animateTo(1.5f, tween(260, easing = LinearOutSlowInEasing))
            dripP.animateTo(1f, Motion.flow())
        } else {
            dripP.animateTo(0f, Motion.flow())
        }
    }
    fun dripRect(m: Rect): Rect {
        val q = dripP.value.coerceAtLeast(0f)
        val qc = q.coerceAtMost(1f)
        // Falls as a drop first, then spreads into a card as it lands.
        val spread = qc * qc * qc
        val s = min(min(m.height, m.width), band)
        val w = lerpF(s, dripW, spread)
        val h = lerpF(s, dripH, spread)
        val t = lerpF(m.bottom - s, m.bottom + gap, q)
        val cx = lerpF(m.center.x, centerFor(dripW), qc)
        return Rect(cx - w / 2f, t, cx + w / 2f, t + h)
    }
    fun dripRadius(r: Rect) = lerpF(min(r.width, r.height) / 2f, px(28f), dripP.value.coerceIn(0f, 1f).let { it * it * it })
        .coerceAtMost(min(r.width, r.height) / 2f)
    val dripTarget = Rect(centerFor(dripW) - dripW / 2f, mainTarget.bottom + gap, centerFor(dripW) + dripW / 2f, mainTarget.bottom + gap + dripH)

    // ---- Touch area: only where the island is, so the rest of the screen stays usable ----

    val touchRect: android.graphics.Rect? = run {
        var u: Rect? = if (primary != null) mainTarget else null
        if (budOut) u = u?.let { unionOf(it, budTarget) } ?: budTarget
        if (dripVisible) u = u?.let { unionOf(it, dripTarget) } ?: unionOf(mainTarget, dripTarget)
        u?.let { android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.roundToInt(), it.bottom.roundToInt()) }
    }
    LaunchedEffect(touchRect) { bounds(touchRect) }

    // ---- The ink ----

    val goo = remember(dp) { if (Build.VERSION.SDK_INT >= 31) Goo.effect(px(7f)) else null }
    val budLatch = remember { NeckLatch() }
    val dripLatch = remember { NeckLatch() }

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
            if (dripP.value > 0.002f) {
                val d = dripRect(m)
                drawRoundRect(InkColors.ink, d.topLeft, d.size, CornerRadius(dripRadius(d)))
                neckDown(m, d, m.center.x, dripLatch, px(26f), px(2f), px(26f), InkColors.ink)
            }
        }

        // ---- Content on the ink, clipped to the ink as it moves ----

        Crossfade(targetState = mode, modifier = Modifier.fillMaxSize(), animationSpec = tween(220), label = "islandContent") { md ->
            val k = md.kind ?: return@Crossfade
            val w = if (md.expanded) cardW else camSlot + sideOf(k) * 2f
            val h = if (md.expanded) expandedHeight(k) else band
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
                        .pointerInput(md) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                touchTick++
                            }
                        }
                        .pointerInput(md) {
                            detectTapGestures(
                                onTap = { expanded = if (md.expanded) null else k },
                                onLongPress = { if (!md.expanded) expanded = k },
                            )
                        }
                        .pointerInput(md) {
                            var dy = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dy = 0f },
                                onDragEnd = {
                                    if (!md.expanded && dy > px(14f)) expanded = k
                                    if (md.expanded && dy < -px(24f)) expanded = null
                                },
                            ) { change, amount ->
                                change.consume()
                                dy += amount
                            }
                        },
                ) {
                    if (md.expanded) {
                        when (k) {
                            Kind.Media -> lastMedia?.let { ExpandedMedia(it, toDp(band)) }
                            Kind.Call -> lastCall?.let { ExpandedCall(it, now, toDp(band)) }
                            Kind.Timer -> lastTimer?.let { ExpandedTimer(it, now, toDp(band)) }
                        }
                    } else {
                        CompactContent(k, toDp(sideOf(k)), lastMedia, lastCall, lastTimer, now, toDp(band))
                    }
                }
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
                    BudContent(budKind, lastMedia, lastCall, lastTimer, now)
                }
            }
        }

        // The drip's content: an incoming call or a notification.
        val dripAlpha by animateFloatAsState(
            if (dripVisible) 1f else 0f,
            if (dripVisible) tween(240, delayMillis = 160) else tween(120),
            label = "dripAlpha",
        )
        val shownDrip = lastDrip
        if (shownDrip != null && dripAlpha > 0.01f) {
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
                            detectTapGestures {
                                when (shownDrip) {
                                    is Drip.Ring -> IslandRepository.send(shownDrip.call.open)
                                    is Drip.Peek -> {
                                        IslandRepository.send(shownDrip.note.open)
                                        peek = null
                                        IslandRepository.note.value = null
                                    }
                                }
                            }
                        }
                        .pointerInput(shownDrip.key) {
                            var dy = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dy = 0f },
                                onDragEnd = {
                                    if (dy < -px(20f) && shownDrip is Drip.Peek) {
                                        peek = null
                                        IslandRepository.dismissNote(shownDrip.note)
                                    }
                                },
                            ) { change, amount ->
                                change.consume()
                                dy += amount
                            }
                        },
                ) {
                    when (shownDrip) {
                        is Drip.Ring -> RingingDrip(shownDrip.call)
                        is Drip.Peek -> NoteDrip(shownDrip.note)
                    }
                }
            }
        }
    }
}

private fun unionOf(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
