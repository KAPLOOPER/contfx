package dev.continuum.fx.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.lab.Lab
import dev.continuum.fx.ui.Fmt
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Palette
import dev.continuum.fx.ui.theme.Txt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a curve is drawn. Colour means one thing each in Continuum, so the Lab's curves are told
 * apart by their ink: black or grey, solid, dashed or dotted, and the result of a fit (or the
 * studio's target) in heavy ink with the ground around it.
 */
internal class Ink(val tone: Int, val dash: FloatArray?, val width: Float, val halo: Boolean = false) {
    fun color(p: Palette): Color = when (tone) {
        0 -> p.text
        1 -> p.sub
        else -> p.quiet
    }

    fun dashed(dash: FloatArray?) = Ink(tone, dash, width, halo)

    companion object {
        private val DASH = floatArrayOf(7f, 4f)
        private val DOT = floatArrayOf(0.1f, 4.2f)
        val SHORT = floatArrayOf(3f, 3f)

        /** One ink for each place on the graph. */
        val SLOTS = listOf(
            Ink(0, null, 2.2f), Ink(0, DASH, 2.0f), Ink(1, null, 2.2f),
            Ink(0, DOT, 2.6f), Ink(1, DASH, 2.0f), Ink(1, DOT, 2.6f),
        )
        val RESULT = Ink(0, null, 3f, halo = true)
        val BASE = Ink(2, floatArrayOf(6f, 4f), 1.6f)
    }
}

/** One curve on the graph. */
internal class LabLine(val id: String, val label: String, val y: DoubleArray, val ink: Ink)

/** The Lab's y scale: room around the curves, in 5 dB steps; or a fixed span. */
private fun scale(lines: List<LabLine>, yRange: String, relative: Boolean): Pair<Double, Double> {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    for (c in lines) for (i in 0 until Lab.N) {
        if (Lab.F[i] > 16000) break
        lo = min(lo, c.y[i])
        hi = max(hi, c.y[i])
    }
    if (!lo.isFinite()) { lo = -15.0; hi = 15.0 }
    return if (yRange == "auto") {
        val top = ceil((hi + 2) / 5) * 5
        var bottom = floor((lo - 2) / 5) * 5
        if (top - bottom < 20) bottom = top - 20
        top to bottom
    } else {
        val span = yRange.toDouble()
        val top = ceil((min(hi, if (relative) 10.0 else 14.0) + 2) / 5) * 5
        top to top - span
    }
}

private fun DrawScope.stroke(ink: Ink, density: Float, cap: StrokeCap = StrokeCap.Round): Stroke = Stroke(
    width = ink.width * density,
    cap = if (ink.dash != null && ink.dash[0] < 1f) StrokeCap.Round else cap,
    join = StrokeJoin.Round,
    pathEffect = ink.dash?.let { PathEffect.dashPathEffect(floatArrayOf(it[0] * density, it[1] * density), 0f) },
)

/**
 * The frequency response graph, edge to edge ([inset] is the page margin its labels keep to).
 * Tap or drag across it to read every curve at one frequency: the values appear in the legend.
 */
@Composable
internal fun LabGraph(
    lines: List<LabLine>,
    yRange: String,
    relative: Boolean,
    balance: Boolean,
    scrubHz: Double?,
    onScrub: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    inset: Dp = 24.dp,
    height: Dp = 300.dp,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val (top, bottom) = remember(lines, yRange, relative) { scale(lines, yRange, relative) }
    val insetPx = with(density) { inset.toPx() }
    val padTop = with(density) { 16.dp.toPx() }
    val padBottom = with(density) { 22.dp.toPx() }
    val lx0 = log10(20.0)
    val lx1 = log10(20000.0)
    val scrub = androidx.compose.runtime.rememberUpdatedState(onScrub)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { o ->
                        val w = size.width - 2 * insetPx
                        val t = ((o.x - insetPx) / w).coerceIn(0f, 1f)
                        scrub.value(10.0.pow(lx0 + t * (lx1 - lx0)))
                    })
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val w = size.width - 2 * insetPx
                        val t = ((change.position.x - insetPx) / w).coerceIn(0f, 1f)
                        scrub.value(10.0.pow(lx0 + t * (lx1 - lx0)))
                    }
                },
        ) {
            val dp = density.density
            val w = size.width - 2 * insetPx
            val plotH = size.height - padTop - padBottom
            fun x(f: Double) = (insetPx + (log10(f) - lx0) / (lx1 - lx0) * w).toFloat()
            fun y(v: Double) = (padTop + (top - v) / (top - bottom) * plotH).toFloat()
            val plot = Rect(insetPx, padTop, insetPx + w, padTop + plotH)
            // Above 10 kHz a 711 coupler is a guide only: the band says so quietly.
            drawRect(p.surface.copy(alpha = 0.55f), Offset(x(10000.0), plot.top), Size(plot.right - x(10000.0), plot.height))
            if (balance) drawRect(p.surface, Offset(plot.left, y(1.0)), Size(w, y(-1.0) - y(1.0)))
            val grid = 1f * dp
            for (f in listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)) {
                drawLine(p.surface, Offset(x(f), plot.top), Offset(x(f), plot.bottom), strokeWidth = grid)
            }
            val step = if (top - bottom > 40) 10.0 else 5.0
            var v = ceil(bottom / step) * step
            while (v <= top + 1e-9) {
                if (v != 0.0) drawLine(p.surface, Offset(plot.left, y(v)), Offset(plot.right, y(v)), strokeWidth = grid)
                v += step
            }
            // Zero, as a line of drops.
            if (0.0 in bottom..top) {
                drawLine(
                    p.quiet, Offset(plot.left, y(0.0)), Offset(plot.right, y(0.0)), strokeWidth = 1.6f * dp, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0f, 6f * dp), 0f),
                )
            }
            clipRect(plot.left - 2 * dp, plot.top - 2 * dp, plot.right + 2 * dp, plot.bottom + 2 * dp) {
                for (c in lines) {
                    val path = Path()
                    for (i in 0 until Lab.N) {
                        val px = x(Lab.F[i])
                        val py = y(c.y[i]).coerceIn(plot.top - 40 * dp, plot.bottom + 40 * dp)
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    if (c.ink.halo) drawPath(path, p.ground, style = Stroke(width = (c.ink.width + 3f) * dp, join = StrokeJoin.Round))
                    drawPath(path, c.ink.color(p), style = stroke(c.ink, dp))
                }
            }
            // The reading: a hairline and a drop on every curve.
            if (scrubHz != null) {
                val sx = x(scrubHz)
                drawLine(p.text.copy(alpha = 0.55f), Offset(sx, plot.top), Offset(sx, plot.bottom), strokeWidth = 1f * dp)
                for (c in lines) {
                    val cy = y(Lab.at(c.y, scrubHz).coerceIn(bottom, top))
                    drawCircle(p.ground, 5f * dp, Offset(sx, cy))
                    drawCircle(c.ink.color(p), 3.5f * dp, Offset(sx, cy))
                }
            }
        }
        // The scale's ends and the decades, in the margin's small capitals.
        val plotTopPx = padTop
        Box(Modifier.offset { IntOffset(insetPx.roundToInt(), (plotTopPx - with(density) { 14.dp.toPx() }).roundToInt()) }) {
            Note(signed(top), p.sub)
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = inset, bottom = 22.dp)) { Note(signed(bottom), p.sub) }
        // The decades, just right of their lines, as on the Sound page's graph.
        val labelY = (height.value * density.density - with(density) { 16.dp.toPx() }).roundToInt()
        for ((f, label) in listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")) {
            val gx = insetPx + ((log10(f) - lx0) / (lx1 - lx0) * (widthPx - 2 * insetPx)).toFloat()
            Box(Modifier.offset { IntOffset((gx + 4f * density.density).roundToInt(), labelY) }) { Note(label, p.sub) }
        }
    }
}

