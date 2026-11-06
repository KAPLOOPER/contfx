package dev.continuum.fx.ui.bench

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.island.ui.Goo
import dev.continuum.fx.island.ui.InkFeel
import dev.continuum.fx.ui.components.inkNeck
import dev.continuum.fx.ui.components.squash
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Mark
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Txt
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Bench: the shell you operate the instrument through. One flat surface, one ink, a ruled grid,
 * and every control in a place you can find twice. Chrome lives in layout, never over it: the
 * header at the top, the status strip and the rail at the bottom, all part of the column, with
 * the scroll area ending above them. Nothing is ever hidden behind anything.
 */

/** The page's side margin, kept from the old build. */
internal val BENCH_MARGIN = 24.dp

/**
 * The header: the page's number in the margin, its title, and one exact note on the right —
 * "02  effects". 52dp tall, folding to 40dp once the page has been read down a little
 * ([collapsed]). Part of the column, never over the page.
 */
@Composable
fun BenchHeader(title: String, state: String?, collapsed: Boolean, index: Int = 0) {
    val p = LocalPalette.current
    val height by animateDpAsState(if (collapsed) 40.dp else 52.dp, Motion.flow(), label = "benchHeader")
    Row(
        Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .height(height)
            .padding(start = BENCH_MARGIN, end = BENCH_MARGIN),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index > 0) {
            // The page's place, in the note face, on the title's baseline: it ties the header to
            // the numbered rules below it.
            androidx.compose.foundation.text.BasicText(
                "%02d".format(index),
                modifier = Modifier.alignByBaseline(),
                style = TextStyle(
                    color = p.quiet,
                    fontSize = 11.sp,
                    fontFamily = Mark,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                    fontFeatureSettings = "tnum, case",
                ),
            )
            Spacer(Modifier.width(10.dp))
        }
        BasicText(
            title,
            modifier = Modifier.alignByBaseline(),
            style = TextStyle(
                color = p.text,
                fontSize = 26.sp,
                lineHeight = 26.sp * 1.15f,
                letterSpacing = (-0.3).sp,
                fontFamily = Libron,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (state != null && state.isNotEmpty()) Note(state, p.quiet)
    }
}

/**
 * The status strip, 42dp of words and one 48dp move, just above the rail. The icon is the power
 * action for whatever this page is about; [state] is the small exact word of where things stand;
 * [line] says what to do next ("Tap to set up · 2 steps"). Tapping the words does [action].
 * [lineContent] replaces the line's text for a page that needs hold-to-compare on it;
 * [trailing] is the rare extra control at the end.
 */
@Composable
fun BenchStatus(
    icon: Int?,
    on: Boolean,
    state: String,
    line: String,
    modifier: Modifier = Modifier,
    onToggle: (() -> Unit)? = null,
    toggleDescription: String? = null,
    action: (() -> Unit)? = null,
    actionLabel: String? = null,
    lineContent: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    Row(
        modifier
            .fillMaxWidth()
            // No top hairline of its own: above the home indicator only the rail keeps one.
            .heightIn(min = 48.dp)
            .padding(start = BENCH_MARGIN, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .squash(source)
                    .size(48.dp)
                    .then(
                        if (onToggle != null) {
                            Modifier
                                .toggleable(on, interactionSource = source, indication = null, role = Role.Switch) {
                                    InkFeel.toggle(view, !on)
                                    onToggle()
                                }
                        } else Modifier,
                    )
                    .let {
                        if (toggleDescription != null) it.semantics { contentDescription = toggleDescription } else it
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (on) {
                    // On: a filled drop of ink; live is the only colour, so ink stands for on.
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(p.text),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            colorFilter = ColorFilter.tint(p.ground),
                        )
                    }
                } else {
                    Image(
                        painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(p.quiet),
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .then(
                    if (lineContent == null && action != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = actionLabel,
                            onClick = action,
                        )
                    } else Modifier,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isNotEmpty()) {
                    Note(state, p.sub)
                    Spacer(Modifier.size(12.dp))
                }
                if (lineContent != null) {
                    lineContent(this)
                } else {
                    Txt(line, 15.sp, p.text, maxLines = 1)
                }
            }
        }
        trailing?.invoke()
    }
}

/**
 * The rail: all five destinations, every one one tap away. The words sit in five equal-weight
 * cells across the content width (the 24dp margins each side), 14sp, optically centred in their
 * cells, so no word can ever touch or pass the screen's edge; inactive `p.sub`, active `p.text`,
 * the live word carrying a 4x4dp drop of ink 7dp under its baseline, always there (the ink origin
 * for what's on it, read by the app's cards). Choosing a word is one liquid move: an ink pill
 * travels from the old word to the new one, its leading edge running ahead and its trailing edge
 * lagging, a small drop left behind shrinking away — the current page's drop never leaves while
 * it happens. [progress] gives the continuous page slot, so a swipe of the page keeps the rail in
 * step.
 */
