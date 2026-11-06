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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    note: String? = null,
    live: Boolean = false,
    muted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(label, 16.5.sp, if (muted) p.sub else p.text, maxLines = 1, modifier = Modifier.weight(1f))
            Note(value, if (live) p.live else p.sub, Modifier.padding(start = 12.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Box(Modifier.weight(1f)) { content() }
            trailing?.invoke()
        }
        if (note != null) {
            Txt(note, 13.sp, p.sub, modifier = Modifier.padding(start = 26.dp, top = 2.dp))
        }
    }
}

/**
 * The chips wrapped, never run off the page's edge: rows of whatever [content] lays out, [gap]
 * between the chips in a row and between the rows, measured as they are and broken where they
 * would pass the width. No scroll, no fade, nothing ever clipped — the manual, predictable wrap.
 */
@Composable
fun BenchWrap(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier,
        measurePolicy = { measurables, constraints ->
            val gapPx = gap.roundToPx()
            val cells = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            if (cells.isEmpty()) {
                return@Layout layout(constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: 0, 0) {}
            }
            val xs = IntArray(cells.size)
            val ys = IntArray(cells.size)
            var x = 0
            var y = 0
            var rowHeight = 0
            cells.forEachIndexed { i, c ->
                val roomy = constraints.hasBoundedWidth
                if (roomy && x > 0 && x + c.width > constraints.maxWidth) {
                    y += rowHeight + gapPx
                    x = 0
                    rowHeight = 0
                }
                xs[i] = x
                ys[i] = y
                x += c.width + gapPx
                rowHeight = maxOf(rowHeight, c.height)
            }
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else x - gapPx
            layout(width, y + rowHeight) {
                cells.forEachIndexed { i, c -> c.place(xs[i], ys[i]) }
            }
        },
    )
}