private fun signed(v: Double): String = when {
    v > 0 -> "+${v.roundToInt()}"
    v < 0 -> "−${(-v).roundToInt()}"
    else -> "0"
}

/** A short sample of a curve's ink, for the legend. */
@Composable
internal fun InkSample(ink: Ink, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Canvas(modifier.size(width = 26.dp, height = 12.dp)) {
        val dp = density
        val cy = size.height / 2f
        if (ink.halo) drawLine(p.ground, Offset(0f, cy), Offset(size.width, cy), strokeWidth = (ink.width + 3f) * dp)
        drawLine(
            ink.color(p), Offset(1.5f * dp, cy), Offset(size.width - 1.5f * dp, cy), strokeWidth = ink.width * dp, cap = StrokeCap.Round,
            pathEffect = ink.dash?.let { PathEffect.dashPathEffect(floatArrayOf(it[0] * dp, it[1] * dp), 0f) },
        )
    }
}

/**
 * The curves, each with its ink; while you read the graph, the frequency heads it and each curve
 * says its level there. Tap the frequency to put the reading away.
 */
@Composable
internal fun LabLegend(lines: List<LabLine>, scrubHz: Double?, onClear: () -> Unit, notes: List<String>) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (scrubHz != null) {
            val source = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Stop reading", onClick = onClear),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Note("at ${Fmt.freq(scrubHz)}", p.text)
                Spacer(Modifier.weight(1f))
                Note("done", p.quiet)
            }
        }
        val rows = if (scrubHz == null) lines else lines.sortedByDescending { Lab.at(it.y, scrubHz) }
        for (c in rows) {
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                InkSample(c.ink)
                Spacer(Modifier.width(12.dp))
                Txt(c.label, 14.sp, if (c.ink.tone == 0) p.text else p.sub, maxLines = 1, modifier = Modifier.weight(1f))
                if (scrubHz != null) {
                    Spacer(Modifier.width(10.dp))
                    Txt(Fmt.db(Lab.at(c.y, scrubHz)), 14.sp, p.text, maxLines = 1)
                }
            }
        }
        for (n in notes) Txt(n, 13.sp, p.sub, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A fit's error from the target, before (grey) and after (ink), ±10 dB, with ±1 dB shaded. */
@Composable
internal fun ResidualGraph(before: DoubleArray, after: DoubleArray, modifier: Modifier = Modifier, inset: Dp = 24.dp) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val insetPx = with(density) { inset.toPx() }
    Box(modifier.fillMaxWidth().height(168.dp).clipToBounds()) {
        Canvas(Modifier.fillMaxWidth().height(168.dp)) {
            val dp = density.density
            val w = size.width - 2 * insetPx
            val padT = 12f * dp
            val padB = 18f * dp
            val h = size.height - padT - padB
            fun x(f: Double) = (insetPx + log10(f / 20) / 3 * w).toFloat()
            fun y(v: Double) = (padT + (10 - v.coerceIn(-10.0, 10.0)) / 20 * h).toFloat()
            drawRect(p.surface.copy(alpha = 0.55f), Offset(x(10000.0), padT), Size(insetPx + w - x(10000.0), h))
            drawRect(p.surface, Offset(insetPx, y(1.0)), Size(w, y(-1.0) - y(1.0)))
            for (v in listOf(-10.0, -5.0, 5.0, 10.0)) drawLine(p.surface, Offset(insetPx, y(v)), Offset(insetPx + w, y(v)), strokeWidth = dp)
            drawLine(
                p.quiet, Offset(insetPx, y(0.0)), Offset(insetPx + w, y(0.0)), strokeWidth = 1.6f * dp, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0f, 6f * dp), 0f),
            )
            fun path(v: DoubleArray) = Path().apply {
                for (i in 0 until Lab.N) {
                    val px = x(Lab.F[i])
                    val py = y(v[i])
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(path(before), p.sub, style = Stroke(1.6f * dp, join = StrokeJoin.Round))
            drawPath(path(after), p.text, style = Stroke(2.4f * dp, join = StrokeJoin.Round, cap = StrokeCap.Round))
        }
        Box(Modifier.padding(start = inset)) { Note("+10", p.sub) }
        Box(Modifier.align(Alignment.BottomStart).padding(start = inset)) { Note("−10", p.sub) }
    }
}
