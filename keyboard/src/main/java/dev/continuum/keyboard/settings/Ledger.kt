package dev.continuum.keyboard.settings

/*
 * Continuum Kit's ledger, for the keyboard's settings. The Kit links into this screen from its
 * Keyboard page (Look, Typing, Swiping…), so arriving here should feel like going to a page of
 * the Kit: one flat ground, a title with its drop, numbered rules, rows with their state as a drop
 * in the rail, the ink switch and the flowing choice pill. The keyboard is its own app and can't
 * use the Kit's code, so the pieces it needs are mirrored here, kept to what this screen uses.
 * Changing one there means changing it here too: the Kit's names are kept, so they're easy to find
 * (ui/theme/Continuum.kt, ui/components/Controls.kt and Ink.kt, ui/bench/BenchShell.kt, ui/Rest.kt).
 */

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.keyboard.R
import dev.continuum.keyboard.ui.Libron
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// --- The ground and its type ------------------------------------------------------------------

/** The Kit's palette: colour means one thing each (bright is on or chosen, red needs you). */
internal data class Ground(
    val ground: Color,
    val surface: Color,
    val text: Color,
    val sub: Color,
    /** Counts and the numbers in the margin: still 4.5:1 on the ground. */
    val quiet: Color,
    val line: Color,
    val ink: Color,
    val onInk: Color,
    /** On, or chosen: the ink of the page (white on the dark ground, black on the light). */
    val primary: Color,
    val onPrimary: Color,
    val danger: Color,
)

internal val LightGround = Ground(
    ground = Color(0xFFEEEEEB),
    surface = Color(0xFFE0E0DC),
    text = Color(0xFF121211),
    sub = Color(0xFF5C5C58),
    quiet = Color(0xFF6B6B66),
    line = Color(0xFFCFCFCA),
    ink = Color(0xFF000000),
    onInk = Color(0xFFF4F4F1),
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFF4F4F1),
    danger = Color(0xFFA32D2D),
)

internal val DarkGround = LightGround.copy(
    ground = Color(0xFF161615),
    surface = Color(0xFF262624),
    text = Color(0xFFEDEDEA),
    sub = Color(0xFFA3A39E),
    quiet = Color(0xFF8C8C87),
    line = Color(0xFF3A3A37),
    primary = Color(0xFFEDEDEA),
    onPrimary = Color(0xFF121211),
    danger = Color(0xFFF09595),
)

internal val LocalGround = staticCompositionLocalOf { LightGround }

/** The small, exact face of the notes: Inter, as in the Kit. */
internal val Mark = FontFamily(Font(R.font.inter_medium, FontWeight.Medium))

/** Continuum's two springs: Flow for most motion, Snap for presses and small controls. */
internal object Motion {
    fun <T> flow() = spring<T>(dampingRatio = 0.613f, stiffness = 240f)
    fun <T> snap() = spring<T>(dampingRatio = 0.843f, stiffness = 620f)

    /** Flow's pace without its give: for room opening and closing, so the rows below never bounce. */
    fun <T> settle() = spring<T>(dampingRatio = 0.86f, stiffness = 240f)
}

/** The rail: the column a row's drop and a section's number sit in; words begin where it ends. */
internal val INK_RAIL = 26.dp

/** The least air between a section's number and its name, and the number's tighter tracking. */
private val NUMBER_GAP = 8.dp
private val NUMBER_TRACKING = 0.3.sp

/** How many lines a caption may take: a guard against a runaway string, not a design limit. */
private const val CAPTION_LINES = 16

/** A headline breaks balanced, two even lines rather than a line and a word. */
private val Balanced = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

/**
 * Words that wrap never leave one word alone on the last line: the last two are kept together.
 * And a middle dot between two facts never starts a line.
 */
internal fun keepLastTwo(text: String, longest: Int = 24): String {
    val dotted = text.replace(" · ", "\u00A0· ")
    val last = dotted.lastIndexOf(' ')
    if (last <= 0 || last == dotted.length - 1) return dotted
    val before = dotted.lastIndexOf(' ', last - 1)
    if (before < 0 || dotted.indexOf('\n', before) >= 0 || dotted.length - before > longest) return dotted
    return dotted.substring(0, last) + '\u00A0' + dotted.substring(last + 1)
}


/**
 * Words held together ([held], from [keepLastTwo] and [factsWhole]) only while that costs no word
 * its wholeness, decided while the text is measured, so the held version is never drawn when it
 * doesn't fit. The no-break spaces are only how it's drawn: whatever reads the screen is given the
 * words as written, [free], or what [says] sets instead. As the Kit's.
 */
