package dev.continuum.fx.ui.components

import androidx.compose.ui.unit.Dp
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.continuum.fx.island.ui.InkFeel
import dev.continuum.fx.ui.bleed
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The ink that builds the page. One ink (black on the light page, white on the dark one), and it
 * only ever separates and merges:
 *  - A choice ([ChoiceRow], [Chip]): the chosen word sits on a pill of ink. Pick another and the
 *    pill's leading edge runs ahead while its trailing edge lags, so it stretches across like
 *    liquid, leaving a drop behind that pinches off and shrinks away.
 *  - Opening ([InkSection]): no panel. A drop in the margin beside the row drips a thread down
 *    the margin; the controls appear as it passes, and each slider grows out of it as a branch
 *    ending in a drop. Closing draws it all back up into the drop.
 *  - A value ([InkSlider]): hold a slider's drop and a bubble of ink rises off it on a neck, with
 *    the number in it; let go and it sinks back in.
 */

private const val HALF_PI = (PI / 2).toFloat()

/**
 * The neck between two drops of ink pulling apart (the metaball outline): null once they're too
 * far apart for one, or when one holds the other. [spread] is how far round each drop the neck
 * takes hold; [reach] how far, in the second drop's radii, the neck stretches before it snaps.
 */
internal fun inkNeck(c1: Offset, r1: Float, c2: Offset, r2: Float, spread: Float = 0.5f, reach: Float = 2.5f): Path? {
    val d = (c2 - c1).getDistance()
    if (r1 <= 0f || r2 <= 0f || d > r1 + r2 * reach || d <= abs(r1 - r2)) return null
    val pi = PI.toFloat()
    val u1: Float
    val u2: Float
    if (d < r1 + r2) {
        u1 = acos(((r1 * r1 + d * d - r2 * r2) / (2 * r1 * d)).coerceIn(-1f, 1f))
        u2 = acos(((r2 * r2 + d * d - r1 * r1) / (2 * r2 * d)).coerceIn(-1f, 1f))
    } else {
        u1 = 0f
        u2 = 0f
    }
    val between = atan2(c2.y - c1.y, c2.x - c1.x)
    val most = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
    val a1 = between + u1 + (most - u1) * spread
    val a2 = between - u1 - (most - u1) * spread
    val a3 = between + pi - u2 - (pi - u2 - most) * spread
    val a4 = between - pi + u2 + (pi - u2 - most) * spread
    fun at(c: Offset, a: Float, r: Float) = Offset(c.x + r * cos(a), c.y + r * sin(a))
    val p1 = at(c1, a1, r1)
    val p2 = at(c1, a2, r1)
    val p3 = at(c2, a3, r2)
    val p4 = at(c2, a4, r2)
    val total = r1 + r2
    val k = min(spread * 2.4f, (p1 - p3).getDistance() / total) * min(1f, d * 2f / total)
    val h1 = at(p1, a1 - HALF_PI, r1 * k)
    val h2 = at(p2, a2 + HALF_PI, r1 * k)
    val h3 = at(p3, a3 + HALF_PI, r2 * k)
    val h4 = at(p4, a4 - HALF_PI, r2 * k)
    return Path().apply {
        moveTo(p1.x, p1.y)
        cubicTo(h1.x, h1.y, h3.x, h3.y, p3.x, p3.y)
        lineTo(p4.x, p4.y)
        cubicTo(h4.x, h4.y, h2.x, h2.y, p2.x, p2.y)
        close()
    }
}

// --- A value ------------------------------------------------------------------------------

/**
 * A bubble of ink risen [b] (0 resting in its drop, 1 up, a little past while it bounces) off a
 * drop at [from], on a neck that's narrowest in the middle and thins as it rises, with [text] in
 * it once it's up. It keeps between [minX] and [maxX]; [below] hangs it under the drop instead.
 */
