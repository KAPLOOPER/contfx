package dev.continuum.fx.ui.bench

/*
 * BenchParts: the page-shaped pieces that go with the shell but aren't the shell itself — the
 * shell owns the frame, the rows and the rules, and the pages that hold an instrument find their
 * parts here. One flat surface, one ink: no card, no radius, nothing but what the ink draws.
 */

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.ui.INK_RAIL
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Txt

/**
 * The slot a page's instrument sits in: full width, flat, nothing over it and nothing wrapped
 * round it — the drawing that goes here already stands on the ground, so the hero adds no chrome
 * of its own.
 */
@Composable
fun BenchHero(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) { content() }
}

/**
 * A labelled slider: the label on the left and the value on the right in the note face, and the
 * slider itself under them, with room for a mark at its left ([leading]) and a control at its
 * right ([trailing]) — the two ways a stream or an app needs to be stepped. [muted] greys the
 * label (a resting stream), [live] sets the value in the live colour (a boost over 100%). A
 * helper line ([note], for "grow this until it hugs your lens") hangs under the slider.
 */
@Composable
fun BenchField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    /** The stream or app mark in the label's rail, leaving the slider ends free for steppers. */
    mark: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    note: String? = null,
    live: Boolean = false,
    muted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    // One control to TalkBack: the slider, named by its label and value (the steppers either
    // side stay buttons of their own).
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(INK_RAIL), contentAlignment = Alignment.Center) { mark?.invoke() }
            // Three lines: an app's own name (Volume's Apps) can be long, and is what tells it apart
            // (two cut a long one off mid-word at 1.3).
            Txt(label, 17.sp, if (muted) p.sub else p.text, maxLines = 3, modifier = Modifier.weight(1f))
            Note(value, if (live) p.liveText else p.sub, Modifier.padding(start = 12.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Box(Modifier.weight(1f)) { content() }
            trailing?.invoke()
        }
        if (note != null) {
            Txt(note, 14.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL, top = 2.dp, bottom = 6.dp))
        }
    }
}

/**
 * A set of equal cells in [columns] columns, filled row by row: for a group of actions that should
 * read as one table, each word starting at the same x in every row, rather than as chips that
 * wrap wherever they happen to fit. Cells are as wide as the grid is, divided; each is as tall as
 * its row's tallest. When a cell's words can't fit its column whole (large type), the table
 * becomes one column, so nothing is ever cut to "Notific…".
 */
@Composable
fun BenchGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier,
        measurePolicy = { measurables, constraints ->
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            val fits = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= width / columns }
            val cols = if (fits) columns else 1
            val cellW = width / cols
            val cells = measurables.map { it.measure(Constraints(minWidth = cellW, maxWidth = cellW)) }
            val rows = cells.chunked(cols)
            val rowH = rows.map { r -> r.maxOf { it.height } }
            layout(cellW * cols, rowH.sum()) {
                var y = 0
                rows.forEachIndexed { ri, r ->
                    r.forEachIndexed { ci, c -> c.place(ci * cellW, y) }
                    y += rowH[ri]
                }
            }
        },
    )
}
