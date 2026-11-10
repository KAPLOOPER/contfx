package dev.continuum.fx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.continuum.fx.ui.labelAt
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.ui.theme.Mark
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.island.ui.dropsLine
import androidx.compose.ui.text.drawText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import dev.continuum.fx.ui.Fmt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.audio.LiveAudio
import dev.continuum.fx.dsp.EqLimits
import dev.continuum.fx.dsp.SpectrumAnalyzer
import dev.continuum.fx.dsp.EqProfile
import dev.continuum.fx.dsp.EqResponse
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private const val POINTS = 220

private const val BANDS = 56

/**
 * Frequency response of the active profile, the centre of the Sound page: it runs edge to edge
 * ([inset] is the page margin its labels keep to). Each filter is a dot: drag it sideways for
 * frequency and up and down for gain, and pinch with two fingers to narrow or widen it (Q).
 * The touched filter's values sit right beside its dot. While processing, the music itself
 * moves behind the curve as a soft liquid spectrum.
 */
@Composable
fun EqGraph(
    profile: EqProfile,
    selected: Int,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, frequency: Double, gainDb: Double) -> Unit,
    modifier: Modifier = Modifier,
    onQ: (index: Int, q: Double) -> Unit = { _, _ -> },
    inset: Dp = 0.dp,
    live: Boolean = false,
    /** Held to compare: the curve drains flat, as the sound does. */
    comparing: Boolean = false,
    /** Off: nothing to drag, so the dots sink away and touches are left to the page. */
    editable: Boolean = true,
    /** Held on empty graph: true while you want to hear the music without the EQ. */
    onCompare: ((Boolean) -> Unit)? = null,
    /** How tall the instrument stands: the whole page's graph, or one page's hero. */
    height: Dp = 300.dp,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current

    // The live spectrum: analysed every other frame while sound is flowing, then let sink.
    val levels = remember { FloatArray(BANDS) }
    var spectrumFrame by remember { mutableIntStateOf(0) }
    DisposableEffect(live) {
        val release = if (live) LiveAudio.listen() else null
        onDispose { release?.invoke() }
    }
    LaunchedEffect(live) {
        var size = LiveAudio.fftSize
        var analyzer = SpectrumAnalyzer(fftSize = size, bands = BANDS)
        var samples = FloatArray(size)
        var last = 0L
        var odd = false
        while (true) {
            var resting = false
            withFrameNanos { t ->
                odd = !odd
                if (!odd) return@withFrameNanos
                val dt = if (last == 0L) 1f / 30f else ((t - last) / 1e9f).coerceIn(0.005f, 0.1f)
                last = t
                if (live) {
                    // The mode can change under a running graph (capture and Direct read differently).
                    if (LiveAudio.fftSize != size) {
                        size = LiveAudio.fftSize
                        analyzer = SpectrumAnalyzer(fftSize = size, bands = BANDS)
                        samples = FloatArray(size)
                    }
                    LiveAudio.tap.latest(samples)
                    analyzer.analyze(samples, levels, dt)
                } else {
                    analyzer.settle(levels, dt)
                    resting = levels.all { it < 0.002f }
                }
                spectrumFrame++
            }
            if (resting) break
        }
    }
    val curve = remember(profile) { EqResponse.curve(profile, POINTS) }
    val shape by animateFloatAsState(if (comparing) 0f else 1f, Motion.flow(), label = "compare")
    val range = remember(curve) {
        val peak = curve.maxOfOrNull { abs(it) } ?: 0.0
        max(12.0, ceil((peak + 2.0) / 6.0) * 6.0)
    }
    val latestProfile = rememberUpdatedState(profile)
    val latestRange = rememberUpdatedState(range)
    val latestSelect = rememberUpdatedState(onSelect)
    val latestMove = rememberUpdatedState(onMove)
    val latestQ = rememberUpdatedState(onQ)
    val latestSelected = rememberUpdatedState(selected)
    val latestEditable = rememberUpdatedState(editable)
    val latestCompare = rememberUpdatedState(onCompare)
    val dotsShown by animateFloatAsState(if (editable) 1f else 0f, Motion.flow(), label = "dots")
    // The filter under your finger: its values rise off its dot in a bubble of ink while you hold.
    var holding by remember { mutableIntStateOf(-1) }
    val lastHeld = remember { intArrayOf(-1) }
    if (holding >= 0) lastHeld[0] = holding
    val lift = remember { Animatable(0f) }
    LaunchedEffect(holding >= 0) {
        lift.animateTo(if (holding >= 0) 1f else 0f, if (holding >= 0) spring(dampingRatio = 0.55f, stiffness = 300f) else Motion.snap())
    }
    val measurer = rememberTextMeasurer()
    // And rows of tiny drops run out from it to the edges, its numbers on ink where they land.
    val guide by animateFloatAsState(if (holding >= 0) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "guide")

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val insetPx = with(density) { inset.toPx() }
        // Just enough for an end dot: the curve runs nearly to the screen's edges.
        val padX = with(density) { 12.dp.toPx() }
        val top = with(density) { 20.dp.toPx() }
        val bottom = with(density) { 32.dp.toPx() }
        val plotW = w - 2 * padX
        val plotH = h - top - bottom
        val hitRadius = with(density) { 40.dp.toPx() }

        fun xOf(f: Double): Float = (padX + (log10(f / EqLimits.MIN_FREQ) / 3.0) * plotW).toFloat()
        fun yOf(db: Double, r: Double): Float = (top + (1.0 - (db + r) / (2.0 * r)) * plotH).toFloat()
        fun freqOf(x: Float): Double = EqLimits.MIN_FREQ * 10.0.pow(((x - padX) / plotW).coerceIn(0f, 1f) * 3.0)
        fun dbOf(y: Float, r: Double): Double = (1.0 - ((y - top) / plotH).coerceIn(0f, 1f)) * 2.0 * r - r
        fun dotY(index: Int, prof: EqProfile, r: Double, k: Float = 1f): Float {
            val b = prof.bands[index]
            val db = if (b.type.usesGain) b.gainDb else EqResponse.gainAt(prof, b.frequency)
            return yOf(db.coerceIn(-r, r) * k, r)
        }
        fun nearest(pos: Offset): Int {
            val prof = latestProfile.value
            val r = latestRange.value
            var best = -1
            var bestDist = hitRadius
            prof.bands.indices.forEach { i ->
                val d = hypot(pos.x - xOf(prof.bands[i].frequency), pos.y - dotY(i, prof, r))
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            return best
        }

        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(w, h) {
                    // One finger on a dot drags it; two fingers pinch the touched (or chosen)
                    // filter's width; a touch that starts away from the dots is left to the page.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val target = if (latestEditable.value) nearest(down.position) else -1
                        // Away from the dots, a still hold compares: the EQ lets go while you hold.
                        val compare = latestCompare.value
                        if (target < 0 && compare != null) {
                            // 1: let go (a tap); 2: moved or a second finger (not a hold); null: held still.
                            val settled: Int? = withTimeoutOrNull(250L) {
                                var how = 0
                                while (how == 0) {
                                    val e = awaitPointerEvent()
                                    val hs = e.changes.filter { it.pressed }
                                    how = when {
                                        hs.isEmpty() -> 1
                                        hs.size >= 2 || e.changes.any { it.isConsumed } -> 2
                                        (hs[0].position - down.position).getDistance() > viewConfiguration.touchSlop -> 2
                                        else -> 0
                                    }
                                }
                                how
                            }
                            when (settled) {
                                null -> {
                                    compare(true)
                                    try {
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            e.changes.forEach { it.consume() }
                                            if (e.changes.none { it.pressed }) break
                                        }
                                    } finally {
                                        compare(false)
                                    }
                                    return@awaitEachGesture
                                }
                                1 -> {
                                    // A tap on empty graph lets go of the chosen dot.
                                    if (latestEditable.value) latestSelect.value(-1)
                                    return@awaitEachGesture
                                }
                                else -> Unit
                            }
                        }
                        var mode = 0 // 0 undecided, 1 dragging, 2 pinching
                        var moved = false
                        var band = -1
                        var q0 = 1.0
                        var d0 = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val held = event.changes.filter { it.pressed }
                            if (held.isEmpty()) break
                            if (held.size >= 2) {
                                if (!latestEditable.value) break
                                if (mode != 2) {
                                    band = if (target >= 0) target else latestSelected.value
                                    if (band < 0 || band >= latestProfile.value.bands.size) continue
                                    mode = 2
                                    holding = band
                                    q0 = latestProfile.value.bands[band].q
                                    d0 = (held[0].position - held[1].position).getDistance()
                                    latestSelect.value(band)
                                }
                                val d = (held[0].position - held[1].position).getDistance()
                                // Fingers apart widens the filter (lower Q); together narrows it.
                                if (d0 > 1f && d > 1f) latestQ.value(band, (q0 * d0 / d).coerceIn(EqLimits.MIN_Q, EqLimits.MAX_Q))
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (mode == 2) {
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val c = held[0]
                            if (!moved && (c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (target < 0) continue
                            if (mode == 0 && moved) {
                                mode = 1
                                holding = target
                                latestSelect.value(target)
                            }
                            if (mode == 1) {
                                c.consume()
                                latestMove.value(target, freqOf(c.position.x), dbOf(c.position.y, latestRange.value))
                            }
                        }
                        holding = -1
                        // A tap: choose the dot under it, or none.
                        if (mode == 0 && !moved && latestEditable.value) latestSelect.value(target)
                    }
                },
        ) {
            // The music, behind everything: a smooth liquid outline over the band levels.
            val tick = spectrumFrame
            if (tick >= 0 && levels.any { it > 0.003f }) {
                val floor = top + plotH
                val shape = Path()
                shape.moveTo(padX, floor)
                var px0 = padX
                var py0 = floor - levels[0] * plotH * 0.85f
                shape.lineTo(px0, py0)
                for (b in 1 until BANDS) {
                    val x = xOf(SpectrumAnalyzer.centre(b, BANDS))
                    val y = floor - levels[b] * plotH * 0.85f
                    shape.quadraticTo(px0, py0, (px0 + x) / 2f, (py0 + y) / 2f)
                    px0 = x
                    py0 = y
                }
                shape.lineTo(padX + plotW, py0)
                shape.lineTo(padX + plotW, floor)
                shape.close()
                drawPath(shape, p.surface)
            }

            val grid = with(density) { 1.dp.toPx() }
            listOf(100.0, 1000.0, 10000.0).forEach { f ->
                val x = xOf(f)
                drawLine(p.surface, Offset(x, top), Offset(x, top + plotH), strokeWidth = grid)
            }
            // Zero, as a line of drops.
            val zero = yOf(0.0, range)
            val dot = with(density) { 1.6.dp.toPx() }
            drawLine(
                p.quiet, Offset(padX, zero), Offset(padX + plotW, zero), strokeWidth = dot, cap = StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0f, with(density) { 6.dp.toPx() }), 0f),
            )

            val path = Path()
            for (i in 0 until POINTS) {
                val x = xOf(EqResponse.frequencyAt(i, POINTS))
                val y = yOf(curve[i].coerceIn(-range, range) * shape, range)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = p.text,
                style = Stroke(width = with(density) { 2.5.dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            profile.bands.forEachIndexed { i, b ->
                val c = Offset(xOf(b.frequency), dotY(i, profile, range, shape))
                val isSel = i == selected
                val radius = with(density) { (if (isSel) 11.dp else 7.dp).toPx() } * (0.4f + 0.6f * dotsShown)
                if (dotsShown > 0.01f) {
                    drawCircle(if (b.enabled) p.primary else p.sub, radius, c, alpha = dotsShown)
                    if (isSel) drawCircle(p.ground, radius * 0.42f, c, alpha = dotsShown)
                }
            }

            val held = lastHeld[0]
            val up = lift.value
            if (guide > 0.01f && held in profile.bands.indices) {
                val b = profile.bands[held]
                val c = Offset(xOf(b.frequency), dotY(held, profile, range, shape))
                val k = guide.coerceIn(0f, 1f)
                val ink = p.primary.copy(alpha = 0.75f)
                val gap = with(density) { 5.dp.toPx() }
                val dotR = with(density) { 1.1.dp.toPx() }
                val leftEnd = insetPx
                val bottomEnd = top + plotH + with(density) { 14.dp.toPx() }
                dropsLine(c, Offset(c.x - (c.x - leftEnd) * k, c.y), ink, gap, dotR)
                dropsLine(c, Offset(c.x, c.y + (bottomEnd - c.y) * k), ink, gap, dotR)
                val a = ((k - 0.6f) / 0.4f).coerceIn(0f, 1f)
                if (a > 0f) {
                    val tagStyle = TextStyle(color = p.onPrimary, fontSize = 9.5.sp, fontFamily = Mark, letterSpacing = 1.3.sp, fontFeatureSettings = "tnum, case")
                    val padX = with(density) { 7.dp.toPx() }
                    val tagH = with(density) { 16.dp.toPx() }
                    fun tag(text: String, x: Float, cy: Float, centred: Boolean) {
                        val t = measurer.measure(text.uppercase(), tagStyle)
                        val w = t.size.width + padX * 2
                        val left = if (centred) (x - w / 2f).coerceIn(insetPx, size.width - insetPx - w) else x
                        drawRoundRect(p.primary, Offset(left, cy - tagH / 2f), androidx.compose.ui.geometry.Size(w, tagH), androidx.compose.ui.geometry.CornerRadius(tagH / 2f), alpha = a)
                        drawText(t, topLeft = Offset(left + padX, cy - t.size.height / 2f), alpha = a)
                    }
                    if (b.type.usesGain) tag(Fmt.db(b.gainDb), leftEnd, c.y, centred = false)
                    tag(Fmt.freq(b.frequency), c.x, bottomEnd, centred = true)
                }
            }
            if (up > 0.02f && held in profile.bands.indices) {
                val b = profile.bands[held]
                val c = Offset(xOf(b.frequency), dotY(held, profile, range, shape))
                val words = buildString {
                    append(Fmt.freq(b.frequency))
                    if (b.type.usesGain) append("   ").append(Fmt.db(b.gainDb))
                    append("   Q ").append(Fmt.q(b.q))
                }
                val text = measurer.measure(words, TextStyle(color = p.onPrimary, fontSize = 13.sp, fontFamily = Libron))
                // Above the dot, unless there's no room: then it hangs below.
                val room = c.y - with(density) { 80.dp.toPx() }
                inkBubble(c, up, text, p.primary, insetPx, w - insetPx, below = room < 0f)
            }
        }

        val labelY = (top + plotH + with(density) { 6.dp.toPx() }).roundToInt()
        listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k").forEach { (f, label) ->
            // The scale is for the eye: TalkBack would only hear "100", "1k", "10k" out of context.
            Box(Modifier.labelAt(xOf(f) + 5f, labelY, w - insetPx).clearAndSetSemantics {}) {
                Note(label, p.sub)
            }
        }
        Box(Modifier.offset { IntOffset(insetPx.roundToInt(), (top - 2f).roundToInt()) }.clearAndSetSemantics {}) {
            Note("+${range.roundToInt()}", p.sub)
        }
        if (shape < 0.98f && editable) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = inset)
                    .graphicsLayer { alpha = (1f - shape).coerceIn(0f, 1f) },
            ) { Note("Original · matched loudness", p.text) }
        }
        Box(Modifier.offset { IntOffset(insetPx.roundToInt(), (top + plotH - with(density) { 13.dp.toPx() }).roundToInt()) }.clearAndSetSemantics {}) {
            Note("−${range.roundToInt()}", p.sub)
        }

        // The touched filter's values, right beside its dot, following it as it moves.
        if (editable && selected in profile.bands.indices) {
            val b = profile.bands[selected]
            val dx = xOf(b.frequency)
            val dy = dotY(selected, profile, range, shape)
            var readoutW by remember { mutableIntStateOf(0) }
            var readoutH by remember { mutableIntStateOf(0) }
            val gap = with(density) { 18.dp.toPx() }
            Row(
                Modifier
                    .graphicsLayer { alpha = (1f - lift.value * 1.6f).coerceIn(0f, 1f) }
                    .offset {
                        val x = (dx - readoutW / 2f).coerceIn(insetPx, w - insetPx - readoutW)
                        // Above the dot, unless that would leave the graph.
                        val above = dy - gap - readoutH
                        val y = if (above >= 0f) above else dy + gap
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
                    .onSizeChanged {
                        readoutW = it.width
                        readoutH = it.height
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Txt(Fmt.freq(b.frequency), 13.sp, p.text, maxLines = 1)
                if (b.type.usesGain) Txt(Fmt.db(b.gainDb), 13.sp, p.text, maxLines = 1)
                Txt("Q ${Fmt.q(b.q)}", 13.sp, p.sub, maxLines = 1)
            }
        }
    }
}
