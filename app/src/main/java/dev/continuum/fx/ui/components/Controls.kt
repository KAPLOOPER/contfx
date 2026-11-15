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
import dev.continuum.fx.ui.eidolon.Star
import dev.continuum.fx.ui.eidolon.lerpF
import dev.continuum.fx.ui.eidolon.skyWindow
import dev.continuum.fx.ui.eidolon.tailedStar
import dev.continuum.fx.ui.eidolon.windowTextShadow
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
import dev.continuum.fx.ui.eidolon.Grotesk
import kotlinx.coroutines.delay
import kotlin.math.min
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
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
 * A line of buttons that wraps onto the next when it runs out of width (a long label, large type),
 * so an action is never left past the page's edge where only a sideways drag would find it, and
 * never squeezed until its word breaks ("Res/to…").
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ActionRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
    ) { content() }
}

/**
 * A button: a word with its icon, in a small ruled box, like a box on a printed form. The icon is
 * required, so a bare line of words never passes for one. It presses in like everything else.
 * [primary] prints it solid, in negative: the one thing to do. It's never less than 48dp tall, so
 * it's always easy to hit; the space after it keeps the next one apart.
 *
 * [enabled] false is for an act that can't happen yet (Volume's Try it before setup): words, icon
 * and rule go [Palette.faint], it takes no touch, and TalkBack hears it as unavailable. Only ever
 * under words that say when it will work, and only on the paper.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int,
    primary: Boolean = false,
    on: Surface = Surface.Ground,
    enabled: Boolean = true,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val onInk = on == Surface.Ink
    val solid = primary && enabled
    val fg by animateColorAsState(
        when {
            solid -> if (onInk) p.ink else p.sheet
            onInk -> p.onInk
            enabled -> p.text
            else -> p.faint
        },
        Motion.flow(),
        label = "button",
    )
    val rule = when {
        onInk -> p.onInkSub
        enabled -> p.text.copy(alpha = 0.5f)
        else -> p.faint.copy(alpha = 0.6f)
    }
    val fill = if (solid) (if (onInk) p.onInk else p.text) else androidx.compose.ui.graphics.Color.Transparent
    Box(
        modifier
            .squash(source, pill = true)
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            // The touch target is the full 48dp; the box sits inside it, with air after it.
            .padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .background(fill)
                .border(1.dp, if (solid) fill else rule)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(fg))
            // Wraps rather than losing its end at large type (a row of them wraps first; see
            // [ActionRow]), so a button never says half of what it does.
            Txt(text, 15.sp, fg, bold = primary, maxLines = 3)
        }
    }
}

/** Icon-only button, 48dp: no shape at rest; pressed, a square well opens behind the icon. */
@Composable
fun IconBubble(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Surface = Surface.Ground,
    active: Boolean = false,
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
            .size(48.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .graphicsLayer {
                    val s = 0.4f + 0.6f * bubble
                    scaleX = s
                    scaleY = s
                    alpha = bubble.coerceIn(0f, 1f)
                }
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
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
        ChoiceRow(fill = false, on = on) { ChipWord(label, selected, onClick, on) }
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
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, 16.sp, fg, maxLines = 1, modifier = Modifier.squash(source, pill = true))
    }
}

/**
 * The power control in the dock, on its ink: off, a ring with the icon in it; turning it on opens a
 * window through the ink to the sky, as a star from the centre, the icon left standing in paper
 * colour, and a glint crosses it. While it's on but [waiting] (nothing playing yet), a halo of ink
 * floats over it.
 */
@Composable
fun PowerButton(on: Boolean, @DrawableRes icon: Int, onClick: () -> Unit, what: String = "processing", waiting: Boolean = false) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val open by animateFloatAsState(if (on) 1f else 0f, INK_IN, label = "power")
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
    val iris = remember { Path() }
    val disc = remember { Path() }
    val onWindow = androidx.compose.ui.graphics.Color(0xFFF6F6F3)
    Box(Modifier.size(56.dp)) {
        Box(
            Modifier
                .squash(source)
                .size(56.dp)
                .clip(CircleShape)
                .background(p.inkTonal)
                .skyWindow {
                    val window = this
                    if (open > 0f) {
                        disc.rewind()
                        disc.addOval(Rect(Offset.Zero, size))
                        val reach = size.minDimension * 1.1f * open
                        clipPath(disc) {
                            window.drawSky(Star.path(center, reach, reach, pinch = lerpF(Star.SOFT, -0.6f, open), into = iris))
                        }
                    }
                    if (open < 1f) {
                        drawCircle(p.onInkSub, size.minDimension / 2f - 1.5.dp.toPx(), alpha = 1f - open,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    }
                }
                .toggleable(on, interactionSource = source, indication = null, role = Role.Switch) {
                    InkFeel.toggle(view, !on)
                    onClick()
                }
                .semantics { contentDescription = if (on) "Turn $what off" else "Turn $what on" },
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp), colorFilter = ColorFilter.tint(lerp(p.onInk, onWindow, open)))
        }
        Canvas(Modifier.matchParentSize()) {
            glint(center, 36.dp.toPx(), glintU.value, p.onInk, 0.9f)
            val y = -4.dp.toPx() + 2.dp.toPx() * sin(bob * 2f * PI.toFloat())
            halo(Offset(center.x, y), 14.dp.toPx(), 3.6.dp.toPx(), haloK, p.onInk, 1.6.dp.toPx())
        }
    }
}