@Composable
fun BenchRail(
    words: List<String>,
    current: Int,
    progress: () -> Float,
    onPick: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val density = LocalDensity.current
    val count = words.size
    val goo = remember(density) { if (Build.VERSION.SDK_INT >= 31) Goo.effect(with(density) { 2.6.dp.toPx() }) else null }
    // Where each cell's middle sits, in root coordinates, so the drawn ink can follow a swipe.
    val centres = remember(words) { mutableStateOf(FloatArray(count) { -1f }) }
    val origin = remember { mutableStateOf(Offset.Zero) }
    val rail = 56.dp
    // The drop rides 9dp under the words' middle: clear of a 14sp descender, still in the rail.
    val dropDrop = 9.dp

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(rail)
            .onGloballyPositioned { origin.value = it.positionInRoot() }
            .drawBehind {
                drawLine(p.line, Offset(0f, 1.dp.toPx() / 2f), Offset(size.width, 1.dp.toPx() / 2f), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Square)
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BENCH_MARGIN)
                .height(rail),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            words.forEachIndexed { i, word ->
                val s = progress()
                // The active colour runs ahead of the page by half a word, as the pill does.
                val d = (0..2).flatMap { k -> listOf(i + k * count, i - k * count) }
                    .minOfOrNull { u -> abs(s - u) } ?: 1f
                val color = lerp(p.text, p.sub, d.coerceIn(0f, 1f))
                val isCurrent = Math.floorMod(current, count) == i
                val source = remember(i) { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            selected = isCurrent
                            contentDescription = if (isCurrent) "$word, this page" else "Go to $word"
                        }
                        .clickable(interactionSource = source, indication = null, role = Role.Tab) {
                            if (!isCurrent) InkFeel.split(view)
                            onPick(i)
                        }
                        .onGloballyPositioned { c ->
                            val r = c.positionInRoot()
                            val cx = centres.value.copyOf()
                            cx[i] = r.x + c.size.width / 2f
                            centres.value = cx
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        word,
                        style = TextStyle(color = color, fontSize = 14.sp, fontFamily = Libron),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        // The ink: a resting drop under the live word, or, mid-move, the pill travelling with a
        // drying drop behind it.
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer { renderEffect = goo },
        ) {
            val cs = centres.value
            val ox = origin.value.x
            if (cs.any { it < 0f }) return@Canvas
            val cw = FloatArray(count) { cs[it] - ox }
            val ly = size.height / 2f + dropDrop.toPx()
            val pw = 4.dp.toPx()
            val half = pw / 2f
            fun cxOf(u: Int): Float {
                val i = Math.floorMod(u, count)
                val k = floor(u / count.toDouble()).toInt()
                return cw[i] + k * size.width
            }
            val s = progress()
            if (abs(s - s.roundToInt()) <= 0.02f) {
                val cur = Math.floorMod(s.roundToInt(), count)
                drawRoundRect(p.text, Offset(cw[cur] - half, ly - half), Size(pw, pw), CornerRadius(half))
                return@Canvas
            }
            val base = floor(s).toInt()
            val f = s - base
            val a = cxOf(base)
            val b = cxOf(base + 1)
            val head = (f * 1.6f).coerceIn(0f, 1f)
            val tail = ((f - 0.375f) * 1.6f).coerceIn(0f, 1f)
            val left = a + (b - a) * tail
            val right = a + (b - a) * head
            drawRoundRect(p.text, Offset(left, ly - half), Size(right - left, pw), CornerRadius(half))
            // The drop it left behind, drying up as the leading edge runs on.
            val r = half * (1f - head)
            if (r > 0.9f) {
                val c1 = Offset(a, ly)
                drawCircle(p.text, r, c1)
                inkNeck(c1, r, Offset(left, ly), half, reach = 1.2f)?.let { drawPath(it, p.text) }
            }
        }
    }
}

/**
 * The page frame: header / scrolling content / status strip / rail, all in normal layout flow, so
 * nothing is ever occluded. [scrollArea] fills the space between header and status; [status]
 * holds whatever the page's strip says; the rail is the five destinations. The scroll area's
 * bottom 14dp fade to the ground, so a row is never sliced by the viewport's edge.
 */
@Composable
fun BenchPage(
    title: String,
    note: String?,
    collapsed: Boolean,
    pageIndex: Int = 0,
    sections: List<String>,
    railCurrent: Int,
    railProgress: () -> Float,
    onRailPick: (Int) -> Unit,
    status: @Composable () -> Unit,
    /** While a sheet is up, the page behind it sits back rather than competing with it. */
    dimmed: Boolean = false,
    scrollArea: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        BenchHeader(title, note, collapsed, pageIndex)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            scrollArea()
            if (dimmed) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(p.ground.copy(alpha = 0.78f)),
                )
            }
            // The page dissolves into the ground under the header, so a row is never sliced by it.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(16.dp)
                    .drawBehind {
                        drawRect(Brush.verticalGradient(0f to p.ground, 1f to Color.Transparent))
                    },
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(14.dp)
                    .drawBehind {
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to p.ground))
                    },
            )
        }
        status()
        BenchRail(sections, railCurrent, railProgress, onRailPick)
    }
}

/**
 * A section's rule: its number, its name, and a hairline running from 8dp after the name to the
 * right edge, on the name's baseline. No box, no card, no border: space does the grouping.
 */
