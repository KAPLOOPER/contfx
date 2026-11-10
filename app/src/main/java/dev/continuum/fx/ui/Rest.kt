package dev.continuum.fx.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.continuum.fx.R
import dev.continuum.fx.ui.theme.Motion
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Txt

/**
 * A rest state: what part of the app looks like with nothing in it yet. The same three things
 * every time: the resting drop (the one under the page's title, so empty reads as calm rather than
 * missing), one sentence, and at most one thing to do. No action is offered where there's nothing
 * for you to do but wait (an app has to play something first, say).
 *
 * Shown with its page, the drop is simply there; turning up while you're looking (the last filter
 * removed, say), it drips from the title's drop, as the rest of the app's ink does.
 */
@Composable
internal fun Rest(
    text: String,
    modifier: Modifier = Modifier,
    /** Nested rests use the open section's thread instead of adding a second rail. */
    inRail: Boolean = true,
    /** Kept in place while false, so the drop can rise back into the title's drop as it goes. */
    visible: Boolean = true,
    action: String? = null,
    @DrawableRes icon: Int? = null,
    on: Surface = Surface.Ground,
    onAction: () -> Unit = {},
) {
    val p = LocalPalette.current
    val seeds = LocalSeeds.current
    val drips = remember { on == Surface.Ground && seeds != null && System.currentTimeMillis() - seeds.pageAt > 700 }
    Row(
        modifier.fillMaxWidth().padding(vertical = if (visible) 10.dp else 0.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (inRail) {
            Box(Modifier.width(INK_RAIL)) {
                Seeded(
                    visible = visible,
                    corner = 4.5.dp,
                    color = p.text,
                    settledAtStart = !drips,
                    modifier = Modifier.padding(start = 3.5.dp, top = 8.dp).size(9.dp),
                ) {}
            }
        }
        Column(Modifier.weight(1f)) {
            if (visible) {
                Txt(text, 17.sp, p.text)
                if (action != null && icon != null) {
                    Gap(8.dp)
                    PillButton(action, onAction, icon = icon, on = on)
                }
            }
        }
    }
}

/**
 * The small exact label above a page's hero line, a count at its right, the way the posters label
 * things. Only here, so it stays special. The hero block is four voices and each says something
 * the others don't:
 *  - The kicker says what the line can't: the start of the sentence the line finishes ("Listening
 *    through" Flat; "The volume keys open" Android's own panel), a count against the whole ("3 of
 *    12 on"), or the one exact fact that qualifies it ("Offline · no internet access"). Never the
 *    page's name (the pivot says it) and never the line's own words. A zero isn't counted.
 *  - The line is what's true now, in words that matter to you: what you're hearing, what's on,
 *    what the keys open. Never a bare flag ("Off.").
 *  - The switch at the line's end wears its name under it ("All effects", "Island", "Volume
 *    panel"). The line says what's true, not what the switch is, so a bare switch beside it reads
 *    as the line's own on or off ("Android's own icons", off). The name is the control's, so it
 *    stays where it's also the page's: it isn't a kicker, and it's the word TalkBack reads.
 *  - The dock at the foot says how it's doing (on or off, set up or not, live) and what's next.
 * On a page of its own (the Lab, the keyboard's settings) the title above is the page it hangs
 * from, so the kicker names this page, and the way back ("Done") takes the switch's place.
 */
@Composable
internal fun Kicker(left: String, right: String? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        dev.continuum.fx.ui.theme.Note(left, p.sub, Modifier.weight(1f))
        if (right != null) {
            // Its own air, so at large type the count never reads as the label's last word.
            Spacer(Modifier.width(16.dp))
            dev.continuum.fx.ui.theme.Note(right, p.quiet)
        }
    }
}

/**
 * A page's hero line: one thing, set large (see [Kicker] for what it says). It hangs from the same
 * margin as the title above it (the headline's edge); the ledger's words indent past the rail below.
 */
@Composable
internal fun HeroLine(text: String, color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 30.sp) {
    androidx.compose.foundation.text.BasicText(
        text,
        modifier,
        style = androidx.compose.ui.text.TextStyle(
            color = color,
            fontSize = size,
            lineHeight = size * 1.18f,
            letterSpacing = (-0.2).sp,
            fontFamily = dev.continuum.fx.ui.theme.Libron,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            lineBreak = dev.continuum.fx.ui.theme.Balanced,
        ),
        maxLines = 3,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** Lets something run past the page's side margins of [by] each, to the screen's edges. */
internal fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * Places a drawing's small label at [x], [y] in its parent, pulled back from [end] so it never runs
 * past it: a graph's last decade ("10k") at large type stays inside the drawing.
 */
internal fun Modifier.labelAt(x: Float, y: Int, end: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, placeable.height) {
        placeable.place(minOf(x, end - placeable.width).toInt().coerceAtLeast(0), y)
    }
}

/**
 * The quiet line at the end of a group that shows the rest of it: the finer settings stay out
 * of the way until asked for. The chevron after it is the sign that it opens. [inRail] false is
 * for one already inside an opened row (an effect's "How it works"), whose words are past the
 * rail already.
 */
@Composable
internal fun More(open: Boolean, onToggle: () -> Unit, label: String = "More", modifier: Modifier = Modifier, inRail: Boolean = true) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onToggle)
            .opens(open, onToggle)
            .padding(start = if (inRail) INK_RAIL else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpensName(label, open, p.sub, size = 15.sp)
    }
}