@Composable
private fun HeldText(
    free: String,
    held: String,
    modifier: Modifier,
    says: (SemanticsPropertyReceiver.() -> Unit)? = null,
    text: @Composable (shown: String, onLayout: ((TextLayoutResult) -> Unit)?, modifier: Modifier) -> Unit,
) {
    val reads: SemanticsPropertyReceiver.() -> Unit = says ?: { this.text = AnnotatedString(free) }
    if (held == free) {
        text(free, null, if (says != null) modifier.clearAndSetSemantics(says) else modifier)
        return
    }
    val broke = remember(held) { BooleanArray(1) }
    val report = remember(held) { { r: TextLayoutResult -> broke[0] = r.hasVisualOverflow || brokeInsideAWord(r, held) } }
    Layout(
        contents = listOf({ text(held, report, Modifier) }, { text(free, null, Modifier) }),
        modifier = modifier.clearAndSetSemantics(reads),
        measurePolicy = remember(broke) { HeldPolicy(broke) },
    )
}

/** Measures the held words; places them, or the free words if the held ones broke badly. */
private class HeldPolicy(private val broke: BooleanArray) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val held = measurables[0].first().measure(constraints)
        val shown = if (broke[0]) measurables[1].first().measure(constraints) else held
        return layout(
            shown.width, shown.height,
            mapOf(FirstBaseline to shown[FirstBaseline], LastBaseline to shown[LastBaseline]),
        ) { shown.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int) =
        measurables[1].first().minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int) =
        measurables[1].first().maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int) =
        maxOf(measurables[0].first().minIntrinsicHeight(width), measurables[1].first().minIntrinsicHeight(width))

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int) =
        maxOf(measurables[0].first().maxIntrinsicHeight(width), measurables[1].first().maxIntrinsicHeight(width))
}

/** Whether a line of [r] ends somewhere a line can't (inside a word, or at a no-break space). */
private fun brokeInsideAWord(r: TextLayoutResult, text: String): Boolean {
    for (i in 0 until r.lineCount - 1) {
        val end = r.getLineEnd(i)
        if (end <= 0 || end >= text.length) continue
        val c = text[end - 1]
        if (c != ' ' && c != '\n' && c != '-' && c != '/' && c != '\u2013' && c != '\u2014') return true
    }
    return false
}

/** A note's facts are kept whole when it wraps: it breaks at a dot, not inside a short fact. */
internal fun factsWhole(text: String, longest: Int = 20): String {
    if (!text.contains(" · ")) return text
    return text.split(" · ").joinToString(" · ") { fact ->
        if (fact.length <= longest) fact.replace(' ', '\u00A0') else fact
    }
}

