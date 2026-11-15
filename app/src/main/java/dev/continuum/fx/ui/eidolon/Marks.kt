/*
 * Small shared pieces of the print: hairlines, the pointer star, the paste shadow, register marks.
 */

package dev.continuum.fx.ui.eidolon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import kotlin.math.hypot

/** The width of one device pixel, so rules stay rules on every density. */
val hairline: Dp
    @Composable get() = with(LocalDensity.current) { 1f.toDp() }

/** The shadow of a pasted piece of paper: a hard offset block, like a second, misregistered pass. */
val PasteShadow = Color(0xFF00000F).copy(alpha = 0.5f)

internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

/** The radius that covers a [Size] from its centre: how far a window has to open to fill it. */
internal fun Size.coverRadius(): Float = hypot(width, height) / 2f

/**
 * A four-point star whose arms differ: [arm] to each side, [crown] up and [tail] down, the sides
 * drawn in hard like the gap between coins. The long tail makes it a pointer.
 */
internal fun tailedStar(centre: Offset, arm: Float, crown: Float, tail: Float, into: Path = Path()): Path {
    val k = 1f - Star.KEEN
    into.rewind()
    into.moveTo(centre.x, centre.y - crown)
    into.cubicTo(centre.x, centre.y - crown * k, centre.x + arm * k, centre.y, centre.x + arm, centre.y)
    into.cubicTo(centre.x + arm * k, centre.y, centre.x, centre.y + tail * k * 0.6f, centre.x, centre.y + tail)
    into.cubicTo(centre.x, centre.y + tail * k * 0.6f, centre.x - arm * k, centre.y, centre.x - arm, centre.y)
    into.cubicTo(centre.x - arm * k, centre.y, centre.x, centre.y - crown * k, centre.x, centre.y - crown)
    into.close()
    return into
}

/**
 * A soft dark halo for type laid over a window, strengthening with [open], so a paper-coloured
 * label stays readable over the brightest cloud.
 */
fun windowTextShadow(open: Float): Shadow =
    Shadow(color = Color(0xFF03032A).copy(alpha = 0.95f * open.coerceIn(0f, 1f)), blurRadius = 9f)

/** A printer's register mark: a hairline cross through a ring. */
@Composable
fun RegisterMark(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val r = this.size.minDimension / 2f
        drawCircle(color, r * 0.55f, center, style = Stroke(1f))
        drawLine(color, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1f)
        drawLine(color, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1f)
    }
}
