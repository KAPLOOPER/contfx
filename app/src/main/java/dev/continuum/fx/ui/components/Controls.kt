package dev.continuum.fx.ui.components

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import dev.continuum.fx.island.ui.glint
import dev.continuum.fx.island.ui.glintPull
import dev.continuum.fx.island.ui.halo
import dev.continuum.fx.island.ui.inkStar
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.continuum.fx.island.ui.lerpF
import dev.continuum.fx.island.ui.Goo
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.rememberCoroutineScope
import dev.continuum.fx.ui.theme.Libron
import kotlinx.coroutines.delay
import kotlin.math.min
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.animateColorAsState
import androidx.annotation.DrawableRes
import android.os.SystemClock
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.continuum.fx.island.ui.InkFeel
import kotlin.math.max
import kotlin.math.sqrt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.launch

/**
 * Continuum press feel: goes in on the Snap spring, comes back out on Flow so it
 * bounces slightly. Pills squash wider and shorter like a bubble; round things shrink.
 */
@Composable
fun Modifier.squash(source: MutableInteractionSource, pill: Boolean = false): Modifier {
    val pressed by source.collectIsPressedAsState()
    val sx = remember { Animatable(1f) }
    val sy = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        val tx = if (pressed) (if (pill) 1.05f else 0.86f) else 1f
        val ty = if (pressed) 0.86f else 1f
        val spec: AnimationSpec<Float> = if (pressed) Motion.snap() else Motion.flow()
        launch { sx.animateTo(tx, spec) }
        launch { sy.animateTo(ty, spec) }
    }
    return this.graphicsLayer {
        scaleX = sx.value
        scaleY = sy.value
    }
}

/**
 * What a control sits on. The app is one flat surface now, so [Card] reads as [Ground]; [Ink] is
 * the black of the dock and the island.
 */
enum class Surface { Ground, Card, Ink }

/**
 * A button that's just its word (and icon), on the surface it sits on: no shape, no border. It
 * presses in like everything else. [primary] sets the word in bold. [height] is the least it
 * takes up, so it's always easy to hit; the space after it keeps the next one apart.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    on: Surface = Surface.Ground,
    height: Dp = 44.dp,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val fg = if (on == Surface.Ink) p.onInk else p.text
    Row(
        modifier
            .squash(source, pill = true)
            .heightIn(min = height)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(fg))
        }
        Txt(text, 16.sp, fg, bold = primary, maxLines = 1)
    }
}

/** Icon-only button: no shape at rest, a flat bubble grows out from behind the icon when pressed. */
@Composable
fun IconBubble(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Surface = Surface.Ground,
    active: Boolean = false,
    size: Dp = 44.dp,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val target = if (pressed || active) 1f else 0f
    val bubble by animateFloatAsState(target, if (pressed) Motion.snap() else Motion.flow(), label = "bubble")
    val tint = if (on == Surface.Ink) p.onInk else p.text
    val bubbleColor = if (on == Surface.Ink) p.inkTonal else p.surface
    Box(
        modifier
            .size(size)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val s = 0.4f + 0.6f * bubble
                    scaleX = s
                    scaleY = s
                    alpha = bubble.coerceIn(0f, 1f)
                }
                .clip(CircleShape)
                .background(bubbleColor),
        )
        Image(
            painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(22.dp).squash(source),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/**
 * Where a finger last went down on a control, so the ink it sets off spreads from there. Read
 * while drawing and set from input, so it deliberately isn't snapshot state.
 */
internal class Touch {
    var at: Offset = Offset.Unspecified
    var time = 0L

    /** The touch, if it's the one that just set this change off (a tap's state can lag a moment). */
    fun recent(): Offset? = at.takeIf { it.isSpecified && SystemClock.uptimeMillis() - time < 700 }
}

/** Notes every finger that lands here in [touch], without taking the touch from anything else. */
internal fun Modifier.notes(touch: Touch): Modifier = pointerInput(touch) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        touch.at = down.position
        touch.time = SystemClock.uptimeMillis()
    }
}

/**
 * How far ink has spread across a control (0 none, 1 all of it) and where from. Turning [on]
 * spreads it out from the finger that did it (the centre when nothing touched it, as when a
 * neighbouring chip takes over the choice) and turning it off drains it back the same way.
 * It starts where it should be, so a page opening doesn't flood every chosen chip at once.
 */
