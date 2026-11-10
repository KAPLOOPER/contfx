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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.island.ui.Goo
import dev.continuum.fx.island.ui.InkFeel
import dev.continuum.fx.ui.INK_RAIL
import dev.continuum.fx.ui.opens
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
 * The ledger: how a page's settings are laid out under its instrument. A 24dp margin for marks,
 * a first column for each row's drop, and words from 50dp, on every page. It's drawn in the same
 * ink as everything else: a row that's on carries a drop, and opening a row drips a thread from
 * that drop down the controls it holds, the controls appearing as the ink reaches them.
 */

/** The least air between a section's number and its name, at any type size. */
internal val NUMBER_GAP = 8.dp

/** A margin number's letter spacing: less than a note's, so two digits fit the rail at large type. */
internal val NUMBER_TRACKING = 0.3.sp

/**
 * How many lines a row's caption may take. Captions carry facts ("or takes it away below 0"), so
 * they run to what they say rather than being ellipsised mid-sentence; the limit is only there to
 * stop a runaway string, and is past what any caption here needs at 2.0 type (the longest, the
 * keyboard's Learned words, runs to about twelve). The keyboard's settings keep the same number.
 */
private const val CAPTION_LINES = 16

/**
 * The air above and below a row's words. Enough that a caption reads as its row's and the next
 * name as the next row (about three times the gap between a name and its caption), and no more,
 * so a long group like Effects or Island's Shows doesn't spend a screen on space.
 */
private val ROW_PAD = 8.dp

/** Where a row's drop sits: the middle of its first column. */
private val DROP_IN = 3.5.dp

/**
 * A section's rule: its number, its name, and a hairline running from 8dp after the name to the
 * right edge, on the name's baseline, with what the group is doing at the end of it. No box, no
 * card, no border: space does the grouping.
 *
 * [index] is the group's place among the page's own groups, counted from 01; null only for end
 * matter (the colophon, a sheet's Backup), which closes a page rather than being part of it. The
 * whole rule for headings is written beside the disclosure rule, at [dev.continuum.fx.ui.OpensName].
 * [onInk] sets it on the dock's ink, in a sheet.
 */