/** Words, in Libron, with room between lines for its descenders. */
@Composable
internal fun Txt(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    val style = TextStyle(
        color = color,
        fontSize = size,
        lineHeight = size * 1.38f,
        fontFamily = Libron,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )
    HeldText(text, if (maxLines > 1) keepLastTwo(text) else text, modifier) { shown, onLayout, m ->
        BasicText(shown, m, style = style, onTextLayout = onLayout, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** A note: tiny spaced capitals for exact facts at the page's edges. */
@Composable
internal fun Note(text: String, color: Color, modifier: Modifier = Modifier, tracking: TextUnit = 0.9.sp) {
    val caps = text.uppercase()
    val style = TextStyle(
        color = color,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        fontFamily = Mark,
        fontWeight = FontWeight.Medium,
        letterSpacing = tracking,
        fontFeatureSettings = "tnum, case",
    )
    // Small capitals are how it looks: TalkBack reads it as written.
    HeldText(caps, keepLastTwo(factsWhole(caps)), modifier, says = { contentDescription = text }) { shown, onLayout, m ->
        BasicText(shown, m, style = style, onTextLayout = onLayout, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The small exact label above a page's hero line. */
@Composable
internal fun Kicker(left: String, right: String? = null) {
    val g = LocalGround.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Note(left, g.sub, Modifier.weight(1f))
        if (right != null) {
            Spacer(Modifier.width(16.dp))
            Note(right, g.quiet)
        }
    }
}

/** A page's hero line: one thing, set large, on the title's edge. */
@Composable
internal fun HeroLine(text: String, modifier: Modifier = Modifier) {
    val g = LocalGround.current
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = g.text,
            fontSize = 30.sp,
            lineHeight = 30.sp * 1.18f,
            letterSpacing = (-0.2).sp,
            fontFamily = Libron,
            fontWeight = FontWeight.Bold,
            lineBreak = Balanced,
        ),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The feel of the ink, as the Kit's: a switch filling or draining, a choice taken. */
internal object InkFeel {
    private var last = 0L
    fun toggle(view: View, on: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - last < 45) return
        last = now
        view.performHapticFeedback(
            when {
                Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
                on -> HapticFeedbackConstants.VIRTUAL_KEY
                else -> HapticFeedbackConstants.CLOCK_TICK
            },
        )
    }
}

// --- The page's title -------------------------------------------------------------------------

/**
 * The page's title as the Kit sets its pages': a big lowercase word on the margin with its drop
 * resting under it, the pill the Kit's pivot wears under the page you're on. Read down the page
 * and it folds ([fold], 0 to 1) to a hero line's size, as the Kit's pivot does, giving the page
 * its room.
 */
@Composable
internal fun PageTitle(title: String, fold: () -> Float, modifier: Modifier = Modifier) {
    val g = LocalGround.current
    val style = TextStyle(fontFamily = Libron, fontSize = 42.sp, fontWeight = FontWeight.Normal, color = g.text)
    Box(
        modifier
            .fillMaxWidth()
            .layoutFold(fold)
            .semantics { heading() },
    ) {
        Column(
            Modifier
                .padding(start = 24.dp)
                .graphicsLayer {
                    val k = 1f - FOLD * fold().coerceIn(0f, 1f)
                    scaleX = k
                    scaleY = k
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                },
        ) {
            BasicText(title, style = style, maxLines = 1, softWrap = false)
            // The drop under the title: the Kit's resting pill, 22 by 9, two in from the margin,
            // tucked up under the word as the pivot's is.
            Canvas(Modifier.padding(start = 2.dp).offset(y = (-3).dp).size(width = 22.dp, height = 9.dp)) {
                drawRoundRect(g.text, cornerRadius = CornerRadius(size.height / 2f))
            }
            Spacer(Modifier.height(19.dp))
        }
    }
}

/** How much the title gives up, folded: 42sp comes down to 26sp, a hero line's size. */
private const val FOLD = 0.38f

/** Folding gives back the height the title no longer needs (read in layout, so nothing recomposes). */
private fun Modifier.layoutFold(fold: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val k = 1f - FOLD * fold().coerceIn(0f, 1f)
    val h = (placeable.height * k).toInt()
    layout(placeable.width, h) { placeable.place(0, 0) }
}

// --- Headings ---------------------------------------------------------------------------------

/**
 * A section's rule, as the Kit's: its number in the rail, its name in spaced capitals, and a
 * hairline out to the edge, with what the group is doing at its end. Numbered for a group of the
 * page's own settings, counted from 01; no number only for end matter (About). TalkBack hears it
 * as a heading, by its name.
 */
@Composable
internal fun Section(index: Int?, name: String, trail: String? = null, modifier: Modifier = Modifier) {
    val g = LocalGround.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 6.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = name + (trail?.let { ", $it" } ?: "")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = INK_RAIL), contentAlignment = Alignment.CenterStart) {
            if (index != null) Note("%02d".format(index), g.quiet, Modifier.padding(end = NUMBER_GAP), tracking = NUMBER_TRACKING)
        }
        RuleLine(
            name = {
                BasicText(
                    name.uppercase(),
                    style = TextStyle(color = g.sub, fontSize = 13.sp, fontFamily = Libron, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp),
                    maxLines = 2,
                )
            },
            rule = { Box(Modifier.height(1.dp).background(g.line)) },
            trail = trail?.let { t -> { Note(t, g.quiet) } },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A rule's name, its hairline and its trail, as the Kit lays them: on one line while they fit; at
 * large type the trail moves under the rule's far end, so the name stays one phrase and the trail
 * is never split. A name too long for a line by itself wraps between its words.
 */
@Composable
private fun RuleLine(
    name: @Composable () -> Unit,
    rule: @Composable () -> Unit,
    trail: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(name, rule, trail ?: {}), modifier = modifier) { (names, rules, trails), c ->
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
 * A row's name and what sits at the end of its line, as the Kit lays them: the name keeps its line
 * whole while the value can fit its longest word beside it; only past that does the name wrap.
 */
@Composable
private fun NameAndValue(name: @Composable () -> Unit, value: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(contents = listOf(name, value), modifier = modifier) { (names, values), c ->
        val w = c.maxWidth
        val nm = names.first()
        val v = values.first()
        val g = 12.dp.roundToPx()
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

/** The name of a control inside a group, in words like any row's. */
@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    val g = LocalGround.current
    Txt(text, 15.sp, g.sub, modifier.padding(start = INK_RAIL, top = 10.dp))
}

// --- Rows -------------------------------------------------------------------------------------

/** A row's drop coming in: past its size by a little, then back. And drying away. */
private val SWELL_IN = spring<Float>(dampingRatio = 0.45f, stiffness = 380f)
private val DRY = spring<Float>(dampingRatio = 1f, stiffness = 520f)

/**
 * A row of the ledger with a switch, as the Kit's: on, a drop of ink rests in the rail on the
 * label's line and the label is bright; off, the label is quieter and the switch waits. The whole
 * row is the switch, so it's one large target and TalkBack meets one control: its name, what it
 * does, and whether it's on.
 */
@Composable
internal fun SwitchRow(label: String, caption: String?, on: Boolean, onToggle: () -> Unit) {
    val g = LocalGround.current
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val held = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) { held.animateTo(if (on) 1f else 0f, if (on) SWELL_IN else DRY) }
    val placed = remember { arrayOfNulls<LayoutCoordinates>(2) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .onGloballyPositioned { placed[0] = it }
            .toggleable(on, interactionSource = source, indication = null, role = Role.Switch) {
                InkFeel.toggle(view, !on)
                onToggle()
            }
            .drawBehind {
                val r = 3.5.dp.toPx() * held.value
                val row = placed[0]
                val text = placed[1]
                if (r > 0.2f && row != null && text != null && row.isAttached && text.isAttached) {
                    val y = row.localPositionOf(text, Offset.Zero).y + text.size.height / 2f
                    drawCircle(g.primary, r, Offset(3.5.dp.toPx(), y))
                }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(INK_RAIL))
        Column(Modifier.weight(1f)) {
            Txt(label, 17.sp, if (on) g.text else g.sub, maxLines = 2, modifier = Modifier.onGloballyPositioned { placed[1] = it })
            if (caption != null) Txt(caption, 14.sp, g.sub, maxLines = CAPTION_LINES, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(10.dp))
        InkSwitch(on, pressed)
    }
}

/**
 * A row that says something and may hold a move under it: its name (bright, it isn't off), a value
 * or a state at the end of its name's line, what it means under it, then [content].
 */
@Composable
internal fun InfoRow(
    label: String,
    caption: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val g = LocalGround.current
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp).padding(start = INK_RAIL)) {
        if (trailing != null) {
            NameAndValue(
                name = { Txt(label, 17.sp, g.text, maxLines = 2) },
                value = { Row(verticalAlignment = Alignment.CenterVertically, content = trailing) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // No minimum height: its caption keeps a row's 3dp under it, as a switch row's does.
            Txt(label, 17.sp, g.text, maxLines = 2)
        }
        if (caption != null) Txt(caption, 14.sp, g.sub, maxLines = CAPTION_LINES, modifier = Modifier.padding(top = 3.dp))
        content?.invoke()
    }
}

// --- Buttons ----------------------------------------------------------------------------------

/** Press feel: in on Snap, back out on Flow; a word button squashes wide like a bubble. */
@Composable
internal fun Modifier.squash(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val sx = remember { Animatable(1f) }
    val sy = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        val spec: AnimationSpec<Float> = if (pressed) Motion.snap() else Motion.flow()
        launch { sx.animateTo(if (pressed) 1.05f else 1f, spec) }
        launch { sy.animateTo(if (pressed) 0.86f else 1f, spec) }
    }
    return graphicsLayer {
        scaleX = sx.value
        scaleY = sy.value
    }
}

/**
 * A button, as the Kit's: words with their icon, no shape and no border, 48dp to hit. The icon
 * says the act. [primary] sets the words in bold, for the one to press next.
 */
@Composable
internal fun WordButton(
    text: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val g = LocalGround.current
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .squash(source)
            .heightIn(min = 48.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(end = 18.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(g.text))
        Txt(text, 16.sp, g.text, bold = primary, maxLines = 3)
    }
}

// --- The switch -------------------------------------------------------------------------------

/** The drop crosses quickly and settles with the slightest give; ink pours after it, drains ahead. */
private val SLIDE = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
private val POUR = spring<Float>(dampingRatio = 1f, stiffness = 230f)
private val DRAIN = spring<Float>(dampingRatio = 1f, stiffness = 760f)

/** How far past the resting drop's centre the switch's channel begins: its open mouth. */
private val MOUTH = 6.dp

/**
 * The Kit's switch, drawn the same (the row around it is what takes the touch). Off, a hollow drop
 * waits in the open mouth of an empty channel: nothing filled, nothing in the ink's colour, every
 * line over 3:1. On, the drop slides to the far end, drawn out a little by its speed, ink pours in
 * after it and closes the channel, and the drop fills with the page's colour and a tick surfaces
 * in it. Off again, the ink drains back first and the drop follows it home, emptying.
 */
@Composable
internal fun InkSwitch(on: Boolean, pressed: Boolean, modifier: Modifier = Modifier) {
    val g = LocalGround.current
    val pos = remember { Animatable(if (on) 1f else 0f) }
    val flood = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) {
        val t = if (on) 1f else 0f
        if (pos.targetValue == t && flood.targetValue == t) return@LaunchedEffect
        launch { pos.animateTo(t, SLIDE) }
        flood.animateTo(t, if (on) POUR else DRAIN)
    }
    val press by animateFloatAsState(if (pressed) 1f else 0f, Motion.snap(), label = "switchPress")
    Box(modifier.size(width = 56.dp, height = 48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = 46.dp, height = 26.dp)) {
            val w = size.width
            val h = size.height
            val r = h / 2f
            val cy = h / 2f
            val x0 = r
            val x1 = w - r
            val hair = 1.6.dp.toPx()
            val rr = r - hair / 2f
            val mouth = x0 + MOUTH.toPx()
            val rim = Path().apply {
                moveTo(mouth, hair / 2f)
                lineTo(x1, hair / 2f)
                arcTo(Rect(x1 - rr, cy - rr, x1 + rr, cy + rr), -90f, 180f, false)
                lineTo(mouth, h - hair / 2f)
            }
            drawPath(rim, g.quiet, style = Stroke(hair, cap = StrokeCap.Round))
            val f = flood.value.coerceIn(0f, 1f)
            if (f > 0.002f) {
                // From nothing, as the Kit's: a bead a third grown would pop in the hollow drop.
                val br = r * min(1f, f * 4.5f)
                val left = x0 - br
                val right = x0 + (x1 - x0) * f + br
                drawRoundRect(g.primary, Offset(left, cy - br), Size(right - left, br * 2f), CornerRadius(br))
            }
            val v = pos.value
            val cx = x0 + (x1 - x0) * v.coerceIn(0f, 1f)
            val stretch = (abs(pos.velocity) * 0.035f).coerceAtMost(0.3f)
            val dr = (r - 3.5.dp.toPx()) * (1f + 0.12f * press)
            val dropAt = Offset(cx - dr * (1f + stretch), cy - dr * (1f - stretch * 0.45f))
            val dropSize = Size(dr * 2f * (1f + stretch), dr * 2f * (1f - stretch * 0.45f))
            val filled = ((v - 0.25f) / 0.6f).coerceIn(0f, 1f)
            if (filled > 0.01f) drawOval(g.onPrimary.copy(alpha = filled), dropAt, dropSize)
            if (filled < 0.99f) {
                val line = 1.8.dp.toPx()
                drawOval(
                    g.sub.copy(alpha = 1f - filled),
                    Offset(dropAt.x + line / 2f, dropAt.y + line / 2f),
                    Size(dropSize.width - line, dropSize.height - line),
                    style = Stroke(line),
                )
            }
            val t = ((v - 0.65f) / 0.35f).coerceIn(0f, 1f)
            if (t > 0.01f) {
                val k = dr * (0.7f + 0.3f * t)
                val tick = Path().apply {
                    moveTo(cx - 0.45f * k, cy + 0.02f * k)
                    lineTo(cx - 0.12f * k, cy + 0.34f * k)
                    lineTo(cx + 0.47f * k, cy - 0.3f * k)
                }
                drawPath(tick, g.primary.copy(alpha = t), style = Stroke(1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// --- Choices ----------------------------------------------------------------------------------

/** The choices in one row, and where the chosen one is, so the ink can go to it. */
private class ChoiceGroup {
    var coords: LayoutCoordinates? = null
    val entries = mutableListOf<ChoiceEntry>()
    var target by mutableStateOf<Pair<ChoiceEntry, Rect>?>(null)

    fun refresh() {
        val c0 = coords?.takeIf { it.isAttached } ?: return
        val chosen = entries.firstOrNull { it.selected }
        if (chosen == null) {
            if (target != null) target = null
            return
        }
        val c = chosen.coords?.takeIf { it.isAttached } ?: return
        val r = c0.localBoundingBoxOf(c, clipBounds = false)
        val t = target
        if (t == null || t.first !== chosen || t.second != r) target = chosen to r
    }
}

private class ChoiceEntry {
    var selected = false
    var coords: LayoutCoordinates? = null
}

private val LocalChoiceGroup = compositionLocalOf<ChoiceGroup?> { null }

private val LEAD = spring<Float>(dampingRatio = 0.843f, stiffness = 620f)
private val TRAIL = spring<Float>(dampingRatio = 0.72f, stiffness = 130f)
private val TAIL = spring<Float>(dampingRatio = 0.9f, stiffness = 260f)
private val SWELL_PILL = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)
private val PILL = 40.dp

/**
 * A row of choices sharing one pill of ink, as the Kit's: the chosen word is knocked out of the
 * ink, and choosing another runs the pill across to it, its leading edge ahead and its trailing
 * edge lagging, leaving a drop behind that pinches off. A row with nothing chosen lets its pill
 * shrink away. Too long for its line (large type), it runs on to the next rather than off the
 * page, and the ink runs between lines.
 * [perLine] sets a set out as a table: at most that many to a line, in even lines.
 */
@Composable
internal fun ChoiceRow(modifier: Modifier = Modifier, perLine: Int = Int.MAX_VALUE, content: @Composable () -> Unit) {
    val g = LocalGround.current
    val group = remember { ChoiceGroup() }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    val shown = remember { Animatable(0f) }
    val mid = remember { Animatable(0f) }
    val tail = remember { Animatable(0f) }
    val tailAt = remember { FloatArray(2) }
    val last = remember { arrayOfNulls<ChoiceEntry>(1) }
    val born = remember { android.os.SystemClock.uptimeMillis() }
    val target = group.target
    LaunchedEffect(target) {
        val (entry, r) = target ?: run {
            shown.animateTo(0f, Motion.snap())
            return@LaunchedEffect
        }
        val fresh = android.os.SystemClock.uptimeMillis() - born < 500
        when {
            shown.value < 0.02f && fresh -> {
                left.snapTo(r.left)
                right.snapTo(r.right)
                mid.snapTo(r.center.y)
                shown.snapTo(1f)
            }
            shown.value < 0.02f -> {
                left.snapTo(r.center.x)
                right.snapTo(r.center.x)
                mid.snapTo(r.center.y)
                shown.snapTo(1f)
                launch { left.animateTo(r.left, SWELL_PILL) }
                launch { right.animateTo(r.right, SWELL_PILL) }
            }
            entry === last[0] -> {
                launch { left.animateTo(r.left, Motion.snap()) }
                launch { right.animateTo(r.right, Motion.snap()) }
                launch { mid.animateTo(r.center.y, Motion.snap()) }
                if (shown.value < 1f) launch { shown.animateTo(1f, Motion.snap()) }
            }
            else -> {
                val toRight = r.center.x > (left.value + right.value) / 2f
                val h = r.height
                tailAt[0] = if (toRight) left.value + h * 0.45f else right.value - h * 0.45f
                tailAt[1] = mid.value
                launch {
                    tail.snapTo(1f)
                    tail.animateTo(0f, TAIL)
                }
                launch { left.animateTo(r.left, if (toRight) TRAIL else LEAD) }
                launch { right.animateTo(r.right, if (toRight) LEAD else TRAIL) }
                launch { mid.animateTo(r.center.y, LEAD) }
                if (shown.value < 1f) launch { shown.animateTo(1f, Motion.snap()) }
            }
        }
        last[0] = entry
    }
    CompositionLocalProvider(LocalChoiceGroup provides group) {
        EvenLines(
            modifier
                .onPlaced {
                    group.coords = it
                    group.refresh()
                }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val s = shown.value
                    val tv = tail.value
                    if (s <= 0.01f && tv <= 0.01f) {
                        // As the page opens, the ink is where it should be from the first frame
                        // the row is drawn. The effect above only catches up a frame or two
                        // later (placed, then recomposed, then snapped), and until then the
                        // chosen word stood bare and the pill popped in under it.
                        val t = group.target
                        if (t != null && last[0] == null && android.os.SystemClock.uptimeMillis() - born < 500) {
                            val box = t.second
                            val h = PILL.toPx()
                            val w = maxOf(box.width, h)
                            drawRoundRect(g.primary, Offset(box.center.x - w / 2f, box.center.y - h / 2f), Size(w, h), CornerRadius(h / 2f), blendMode = BlendMode.Xor)
                        }
                        return@drawWithContent
                    }
                    val cy = mid.value
                    val h = PILL.toPx()
                    var l = left.value
                    var r = right.value
                    if (r - l < h) {
                        val c = (l + r) / 2f
                        l = c - h / 2f
                        r = c + h / 2f
                    }
                    val cx = (l + r) / 2f
                    val w = (r - l) * min(1f, s.coerceAtLeast(0f))
                    val hh = h * min(1f, s.coerceAtLeast(0f))
                    if (tv <= 0.01f) {
                        drawRoundRect(g.primary, Offset(cx - w / 2f, cy - hh / 2f), Size(w, hh), CornerRadius(hh / 2f), blendMode = BlendMode.Xor)
                        return@drawWithContent
                    }
                    var shape = Path().apply { addRoundRect(RoundRect(cx - w / 2f, cy - hh / 2f, cx + w / 2f, cy + hh / 2f, CornerRadius(hh / 2f))) }
                    val tr = h * 0.36f * tv
                    val tc = Offset(tailAt[0], tailAt[1])
                    shape = union(shape, Path().apply { addOval(Rect(tc, tr)) })
                    val cap = if (tc.x < cx) Offset(cx - w / 2f + hh / 2f, cy) else Offset(cx + w / 2f - hh / 2f, cy)
                    inkNeck(tc, tr, cap, hh / 2f, reach = 1.5f)?.let { shape = union(shape, it) }
                    drawPath(shape, g.primary, blendMode = BlendMode.Xor)
                },
            perLine = perLine,
            spacing = 2.dp,
            content = content,
        )
    }
}

/**
 * A set of choices in even lines: as many to a line as fit (at most [perLine]), then shared out so
 * the lines are as even as they can be ("20 Hz  30 Hz / 60 Hz  100 Hz", never three and one), each
 * line's words sharing their middle. On one line whenever the whole set fits.
 */
@Composable
private fun EvenLines(modifier: Modifier, perLine: Int, spacing: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, c ->
        val gap = spacing.roundToPx()
        val words = measurables.map { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = c.maxWidth)) }
        if (words.isEmpty()) return@Layout layout(c.minWidth, c.minHeight) {}
        val n = words.size
        fun fits(per: Int) = words.chunked(per).all { line -> line.sumOf { it.width } + gap * (line.size - 1) <= c.maxWidth }
        var per = 1
        for (k in minOf(perLine, n) downTo 1) {
            val lines = (n + k - 1) / k
            val even = (n + lines - 1) / lines
            if (fits(even)) {
                per = even
                break
            }
        }
        val lines = words.chunked(per)
        val widest = lines.maxOf { line -> line.sumOf { it.width } + gap * (line.size - 1) }
        val heights = lines.map { line -> line.maxOf { it.height } }
        layout(maxOf(c.minWidth, minOf(widest, c.maxWidth)), maxOf(c.minHeight, heights.sum())) {
            var y = 0
            lines.forEachIndexed { i, line ->
                var x = 0
                line.forEach { w ->
                    w.place(x, y + (heights[i] - w.height) / 2)
                    x += w.width + gap
                }
                y += heights[i]
            }
        }
    }
}

/** One choice in a [ChoiceRow]: its word, bright when chosen; the ink goes to it. */
@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val g = LocalGround.current
    val group = LocalChoiceGroup.current
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val fg by animateColorAsState(if (selected) g.text else g.sub, Motion.flow(), label = "choice")
    val entry = remember { ChoiceEntry() }
    if (group != null) {
        DisposableEffect(group) {
            group.entries += entry
            onDispose {
                group.entries -= entry
                group.refresh()
            }
        }
        SideEffect {
            entry.selected = selected
            group.refresh()
        }
    }
    Box(
        Modifier
            .onPlaced {
                entry.coords = it
                group?.refresh()
            }
            .selectable(selected, interactionSource = source, indication = null, role = Role.RadioButton) {
                if (!selected) InkFeel.toggle(view, true)
                onClick()
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, 16.sp, fg, maxLines = 1, modifier = Modifier.squash(source))
    }
}

private fun union(a: Path, b: Path): Path = runCatching { Path.combine(PathOperation.Union, a, b) }.getOrDefault(a)

/** The neck between two drops of ink pulling apart (the metaball outline), as the Kit draws it. */
private fun inkNeck(c1: Offset, r1: Float, c2: Offset, r2: Float, spread: Float = 0.5f, reach: Float = 2.5f): Path? {
    val d = (c2 - c1).getDistance()
    if (r1 <= 0f || r2 <= 0f || d > r1 + r2 * reach || d <= abs(r1 - r2)) return null
    val pi = PI.toFloat()
    val halfPi = pi / 2f
    val u1: Float
    val u2: Float
    if (d < r1 + r2) {
        u1 = acos(((r1 * r1 + d * d - r2 * r2) / (2 * r1 * d)).coerceIn(-1f, 1f))
        u2 = acos(((r2 * r2 + d * d - r1 * r1) / (2 * r2 * d)).coerceIn(-1f, 1f))
    } else {
        u1 = 0f
        u2 = 0f
    }
    val between = atan2(c2.y - c1.y, c2.x - c1.x)
    val most = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
    val a1 = between + u1 + (most - u1) * spread
    val a2 = between - u1 - (most - u1) * spread
    val a3 = between + pi - u2 - (pi - u2 - most) * spread
    val a4 = between - pi + u2 + (pi - u2 - most) * spread
    fun at(c: Offset, a: Float, r: Float) = Offset(c.x + r * cos(a), c.y + r * sin(a))
    val p1 = at(c1, a1, r1)
    val p2 = at(c1, a2, r1)
    val p3 = at(c2, a3, r2)
    val p4 = at(c2, a4, r2)
    val total = r1 + r2
    val k = min(spread * 2.4f, (p1 - p3).getDistance() / total) * min(1f, d * 2f / total)
    val h1 = at(p1, a1 - halfPi, r1 * k)
    val h2 = at(p2, a2 + halfPi, r1 * k)
    val h3 = at(p3, a3 + halfPi, r2 * k)
    val h4 = at(p4, a4 - halfPi, r2 * k)
    return Path().apply {
        moveTo(p1.x, p1.y)
        cubicTo(h1.x, h1.y, h3.x, h3.y, p3.x, p3.y)
        lineTo(p4.x, p4.y)
        cubicTo(h4.x, h4.y, h2.x, h2.y, p2.x, p2.y)
        close()
    }
}

/** How far a [Reveal]'s layer reaches past its sides, as the Kit's does. */
private val REVEAL_BLEED = 48.dp

/** The soft edge a [Reveal] uncovers through, as the Kit's does. */
private val REVEAL_FADE = 24.dp

/**
 * A setting that only means something while another says so (Letter drops while the theme is
 * Caps; how long clips are kept while history is on) follows it in and out instead of popping,
 * as in the Kit: the page makes room at Flow's pace without the give ([Motion.settle]), so the
 * rows below glide and never bounce, and it's uncovered from the top through a soft edge, so no
 * line is ever cut. It goes the same way back, and once gone it's gone (not composed, so not read
 * out). Shown with the page, it's simply there.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shown = remember { Animatable(if (visible) 1f else 0f) }
    var composed by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            composed = true
            shown.animateTo(1f, Motion.settle())
        } else {
            shown.animateTo(0f, Motion.settle())
            composed = false
        }
    }
    if (!composed) return
    // A fade is only drawing, not a hit-test or focus boundary. Keep the moving group out of
    // accessibility and keyboard traversal, and consume touches before its controls see them.
    // Once fully uncovered it has its normal semantics and actions again.
    val ready = visible && !shown.isRunning && shown.value >= 0.999f
    val focus = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (!ready && hasFocus) focus.clearFocus(force = true) }
    // The whole height of what's in it, for the edge the drawing uncovers to.
    val full = remember { FloatArray(1) }
    Layout(
        content = {
            Column(
                Modifier
                    .then(if (ready) Modifier else Modifier.clearAndSetSemantics {})
                    .onFocusChanged { hasFocus = it.hasFocus }
                    .focusProperties { enter = { if (ready) FocusRequester.Default else FocusRequester.Cancel } }
                    .focusGroup()
                    .pointerInput(ready) {
                        if (!ready) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                    // Its own layer only while it moves, a little wider than the column so
                    // nothing that reaches past its sides is cut off.
                    .graphicsLayer {
                        compositingStrategy = if (shown.value < 0.999f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    }
                    .drawWithContent {
                        drawContent()
                        val q = shown.value.coerceIn(0f, 1f)
                        if (q < 0.999f) {
                            // The edge runs a fade's length past the end, so nothing is still
                            // faded when it stops.
                            val f = REVEAL_FADE.toPx()
                            val edge = (full[0] + f) * q
                            drawRect(
                                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = edge - f, endY = edge),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    }
                    .padding(horizontal = REVEAL_BLEED),
            ) { content() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val bleed = REVEAL_BLEED.roundToPx()
        val inner = measurables.first().measure(
            if (constraints.hasBoundedWidth) Constraints.fixedWidth(constraints.maxWidth + 2 * bleed) else Constraints(),
        )
        val w = inner.width - 2 * bleed
        full[0] = inner.height.toFloat()
        val q = shown.value.coerceIn(0f, 1f)
        val h = min(inner.height.toFloat(), (inner.height + REVEAL_FADE.toPx()) * q).roundToInt()
        layout(w, h) { inner.place(-bleed, 0) }
    }
}
