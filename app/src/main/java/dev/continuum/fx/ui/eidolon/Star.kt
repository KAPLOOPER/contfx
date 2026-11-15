/*
 * The four-point star: the one shape everything else is cut from.
 *
 * Its sides are the gaps left between four circles, so it reads as an opening rather than an
 * object: a window in the poster, an eyelid, a switch thumb, a slider's pointer.
 */

package dev.continuum.fx.ui.eidolon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

object Star {
    /** Sides are true quarter circles: the classic gap between four coins. */
    const val CIRCULAR = 0.5523f

    /** Pulled a little further in: long thin arms, for the poster window. */
    const val KEEN = 0.62f

    /** Fuller, so the shape survives at the size of a switch thumb. */
    const val SOFT = 0.44f

    /**
     * Writes a four-point star into [into] and returns it.
     *
     * @param rx half the width, tip to centre
     * @param ry half the height, tip to centre; shrinking it closes the star like an eyelid
     * @param pinch how hard the sides are drawn in toward the centre, 0 (a diamond) to 1 (a cross)
     * @param rotation degrees, clockwise
     */
    fun path(
        center: Offset,
        rx: Float,
        ry: Float = rx,
        pinch: Float = CIRCULAR,
        rotation: Float = 0f,
        into: Path = Path(),
    ): Path {
        val k = 1f - pinch
        into.rewind()
        into.moveTo(0f, -ry)
        into.cubicTo(0f, -ry * k, rx * k, 0f, rx, 0f)
        into.cubicTo(rx * k, 0f, 0f, ry * k, 0f, ry)
        into.cubicTo(0f, ry * k, -rx * k, 0f, -rx, 0f)
        into.cubicTo(-rx * k, 0f, 0f, -ry * k, 0f, -ry)
        into.close()
        val m = Matrix()
        m.translate(center.x, center.y)
        if (rotation != 0f) m.rotateZ(rotation)
        into.transform(m)
        return into
    }
}

fun DrawScope.drawStar(
    color: Color,
    center: Offset = this.center,
    radius: Float = size.minDimension / 2f,
    radiusY: Float = radius,
    pinch: Float = Star.CIRCULAR,
    rotation: Float = 0f,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
) {
    drawPath(Star.path(center, radius, radiusY, pinch, rotation), color, alpha = alpha, style = style)
}

/** A star inscribed in the layout bounds. */
class StarShape(private val pinch: Float = Star.CIRCULAR) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Star.path(Offset(size.width / 2f, size.height / 2f), size.width / 2f, size.height / 2f, pinch))
}

/** A small printed star: section marks, bullets, the lit state of anything. */
@Composable
fun StarGlyph(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    pinch: Float = Star.SOFT,
    rotation: Float = 0f,
) {
    val path = remember { Path() }
    Canvas(modifier.size(size)) {
        drawPath(Star.path(center, this.size.minDimension / 2f, pinch = pinch, rotation = rotation, into = path), color)
    }
}
