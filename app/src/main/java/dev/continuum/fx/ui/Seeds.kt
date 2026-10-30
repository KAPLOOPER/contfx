package dev.continuum.fx.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.island.ui.Goo
import dev.continuum.fx.island.ui.NeckLatch
import dev.continuum.fx.island.ui.lerpF
import dev.continuum.fx.island.ui.neckDown
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The ink rule, app-wide: ink never appears or vanishes, it only separates from or merges with
 * ink already on screen. In the app there are two pieces of ink to start from: the drop under
 * the page's title, and the dock. Ink cards on a page (an update, an app that sent you here)
 * drip out of the drop and spread where they sit; messages bud off the top of the dock and sink
 * back into it.
 */

/** Ink cards growing out of the title's drop, and where that drop is. */
internal class Seeds {
    /** The drop under the current title, at rest, in root coordinates. */
    var drop by mutableStateOf<Rect?>(null)
    val cards = mutableStateListOf<SeedCard>()
}

internal class SeedCard {
    val grow = Animatable(0f)
    var topLeft by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(Size.Zero)
    var corner = 0f
    val latch = NeckLatch()
}

internal val LocalSeeds = compositionLocalOf<Seeds?> { null }

/** How much of its height a card being seeded takes in the page, for its progress [q]. */
private fun opened(q: Float): Float {
    val t = ((q - 0.3f) / 0.7f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * An ink card that grows out of the drop under the page's title: a drop falls from it on a neck,
 * lands where the card goes and spreads into it, the neck thinning and letting go; the page makes
 * room as it spreads. Going, it gathers back into a drop and rises home. Its contents fade in
 * once it has spread. [content] is drawn on the ink, which this provides.
 */
@Composable
internal fun Seeded(
    visible: Boolean,
    corner: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val seeds = LocalSeeds.current
    val p = LocalPalette.current
    val card = remember { SeedCard() }
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    card.corner = cornerPx
    LaunchedEffect(visible) {
        if (visible) {
            // A moment for the page to settle first, so the drip is seen.
            if (card.grow.value == 0f) delay(280)
            card.grow.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 120f))
        } else {
            card.grow.animateTo(0f, tween(460, easing = FastOutSlowInEasing))
        }
    }
    val q = card.grow.value
    if (!visible && q <= 0f) return
    if (seeds != null) {
        DisposableEffect(seeds, card) {
            seeds.cards += card
            onDispose { seeds.cards -= card }
        }
    }
    val settled = q >= 0.999f && !card.grow.isRunning
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val h = if (settled || seeds == null) placeable.height else (placeable.height * opened(q)).roundToInt()
                layout(placeable.width, h) { placeable.place(0, 0) }
            }
            .onGloballyPositioned {
                card.topLeft = it.positionInRoot()
                card.size = Size(it.size.width.toFloat(), it.size.height.toFloat())
            }
            .clip(shape)
            .then(if (settled || seeds == null) Modifier.background(p.ink) else Modifier),
    ) {
        Box(Modifier.graphicsLayer { alpha = if (settled || seeds == null) 1f else ((q - 0.78f) / 0.22f).coerceIn(0f, 1f) }) {
            content()
        }
    }
}

/** Draws every card on its way out of (or back into) the title's drop. Sits over the page. */
@Composable
internal fun SeedOverlay(seeds: Seeds) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val goo = remember(density) { if (Build.VERSION.SDK_INT >= 31) Goo.effect(with(density) { 4.dp.toPx() }) else null }
    val moving = seeds.cards.any { it.grow.value < 0.999f || it.grow.isRunning }
    if (!moving) return
    Canvas(Modifier.fillMaxSize().graphicsLayer { renderEffect = goo }) {
        val d = seeds.drop ?: return@Canvas
        val s = 18.dp.toPx()
        val seed = p.text
        drawRoundRect(seed, d.topLeft, d.size, CornerRadius(d.height / 2f))
        for (card in seeds.cards) {
            val q = card.grow.value.coerceIn(0f, 1f)
            if (q <= 0.001f || (q >= 0.999f && !card.grow.isRunning)) continue
            val c = Rect(card.topLeft, card.size)
            val a = (q / 0.45f).coerceIn(0f, 1f)
            val b = ((q - 0.45f) / 0.55f).coerceIn(0f, 1f)
            val spread = 1f - (1f - b) * (1f - b) * (1f - b)
            val cx = d.center.x
            val fall = lerpF(d.bottom - s * 0.8f, c.top, a * a)
            val drop = Rect(cx - s / 2f, fall, cx + s / 2f, fall + s)
            val shape = Rect(
                lerpF(drop.left, c.left, spread),
                lerpF(drop.top, c.top, spread),
                lerpF(drop.right, c.right, spread),
                lerpF(drop.bottom, max(c.bottom, c.top + s), spread),
            )
            val r = lerpF(s / 2f, card.corner, spread).coerceAtMost(min(shape.width, shape.height) / 2f)
            // Light while it's still the title's drop (in dark mode the drop is light), ink once it lands.
            drawRoundRect(lerp(seed, p.ink, spread), shape.topLeft, shape.size, CornerRadius(r))
            val gap = (c.top - d.bottom).coerceAtLeast(1f)
            neckDown(d, shape, cx, card.latch, gap * (1.3f - 0.7f * b), 1.dp.toPx(), 5.dp.toPx(), seed)
        }
    }
}