@Composable
fun BenchSection(index: Int?, name: String, trail: String? = null, onInk: Boolean = false) {
    val p = LocalPalette.current
    val nameColor = if (onInk) p.onInkSub else p.sub
    val quiet = if (onInk) p.onInkSub else p.quiet
    val rule = if (onInk) androidx.compose.ui.graphics.lerp(p.ink, p.onInkSub, 0.4f) else p.line
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 6.dp)
            // TalkBack meets it as a heading, by its name and what it says at its end; the number
            // is only where it sits.
            .clearAndSetSemantics {
                heading()
                contentDescription = name + (trail?.let { ", $it" } ?: "")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The number has the rail, and the name begins where the rail ends: unless the type is
        // large enough that the number needs more, and then the name makes room (a gap of its own
        // is always kept), so "01" never runs into "THIS EQ".
        Box(Modifier.widthIn(min = INK_RAIL), contentAlignment = Alignment.CenterStart) {
            if (index != null) Note("%02d".format(index), quiet, Modifier.padding(end = NUMBER_GAP), tracking = NUMBER_TRACKING)
        }
        // The name, the rule level with its middle out to the edge, and the trail at the end. At
        // large type the trail keeps its words whole and the name wraps between its own words
        // rather than either being cut off; the rule keeps at least a stroke's length between them.
        RuleLine(
            name = {
                BasicText(
                    name.uppercase(),
                    style = TextStyle(
                        color = nameColor,
                        fontSize = 13.sp,
                        fontFamily = Libron,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.2.sp,
                    ),
                    maxLines = 2,
                )
            },
            rule = { Box(Modifier.height(1.dp).background(rule)) },
            trail = trail?.let { t -> { Note(t, quiet) } },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A rule's name, its hairline and its trail. On one line while they fit; at large type the trail
 * moves under the rule's far end, on a line of its own, so the name stays one phrase and the trail
 * is never split ("BUILD / 63") or run into the name. A name too long for a line by itself wraps
 * between its words, never inside one.
 */
@Composable
private fun RuleLine(
    name: @Composable () -> Unit,
    rule: @Composable () -> Unit,
    trail: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(name, rule, trail ?: {}),
        modifier = modifier,
    ) { (names, rules, trails), c ->
        val gap = 8.dp.roundToPx()
        val least = 16.dp.roundToPx()
        val w = c.maxWidth
        val nm = names.first()
        val tm = trails.firstOrNull()
        val stroke = 1.dp.roundToPx()
        if (tm == null) {
            val n = nm.measure(Constraints(maxWidth = (w - gap - least).coerceAtLeast(0)))
            val r = rules.first().measure(Constraints.fixed((w - n.width - gap).coerceAtLeast(0), stroke))
            return@Layout layout(w, n.height) {
                n.place(0, 0)
                r.place(n.width + gap, n.height / 2)
            }
        }
        val trailWhole = tm.maxIntrinsicWidth(Constraints.Infinity)
        val nameWhole = nm.maxIntrinsicWidth(Constraints.Infinity)
        val beside = w - trailWhole - gap - least - gap
        val alone = (w - gap - least).coerceAtLeast(0)
        // 1. All of it on one line. 2. Past that, the name stays whole (a heading is read as one
        // phrase) and the trail takes a line of its own under the rule's far end. 3. Only a name
        // too long for a line by itself wraps, between its words: the trail stays beside it if it
        // fits, or goes under it, the hairline then leading into it rather than hanging as a
        // stray dash between the name's lines.
        if (nameWhole <= beside || (nameWhole > alone && nm.minIntrinsicWidth(Constraints.Infinity) <= beside)) {
            val n = nm.measure(Constraints(maxWidth = beside))
            val t = tm.measure(Constraints(maxWidth = trailWhole))
            val r = rules.first().measure(Constraints.fixed((w - n.width - gap - t.width - gap).coerceAtLeast(0), stroke))
            val h = maxOf(n.height, t.height)
            layout(w, h) {
                n.place(0, (h - n.height) / 2)
                r.place(n.width + gap, h / 2)
                t.place(w - t.width, (h - t.height) / 2)
            }
        } else {
            val oneLine = nameWhole <= alone
            val n = nm.measure(Constraints(maxWidth = alone))
            val t = tm.measure(Constraints(maxWidth = w))
            val under = 2.dp.roundToPx()
            val rw = if (oneLine) w - n.width - gap else w - t.width - gap
            val r = rules.first().measure(Constraints.fixed(rw.coerceAtLeast(0), stroke))
            layout(w, n.height + under + t.height) {
                n.place(0, 0)
                if (oneLine) r.place(n.width + gap, n.height / 2) else r.place(0, n.height + under + t.height / 2)
                t.place(w - t.width, n.height + under)
            }
        }
    }
}

/**
 * A row's name and what sits at the end of its line (a value, a state, a mode). The name is what
 * you read first, so it keeps its line whole while the value can still fit its longest word
 * beside it, wrapping between its own words ("NEEDS / YOU"); only past that does the name wrap
 * too, between its words and never inside one, and the value takes what's left.
 */
@Composable
internal fun NameAndValue(
    name: @Composable () -> Unit,
    value: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(name, value ?: {}),
        modifier = modifier,
    ) { (names, values), c ->
        val w = c.maxWidth
        val nm = names.first()
        val v = values.firstOrNull()
        if (v == null) {
            val n = nm.measure(Constraints(maxWidth = w))
            return@Layout layout(w, n.height) { n.place(0, 0) }
        }
        val g = gap.roundToPx()
        val nameWhole = nm.maxIntrinsicWidth(Constraints.Infinity)
        val valueWhole = v.maxIntrinsicWidth(Constraints.Infinity)
        val valueLeast = v.minIntrinsicWidth(Constraints.Infinity)
        val room = when {
            nameWhole + g + valueWhole <= w -> valueWhole
            nameWhole + g + valueLeast <= w -> w - nameWhole - g
            else -> (w - nm.minIntrinsicWidth(Constraints.Infinity) - g).coerceAtLeast(w / 3)
        }
        val vp = v.measure(Constraints(maxWidth = room.coerceIn(0, w)))
        val n = nm.measure(Constraints(maxWidth = (w - vp.width - g).coerceAtLeast(0)))
        val h = maxOf(n.height, vp.height)
        layout(w, h) {
            n.place(0, (h - n.height) / 2)
            vp.place(w - vp.width, (h - vp.height) / 2)
        }
    }
}

