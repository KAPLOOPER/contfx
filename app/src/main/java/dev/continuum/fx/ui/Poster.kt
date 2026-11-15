package dev.continuum.fx.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.continuum.fx.ui.eidolon.Star
import kotlin.math.min
import kotlin.math.pow

/*
 * The masthead's poster. A four-point star is cut out of the paper behind the masthead's top-right
 * corner, and through it the sky drifts; the page's sheet is pasted over its lower arm. Read down a
 * page and the star opens wider and wider, drifts to the middle and swells, until there is no paper
 * left: the sky the poster framed becomes the night the page's slips sit on, and the masthead's
 * titles turn paper-coloured over it.
 */

/** How far down a page has to be read for the poster to open over everything. */
private val OPEN_OVER = 240.dp

/** How far the poster has opened for a page read [scrollPx] down: 0 at rest, 1 open. Eased. */
internal fun posterOpening(scrollPx: Int, density: Density): Float {
    val t = (scrollPx / with(density) { OPEN_OVER.toPx() }).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The star in the stage's coordinates, for a stage [width] by [height] under a status bar
 * [topInset] pixels tall, opened by [opening].
 */
internal fun Density.posterStar(width: Float, height: Float, topInset: Float, opening: Float, into: Path): Path {
    val e = opening.coerceIn(0f, 1f)
    val x0 = width - 60.dp.toPx()
    val y0 = topInset + 96.dp.toPx()
    // It only starts drifting to the middle once it has opened a way.
    val drift = ((e - 0.35f) / 0.65f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val centre = Offset(x0 + (width * 0.5f - x0) * drift, y0 + (height * 0.45f - y0) * drift)
    val grow = 1f + 9f * e.pow(1.5f)
    val rx = min(230.dp.toPx(), width * 0.56f)
    return Star.path(centre, rx * grow, 150.dp.toPx() * grow, pinch = Star.KEEN + (-0.4f - Star.KEEN) * e, into = into)
}

/** The paper with the star cut out of it, over the sky. */
internal fun DrawScope.drawPosterPaper(topInset: Float, opening: Float, paper: Color, star: Path, sheet: Path) {
    if (opening >= 0.999f) return
    posterStar(size.width, size.height, topInset, opening, star)
    sheet.rewind()
    sheet.fillType = PathFillType.EvenOdd
    sheet.addRect(Rect(Offset.Zero, size))
    sheet.addPath(star)
    drawPath(sheet, paper)
}