internal class Spread(on: Boolean) {
    val fill = Animatable(if (on) 1f else 0f)
    var origin by mutableStateOf(Offset.Unspecified)
}

@Composable
internal fun rememberSpread(on: Boolean, touch: Touch, feel: Boolean = true): Spread {
    val spread = remember { Spread(on) }
    val view = LocalView.current
    LaunchedEffect(on) {
        val target = if (on) 1f else 0f
        if (spread.fill.targetValue == target) return@LaunchedEffect
        val from = touch.recent()
        spread.origin = from ?: Offset.Unspecified
        // Felt only when it was your finger that moved the ink.
        if (feel && from != null) InkFeel.toggle(view, on)
        spread.fill.animateTo(target, if (on) INK_IN else Motion.snap())
    }
    return spread
}

/** Spreading ink eases out, quick then slow, without bouncing back from the edges. */
private val INK_IN = spring<Float>(dampingRatio = 1f, stiffness = 300f)

/**
 * Draws this layer only inside a circle that grows from the spread's origin until, at full,
 * it covers the whole control. What's under it is drawn again beneath in the resting colours,
 * so text and icons change colour exactly where the ink reaches them.
 */
internal fun Modifier.spreadInk(spread: Spread): Modifier = drawWithContent {
    val f = spread.fill.value
    if (f <= 0.002f) return@drawWithContent
    if (f >= 0.998f) {
        drawContent()
        return@drawWithContent
    }
    val o = spread.origin.takeIf { it.isSpecified } ?: center
    val dx = max(o.x, size.width - o.x)
    val dy = max(o.y, size.height - o.y)
    val r = sqrt(dx * dx + dy * dy) * f
    val circle = Path().apply { addOval(Rect(o, r)) }
    clipPath(circle) { this@drawWithContent.drawContent() }
}

/**
 * A choice: its word, and when it's chosen, a pill of ink behind it that the word is knocked out
 * of. In a [ChoiceRow] the row shares one pill, which flows from word to word; on its own it's a
 * row of one and its pill swells in and out. Felt when your finger did it.
 */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, on: Surface = Surface.Ground) {
    if (LocalChoiceGroup.current == null) {
        ChoiceRow(scroll = false, on = on) { ChipWord(label, selected, onClick, on) }
    } else {
        ChipWord(label, selected, onClick, on)
    }
}

@Composable
private fun ChipWord(label: String, selected: Boolean, onClick: () -> Unit, on: Surface) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val touch = remember { Touch() }
    val view = LocalView.current
    val bright = if (on == Surface.Ink) p.onInk else p.text
    val dim = if (on == Surface.Ink) p.onInkSub else p.sub
    val fg by animateColorAsState(if (selected) bright else dim, Motion.flow(), label = "choice")
    val was = remember { booleanArrayOf(selected) }
    LaunchedEffect(selected) {
        if (was[0] == selected) return@LaunchedEffect
        was[0] = selected
        if (touch.recent() != null) InkFeel.toggle(view, selected)
    }
    Box(
        Modifier
            .choice(selected)
            .notes(touch)
            .selectable(selected, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, 16.sp, fg, maxLines = 1, modifier = Modifier.squash(source, pill = true))
    }
}

/**
 * The power control in the dock: tonal when off; turning it on floods it from the centre, and the
 * drop glints. While it's on but [waiting] (nothing playing yet), a halo of ink floats over it.
 */