/**
 * A switch, cut from the paper. Off, it's an outline on the page with an empty ring waiting at its
 * near end: plainly a switch you can touch, at full strength (`quiet` for the outline, 3:1 or
 * better on the paper; `sub` for the ring), not a disabled one. Turned on, the track is cut out of
 * the paper: the sky behind the page opens into it as a star from where the ring was, and a paper
 * star crosses to the far end, turning a quarter as it goes. Off again, the star goes home and the
 * window closes behind it. On the dock's ink ([Surface.Ink]) the same cut shows the same sky.
 * The keyboard's settings draw Continuum's own switch.
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
    val progress by animateFloatAsState(if (on) 1f else 0f, SLIDE, label = "switch")
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, Motion.snap(), label = "switchPress")
    val onInk = surface == Surface.Ink
    val edge = if (onInk) p.onInk else p.text
    val outline = if (onInk) lerp(p.ink, p.onInkSub, 0.62f) else p.quiet
    val ring = if (onInk) p.onInkSub else p.sub
    val onWindow = androidx.compose.ui.graphics.Color(0xFFF6F6F3)
    val iris = remember { Path() }
    val track = remember { Path() }
    val thumb = remember { Path() }
    Box(
        modifier
            .size(width = 56.dp, height = 48.dp)
            .toggleable(on, interactionSource = source, indication = null, role = Role.Switch) {
                InkFeel.toggle(view, !on)
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            Modifier
                .size(width = 48.dp, height = 28.dp)
                .skyWindow {
                    val window = this
                    val v = progress.coerceIn(0f, 1f)
                    val w = size.width
                    val h = size.height
                    val r = h / 2f
                    val centre = Offset(r + (w - h) * progress, r)
                    track.rewind()
                    track.addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(r)))
                    // The window opens as a star from the thumb, trimmed to the track.
                    if (v > 0f) {
                        val reach = w * 1.25f * v
                        clipPath(track) {
                            window.drawSky(Star.path(centre, reach, reach, pinch = lerpF(Star.SOFT, -0.6f, v), into = iris))
                        }
                    }
                    // A hairline round the open window; the empty track's outline round the shut one.
                    val line = if (v > 0.5f) 1f else 1.6.dp.toPx()
                    drawRoundRect(
                        color = lerp(outline, edge, v),
                        topLeft = Offset(line / 2f, line / 2f),
                        size = Size(w - line, h - line),
                        cornerRadius = CornerRadius(r - line / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(line),
                        alpha = if (v > 0.5f) 0.75f else 1f,
                    )
                    // Off: an empty ring, a touch fuller under a finger, thinning as the star
                    // grows through it.
                    if (v < 1f) {
                        drawCircle(
                            ring, (5.dp.toPx()) * (1f + 0.15f * press), centre,
                            alpha = 1f - v, style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()),
                        )
                    }
                    // On: the paper star, edged dark so it stays clear over the brightest cloud.
                    if (v > 0f) {
                        val k = (r - 4.dp.toPx()) * (0.35f + 0.65f * v) * (1f + 0.1f * press)
                        val shape = Star.path(centre, k, pinch = Star.SOFT, rotation = (1f - v) * -90f, into = thumb)
                        drawPath(shape, ThumbEdge, alpha = v * 0.9f, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        drawPath(shape, onWindow, alpha = v)
                    }
                },
        )
    }
}

/** The dark edge round a switch's paper star. */
private val ThumbEdge = androidx.compose.ui.graphics.Color(0xFF03032A)

/**
 * The one switch for a whole page, at the end of the hero line, wearing its name ([label]) under
 * it in small capitals: "All effects", "Island", "Volume panel". The line beside it says what's
 * true (what's on, what the status bar shows, what the volume keys open), not what the switch is,
 * so a bare switch there reads as the line's own on or off: "Android's own icons", off. The name is
 * the control's, so it stays even where it's also the page's: it's what the eye and TalkBack read.
 */
@Composable
fun MasterSwitch(on: Boolean, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    // Flush right, so the switch keeps the page's end on every page whatever its name's length
    // (centred, "Volume panel" pulled it 20dp in from Island's), and the name ends where the
    // drawn channel does: the drawing sits 5dp inside the switch's 56dp target.
    Column(horizontalAlignment = Alignment.End) {
        InkSwitch(on, label, onClick)
        // The switch is already named by it, so TalkBack hears "Island, switch" once, not the
        // name again after it.
        dev.continuum.fx.ui.theme.Note(label, p.sub, Modifier.padding(end = 5.dp).clearAndSetSemantics {})
    }
}

