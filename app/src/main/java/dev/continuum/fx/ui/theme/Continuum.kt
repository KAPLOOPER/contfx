package dev.continuum.fx.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R

/**
 * The Continuum palette. Ink is always pure black; the ground changes with the system theme.
 *
 * Colour means one thing each, and nothing is coloured for decoration:
 *  - [text] / [primary] (white on dark): on, or chosen.
 *  - [live] (green): happening right now (charging, a call, a boost in use).
 *  - [danger] (red): needs you (an error, something that stopped).
 * Everything else is the ground, the ink and greys.
 */
data class Palette(
    val ground: Color,
    val surface: Color,
    val text: Color,
    val sub: Color,
    /**
     * Quieter than [sub]: counts and the numbers in the margin. Still 4.5:1 on the ground, so it
     * reads; what sets it apart from [sub] is the small capitals it's set in, not fading.
     */
    val quiet: Color,
    /**
     * Below [quiet], on purpose: the words of a control that can't be used yet (a button before
     * setup), and nothing else. 3:1 on the ground, so it still reads, and plainly fainter than
     * every word you can touch.
     */
    val faint: Color,
    val line: Color,
    val ink: Color,
    val onInk: Color,
    val onInkSub: Color,
    val inkTonal: Color,
    val inkRaised: Color,
    val primary: Color,
    val onPrimary: Color,
    val danger: Color,
    val live: Color,
    /** Green for words on the ground, dark enough to stay readable in the light theme. */
    val liveText: Color,
    val onLive: Color,
    val edge: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    ground = Color(0xFFEEEEEB),
    surface = Color(0xFFE0E0DC),
    text = Color(0xFF121211),
    sub = Color(0xFF5C5C58),
    quiet = Color(0xFF6B6B66),
    faint = Color(0xFF868682),
    line = Color(0xFFCFCFCA),
    ink = Color(0xFF000000),
    onInk = Color(0xFFF4F4F1),
    onInkSub = Color(0xFFA3A39E),
    inkTonal = Color(0xFF242423),
    inkRaised = Color(0xFF1C1C1B),
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFF4F4F1),
    danger = Color(0xFFA32D2D),
    live = Color(0xFF97C459),
    liveText = Color(0xFF456B1F),
    onLive = Color(0xFF173404),
    edge = Color.Transparent,
    dark = false,
)

