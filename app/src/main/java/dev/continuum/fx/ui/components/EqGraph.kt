package dev.continuum.fx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.dsp.EqLimits
import dev.continuum.fx.dsp.EqProfile
import dev.continuum.fx.dsp.EqResponse
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Txt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private const val POINTS = 220

/**
 * Frequency response of the active profile. Each filter is a dot you can drag:
 * sideways changes its frequency, up and down changes its gain.
 */
@Composable
fun EqGraph(
    profile: EqProfile,
    selected: Int,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, frequency: Double, gainDb: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val curve = remember(profile) { EqResponse.curve(profile, POINTS) }
    val range = remember(curve) {
        val peak = curve.maxOfOrNull { abs(it) } ?: 0.0
        max(12.0, ceil((peak + 2.0) / 6.0) * 6.0)
    }
    val latestProfile = rememberUpdatedState(profile)
    val latestRange = rememberUpdatedState(range)
    val latestSelect = rememberUpdatedState(onSelect)
    val latestMove = rememberUpdatedState(onMove)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(p.surface),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val padX = with(density) { 18.dp.toPx() }
        val top = with(density) { 20.dp.toPx() }
        val bottom = with(density) { 32.dp.toPx() }
        val plotW = w - 2 * padX
        val plotH = h - top - bottom
        val hitRadius = with(density) { 40.dp.toPx() }

        fun xOf(f: Double): Float = (padX + (log10(f / EqLimits.MIN_FREQ) / 3.0) * plotW).toFloat()
        fun yOf(db: Double, r: Double): Float = (top + (1.0 - (db + r) / (2.0 * r)) * plotH).toFloat()
        fun freqOf(x: Float): Double = EqLimits.MIN_FREQ * 10.0.pow(((x - padX) / plotW).coerceIn(0f, 1f) * 3.0)
        fun dbOf(y: Float, r: Double): Double = (1.0 - ((y - top) / plotH).coerceIn(0f, 1f)) * 2.0 * r - r
        fun dotY(index: Int, prof: EqProfile, r: Double): Float {
            val b = prof.bands[index]
            val db = if (b.type.usesGain) b.gainDb else EqResponse.gainAt(prof, b.frequency)
            return yOf(db.coerceIn(-r, r), r)
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
                    var dragging = -1
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragging = nearest(pos)
                            if (dragging >= 0) latestSelect.value(dragging)
                        },
                        onDragEnd = { dragging = -1 },
                        onDragCancel = { dragging = -1 },
                    ) { change, _ ->
                        if (dragging >= 0) {
                            change.consume()
                            latestMove.value(dragging, freqOf(change.position.x), dbOf(change.position.y, latestRange.value))
                        }
                    }
                }
                .pointerInput(w, h) {
                    detectTapGestures { pos -> latestSelect.value(nearest(pos)) }
                },
        ) {
            val grid = with(density) { 1.dp.toPx() }
            listOf(100.0, 1000.0, 10000.0).forEach { f ->
                val x = xOf(f)
                drawLine(p.line, Offset(x, top), Offset(x, top + plotH), strokeWidth = grid)
            }
            val zero = yOf(0.0, range)
            drawLine(p.line, Offset(padX, zero), Offset(padX + plotW, zero), strokeWidth = grid * 1.5f)

            val path = Path()
            for (i in 0 until POINTS) {
                val x = xOf(EqResponse.frequencyAt(i, POINTS))
                val y = yOf(curve[i].coerceIn(-range, range), range)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = p.text,
                style = Stroke(width = with(density) { 2.5.dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            profile.bands.forEachIndexed { i, b ->
                val c = Offset(xOf(b.frequency), dotY(i, profile, range))
                val isSel = i == selected
                val radius = with(density) { (if (isSel) 11.dp else 7.dp).toPx() }
                drawCircle(if (b.enabled) p.text else p.sub, radius, c)
                if (isSel) drawCircle(p.surface, radius * 0.42f, c)
            }
        }

        val labelY = (top + plotH + with(density) { 6.dp.toPx() }).roundToInt()
        listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k").forEach { (f, label) ->
            Box(Modifier.offset { IntOffset((xOf(f) + 5f).roundToInt(), labelY) }) {
                Txt(label, 12.sp, p.sub)
            }
        }
        Box(Modifier.offset { IntOffset((padX + 4f).roundToInt(), (top - 2f).roundToInt()) }) {
            Txt("+${range.roundToInt()}", 12.sp, p.sub)
        }
        Box(Modifier.offset { IntOffset((padX + 4f).roundToInt(), (top + plotH - with(density) { 16.dp.toPx() }).roundToInt()) }) {
            Txt("−${range.roundToInt()}", 12.sp, p.sub)
        }
    }
}