/** The star crosses quickly and settles with the slightest give. */
private val SLIDE = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)

/** The value's tag rises bouncily and sinks back on Snap. */
private val RISE = spring<Float>(dampingRatio = 0.55f, stiffness = 300f)

/**
 * A slider, set as a measuring rule: a hairline runs the length of it with a scale hanging under,
 * the distance measured is cut out of the paper as a strip of sky sitting on the rule, and the
 * reading is a long-tailed star standing on it, its tail dropping through the scale. Under your
 * finger the star grows and the value rises above it on a tag of paper ([label]); let go and it
 * settles back. Inside an [InkSection] the rule is drawn out as the section's ink first reaches it.
 * [value] is 0..1; [muted] draws it in grey with the window shut.
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
    val ink = if (muted) p.sub else p.text
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
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val measurer = rememberTextMeasurer()
    val tagStyle = TextStyle(color = p.text, fontSize = 13.sp, fontFamily = dev.continuum.fx.ui.eidolon.GroteskTight, fontFeatureSettings = "tnum")
    val latest = rememberUpdatedState(onChange)
    val band = remember { Path() }
    val pointer = remember { Path() }

    /** How far the section's ink has reached this slider: 1 outside a section, or once it has. */
    fun reached(): Float {
        val t = thread ?: return 1f
        val mine = coords[0] ?: return 1f
        val sc = t.coords ?: return 1f
        if (!sc.isAttached || !mine.isAttached) return 1f
        val at = sc.localPositionOf(mine, Offset.Zero)
        return t.reach(at.y + mine.size.height / 2f)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                // A slider to TalkBack: where it is along its run, said as its own value ("+4 dB",
                // "33%"), and moved by swiping up and down or with the volume keys.
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f, steps = 0)
                if (label != null) stateDescription = label
                setProgress { next ->
                    latest.value(next.coerceIn(0f, 1f))
                    true
                }
            }
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
                        // A moment's hold before the tag rises, so a scroll passing over
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
            .skyWindow {
                val window = this
                val reach = reached()
                if (reach <= 0f) return@skyWindow
                val w = size.width * reach
                val line = size.height * 0.56f
                val kx = value.coerceIn(0f, 1f) * size.width * reach
                val thin = 1f.coerceAtLeast(0.5.dp.toPx())
                // The distance measured, cut out of the paper.
                val bandH = lerpF(9.dp.toPx(), 11.dp.toPx(), grab.value)
                if (kx > 0.5f) {
                    band.rewind()
                    band.addRect(Rect(0f, line - bandH, kx, line))
                    if (muted) drawPath(band, p.sub, alpha = 0.35f) else window.drawSky(band)
                }
                // The rule, solid where it has been measured, and its scale.
                drawLine(ink.copy(alpha = 0.45f), Offset(kx, line), Offset(w, line), thin)
                drawLine(ink, Offset(0f, line), Offset(kx, line), 1.5.dp.toPx())
                val marks = 20
                for (i in 0..marks) {
                    val x = w * i / marks
                    val major = i % 5 == 0
                    drawLine(
                        ink.copy(alpha = if (x <= kx) 0.9f else 0.5f),
                        Offset(x, line), Offset(x, line + if (major) 6.dp.toPx() else 3.dp.toPx()), thin,
                    )
                }
                // The reading: a four-point star with a long tail, a little larger under a finger.
                val g = grab.value
                drawPath(
                    tailedStar(
                        Offset(kx, line),
                        arm = lerpF(7.dp.toPx(), 9.dp.toPx(), g),
                        crown = lerpF(12.dp.toPx(), 15.dp.toPx(), g),
                        tail = lerpF(16.dp.toPx(), 19.dp.toPx(), g),
                        into = pointer,
                    ),
                    ink,
                )
            }
            .drawWithContent {
                drawContent()
                // The value, on a tag of paper rising above the star while it's held.
                val b = lift.value
                if (b > 0.02f && label != null) {
                    val text = measurer.measure(label, tagStyle)
                    val padX = 7.dp.toPx()
                    val tw = text.size.width + padX * 2f
                    val th = text.size.height + 4.dp.toPx()
                    val kx = value.coerceIn(0f, 1f) * size.width
                    val left = (kx - tw / 2f).coerceIn(-12.dp.toPx(), size.width + 12.dp.toPx() - tw)
                    val top = size.height * 0.56f - 11.dp.toPx() - 6.dp.toPx() - th - 6.dp.toPx() * (1f - b)
                    val a = b.coerceIn(0f, 1f)
                    drawRect(dev.continuum.fx.ui.eidolon.PasteShadow, Offset(left + 2.dp.toPx(), top + 2.dp.toPx()), Size(tw, th), alpha = a)
                    drawRect(p.sheet, Offset(left, top), Size(tw, th), alpha = a)
                    drawRect(p.text, Offset(left, top), Size(tw, th), alpha = a * 0.4f, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                    drawText(text, topLeft = Offset(left + padX, top + 2.dp.toPx()), alpha = a)
                }
            },
    )
}
