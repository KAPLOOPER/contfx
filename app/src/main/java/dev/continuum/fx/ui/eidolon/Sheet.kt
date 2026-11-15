/*
 * The sheet a page is printed on: one piece of paper pasted over the stage, cut into slips at each
 * of the page's section rules.
 */

package dev.continuum.fx.ui.eidolon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

/** Where the page's sheet runs across the screen, for a section rule to cut it from edge to edge. */
@Stable
class PageSheet {
    var left by mutableFloatStateOf(0f)
        internal set
    var right by mutableFloatStateOf(0f)
        internal set
}

val LocalPageSheet = staticCompositionLocalOf<PageSheet?> { null }

/**
 * Prints this node as the page's sheet: paper, a hairline edge and a hard paste shadow, and tells
 * [sheet] where it is.
 */
fun Modifier.pageSheet(sheet: PageSheet, paper: Color, edge: Color, shadow: Boolean): Modifier =
    this
        .onGloballyPositioned {
            val x = it.positionInRoot().x
            sheet.left = x
            sheet.right = x + it.size.width
        }.drawBehind {
            if (shadow) drawRect(PasteShadow, topLeft = Offset(4.dp.toPx(), 4.dp.toPx()), size = size)
        }.background(paper)
        .border(1.dp, edge)