@Composable
fun BenchSection(index: Int?, name: String, trail: String? = null) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 26.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (index != null) {
            Note("%02d".format(index), p.quiet, Modifier.padding(bottom = 1.5.dp))
            Spacer(Modifier.width(10.dp))
        }
        BasicText(
            name.uppercase(),
            modifier = Modifier.alignByBaseline(),
            style = TextStyle(
                color = p.sub,
                fontSize = 13.sp,
                fontFamily = Libron,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp,
            ),
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .alignByBaseline()
                .height(1.dp)
                .background(p.line),
        )
        if (trail != null) {
            // What the group is doing, at the end of its rule: a second axis to scan by.
            Spacer(Modifier.width(8.dp))
            Note(trail, p.quiet, Modifier.padding(bottom = 1.5.dp))
        }
    }
}

/**
 * A row of the ledger. On, it carries a 7dp drop of ink 10dp in from the margin, sitting on the
 * label's first-line centre line (invisible spacing when off, so labels never shift); off, its
 * label is `p.sub`. The label is 16.5sp, the caption 13sp single line — always `p.sub`, whether
 * the effect is on or off: the label and the drop carry the state, so the caption never competes.
 * The trailing control (the ink switch, or whatever [trailing] is) hangs off the label line; a
 * row with a switch shows no chevron (the whole row already opens on tap), so its caption runs
 * the full width to the switch. Where [onExpand] and [content] are given, tapping opens the block
 * right under the row: indented 16dp with a 1dp ink thread down its left. The switch toggles;
 * the row opens: taps don't double-fire.
 */
@Composable
fun BenchRow(
    label: String,
    caption: String?,
    on: Boolean,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    onToggle: (() -> Unit)? = null,
    switchDescription: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    resting: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(Motion.flow()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(
                    if (content != null && onExpand != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = if (expanded) "Close" else "Open",
                            onClick = {
                                if (expanded) InkFeel.merge(view) else InkFeel.split(view)
                                onExpand()
                            },
                        )
                    } else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (on) {
                    // A 7dp drop, its left edge 10dp in from the margin, sitting on the label's
                    // first-line centre: two lines of label under it (label 22.8dp, caption
                    // 17.9dp, both centred) put that centre 9dp above the row's middle.
                    Box(
                        Modifier
                            .size(7.dp)
                            .offset(y = if (caption != null) (-9).dp else 0.dp)
                            .clip(CircleShape)
                            .background(if (resting) p.quiet else p.text),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(label, 16.5.sp, if (on) p.text else p.sub, maxLines = 1)
                if (caption != null) {
                    // Two lines is the most the ledger gives a caption; past that it would be prose.
                    Txt(caption, 13.sp, p.sub, maxLines = 2)
                }
            }
            if (content != null && onExpand != null && onToggle == null) {
                Spacer(Modifier.width(10.dp))
                dev.continuum.fx.ui.Chevron(expanded, p.quiet, 15.dp)
            }
            if (onToggle != null) {
                // A small chevron keeps "the row opens" visible even when a switch sits here.
                if (content != null && onExpand != null) {
                    Spacer(Modifier.width(8.dp))
                    dev.continuum.fx.ui.Chevron(expanded, p.quiet, 13.dp)
                }
                Spacer(Modifier.width(10.dp))
                dev.continuum.fx.ui.components.InkSwitch(on, switchDescription ?: label, onToggle)
            } else if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
        if (content != null && expanded) {
            BenchThread { content() }
        }
    }
}

/**
 * A row that leads somewhere: its name, its value on the right in small exact type, and (opened)
 * the block it leads to under it, threaded like every other opened block.
 */
@Composable
fun BenchValueRow(
    label: String,
    value: String,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(Motion.flow()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(
                    if (content != null && onExpand != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = if (expanded) "Close" else "Open",
                            onClick = {
                                if (expanded) InkFeel.merge(view) else InkFeel.split(view)
                                onExpand()
                            },
                        )
                    } else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Txt(label, 16.5.sp, p.text, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            dev.continuum.fx.ui.ValueNote(value)
            if (content != null && onExpand != null) {
                Spacer(Modifier.width(8.dp))
                dev.continuum.fx.ui.Chevron(expanded, p.quiet, 15.dp)
            }
        }
        if (content != null && expanded) {
            BenchThread { content() }
        }
    }
}

/** The quiet secondary controls: still a 48dp target, still a chevron, still one word. */
@Composable
fun BenchMore(label: String, open: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 15.sp, p.sub, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        dev.continuum.fx.ui.Chevron(open, p.quiet, 15.dp)
    }
}

/**
 * What opening looks like now: no card. The block is indented 16dp with a 1dp thread of ink down
 * its left, every control hanging off it.
 */
@Composable
private fun BenchThread(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(p.text, Offset(0.5.dp.toPx(), 0f), Offset(0.5.dp.toPx(), size.height), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Square)
            }
            .padding(start = 26.dp, top = 4.dp, bottom = 14.dp),
    ) {
        content()
    }
}