/*
 * The grammar of headings, said once, beside the grammar of opening below. A page is a hero (its
 * kicker, its line, its drawing) and then its ledger, and a heading says which part you're in:
 *  - A numbered rule (`01 BASS`) opens one of the page's own groups of settings. Every group under
 *    the hero has one, counted from 01 down the page, so a group that comes and goes (Set it up,
 *    until it's done) moves the count rather than leaving a gap.
 *  - A rule with no number is end matter: what closes a page or a sheet and isn't its subject (the
 *    Kit's colophon, a setup sheet's Backup, the keyboard's About). It only ever comes last.
 *  - A label in small capitals with no rule names something that belongs to the hero straight
 *    above it rather than starting a group: Island's Style, which changes the preview, and "Try it"
 *    over a table of samples, on Island (played into its preview) and on Volume (the panel the line
 *    names, shown for real). Same words, same shape. A control of the hero's that's a row (Effects'
 *    Listening on) sits between the drawing and the first rule with no heading at all.
 *  - Inside a group, a control's name is words like any row's (Side, Height, Frequency), never a
 *    label: small capitals are for the page's edges.
 */

/**
 * The grammar of "this opens in place", said once: the row's name with a quiet chevron right after
 * it, turning over once it's open. The far end of a row never carries the chevron: it's for what
 * the row is (a value, a switch) or, past it, the arrow of a row that goes to a page of its own.
 * [chevron] false leaves the name bare, for a row that only shows a value.
 */
@Composable
internal fun OpensName(
    text: String,
    open: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.TextUnit = 17.sp,
    chevron: Boolean = true,
    textModifier: Modifier = Modifier,
    /** The chevron's colour: the ground's quiet grey, or on the ink, its own. */
    chevronColor: Color = LocalPalette.current.quiet,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Two lines before it gives up, so a long name beside a long value wraps rather than
        // losing its end ("Sample rate and ran…").
        Txt(text, size, color, maxLines = 2, modifier = textModifier.weight(1f, fill = false))
        if (chevron) {
            Spacer(Modifier.width(7.dp))
            Chevron(open, chevronColor, 14.dp)
        }
    }
}

/**
 * Says to TalkBack that this opens in place, and whether it's open now ("collapsed", "expanded"),
 * the way the chevron says it to the eye.
 */
internal fun Modifier.opens(open: Boolean, onToggle: () -> Unit): Modifier = semantics {
    if (open) {
        collapse { onToggle(); true }
    } else {
        expand { onToggle(); true }
    }
}

/** [More], and the rest of the group it opens, written out by the ink. */
@Composable
internal fun MoreSection(open: Boolean, onToggle: () -> Unit, label: String = "More", content: @Composable () -> Unit) {
    // On the ledger's column like every row: the thread drips from the words' first column and
    // what it opens hangs under the words.
    dev.continuum.fx.ui.components.InkSection(open, threadIn = 3.5.dp, header = { More(open, onToggle, label) }) {
        androidx.compose.foundation.layout.Column(Modifier.padding(start = INK_RAIL)) { content() }
    }
}

/**
 * The one sign that a row leads somewhere: pointing right for a deeper page, or down (and up
 * once open) for something that opens in place. A channel with a drop is always a switch; a
 * word, a value.
 */
@Composable
internal fun Chevron(open: Boolean?, color: Color, size: Dp = 18.dp) {
    val turn by animateFloatAsState(
        when (open) {
            null -> -90f
            true -> 180f
            false -> 0f
        },
        Motion.flow(),
        label = "chevron",
    )
    Image(
        painterResource(R.drawable.ic_chevron_down),
        contentDescription = null,
        modifier = Modifier.size(size).rotate(turn),
        colorFilter = ColorFilter.tint(color),
    )
}

/** A list said as a sentence: "A.", "A and B.", "A, B and C.", or "A, B and 3 more." */
internal fun inWords(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "${names[0]}."
    2 -> "${names[0]} and ${names[1]}."
    3 -> "${names[0]}, ${names[1]} and ${names[2]}."
    else -> "${names[0]}, ${names[1]} and ${names.size - 2} more."
}

/**
 * The rail: the column the title's drop lives in (26dp from the page margin, the width of the
 * pivot's resting pill). Section numbers and state drops sit in it; the ledger's words begin
 * where it ends. Headlines (title, kicker, hero and what's said straight under it) and the
 * drawings hang from the margin instead.
 */
internal val INK_RAIL = 26.dp

/** The widest the page's content grows on large screens: a comfortable line length. */
internal val CONTENT_MAX = 600.dp
