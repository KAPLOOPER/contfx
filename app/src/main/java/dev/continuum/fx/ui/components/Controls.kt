package dev.continuum.fx.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
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

enum class Surface { Ground, Card, Ink }

/** Flat, fully rounded button. Primary is a solid fill; otherwise one tone off its background. */
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
    val bg: Color
    val fg: Color
    when {
        primary && on == Surface.Ink -> { bg = p.onInk; fg = p.ink }
        primary -> { bg = p.primary; fg = p.onPrimary }
        on == Surface.Ink -> { bg = p.inkTonal; fg = p.onInk }
        on == Surface.Card -> { bg = p.ground; fg = p.text }
        else -> { bg = p.surface; fg = p.text }
    }
    Row(
        modifier
            .squash(source, pill = true)
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(fg))
        }
        Txt(text, 16.sp, fg, maxLines = 1)
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
    val bubbleColor = when (on) {
        Surface.Ink -> p.inkTonal
        Surface.Card -> p.ground
        Surface.Ground -> p.surface
    }
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

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, on: Surface = Surface.Ground) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val restBg = when (on) {
        Surface.Card -> p.ground
        Surface.Ink -> p.inkTonal
        Surface.Ground -> p.surface
    }
    val selBg = if (on == Surface.Ink) p.onInk else p.primary
    val selFg = if (on == Surface.Ink) p.ink else p.onPrimary
    val restFg = if (on == Surface.Ink) p.onInk else p.text
    val bg by animateColorAsState(if (selected) selBg else restBg, Motion.flow(), label = "chipBg")
    val fg by animateColorAsState(if (selected) selFg else restFg, Motion.flow(), label = "chipFg")
    Box(
        Modifier
            .squash(source, pill = true)
            .height(38.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, 15.sp, fg, maxLines = 1)
    }
}

/** The power control in the dock: tonal when off, a solid near-white fill when on. */
@Composable
fun PowerButton(on: Boolean, @DrawableRes icon: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (on) p.onInk else p.inkTonal, Motion.flow(), label = "powerBg")
    val fg by animateColorAsState(if (on) p.ink else p.onInk, Motion.flow(), label = "powerFg")
    Box(
        Modifier
            .squash(source)
            .size(56.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = source, indication = null, role = Role.Switch, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(icon),
            contentDescription = if (on) "Turn processing off" else "Turn processing on",
            modifier = Modifier.size(26.dp),
            colorFilter = ColorFilter.tint(fg),
        )
    }
}

/** Small round switch for a filter: solid fill with a tick when on, flat and empty when off. */
@Composable
fun OnOffDot(on: Boolean, @DrawableRes checkIcon: Int, description: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (on) p.primary else p.ground, Motion.flow(), label = "dot")
    Box(
        Modifier
            .size(44.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.squash(source).size(30.dp).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                Image(painterResource(checkIcon), contentDescription = description, modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(p.onPrimary))
            }
        }
    }
}

/**
 * Slider in the Continuum scrubber style: a flat track that thickens under the
 * finger on the Snap spring and settles back on Flow. [value] is 0..1.
 */
@Composable
fun InkSlider(
    value: Float,
    onChange: (Float) -> Unit,
    track: Color,
    fill: Color,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(
        if (dragging) 12.dp else 6.dp,
        if (dragging) Motion.snap() else Motion.flow(),
        label = "thickness",
    )
    val latest = rememberUpdatedState(onChange)
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
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
                detectTapGestures { o -> latest.value((o.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(thickness)
                .clip(RoundedCornerShape(50))
                .background(track),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(fill),
            )
        }
    }
}