@Composable
fun PowerButton(on: Boolean, @DrawableRes icon: Int, onClick: () -> Unit, what: String = "processing", waiting: Boolean = false) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val touch = remember { Touch() }
    // From the centre whatever part of it you pressed: it's a button, not a surface.
    val spread = rememberSpread(on, touch, feel = false)
    val view = LocalView.current
    val glintU = remember { Animatable(1f) }
    val was = remember { booleanArrayOf(on) }
    LaunchedEffect(on) {
        val came = on && !was[0]
        was[0] = on
        if (came) {
            glintU.snapTo(0f)
            glintU.animateTo(1f, tween(600, easing = LinearEasing))
        }
    }
    val haloK by animateFloatAsState(if (waiting) 1f else 0f, Motion.flow(), label = "halo")
    val bob = if (haloK > 0.01f) {
        rememberInfiniteTransition(label = "haloBob").animateFloat(
            0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "haloPhase",
        ).value
    } else 0f
    Box(Modifier.size(56.dp)) {
        Box(
            Modifier
                .squash(source)
                .size(56.dp)
                .clip(CircleShape)
                .background(p.inkTonal)
                .toggleable(on, interactionSource = source, indication = null, role = Role.Switch) {
                    InkFeel.toggle(view, !on)
                    onClick()
                }
                .semantics { contentDescription = if (on) "Turn $what off" else "Turn $what on" },
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp), colorFilter = ColorFilter.tint(p.onInk))
            Box(Modifier.matchParentSize().spreadInk(spread).background(p.onInk), contentAlignment = Alignment.Center) {
                Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp), colorFilter = ColorFilter.tint(p.ink))
            }
        }
        Canvas(Modifier.matchParentSize()) {
            glint(center, 36.dp.toPx(), glintU.value, p.onInk, 0.9f)
            val y = -4.dp.toPx() + 2.dp.toPx() * sin(bob * 2f * PI.toFloat())
            halo(Offset(center.x, y), 14.dp.toPx(), 3.6.dp.toPx(), haloK, p.onInk, 1.6.dp.toPx())
        }
    }
}

/**
 * A switch: off, a small dim dot; on, it swells into a drop (a little past, then back), pulled
 * out into a four-point glint for a moment as it comes on, and the tick surfaces; off again, it
 * shrinks back to the dot. No socket, no border: just the ink.
 */
