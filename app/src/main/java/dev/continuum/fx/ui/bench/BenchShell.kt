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
 * The ledger: how a page's settings are laid out under its instrument. A 24dp margin for marks,
 * a first column for each row's drop, and words from 50dp, on every page. It's drawn in the same
 * ink as everything else: a row that's on carries a drop, and opening a row drips a thread from
 * that drop down the controls it holds, the controls appearing as the ink reaches them.
 */

/** The page's side margin. */
internal val BENCH_MARGIN = 24.dp

/** Where a row's drop sits: the middle of its first column. */
private val DROP_IN = 3.5.dp

/**
 * A section's rule: its number, its name, and a hairline running from 8dp after the name to the
 * right edge, on the name's baseline, with what the group is doing at the end of it. No box, no
 * card, no border: space does the grouping.
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
            Spacer(Modifier.width(8.dp))
            Note(trail, p.quiet, Modifier.padding(bottom = 1.5.dp))
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
                    .heightIn(min = 56.dp)
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
                            )
                        } else Modifier,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The drop's column: the ink is drawn by the section, so the label never moves.
                Spacer(Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Txt(
                        label, 16.5.sp, if (on) p.text else p.sub, maxLines = 1,
                        modifier = Modifier.onGloballyPositioned {
                            placed[1] = it
                            measureLabel()
                        },
                    )
                    if (caption != null) Txt(caption, 13.sp, p.sub, maxLines = 2)
                }
                if (opens) {
                    Spacer(Modifier.width(if (onToggle != null) 8.dp else 10.dp))
                    dev.continuum.fx.ui.Chevron(expanded, p.quiet, if (onToggle != null) 13.dp else 15.dp)
                }
                if (onToggle != null) {
                    Spacer(Modifier.width(10.dp))
                    dev.continuum.fx.ui.components.InkSwitch(on, switchDescription ?: label, onToggle)
                } else if (trailing != null) {
                    Spacer(Modifier.width(10.dp))
                    trailing()
                }
            }
        },
    ) {
        if (content != null) {
            Column(Modifier.fillMaxWidth().padding(start = 26.dp, top = 4.dp, bottom = 14.dp)) { content() }
        }
    }
}

/**
 * A row that leads somewhere: its name, its value on the right in small exact type, and (opened)
 * the block it leads to under it, on a thread of ink like every other opened row.
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
    val opens = content != null && onExpand != null
    dev.continuum.fx.ui.components.InkSection(
        open = opens && expanded,
        threadIn = DROP_IN,
        header = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(
                        if (opens) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClickLabel = if (expanded) "Close" else "Open",
                                onClick = { onExpand?.invoke() },
                            )
                        } else Modifier,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(26.dp))
                Txt(label, 16.5.sp, p.text, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                dev.continuum.fx.ui.ValueNote(value)
                if (opens) {
                    Spacer(Modifier.width(8.dp))
                    dev.continuum.fx.ui.Chevron(expanded, p.quiet, 15.dp)
                }
            }
        },
    ) {
        if (content != null) {
            Column(Modifier.fillMaxWidth().padding(start = 26.dp, top = 4.dp, bottom = 14.dp)) { content() }
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