internal fun DrawScope.inkBubble(
    from: Offset,
    b: Float,
    text: TextLayoutResult?,
    ink: Color,
    minX: Float,
    maxX: Float,
    below: Boolean = false,
) {
    if (b <= 0.02f) return
    val s = min(1.08f, b)
    val hgt = 32.dp.toPx() * s
    val wid = max(hgt, ((text?.size?.width ?: 0) + 24.dp.toPx()) * s)
    val dir = if (below) 1f else -1f
    // The bubble's edge nearest the drop, and its middle across.
    val near = from.y + dir * (12.dp.toPx() + 30.dp.toPx() * b)
    val top = if (below) near else near - hgt
    val bx = if (maxX - minX <= wid) (minX + maxX) / 2f else from.x.coerceIn(minX + wid / 2f, maxX - wid / 2f)
    if (b > 0.04f) {
        val nx = from.x.coerceIn(bx - wid / 2f + hgt * 0.45f, bx + wid / 2f - hgt * 0.45f)
        val waist = 1.2.dp.toPx() + 2.5.dp.toPx() * (1f - min(1f, b))
        val mid = (from.y + near) / 2f
        val mx = (from.x + nx) / 2f
        val base = 6.dp.toPx()
        val head = 11.dp.toPx() * s
        val into = near + dir * hgt * 0.3f
        val neck = Path().apply {
            moveTo(from.x - base, from.y)
            quadraticTo(mx - waist, mid, nx - head, into)
            lineTo(nx + head, into)
            quadraticTo(mx + waist, mid, from.x + base, from.y)
            close()
        }
        drawPath(neck, ink)
    }
    drawRoundRect(ink, Offset(bx - wid / 2f, top), Size(wid, hgt), CornerRadius(hgt / 2f))
    if (text != null && b > 0.55f) {
        drawText(
            text,
            topLeft = Offset(bx - text.size.width / 2f, top + (hgt - text.size.height) / 2f),
            alpha = ((b - 0.55f) / 0.35f).coerceIn(0f, 1f),
        )
    }
}

// --- A choice -------------------------------------------------------------------------------

internal class ChoiceEntry {
    var selected = false
    var coords: LayoutCoordinates? = null
}

/** The choices in one row, and where the chosen one is, so the ink can go to it. */
internal class ChoiceGroup {
    var coords: LayoutCoordinates? = null
    val entries = mutableListOf<ChoiceEntry>()
    var target by mutableStateOf<Pair<ChoiceEntry, Rect>?>(null)

    fun refresh() {
        val g = coords?.takeIf { it.isAttached } ?: return
        val chosen = entries.firstOrNull { it.selected }
        if (chosen == null) {
            if (target != null) target = null
            return
        }
        val c = chosen.coords?.takeIf { it.isAttached } ?: return
        val r = g.localBoundingBoxOf(c, clipBounds = false)
        val t = target
        if (t == null || t.first !== chosen || t.second != r) target = chosen to r
    }
}

internal val LocalChoiceGroup = compositionLocalOf<ChoiceGroup?> { null }

/** Marks this as one of the row's choices: the ink goes to it while it's [selected]. */
@Composable
internal fun Modifier.choice(selected: Boolean): Modifier {
    val group = LocalChoiceGroup.current ?: return this
    val entry = remember { ChoiceEntry() }
    DisposableEffect(group) {
        group.entries += entry
        onDispose {
            group.entries -= entry
            group.refresh()
        }
    }
    SideEffect {
        entry.selected = selected
        group.refresh()
    }
    return this.onPlaced {
        entry.coords = it
        group.refresh()
    }
}

/** The pill's leading edge runs on Snap; the trailing edge is slower, so the ink stretches. */
private val LEAD = spring<Float>(dampingRatio = 0.843f, stiffness = 620f)
private val TRAIL = spring<Float>(dampingRatio = 0.72f, stiffness = 130f)
private val TAIL = spring<Float>(dampingRatio = 0.9f, stiffness = 260f)
private val SWELL_PILL = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)

/**
 * A row of choices sharing one pill of ink. The chosen word is knocked out of the ink, so where
 * the ink covers a word it takes the page's own colour, exactly as far as the ink reaches.
 * [scroll] lets a long row scroll sideways. A choice on its own ([Chip] outside a row) is a row
 * of one: its pill swells in and out.
 */