@Composable
fun OnOffDot(on: Boolean, @DrawableRes checkIcon: Int, description: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val dot = remember { Animatable(if (on) 1f else 0f) }
    val glintU = remember { Animatable(1f) }
    LaunchedEffect(on) {
        val target = if (on) 1f else 0f
        if (dot.targetValue == target) return@LaunchedEffect
        if (on) {
            launch {
                glintU.snapTo(0f)
                glintU.animateTo(1f, tween(620, easing = LinearEasing))
            }
        }
        dot.animateTo(target, if (on) SWELL else Motion.snap())
    }
    val off = p.sub.copy(alpha = 0.45f)
    Box(
        Modifier
            .size(44.dp)
            .toggleable(on, interactionSource = source, indication = null, role = Role.Checkbox) {
                InkFeel.toggle(view, !on)
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.squash(source).size(30.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                // From the 9 dp dot to the full 30 dp drop; pulled four ways at the glint.
                val v = dot.value.coerceAtLeast(0f)
                val pull = glintPull(glintU.value)
                val r = size.minDimension / 2f * (0.3f + 0.7f * v) * (1f + 0.6f * pull)
                val color = lerp(off, p.primary, dot.value.coerceIn(0f, 1f))
                if (pull > 0.002f) drawPath(inkStar(center.x, center.y, r, 2f - 1.6f * pull, 0.32f * pull), color)
                else drawCircle(color, r, center)
            }
            Image(
                painterResource(checkIcon),
                contentDescription = null,
                modifier = Modifier.size(18.dp).graphicsLayer {
                    val t = ((dot.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                    alpha = t
                    scaleX = 0.6f + 0.4f * t
                    scaleY = 0.6f + 0.4f * t
                },
                colorFilter = ColorFilter.tint(p.onPrimary),
            )
        }
    }
}

/** A drop filling its socket: past it by a tenth, then back. */
private val SWELL = spring<Float>(dampingRatio = 0.42f, stiffness = 420f)

/** The value's bubble rises bouncily and sinks back on Snap. */
private val RISE = spring<Float>(dampingRatio = 0.55f, stiffness = 300f)

/** How far left of a slider its ink can reach: room for the branch from a section's thread. */
private val BRANCH_ROOM = 56.dp

/** The island's goo, for a slider's line and drop: Android 12 and later. */
@Composable
private fun rememberSliderGoo(): RenderEffect? {
    val density = LocalDensity.current
    return remember(density) {
        if (Build.VERSION.SDK_INT >= 31) Goo.effect(with(density) { 2.6.dp.toPx() }) else null
    }
}

/**
 * The island's seek bar, as every slider: a soft track, and the part up to the value a line of
 * ink ending in a drop that the goo fuses to it. Under your finger the line thickens and the drop
 * swells, and a bubble of ink rises off the drop on a neck with [label] (the value) in it; let go
 * and it all settles back. Inside an [InkSection] a thin branch joins the line to the section's
 * thread, and the slider grows out along it as the ink first reaches it.
 * [value] is 0..1; [muted] draws it in grey.
 */
@Composable
fun InkSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    muted: Boolean = false,
) {
    val p = LocalPalette.current
    val ink = if (muted) p.sub else p.primary
    val track = p.sub.copy(alpha = 0.3f)
    val onInk = p.onPrimary
    val thread = LocalInkThread.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var pressing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val held = pressing || dragging
    val lift = remember { Animatable(0f) }
    val grab = remember { Animatable(0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(held) {
        if (!first[0]) {
            if (held) InkFeel.split(view) else InkFeel.merge(view)
        }
        first[0] = false
        launch { lift.animateTo(if (held) 1f else 0f, if (held) RISE else Motion.snap()) }
        launch { grab.animateTo(if (held) 1f else 0f, Motion.flow()) }
    }
    val goo = rememberSliderGoo()
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = onInk, fontSize = 14.sp, fontFamily = Libron)
    val latest = rememberUpdatedState(onChange)

    // Where the section's thread is, left of this slider (none outside a section), and how far
    // the ink has reached it.
    val branch = FloatArray(2)
    fun measureBranch() {
        branch[0] = 0f
        branch[1] = 1f
        val t = thread ?: return
        val mine = coords[0] ?: return
        val sc = t.coords ?: return
        if (!sc.isAttached || !mine.isAttached) return
        val at = sc.localPositionOf(mine, Offset.Zero)
        branch[0] = t.threadX - at.x
        branch[1] = t.reach(at.y + mine.size.height / 2f)
    }
    fun Density.thick() = lerpF(6.dp.toPx(), 9.dp.toPx(), grab.value)
    fun Density.bulb() = lerpF(5.5.dp.toPx(), 10.dp.toPx(), grab.value)
    /** The drop's centre across: on the value, kept inside the track's ends. */
    fun Density.dropX(w: Float, from: Float, reach: Float): Float {
        val kx = value.coerceIn(0f, 1f) * w
        return if (reach >= 1f) kx.coerceIn(bulb(), max(bulb(), w - bulb())) else from + (kx - from) * reach
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .onPlaced { coords[0] = it }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        latest.value((o.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    latest.value((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // A moment's hold before the bubble rises, so a scroll passing over
                        // doesn't raise it.
                        val rise = scope.launch {
                            delay(90)
                            pressing = true
                        }
                        tryAwaitRelease()
                        rise.cancel()
                        pressing = false
                    },
                    onTap = { o -> latest.value((o.x / size.width).coerceIn(0f, 1f)) },
                )
            }
            .drawWithContent {
                drawContent()
                measureBranch()
                if (branch[1] < 1f) return@drawWithContent
                val b = lift.value
                if (b > 0.02f) {
                    val spill = 16.dp.toPx()
                    val at = Offset(dropX(size.width, branch[0], 1f), size.height / 2f)
                    inkBubble(at, b, label?.let { measurer.measure(it, style) }, ink, -spill, size.width + spill)
                }
            },
    ) {
        // The track, drawn out as the ink reaches it.
        Canvas(Modifier.fillMaxSize()) {
            measureBranch()
            val reach = branch[1]
            if (reach <= 0f) return@Canvas
            val th = 6.dp.toPx()
            drawRoundRect(track, Offset(0f, (size.height - th) / 2f), Size(size.width * reach, th), CornerRadius(th / 2f))
        }
        // The line and its drop, fused by the goo; with room to the left for the branch.
        Canvas(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val room = BRANCH_ROOM.roundToPx()
                    val wide = constraints.maxWidth + room
                    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-room, 0) }
                }
                .graphicsLayer { renderEffect = goo },
        ) {
            measureBranch()
            val from = branch[0]
            val reach = branch[1]
            if (reach <= 0f) return@Canvas
            val room = BRANCH_ROOM.toPx()
            val w = size.width - room
            val cy = size.height / 2f
            val th = thick()
            val kx = value.coerceIn(0f, 1f) * w
            val end = from + (kx - from) * reach
            if (from < 0f && end > from) {
                drawLine(ink, Offset(room + from, cy), Offset(room + min(end, th / 2f), cy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            if (end > 0f) {
                drawRoundRect(ink, Offset(room, cy - th / 2f), Size(end, th), CornerRadius(th / 2f))
            }
            drawCircle(ink, bulb() * min(1f, reach * 1.3f), Offset(room + dropX(w, from, reach), cy))
        }
    }
}