/**
 * Messages bud off the top of the dock: a drop rises out of it on a neck, swells into a pill with
 * the message, hangs there on a thread of ink, and sinks back in when it's said its piece.
 */
@Composable
internal fun DockMessage(message: String?, dock: State<Rect?>, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            shown = message
            delay(3000)
            onDismiss()
        }
    }
    val q = remember { Animatable(0f) }
    val out = message != null
    LaunchedEffect(out) {
        if (out) {
            q.animateTo(1.3f, tween(230, easing = LinearOutSlowInEasing))
            q.animateTo(1f, Motion.flow())
        } else {
            q.animateTo(0f, Motion.flow())
        }
    }
    val text = shown ?: return
    if (q.value <= 0.002f && !out) return
    val k = dock.value ?: return

    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = Libron, fontSize = 15.sp, color = p.onInk)
    val padX = with(density) { 20.dp.toPx() }
    val padY = with(density) { 12.dp.toPx() }
    val maxText = (screenW - with(density) { 48.dp.toPx() } - padX * 2f).roundToInt().coerceAtLeast(1)
    val layout = remember(text, maxText) {
        measurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = 3, constraints = Constraints(maxWidth = maxText))
    }
    // A hair wider than measured, so the text never wraps differently when it's drawn.
    val w = layout.size.width + padX * 2f + with(density) { 2.dp.toPx() }
    val h = layout.size.height + padY * 2f
    val gapPx = with(density) { 12.dp.toPx() }
    val rest = Rect(k.center.x - w / 2f, k.top - gapPx - h, k.center.x + w / 2f, k.top - gapPx)
    val latch = remember { NeckLatch() }
    val goo = remember(density) { if (Build.VERSION.SDK_INT >= 31) Goo.effect(with(density) { 6.dp.toPx() }) else null }

    fun drop(): Rect {
        val s = min(h, with(density) { 40.dp.toPx() })
        val t = q.value
        val spread = t.coerceIn(0f, 1f).let { it * it * it }
        val bottom = lerpF(k.top + s * 0.75f, rest.bottom, t)
        val dw = lerpF(s, rest.width, spread)
        val dh = lerpF(s, rest.height, spread)
        return Rect(k.center.x - dw / 2f, bottom - dh, k.center.x + dw / 2f, bottom)
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { renderEffect = goo }) {
            val m = drop()
            // The top of the dock, for the neck to grow from; the dock itself covers the rest.
            val strip = Rect(k.left, k.top, k.right, k.top + min(k.height, 60.dp.toPx()))
            drawRoundRect(p.ink, strip.topLeft, strip.size, CornerRadius(min(38.dp.toPx(), strip.height / 2f)))
            drawRoundRect(p.ink, m.topLeft, m.size, CornerRadius(min(m.width, m.height) / 2f))
            neckDown(m, strip, k.center.x, latch, 26.dp.toPx(), 2.dp.toPx(), 26.dp.toPx(), p.ink)
        }
        Box(
            Modifier
                .offset { IntOffset(rest.left.roundToInt(), rest.top.roundToInt()) }
                .size(with(density) { rest.width.toDp() }, with(density) { rest.height.toDp() })
                .graphicsLayer { alpha = ((q.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                .drawWithContent {
                    val m = drop().translate(-rest.left, -rest.top)
                    val path = Path().apply { addRoundRect(RoundRect(m, CornerRadius(min(m.width, m.height) / 2f))) }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Txt(text, 15.sp, p.onInk, maxLines = 3)
        }
    }
}

/** For callers holding a value that goes away: keeps the last one, so a card can leave with it. */
@Composable
internal fun <T : Any> rememberLastOf(current: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (current != null) holder[0] = current
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}
