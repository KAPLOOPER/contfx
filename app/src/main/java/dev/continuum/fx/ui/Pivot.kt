package dev.continuum.fx.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.island.ui.Goo
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.ui.theme.LocalPalette
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Where the pivot is. [slot] runs on continuously past the last page and round to the first
 * again, the way a Windows Phone pivot wraps; the page shown is [slot] modulo [count].
 */
@Stable
class PivotState(initial: Int, val count: Int) {
    /** Follows a swipe exactly, and springs to the page it settles on. */
    val slot = Animatable(initial.toFloat())

    /** The slot of the page on screen. */
    var contentSlot by mutableIntStateOf(initial)

    fun indexOf(s: Int): Int = PivotMath.indexOf(s, count)

    /** The slot nearest [from] that shows page [index] (forward when it's a tie). */
    fun nearest(index: Int, from: Int): Int = PivotMath.nearest(index, from, count)
}

/** The pivot's arithmetic: slots run on forever, pages repeat every [count] slots. */
internal object PivotMath {
    fun indexOf(slot: Int, count: Int): Int = Math.floorMod(slot, count)

    fun nearest(index: Int, from: Int, count: Int): Int {
        var best = from
        var bestD = Int.MAX_VALUE
        for (k in -count..count) {
            val s = from + k
            if (indexOf(s, count) == index && (abs(k) < bestD || (abs(k) == bestD && k > 0))) {
                best = s
                bestD = abs(k)
            }
        }
        return best
    }
}

/**
 * The page titles as a pivot, after Windows Phone: big lowercase titles in a row, the current one
 * against the left margin and the rest running off the edge, wrapping round. Swiping the page
 * slides them along. Continuum's part is the ink: a drop under the current title that, as you
 * swipe, stretches out to reach the next one on a neck that thins (and, swiped far enough,
 * snaps), its tail letting go of the title that's leaving, then gathers itself under the new one.
 */
@Composable
internal fun PivotHeader(
    titles: List<String>,
    pivot: PivotState,
    onSelect: (slot: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(fontFamily = Libron, fontSize = 42.sp, fontWeight = FontWeight.Normal) }
    val widths = remember(titles, density) { titles.map { measurer.measure(it, style).size.width.toFloat() } }
    val textH = remember(density) { measurer.measure("kd", style).size.height.toFloat() }
    val goo = remember(density) { if (Build.VERSION.SDK_INT >= 31) Goo.effect(with(density) { 3.5.dp.toPx() }) else null }
    fun px(dp: Float) = dp * density.density
    val gap = px(22f)
    val x0 = px(20f)

    Column(modifier) {
        // The app's name above, small and spaced out, as Windows Phone did; the drop is Continuum's.
        Row(Modifier.padding(start = 21.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(p.text))
            Spacer(Modifier.width(9.dp))
            BasicText(
                "CONTINUUM FX",
                style = TextStyle(color = p.sub, fontSize = 12.sp, fontFamily = Libron, fontWeight = FontWeight.Bold, letterSpacing = 2.6.sp),
            )
        }
        val seeds = LocalSeeds.current
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(with(density) { textH.toDp() } + 16.dp)
                .onGloballyPositioned { coords ->
                    // Where the drop rests, for ink cards on the page to grow out of.
                    val o = coords.positionInRoot()
                    val left = o.x + x0 + px(2f)
                    val top = o.y + textH - px(3f)
                    seeds?.drop = androidx.compose.ui.geometry.Rect(left, top, left + px(30f), top + px(9f))
                }
                .clipToBounds(),
        ) {
            val slot = pivot.slot.value
            val base = floor(slot).toInt()
            val f = slot - base
            fun w(s: Int) = widths[pivot.indexOf(s)]
            // A title's left edge: the current one sits on the margin, the rest follow it along.
            fun x(s: Int): Float {
                var x = x0 - f * (w(base) + gap)
                if (s >= base) for (j in base until s) x += w(j) + gap else for (j in s until base) x -= w(j) + gap
                return x
            }
            val right = constraints.maxWidth.toFloat()
            for (k in -1..titles.size) {
                val s = base + k
                val xs = x(s)
                if (xs > right || xs + w(s) < 0f) continue
                val dist = abs(s - slot)
                val color = if (dist <= 1f) lerp(p.text, p.sub, dist) else p.sub.copy(alpha = p.sub.alpha * (1.25f - dist * 0.25f).coerceIn(0.45f, 1f))
                key(s) {
                    val source = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .offset { IntOffset(xs.roundToInt(), 0) }
                            .semantics { selected = s == pivot.contentSlot }
                            .clickable(interactionSource = source, indication = null, role = Role.Tab) { onSelect(s) },
                    ) {
                        BasicText(titles[pivot.indexOf(s)], style = style.copy(color = color), maxLines = 1, softWrap = false)
                    }
                }
            }

            // The drop rests on the margin, under whichever title is current. Mid-swipe it reaches
            // both ways at once: its tail clinging to the title leaving, its head to the one
            // arriving, the middle pinching as it stretches. Then it gathers again on the margin.
            Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = goo }) {
                val m = x0 + px(2f)
                val dw = px(30f)
                val h = px(9f)
                val left = m - (m - (x(base) + px(2f))) * (1f - f) * REACH
                val rightEdge = m + dw + ((x(base + 1) + px(2f)) - m) * f * REACH
                drawDrop(p.text, left, rightEdge - left, textH - px(3f), dw, h)
            }
        }
    }
}

/** A drop [len] long from [left], full-bodied at both ends with a neck that thins between. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDrop(color: Color, left: Float, len: Float, y: Float, dw: Float, h: Float) {
    if (len <= dw * 1.05f) {
        drawRoundRect(color, Offset(left, y), Size(len, h), CornerRadius(h / 2f))
        return
    }
    // The ends keep their body; what's between is what's left of a drop dw long, spread thin.
    val end = minOf(dw * 0.75f, len * 0.42f)
    val neck = (h * (dw / len).pow(0.55f)).coerceAtLeast(h * 0.3f)
    drawRoundRect(color, Offset(left, y), Size(end, h), CornerRadius(h / 2f))
    drawRoundRect(color, Offset(left + len - end, y), Size(end, h), CornerRadius(h / 2f))
    drawRect(color, Offset(left + end * 0.5f, y + (h - neck) / 2f), Size(len - end, neck))
}

/** How far toward the neighbouring titles the drop reaches mid-swipe. */
private const val REACH = 0.38f