val DarkPalette = LightPalette.copy(
    ground = Color(0xFF161615),
    surface = Color(0xFF262624),
    text = Color(0xFFEDEDEA),
    sub = Color(0xFFA3A39E),
    quiet = Color(0xFF8C8C87),
    faint = Color(0xFF666662),
    line = Color(0xFF3A3A37),
    primary = Color(0xFFEDEDEA),
    onPrimary = Color(0xFF121211),
    danger = Color(0xFFF09595),
    liveText = LightPalette.live,
    edge = Color(0xFF2C2C2A),
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val Libron = FontFamily(
    Font(R.font.libron_regular, FontWeight.Normal),
    Font(R.font.libron_bold, FontWeight.Bold),
)

/**
 * The small, exact face of the notes along the top of the app (Inter), set against Libron's
 * warmth: tiny capitals, figures all one width.
 */
val Mark = FontFamily(Font(R.font.inter_medium, FontWeight.Medium))

/**
 * A note: tiny spaced capitals in [Mark], for exact facts at the page's edges. [tracking] is
 * the spacing between letters; the section numbers in the margin take less of it, so they fit
 * their column at larger type.
 */
@Composable
fun Note(text: String, color: Color, modifier: Modifier = Modifier, tracking: TextUnit = 0.9.sp) {
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
    // Small capitals are how it looks, not what it says: TalkBack reads it as written, so "On" or
    // "New" is a word and never spelled out.
    HeldText(caps, keepLastTwo(factsWhole(caps)), modifier, says = { contentDescription = text }) { shown, onLayout, m ->
        BasicText(shown, m, style = style, onTextLayout = onLayout, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * How a headline breaks: balanced, so one that runs to two lines splits into two of about the
 * same length rather than a full line and a word.
 */
val Balanced = LineBreak(
    strategy = LineBreak.Strategy.Balanced,
    strictness = LineBreak.Strictness.Normal,
    wordBreak = LineBreak.WordBreak.Default,
)

/**
 * Words that wrap never end on one word alone on its last line ("…from the phone's own / speaker"):
 * the last two words are held together, so the line before gives one up. Said once, here, for
 * every line of words in the app rather than string by string. A pair too long to move as one is
 * left free, so nothing is ever broken mid-word.
 */
internal fun keepLastTwo(text: String, longest: Int = 24): String {
    val dotted = dotsTrail(text)
    val last = dotted.lastIndexOf(' ')
    if (last <= 0 || last == dotted.length - 1) return dotted
    val before = dotted.lastIndexOf(' ', last - 1)
    if (before < 0 || dotted.indexOf('\n', before) >= 0 || dotted.length - before > longest) return dotted
    return dotted.substring(0, last) + '\u00A0' + dotted.substring(last + 1)
}


/**
 * Words held together ([held], from [keepLastTwo] and [factsWhole]) only while that costs no word
 * its wholeness: if the held text, as it's laid out, breaks a line inside a word (a held pair wider
 * than a narrow line at large type) or is cut off, the words break freely instead ([free]). It's
 * decided while the text is measured, so the held version is never drawn when it doesn't fit, not
 * even for a frame. And the no-break spaces are only how it's drawn: whatever reads the screen
 * (TalkBack, Voice Access, Switch Access, tests) is given the words as written, [free], or what
 * [says] sets instead.
 */
@Composable
internal fun HeldText(
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
    // Read in measure, straight after the held text reports its layout (that report comes during
    // its own measure), so the choice never waits for a recomposition.
    val broke = remember(held) { BooleanArray(1) }
    val report = remember(held) { { r: TextLayoutResult -> broke[0] = r.hasVisualOverflow || brokeInsideAWord(r, held) } }
    Layout(
        contents = listOf(
            { text(held, report, Modifier) },
            { text(free, null, Modifier) },
        ),
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

    // Asked how wide or tall it would be, it answers for the words breaking freely: the narrowest
    // it can go is the free words' longest word, not a held pair.
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
internal fun brokeInsideAWord(r: TextLayoutResult, text: String): Boolean {
    for (i in 0 until r.lineCount - 1) {
        val end = r.getLineEnd(i)
        if (end <= 0 || end >= text.length) continue
        val c = text[end - 1]
        if (c != ' ' && c != '\n' && c != '-' && c != '/' && c != '\u2013' && c != '\u2014') return true
    }
    return false
}

/**
 * A middle dot between two facts ("Notifications · optional") never starts a line: it stays at
 * the end of the line before, after the fact it closes.
 */
private fun dotsTrail(text: String): String = text.replace(" · ", "\u00A0· ")

/**
 * A note's facts are kept whole when it wraps: "OFFLINE · NO INTERNET ACCESS" breaks at the dot,
 * not inside "no internet access". A fact too long to fit a line on its own is left free.
 */
internal fun factsWhole(text: String, longest: Int = 20): String {
    if (!text.contains(" · ")) return text
    return text.split(" · ").joinToString(" · ") { fact ->
        if (fact.length <= longest) fact.replace(' ', '\u00A0') else fact
    }
}

/** Continuum's two springs: Flow for most motion, Snap for presses and small controls. */
object Motion {
    fun <T> flow(): SpringSpec<T> = spring(dampingRatio = 0.613f, stiffness = 240f)
    fun <T> snap(): SpringSpec<T> = spring(dampingRatio = 0.843f, stiffness = 620f)

    /**
     * Flow's pace without its give, for ink travelling a long way to rest: the dock gathering back
     * from a sheet, the update card closing its notes. Flow's overshoot is a share of the distance,
     * so from that far it dips a third of the way under the resting size (the card vanished for
     * eight frames and came back); this one settles within a couple of dp.
     */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 240f)
}

@Composable
fun ContinuumTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette, content = content)
}

@Composable
fun Txt(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
    mono: Boolean = false,
) {
    val style = TextStyle(
        color = color,
        fontSize = size,
        // Room between lines: Libron's descenders and ascenders crowd at its natural spacing.
        lineHeight = size * 1.38f,
        fontFamily = if (mono) FontFamily.Monospace else Libron,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
    )
    HeldText(text, if (maxLines > 1 && !mono) keepLastTwo(text) else text, modifier) { shown, onLayout, m ->
        BasicText(
            shown, m,
            style = style,
            onTextLayout = onLayout,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
