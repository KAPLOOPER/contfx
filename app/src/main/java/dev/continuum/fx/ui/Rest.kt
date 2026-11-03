package dev.continuum.fx.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
    Column(modifier.fillMaxWidth().padding(vertical = if (visible) 10.dp else 0.dp)) {
        Seeded(visible = visible, corner = 4.5.dp, color = p.text, settledAtStart = !drips, modifier = Modifier.size(30.dp, 9.dp)) {}
        if (visible) {
            Gap(14.dp)
            Txt(text, 17.sp, p.text)
            if (action != null) {
                Gap(14.dp)
                PillButton(action, onAction, icon = icon, height = 38.dp, on = on)
            }
        }
    }
}

/**
 * A section's name, small and spaced out like the app's name above the titles: on one flat
 * surface, space and these labels are what group things, not boxes.
 */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, top: androidx.compose.ui.unit.Dp = 34.dp, index: Int? = null) {
    val p = LocalPalette.current
    Box(modifier.padding(top = top, bottom = 6.dp)) {
        // Its number sits out in the margin, in the column the drops hang in.
        if (index != null) {
            dev.continuum.fx.ui.theme.Note("%02d".format(index), p.quiet, Modifier.offset(x = (-21).dp, y = 1.5.dp))
        }
        androidx.compose.foundation.text.BasicText(
            text.uppercase(),
            style = androidx.compose.ui.text.TextStyle(
                color = p.sub.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontFamily = dev.continuum.fx.ui.theme.Libron,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                letterSpacing = 2.4.sp,
            ),
        )
    }
}

/**
 * The small exact label above a page's hero line: what it is on the left, a count on the right,
 * the way the posters label things. Only here, so it stays special.
 */
@Composable
internal fun Kicker(left: String, right: String? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        dev.continuum.fx.ui.theme.Note(left, p.sub, Modifier.weight(1f))
        if (right != null) dev.continuum.fx.ui.theme.Note(right, p.quiet)
    }
}

/** A page's hero line: one thing, set large. */
@Composable
internal fun HeroLine(text: String, color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 28.sp) {
    androidx.compose.foundation.text.BasicText(
        text,
        modifier,
        style = androidx.compose.ui.text.TextStyle(
            color = color,
            fontSize = size,
            lineHeight = size * 1.18f,
            letterSpacing = (-0.2).sp,
            fontFamily = dev.continuum.fx.ui.theme.Libron,
        ),
        maxLines = 3,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** A value at the end of a row, in small exact type: a count, a device, a mode. */
@Composable
internal fun ValueNote(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    dev.continuum.fx.ui.theme.Note(text, p.sub, modifier.padding(start = 12.dp))
}

/** Lets something run past the page's side margins of [by] each, to the screen's edges. */
internal fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * The quiet line at the end of a group that shows the rest of it: the finer settings stay out
 * of the way until asked for. Its drop in the margin is the sign that it opens.
 */
@Composable
internal fun More(open: Boolean, onToggle: () -> Unit, label: String = "More", modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(if (open) "Less" else label, 15.sp, p.sub, maxLines = 1)
    }
}

/** [More], and the rest of the group it opens, written out by the ink. */
@Composable
internal fun MoreSection(open: Boolean, onToggle: () -> Unit, label: String = "More", content: @Composable () -> Unit) {
    dev.continuum.fx.ui.components.InkSection(open, header = { More(open, onToggle, label) }, content = content)
}

/**
 * The one sign that a row leads somewhere: pointing right for a deeper page, or down (and up
 * once open) for something that opens in place. A dot is always a switch; a word, a value.
 */
@Composable
internal fun Chevron(open: Boolean?, color: Color) {
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
        modifier = Modifier.size(18.dp).rotate(turn),
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

/** The widest the page's content grows on large screens: a comfortable line length. */
internal val CONTENT_MAX = 600.dp
