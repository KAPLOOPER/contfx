package dev.continuum.keyboard.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.keyboard.R

val Libron = FontFamily(
    Font(R.font.libron_regular, FontWeight.Normal),
    Font(R.font.libron_bold, FontWeight.Bold),
)

object Motion {
    fun <T> flow() = spring<T>(dampingRatio = 0.613f, stiffness = 240f)
    fun <T> snap() = spring<T>(dampingRatio = 0.843f, stiffness = 620f)
}

@Composable
fun Txt(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontFamily = Libron, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A Continuum line icon. */
@Composable
fun LineIcon(d: String, color: Color, size: Dp, modifier: Modifier = Modifier, fill: Boolean = false) {
    val path = remember(d) { Icons.path(d).asComposePath() }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            if (fill) drawPath(path, color)
            drawPath(path, color, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A pressable round bubble with an icon, Continuum's icon-only button. */
@Composable
fun IconBubble(
    d: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, if (pressed) Motion.snap() else Motion.flow(), label = "bubble")
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(background)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                    onLongPress = onLongClick?.let { lc -> { _: Offset -> lc() } },
                )
            },
        contentAlignment = Alignment.Center,
    ) { LineIcon(d, tint, iconSize) }
}

/** A pressable pill with a label, squashing wide (1.05 x 0.86) as pills do. */
@Composable
fun Pill(
    label: String,
    background: Color,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    height: Dp = 44.dp,
) {
    var pressed by remember { mutableStateOf(false) }
    val sx by animateFloatAsState(if (pressed) 1.05f else 1f, if (pressed) Motion.snap() else Motion.flow(), label = "pillX")
    val sy by animateFloatAsState(if (pressed) 0.86f else 1f, if (pressed) Motion.snap() else Motion.flow(), label = "pillY")
    Row(
        modifier
            .height(height)
            .graphicsLayer { scaleX = sx; scaleY = sy }
            .clip(RoundedCornerShape(50))
            .background(background)
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LineIcon(icon, color, 18.dp)
            Box(Modifier.size(8.dp))
        }
        Txt(label, 16.sp, color, maxLines = 1)
    }
}

fun Int.toColor() = Color(this)