/**
 * A row of the ledger. On, a drop of ink rests in its first column on the label's line (swelling
 * in as it's switched on, drying away as it's switched off); off, its label is `p.sub`. The label
 * is 16.5sp, the caption 13sp, up to two lines, always `p.sub`: the label and the drop carry the
 * state. With [content], tapping the row opens it: a thread drips from the drop down the left of
 * what it holds, the controls appearing as the ink passes them, and closing draws it back up. The
 * switch toggles and the row opens: taps don't double-fire.
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
    /** The row does something instead of opening (it takes you to a sheet): no chevron, one tap. */
    onAct: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    val opens = content != null && onExpand != null
    // Where the label's line sits down the row, for the drop to rest on it.
    val labelY = remember { mutableFloatStateOf(-1f) }
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(2) }
    fun measureLabel() {
        val row = placed[0] ?: return
        val text = placed[1] ?: return
        if (!row.isAttached || !text.isAttached) return
        val y = row.localPositionOf(text, Offset.Zero).y + text.size.height / 2f
        if (kotlin.math.abs(y - labelY.floatValue) > 0.5f) labelY.floatValue = y
    }
    dev.continuum.fx.ui.components.InkSection(
        open = opens && expanded,
        modifier = modifier,
        threadIn = DROP_IN,
        dropAt = { labelY.floatValue },
        on = on,
        resting = resting,
        header = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    // When switching changes the caption (what an effect does, then what it's
                    // doing: two lines become one), the row's height flows, so the rows under
                    // your finger glide rather than jump.
                    .animateContentSize(Motion.flow())
                    .onGloballyPositioned {
                        placed[0] = it
                        measureLabel()
                    }
                    .then(
                        if (opens) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClickLabel = if (expanded) "Close" else "Open",
                                onClick = { onExpand?.invoke() },
                            ).opens(expanded) { onExpand?.invoke() }
                        } else if (onAct != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onAct,
                            )
                        } else Modifier,
                    )
                    .padding(vertical = ROW_PAD),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The drop's column: the ink is drawn by the section, so the label never moves.
                Spacer(Modifier.width(INK_RAIL))
                Column(Modifier.weight(1f)) {
                    // What a row is (a mode, a state) is said at the end of its name's line, so the
                    // caption under it has the whole width. A switch is taller than the line, so it
                    // sits at the end of the whole row.
                    NameAndValue(
                        name = {
                            dev.continuum.fx.ui.OpensName(
                                label, expanded, if (on) p.text else p.sub, chevron = opens,
                                textModifier = Modifier.onGloballyPositioned {
                                    placed[1] = it
                                    measureLabel()
                                },
                            )
                        },
                        value = trailing?.takeIf { onToggle == null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (caption != null) Txt(caption, 14.sp, p.sub, maxLines = CAPTION_LINES, modifier = Modifier.padding(top = 3.dp))
                }
                if (onToggle != null) {
                    Spacer(Modifier.width(10.dp))
                    dev.continuum.fx.ui.components.InkSwitch(on, switchDescription ?: label, onToggle)
                }
            }
        },
    ) {
        if (content != null) {
            Column(Modifier.fillMaxWidth().padding(start = INK_RAIL, top = 4.dp, bottom = 14.dp)) { content() }
        }
    }
}

/**
 * A row that holds a value: its name, and the value at the far end in small exact type. With
 * [content] the row opens in place, and says so the way every opening word does: a chevron right
 * after the name (it turns over once open), never after the value. [caption] sits under the name
 * like any ledger row's. Without [content] it's only a value to read.
 */
@Composable
fun BenchValueRow(
    label: String,
    value: String,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    caption: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    val opens = content != null && onExpand != null
    dev.continuum.fx.ui.components.InkSection(
        open = opens && expanded,
        threadIn = DROP_IN,
        header = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .then(
                        if (opens) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClickLabel = if (expanded) "Close" else "Open",
                                onClick = { onExpand?.invoke() },
                            ).opens(expanded) { onExpand?.invoke() }
                        } else Modifier,
                    )
                    .padding(vertical = if (caption != null) ROW_PAD else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(INK_RAIL))
                Column(Modifier.weight(1f)) {
                    NameAndValue(
                        name = { dev.continuum.fx.ui.OpensName(label, expanded, p.text, chevron = opens) },
                        value = { Note(value, p.sub) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (caption != null) Txt(caption, 14.sp, p.sub, maxLines = CAPTION_LINES, modifier = Modifier.padding(top = 3.dp))
                }
            }
        },
    ) {
        if (content != null) {
            Column(Modifier.fillMaxWidth().padding(start = INK_RAIL, top = 4.dp, bottom = 14.dp)) { content() }
        }
    }
}

/**
 * A row that goes to a page of its own (the Lab, one of the keyboard's settings pages): its name
 * and what's there, a value if it has one, and an arrow at the very end, because it leaves this
 * page rather than opening in it.
 */
@Composable
fun BenchLinkRow(
    label: String,
    caption: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClickLabel: String? = null,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                // It goes to a page of its own, so TalkBack hears where: "double tap to open Look".
                onClickLabel = onClickLabel ?: "Open $label",
                onClick = onOpen,
            )
            .padding(vertical = ROW_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(INK_RAIL))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NameAndValue(
                    name = { Txt(label, 17.sp, p.text, maxLines = 2) },
                    value = value?.let { v -> { dev.continuum.fx.ui.theme.Note(v, p.sub) } },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                dev.continuum.fx.ui.Chevron(null, p.quiet, 15.dp)
            }
            if (caption != null) Txt(caption, 14.sp, p.sub, maxLines = CAPTION_LINES, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
