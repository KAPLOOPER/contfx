package dev.continuum.fx.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import dev.continuum.fx.island.ui.glint

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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.island.ui.Goo
import dev.continuum.fx.island.ui.InkFeel
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
 * slides them along. Continuum's part is the ink: under the title, one small drop for each page,
 * the current page's drawn out into the ink pill. As you swipe, the pill stretches out to reach
 * the next drop on a neck that thins, its tail letting go of the one it's leaving, then gathers
 * itself there; from the last page round to the first it drains out of the end and gathers again
 * at the start. Tap a drop to go to its page.
 *
 * Read down a page and the pivot folds ([fold], 0 to 1): the titles and drops shrink towards the
 * margin to a header the size of a hero line and give the page their room, so only one thing is
 * ever set large. Back at the top, they open out again.
 */
@Composable
internal fun PivotHeader(
    titles: List<String>,
    pivot: PivotState,
    onSelect: (slot: Int) -> Unit,
    modifier: Modifier = Modifier,
    fold: () -> Float = { 0f },
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val view = LocalView.current
    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(fontFamily = Libron, fontSize = 42.sp, fontWeight = FontWeight.Normal) }
    val widths = remember(titles, density) { titles.map { measurer.measure(it, style).size.width.toFloat() } }
    val textH = remember(density) { measurer.measure("kd", style).size.height.toFloat() }
    val goo = remember(density) { if (Build.VERSION.SDK_INT >= 31) Goo.effect(with(density) { 3.5.dp.toPx() }) else null }
    fun px(dp: Float) = dp * density.density
    val gap = px(22f)
    val x0 = px(24f)

    // As a page arrives, its title's drop glints.
    val glintU = remember { Animatable(1f) }
    LaunchedEffect(pivot.contentSlot) {
        delay(80)
        snapshotFlow { pivot.slot.isRunning }.first { !it }
        glintU.snapTo(0f)
        glintU.animateTo(1f, tween(700, easing = LinearEasing))
    }

    // The page drops: where each sits, and the pill's size.
    val pillW = px(22f)
    val pillH = px(9f)
    val step = px(22f)
    fun dropX(i: Int) = x0 + px(2f) + pillW / 2f + i * step
    val dropY = textH - px(3f)
    val latestFold = androidx.compose.runtime.rememberUpdatedState(fold)
    val fullH = with(density) { textH.toDp() } + 22.dp

    // Folded, the header gives back the height it no longer needs (read in layout, so a fold
    // doesn't recompose anything).
    Column(
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val k = 1f - FOLD * latestFold.value().coerceIn(0f, 1f)
            val h = (fullH.toPx() * k).roundToInt()
            layout(placeable.width, h) { placeable.place(0, 0) }
        },
    ) {
        val seeds = LocalSeeds.current
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(fullH)
                .graphicsLayer {
                    // Folding: titles and drops shrink towards the margin's top.
                    val k = 1f - FOLD * latestFold.value().coerceIn(0f, 1f)
                    scaleX = k
                    scaleY = k
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin((x0 / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f), 0f)
                }
                .onGloballyPositioned { coords ->
                    // Where the pill rests, for ink cards on the page to grow out of.
                    val o = coords.positionInRoot()
                    val left = o.x + dropX(pivot.indexOf(pivot.contentSlot)) - pillW / 2f
                    val top = o.y + dropY
                    seeds?.drop = androidx.compose.ui.geometry.Rect(left, top, left + pillW, top + pillH)
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
            // The current title and those after it: nothing hangs off the left edge.
            for (k in 0..titles.size) {
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
                            .clickable(interactionSource = source, indication = null, role = Role.Tab) {
                                // A drop squeezes out of the line for the page you chose.
                                if (s != pivot.contentSlot) InkFeel.split(view)
                                onSelect(s)
                            },
                    ) {
                        BasicText(titles[pivot.indexOf(s)], style = style.copy(color = color), maxLines = 1, softWrap = false)
                    }
                }
            }

            // A drop for every page, quiet.
            Canvas(Modifier.matchParentSize()) {
                for (i in titles.indices) drawCircle(p.quiet, px(3f), Offset(dropX(i), dropY + pillH / 2f))
            }
            // The pill, on the current page's drop. Mid-swipe it reaches both ways at once: its
            // tail clinging to the drop it's leaving, its head to the one arriving, the middle
            // pinching as it stretches. Round from the last page to the first, it drains out of
            // the end and gathers at the start.
            Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = goo }) {
                val bi = pivot.indexOf(base)
                val ni = pivot.indexOf(base + 1)
                if (ni == bi + 1) {
                    val head = (f * 1.6f).coerceIn(0f, 1f)
                    val tail = ((f - 0.375f) * 1.6f).coerceIn(0f, 1f)
                    val span = dropX(ni) - dropX(bi)
                    val left = dropX(bi) - pillW / 2f + span * tail
                    val rightEdge = dropX(bi) + pillW / 2f + span * head
                    drawDrop(p.text, left, rightEdge - left, dropY, pillW, pillH)
                } else {
                    fun part(i: Int, a: Float) {
                        if (a < 0.02f) return
                        val w = pillW * a
                        val hh = pillH * minOf(1f, 0.4f + a)
                        drawRoundRect(p.text, Offset(dropX(i) - w / 2f, dropY + (pillH - hh) / 2f), Size(w, hh), CornerRadius(hh / 2f))
                    }
                    part(bi, 1f - f)
                    part(ni, f)
                }
            }
            Canvas(Modifier.matchParentSize()) {
                val cur = pivot.indexOf(pivot.contentSlot)
                glint(Offset(dropX(cur) + pillW / 2f, dropY + pillH / 2f), px(13f), glintU.value, p.text)
            }
            // Each drop takes you to its page.
            titles.forEachIndexed { i, title ->
                val source = remember(i) { MutableInteractionSource() }
                val current = pivot.indexOf(pivot.contentSlot) == i
                Box(
                    Modifier
                        .offset { IntOffset((dropX(i) - step / 2f).roundToInt(), (dropY + pillH / 2f - px(18f)).roundToInt()) }
                        .size(with(density) { step.toDp() }, 36.dp)
                        .semantics {
                            selected = current
                            contentDescription = if (current) "$title, this page" else "Go to $title"
                        }
                        .clickable(interactionSource = source, indication = null, role = Role.Tab) {
                            if (!current) InkFeel.split(view)
                            onSelect(pivot.nearest(i, pivot.contentSlot))
                        },
                )
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

/** How much the pivot gives up, folded: 42sp titles come down to 26sp, a hero line's size. */
private const val FOLD = 0.38f