@Composable
fun ChoiceRow(
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    on: Surface = Surface.Ground,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalPalette.current
    val ink = if (on == Surface.Ink) p.onInk else p.primary
    val group = remember { ChoiceGroup() }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    val shown = remember { Animatable(0f) }
    val tail = remember { Animatable(0f) }
    // Read while drawing; the animations above are what redraw it.
    val tailAt = remember { FloatArray(3) }
    val last = remember { arrayOfNulls<ChoiceEntry>(1) }
    val born = remember { SystemClock.uptimeMillis() }
    val target = group.target
    LaunchedEffect(target) {
        val (entry, r) = target ?: run {
            shown.animateTo(0f, Motion.snap())
            return@LaunchedEffect
        }
        tailAt[2] = r.center.y
        val fresh = SystemClock.uptimeMillis() - born < 500
        when {
            // As the page opens, the ink is simply where it should be.
            shown.value < 0.02f && fresh -> {
                left.snapTo(r.left)
                right.snapTo(r.right)
                shown.snapTo(1f)
            }
            // Chosen from nothing: it swells out from under the word.
            shown.value < 0.02f -> {
                left.snapTo(r.center.x)
                right.snapTo(r.center.x)
                shown.snapTo(1f)
                launch { left.animateTo(r.left, SWELL_PILL) }
                launch { right.animateTo(r.right, SWELL_PILL) }
            }
            // The same word, grown or shrunk.
            entry === last[0] -> {
                launch { left.animateTo(r.left, Motion.snap()) }
                launch { right.animateTo(r.right, Motion.snap()) }
                if (shown.value < 1f) launch { shown.animateTo(1f, Motion.snap()) }
            }
            // Another word: the ink runs to it, and a drop of it stays behind a moment.
            else -> {
                val toRight = r.center.x > (left.value + right.value) / 2f
                val h = r.height
                tailAt[0] = if (toRight) left.value + h * 0.45f else right.value - h * 0.45f
                tailAt[1] = r.center.y
                launch {
                    tail.snapTo(1f)
                    tail.animateTo(0f, TAIL)
                }
                launch { left.animateTo(r.left, if (toRight) TRAIL else LEAD) }
                launch { right.animateTo(r.right, if (toRight) LEAD else TRAIL) }
                if (shown.value < 1f) launch { shown.animateTo(1f, Motion.snap()) }
            }
        }
        last[0] = entry
    }
    CompositionLocalProvider(LocalChoiceGroup provides group) {
        Row(
            modifier
                .then(if (scroll) Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()) else Modifier)
                .onPlaced {
                    group.coords = it
                    group.refresh()
                }
                // Its own layer, so the ink can knock the chosen word out of itself.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val s = shown.value
                    val tv = tail.value
                    if (s <= 0.01f && tv <= 0.01f) return@drawWithContent
                    val cy = tailAt[2].takeIf { it > 0f } ?: (size.height / 2f)
                    val h = PILL.toPx()
                    var l = left.value
                    var r = right.value
                    if (r - l < h) {
                        val c = (l + r) / 2f
                        l = c - h / 2f
                        r = c + h / 2f
                    }
                    val cx = (l + r) / 2f
                    val w = (r - l) * min(1f, s.coerceAtLeast(0f))
                    val hh = h * min(1f, s.coerceAtLeast(0f))
                    if (tv <= 0.01f) {
                        drawRoundRect(ink, Offset(cx - w / 2f, cy - hh / 2f), Size(w, hh), CornerRadius(hh / 2f), blendMode = BlendMode.Xor)
                        return@drawWithContent
                    }
                    var shape = Path().apply { addRoundRect(RoundRect(cx - w / 2f, cy - hh / 2f, cx + w / 2f, cy + hh / 2f, CornerRadius(hh / 2f))) }
                    val tr = h * 0.36f * tv
                    val tc = Offset(tailAt[0], tailAt[1])
                    shape = union(shape, Path().apply { addOval(Rect(tc, tr)) })
                    val cap = if (tc.x < cx) Offset(cx - w / 2f + hh / 2f, cy) else Offset(cx + w / 2f - hh / 2f, cy)
                    inkNeck(tc, tr, cap, hh / 2f, reach = 1.5f)?.let { shape = union(shape, it) }
                    drawPath(shape, ink, blendMode = BlendMode.Xor)
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** One outline from two (drawn once, so the knock-out works); the first alone should that fail. */
private fun union(a: Path, b: Path): Path = runCatching { Path.combine(PathOperation.Union, a, b) }.getOrDefault(a)

/** How tall the ink is behind a chosen word. */
private val PILL = 36.dp

// --- Opening --------------------------------------------------------------------------------

/**
 * One opening section's ink: how far it has dripped (0 closed, 1 open), and where its thread
 * runs, in the section's own coordinates. Controls inside it ask it where the ink has reached.
 */
internal class InkThread(open: Boolean) {
    val progress = Animatable(if (open) 1f else 0f)
    var coords: LayoutCoordinates? = null
    var threadX = 0f
    var headerH = 0f
    var dropY = 0f
    var end = 0f
    var fade = 0f
    var lag = 0f
    var grow = 0f

    /** Done dripping: everything in it is simply shown. */
    val settled: Boolean get() = progress.value >= 0.999f

    /** Where the drip has got to. */
    val tip: Float get() = dropY + (end - dropY) * progress.value.coerceIn(0f, 1f)

    /** How far a branch at [y] has grown out of the thread: it waits until the ink has passed. */
    fun reach(y: Float): Float = if (settled) 1f else ((tip - y - lag) / grow).coerceIn(0f, 1f)
}

internal val LocalInkThread = compositionLocalOf<InkThread?> { null }

private val DRIP = spring<Float>(dampingRatio = 0.75f, stiffness = 90f)

/** A row's drop coming in: past its size by a little, then back. */
private val SWELL_IN = spring<Float>(dampingRatio = 0.45f, stiffness = 380f)

/** And drying away. */
private val DRY = spring<Float>(dampingRatio = 1f, stiffness = 520f)
private val DRAW_BACK = spring<Float>(dampingRatio = 0.9f, stiffness = 160f)

/** Where the thread runs: this far into the page's margin. */
private val THREAD_IN = 13.dp

/** The page's side margin, which the thread runs down. */
private val MARGIN = 24.dp

/**
 * A row that opens in place. A drop sits in the margin beside it; opening drips a thread of ink
 * down the margin, and what's inside appears as the ink passes (sliders growing out of it as
 * branches). Closing draws the ink back up into the drop and the section with it. The thread
 * stays while it's open, the controls hanging off it.
 *
 * It uses the page's side margin, so it belongs at the page's edge (or inside another section).
 *
 * A ledger row ([threadIn] given) keeps its drop in the row's own first column instead, that far
 * in from the content's edge, finer: there the drop is also the row's state, resting beside the
 * label while it's [on] (quiet while [resting]), swelling in as it's switched on and drying away
 * as it's switched off; opening drips the thread from it whether it's on or not. [dropAt] puts
 * the drop on the label's line (pixels down from the row's top) when a caption sits under it.
 */
@Composable
internal fun InkSection(
    open: Boolean,
    modifier: Modifier = Modifier,
    /** Its drop sits quieter in the margin (for something that's off). */
    quiet: Boolean = false,
    threadIn: Dp? = null,
    dropAt: (() -> Float)? = null,
    on: Boolean = false,
    resting: Boolean = false,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val ink = p.primary
    val thread = remember { InkThread(open) }
    val fine = threadIn != null
    // The row's state, as a drop: in with a swell, out by drying.
    val held = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) { held.animateTo(if (on) 1f else 0f, if (on) SWELL_IN else DRY) }
    val heldColor = if (resting) p.quiet else ink
    val view = LocalView.current
    var composed by remember { mutableStateOf(open) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(open) {
        val felt = !first[0]
        first[0] = false
        if (open) {
            composed = true
            if (felt) InkFeel.split(view)
            thread.progress.animateTo(1f, DRIP)
        } else {
            thread.progress.animateTo(0f, DRAW_BACK)
            if (felt) InkFeel.merge(view)
            composed = false
        }
    }
    val body: @Composable () -> Unit = {
        if (composed) {
            CompositionLocalProvider(LocalInkThread provides thread) {
                Column(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
    Layout(
        contents = listOf(header, body),
        modifier = modifier
            .bleed(MARGIN)
            .onPlaced { thread.coords = it }
            // While the ink runs, the section is its own layer: what's below the drip is
            // faded out of it, and anything past its edge is cut off.
            .graphicsLayer {
                compositingStrategy = if (composed && !thread.settled) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                val pr = thread.progress.value
                if (composed && pr < 0.999f && size.height > thread.headerH) {
                    val tip = thread.tip
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = tip - thread.fade, endY = tip),
                        topLeft = Offset(0f, thread.headerH),
                        size = Size(size.width, size.height - thread.headerH),
                        blendMode = BlendMode.DstIn,
                    )
                }
                val x = thread.threadX
                // Read here as well as in layout, so the drop follows the label the moment it's
                // measured rather than waiting for something else to redraw the row.
                val y = dropAt?.invoke()?.takeIf { it > 0f && it < thread.headerH } ?: thread.dropY
                val q = pr.coerceIn(0f, 1f)
                val line = if (fine) 2.dp.toPx() else 3.dp.toPx()
                if (q > 0.001f) {
                    val tip = thread.tip.coerceIn(y, max(y, thread.end))
                    drawLine(ink, Offset(x, y), Offset(x, tip), strokeWidth = line, cap = StrokeCap.Round)
                    // The drip at the thread's tip while it runs.
                    if (q in 0.02f..0.97f) drawCircle(ink, if (fine) 3.dp.toPx() else 4.5.dp.toPx(), Offset(x, tip))
                }
                // The drop the thread hangs from: it gathers as the row opens (the chevron says
                // that it opens), swells a moment as it starts to drip, and is drawn back in with
                // the thread. A ledger row's drop is also its state, there while it's on.
                val base = if (fine) 3.5.dp.toPx() else 5.dp.toPx()
                val opening = if (q > 0.001f) (base + 2.dp.toPx() * min(1f, q * 3f) * (1f - q)) * min(1f, q * 5f) else 0f
                val h = held.value
                val state = if (fine && h > 0.001f) base * h else 0f
                val r = max(opening, state)
                if (r > 0.2f) drawCircle(if (opening >= state) ink else heldColor, r, Offset(x, y))
            },
    ) { measurables, constraints ->
        val margin = MARGIN.roundToPx()
        val inner = (constraints.maxWidth - 2 * margin).coerceAtLeast(0)
        val fixed = Constraints(minWidth = inner, maxWidth = inner)
        val heads = measurables[0].map { it.measure(fixed) }
        val headerH = heads.maxOfOrNull { it.height } ?: 0
        val bodies = measurables[1].map { it.measure(fixed) }
        val full = headerH + bodies.sumOf { it.height }
        thread.threadX = if (threadIn != null) margin + threadIn.toPx() else margin - THREAD_IN.toPx()
        thread.headerH = headerH.toFloat()
        thread.dropY = dropAt?.invoke()?.takeIf { it > 0f && it < headerH } ?: (headerH / 2f)
        thread.end = max(thread.dropY, full - 10.dp.toPx())
        // The controls fade in over the last stretch above the drip; a slider's branch waits
        // until the fade has passed it, so it grows out in solid ink.
        thread.fade = 24.dp.toPx()
        thread.lag = 24.dp.toPx()
        thread.grow = 36.dp.toPx()
        val height = when {
            bodies.isEmpty() -> headerH
            thread.settled -> full
            else -> (thread.tip + 30.dp.toPx()).roundToInt().coerceIn(headerH, full)
        }
        layout(constraints.maxWidth, height) {
            heads.forEach { it.place(margin, 0) }
            var y = headerH
            bodies.forEach {
                it.place(margin, y)
                y += it.height
            }
        }
    }
}
