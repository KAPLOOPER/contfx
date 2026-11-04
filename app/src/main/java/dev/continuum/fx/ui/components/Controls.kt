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
import kotlin.math.abs
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
 * A switch, in ink. Off, it's a hairline channel with a drop resting at its near end. Turned on,
 * the drop slides to the far end, drawn out a little by its speed, and ink pours into the channel
 * after it, from a bead at the near end out to the whole channel; the drop is left in the page's
 * own colour with a tick in it. Off again, the ink drains back first and the drop follows it home.
 * The hairline keeps 3:1 against the ground, so an off switch is still plainly a switch.
 */
@Composable
fun InkSwitch(
    on: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    surface: Surface = Surface.Ground,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val pos = remember { Animatable(if (on) 1f else 0f) }
    val flood = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) {
        val t = if (on) 1f else 0f
        if (pos.targetValue == t && flood.targetValue == t) return@LaunchedEffect
        launch { pos.animateTo(t, SLIDE) }
        flood.animateTo(t, if (on) POUR else DRAIN)
    }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, Motion.snap(), label = "switchPress")
    val onInk = surface == Surface.Ink
    val ink = if (onInk) p.onInk else p.primary
    val paper = if (onInk) p.ink else p.onPrimary
    val rim = if (onInk) p.onInkSub else p.quiet
    val resting = if (onInk) p.onInkSub else p.sub
    Box(
        modifier
            .size(width = 56.dp, height = 44.dp)
            .toggleable(on, interactionSource = source, indication = null, role = Role.Switch) {
                InkFeel.toggle(view, !on)
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 42.dp, height = 24.dp)) {
            val w = size.width
            val h = size.height
            val r = h / 2f
            val cy = h / 2f
            val x0 = r
            val x1 = w - r
            val hair = 1.4.dp.toPx()
            drawRoundRect(
                rim, Offset(hair / 2f, hair / 2f), Size(w - hair, h - hair), CornerRadius(r - hair / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(hair),
            )
            // The ink in the channel: a bead at the near end that swells to fill it as it pours.
            val f = flood.value.coerceIn(0f, 1f)
            if (f > 0.002f) {
                val rr = r * min(1f, 0.35f + f * 3f)
                val left = x0 - rr
                val right = lerpF(x0, x1, f) + rr
                drawRoundRect(ink, Offset(left, cy - rr), Size(right - left, rr * 2f), CornerRadius(rr))
            }
            // The drop, drawn out along its way by its speed, and a touch fuller under a finger.
            val v = pos.value
            val cx = lerpF(x0, x1, v.coerceIn(0f, 1f))
            val stretch = (abs(pos.velocity) * 0.035f).coerceAtMost(0.3f)
            val dr = (r - 4.dp.toPx()) * (1f + 0.12f * press)
            val color = lerp(resting, paper, ((v - 0.25f) / 0.6f).coerceIn(0f, 1f))
            drawOval(
                color,
                Offset(cx - dr * (1f + stretch), cy - dr * (1f - stretch * 0.45f)),
                Size(dr * 2f * (1f + stretch), dr * 2f * (1f - stretch * 0.45f)),
            )
            // Its tick, surfacing as it arrives.
            val t = ((v - 0.65f) / 0.35f).coerceIn(0f, 1f)
            if (t > 0.01f) {
                val k = dr * (0.7f + 0.3f * t)
                val tick = Path().apply {
                    moveTo(cx - 0.45f * k, cy + 0.02f * k)
                    lineTo(cx - 0.12f * k, cy + 0.34f * k)
                    lineTo(cx + 0.47f * k, cy - 0.3f * k)
                }
                drawPath(
                    tick, ink.copy(alpha = ink.alpha * t),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        1.9.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

/**
 * The one switch for a whole page, with its name under it in small capitals, so it's plain what
 * it turns off: "All effects", "Island", "Panel".
 */
@Composable
fun MasterSwitch(on: Boolean, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        InkSwitch(on, label, onClick)
        dev.continuum.fx.ui.theme.Note(label, p.sub)
    }
}

/** The drop crosses quickly and settles with the slightest give. */
private val SLIDE = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)

/** Ink pours in after the drop, a little behind it. */
private val POUR = spring<Float>(dampingRatio = 1f, stiffness = 230f)

/** And drains back ahead of it. */
private val DRAIN = spring<Float>(dampingRatio = 1f, stiffness = 760f)

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
    /** Told when a finger takes hold of the slider and when it lets go. */
    onHeld: ((Boolean) -> Unit)? = null,
) {
    val p = LocalPalette.current
    val ink = if (muted) p.sub else p.primary
    // Enough to see where the slider runs to (about 2:1), well under the ink of the value.
    val track = p.sub.copy(alpha = 0.42f)
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
    val heldTo = rememberUpdatedState(onHeld)
    LaunchedEffect(held) {
        if (!first[0]) {
            if (held) InkFeel.split(view) else InkFeel.merge(view)
            heldTo.value?.invoke(held)
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
