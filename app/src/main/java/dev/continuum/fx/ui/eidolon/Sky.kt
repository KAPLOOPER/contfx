/*
 * The sky.
 *
 * One slow field of cloud is computed for the whole screen and printed three ways: as the poster's
 * day (night at the top falling to day), as the calm night the settings sit on, and lit, for the
 * windows cut into the paper. Each print is quantised to five inks with an ordered dither and laid
 * under a dot screen, so it reads as a halftone plate rather than a gradient. It moves a few frames
 * a second on purpose: the stutter is part of the print.
 *
 * The sky never scrolls. [SkyStage] fixes it behind the page, and anything that wants to show it
 * through the paper uses [skyWindow], which works out where on the screen it is and draws the part
 * of the sky that is behind it, so a switch or a chosen cell reads as a hole cut through the slip.
 */

package dev.continuum.fx.ui.eidolon

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Inks

/** The five inks of the backdrop and of the windows, darkest first, and the ground between dots. */
@Immutable
internal class SkyInks(
    val sky: IntArray,
    val skyGround: Color,
    val window: IntArray,
    val windowGround: Color,
    /** How dark the night is: where it starts, how far it falls, how high the cloud lifts it. */
    val nightBase: Float,
    val nightFall: Float,
    val nightLift: Float,
)

/** The palette's inks with [wake] of their colour left in them: 0 asleep (drained), 1 awake. */
internal fun EidolonColors.skyInks(wake: Float): SkyInks {
    val w = wake.coerceIn(0f, 1f)
    return SkyInks(
        sky = IntArray(sky.size) { lerp(skyDormant[it], sky[it], w).toArgb() },
        skyGround = lerp(skyGroundDormant, skyGround, w),
        window = IntArray(windowSky.size) { lerp(windowSkyDormant[it], windowSky[it], w).toArgb() },
        windowGround = lerp(windowGroundDormant, windowGround, w),
        // On paper the night is kept deep, navy with ultramarine cloud, so the windows cut into
        // the slips are the brightest thing on the page. The negative print's night is greys and
        // can carry more cloud without competing with the coloured windows.
        nightBase = if (isDark) 0.18f else -0.55f,
        nightFall = if (isDark) 0.32f else 0.3f,
        nightLift = if (isDark) 1.95f else 2.05f,
    )
}

// ---------------------------------------------------------------------------------------------
// The field

/** One cell of sky per pixel; drawn scaled up, unfiltered, with the dot screen over it. */
class SkyField internal constructor(val cols: Int, val rows: Int) {
    private val cloud = FloatArray(cols * rows)
    private val dayPixels = IntArray(cols * rows)
    private val nightPixels = IntArray(cols * rows)
    private val litPixels = IntArray(cols * rows)
    private val dayBitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
    private val nightBitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
    private val litBitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)

    /** The poster's sky: night at the top of the poster falling to day at its foot. */
    val day: ImageBitmap = dayBitmap.asImageBitmap()

    /** The calm night the settings are pasted on. */
    val night: ImageBitmap = nightBitmap.asImageBitmap()

    /** The sky seen through a window: brighter, and in colour even when the page is in negative. */
    val lit: ImageBitmap = litBitmap.asImageBitmap()

    /** Fills the working buffers. Safe off the main thread; [commit] publishes them. */
    internal fun print(time: Float, inks: SkyInks, dayRows: Int) {
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val drift = fbm(x * 0.045f + time, y * 0.075f + 3.1f, 4)
                val billow = fbm(x * 0.11f + time * 1.6f + 40f, y * 0.16f + 9f, 3)
                cloud[y * cols + x] = ((drift * 0.75f + billow * 0.25f - 0.47f) * 5.2f).coerceIn(0f, 1f)
            }
        }
        val top = inks.sky.size - 1
        val fall = max(1, dayRows).toFloat()
        for (y in 0 until rows) {
            val bayerRow = BAYER[y and 3]
            val dayBase = 0.55f + 1.25f * min(1f, y / fall)
            val nightBase = inks.nightBase + inks.nightFall * y / rows
            for (x in 0 until cols) {
                val i = y * cols + x
                val c = cloud[i]
                val b = bayerRow[x and 3]
                dayPixels[i] = inks.sky[tone(dayBase + c * (top - dayBase) + b, top)]
                nightPixels[i] = inks.sky[tone(nightBase + c * inks.nightLift + b, top)]
                litPixels[i] = inks.window[tone(LIT_BASE + c * LIT_LIFT + b, top)]
            }
        }
    }

    internal fun commit() {
        dayBitmap.setPixels(dayPixels, 0, cols, 0, 0, cols, rows)
        nightBitmap.setPixels(nightPixels, 0, cols, 0, 0, cols, rows)
        litBitmap.setPixels(litPixels, 0, cols, 0, 0, cols, rows)
    }

    private companion object {
        // Lit: ultramarine, the cloud reaching periwinkle and only rarely white, so paper-coloured
        // type laid over a window stays readable.
        const val LIT_BASE = 1.0f
        const val LIT_LIFT = 1.62f

        fun tone(value: Float, top: Int): Int = floor(value).toInt().coerceIn(0, top)

        val BAYER: Array<FloatArray> =
            arrayOf(
                floatArrayOf(0f, 8f, 2f, 10f),
                floatArrayOf(12f, 4f, 14f, 6f),
                floatArrayOf(3f, 11f, 1f, 9f),
                floatArrayOf(15f, 7f, 13f, 5f),
            ).map { row -> FloatArray(4) { row[it] / 16f } }.toTypedArray()

        // A fixed lattice of noise, seeded so the same clouds return every launch.
        val LATTICE: FloatArray =
            java.util.Random(7L).let { random -> FloatArray(256 * 256) { random.nextFloat() } }

        fun lattice(x: Int, y: Int): Float = LATTICE[((y and 255) shl 8) or (x and 255)]

        fun valueNoise(x: Float, y: Float): Float {
            val xi = floor(x).toInt()
            val yi = floor(y).toInt()
            val xf = x - xi
            val yf = y - yi
            val u = xf * xf * (3f - 2f * xf)
            val v = yf * yf * (3f - 2f * yf)
            val a = lattice(xi, yi)
            val b = lattice(xi + 1, yi)
            val c = lattice(xi, yi + 1)
            val d = lattice(xi + 1, yi + 1)
            return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
        }

        fun fbm(x0: Float, y0: Float, octaves: Int): Float {
            var x = x0
            var y = y0
            var sum = 0f
            var amplitude = 0.5f
            var total = 0f
            repeat(octaves) {
                sum += amplitude * valueNoise(x, y)
                total += amplitude
                x = x * 2.02f + 11.3f
                y = y * 2.02f + 4.7f
                amplitude *= 0.5f
            }
            return sum / total
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The stage

/** The sky behind the current page, for anything inside it that wants to cut a window. */
@Stable
class SkyState internal constructor(internal val plate: SkyField, val cellPx: Int) {
    internal val frame = mutableIntStateOf(0)

    /** Where the stage's top-left corner is on the screen. */
    internal var origin by mutableStateOf(Offset.Zero)

    internal var groundScreen: ShaderBrush by mutableStateOf(dotScreen(cellPx, Color.Black))
    internal var windowScreen: ShaderBrush by mutableStateOf(dotScreen(cellPx, Color.Black))
    /**
     * Draws the stage as it stands behind the page (the sky, the poster's day over it, and the
     * paper with its window), in the stage's coordinates. Set by [SkyStage].
     */
    internal var backdrop: (DrawScope.() -> Unit)? = null

    /** The stage's own size, which the backdrop is drawn at wherever it is drawn. */
    internal var stageSize = Size.Zero

    private var groundColor = Color.Unspecified
    private var windowColor = Color.Unspecified

    internal val printedSize: Size get() = Size((plate.cols * cellPx).toFloat(), (plate.rows * cellPx).toFloat())

    internal fun updateScreens(inks: SkyInks) {
        if (inks.skyGround != groundColor) {
            groundColor = inks.skyGround
            groundScreen = dotScreen(cellPx, inks.skyGround)
        }
        if (inks.windowGround != windowColor) {
            windowColor = inks.windowGround
            windowScreen = dotScreen(cellPx, inks.windowGround)
        }
    }
}

val LocalSky = staticCompositionLocalOf<SkyState?> { null }

/** One print of the sky, scaled up to the screen, with its dot screen over it. */
internal fun DrawScope.printSky(sky: SkyState, image: ImageBitmap, screen: ShaderBrush, alpha: Float = 1f) {
    val printed = sky.printedSize
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(sky.plate.cols, sky.plate.rows),
        dstSize = IntSize(printed.width.toInt(), printed.height.toInt()),
        alpha = alpha,
        filterQuality = FilterQuality.None,
    )
    drawRect(screen, size = printed, alpha = alpha)
}

/**
 * Fixes a sky behind [content] and keeps it drifting while the screen is resumed.
 *
 * @param wake 0 when the island is off (the colour drains out of every print), 1 when it is on
 * @param dayHeight how far down the screen the poster's day falls from night
 * @param dayWeight how much of the poster's day is showing over the night, read every frame
 * @param mask drawn over the sky and under the content: the paper with its window cut out
 */
@Composable
fun SkyStage(
    wake: () -> Float,
    modifier: Modifier = Modifier,
    dayHeight: Dp = 0.dp,
    dayWeight: () -> Float = { 0f },
    mask: (DrawScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalEidolonColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 1080
        val heightPx = if (constraints.hasBoundedHeight) constraints.maxHeight else 2400
        val scale = min(maxWidth.value, 520f) / 412f
        val cell = with(density) { (3.2f * scale).dp.toPx() }.roundToInt().coerceAtLeast(3)
        val cols = ceil(widthPx / cell.toFloat()).toInt() + 1
        val rows = ceil(heightPx / cell.toFloat()).toInt() + 1
        val dayRows = with(density) { dayHeight.toPx() / cell }.roundToInt()

        val inks = rememberUpdatedState { colors.skyInks(wake()) }
        // The first frame is printed on the spot (a few milliseconds) so the screen never opens
        // onto a blank plate; every later frame is computed off the main thread.
        val sky =
            remember(cols, rows, cell, dayRows, colors) {
                SkyState(SkyField(cols, rows), cell).also {
                    val first = inks.value()
                    it.plate.print(0f, first, dayRows)
                    it.plate.commit()
                    it.updateScreens(first)
                }
            }
        val still =
            remember(context) {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }

        LaunchedEffect(sky, still) {
            var time = 0f
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var printedWith = inks.value()
                while (true) {
                    delay(FRAME_MS)
                    val now = inks.value()
                    // With animations switched off system-wide the clouds hold still, but the
                    // plate still reprints when its inks change (the island waking or sleeping).
                    val inksChanged = !now.sky.contentEquals(printedWith.sky) || !now.window.contentEquals(printedWith.window)
                    if (still && !inksChanged) continue
                    if (!still) time += DRIFT_PER_FRAME
                    withContext(Dispatchers.Default) { sky.plate.print(time, now, dayRows) }
                    sky.plate.commit()
                    sky.updateScreens(now)
                    printedWith = now
                    sky.frame.intValue++
                }
            }
        }

        CompositionLocalProvider(LocalSky provides sky) {
            val latestMask = rememberUpdatedState(mask)
            val latestDay = rememberUpdatedState(dayWeight)
            sky.backdrop = {
                printSky(sky, sky.plate.night, sky.groundScreen)
                val day = latestDay.value().coerceIn(0f, 1f)
                if (day > 0.002f) printSky(sky, sky.plate.day, sky.groundScreen, day)
                latestMask.value?.invoke(this)
            }
            Canvas(
                Modifier
                    .matchParentSize()
                    .onGloballyPositioned { sky.origin = it.positionOnScreen() },
            ) {
                sky.frame.intValue // a new frame of sky has been printed: redraw
                sky.stageSize = size
                sky.backdrop?.invoke(this)
            }
            content()
        }
    }
}

private const val FRAME_MS = 140L
private const val DRIFT_PER_FRAME = 0.012f

// ---------------------------------------------------------------------------------------------
// Windows

/** Drawing inside a [skyWindow]: everything a [ContentDrawScope] does, and the sky behind the page. */
interface SkyWindowScope : ContentDrawScope {
    /**
     * Fills [path] with the part of the sky that is behind it on the screen, lit. [behind] draws it
     * under whatever this layer has drawn already (the layer must be offscreen for that to mean
     * anything), so a word drawn first stays on top of the window.
     */
    fun drawSky(path: Path, alpha: Float = 1f, behind: Boolean = false)

    /**
     * Fills [path] with exactly what the stage shows behind it: the night, the poster's day and the
     * paper, as they stand this frame. A hole cut to it looks like a hole in whatever it is cut in.
     */
    fun drawBackdrop(path: Path)
}

/**
 * Draws [onDraw] behind this node's content, with the means to cut windows through to the sky.
 * With [drawsContent] the content is not drawn for it: [onDraw] calls drawContent() itself, where
 * it wants it. Outside a [SkyStage] (a preview, a popup in another window) a window is filled with
 * ultramarine instead, and the backdrop with nothing.
 */
fun Modifier.skyWindow(drawsContent: Boolean = false, onDraw: SkyWindowScope.() -> Unit): Modifier =
    this then SkyWindowElement(drawsContent, onDraw)

private data class SkyWindowElement(
    val drawsContent: Boolean,
    val onDraw: SkyWindowScope.() -> Unit,
) : ModifierNodeElement<SkyWindowNode>() {
    override fun create() = SkyWindowNode(drawsContent, onDraw)

    override fun update(node: SkyWindowNode) {
        node.drawsContent = drawsContent
        node.onDraw = onDraw
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "skyWindow"
    }
}

private class SkyWindowNode(var drawsContent: Boolean, var onDraw: SkyWindowScope.() -> Unit) :
    Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    private var position = Offset.Unspecified

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val now = coordinates.positionOnScreen()
        if (now != position) {
            position = now
            // Redraw in the same frame the node moved, so the window never lags the scroll.
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val sky = currentValueOf(LocalSky)
        val colors = currentValueOf(LocalEidolonColors)
        sky?.frame?.intValue
        WindowScope(this, sky, colors.ultramarine, position).onDraw()
        if (!drawsContent) drawContent()
    }

    private class WindowScope(
        private val base: ContentDrawScope,
        private val sky: SkyState?,
        private val fallback: Color,
        private val position: Offset,
    ) : SkyWindowScope, ContentDrawScope by base {
        override fun drawSky(path: Path, alpha: Float, behind: Boolean) {
            val stage = sky
            if (stage == null || !position.isSpecified) {
                base.drawPath(path, fallback, alpha = alpha, blendMode = if (behind) BlendMode.DstOver else BlendMode.SrcOver)
                return
            }
            val offset = position - stage.origin
            base.clipPath(path) {
                translate(-offset.x, -offset.y) {
                    if (behind) {
                        // Under what is there: the screen first, then the print beneath it.
                        drawRect(stage.windowScreen, size = stage.printedSize, alpha = alpha, blendMode = BlendMode.DstOver)
                        drawSkyImage(stage, stage.plate.lit, alpha, BlendMode.DstOver)
                    } else {
                        printSky(stage, stage.plate.lit, stage.windowScreen, alpha)
                    }
                }
            }
        }

        override fun drawBackdrop(path: Path) {
            val stage = sky ?: return
            val draw = stage.backdrop ?: return
            if (!position.isSpecified) return
            val offset = position - stage.origin
            base.clipPath(path) {
                translate(-offset.x, -offset.y) {
                    // Drawn at the stage's size, not this node's: the paper's window is placed
                    // against the whole stage.
                    val own = drawContext.size
                    drawContext.size = stage.stageSize
                    try {
                        draw()
                    } finally {
                        drawContext.size = own
                    }
                }
            }
        }
    }
}

/** Just the print of a sky, scaled up, without its screen. */
internal fun DrawScope.drawSkyImage(sky: SkyState, image: ImageBitmap, alpha: Float, blendMode: BlendMode) {
    val printed = sky.printedSize
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(sky.plate.cols, sky.plate.rows),
        dstSize = IntSize(printed.width.toInt(), printed.height.toInt()),
        alpha = alpha,
        blendMode = blendMode,
        filterQuality = FilterQuality.None,
    )
}

// ---------------------------------------------------------------------------------------------
// Screens and edges

/**
 * The halftone screen laid over a sky: a tile the size of one cell, opaque everywhere except a
 * round hole, so each square of sky shows through as a dot on the plate's ground.
 */
fun dotScreen(cell: Int, ground: Color): ShaderBrush {
    val rgb = ground.toArgb() and 0x00FFFFFF
    val pixels = IntArray(cell * cell)
    val mid = (cell - 1) / 2f
    val radius = cell * 0.46f
    for (y in 0 until cell) {
        for (x in 0 until cell) {
            val dx = x - mid
            val dy = y - mid
            val hole = (radius + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
            val alpha = ((1f - hole) * 255f).roundToInt()
            pixels[y * cell + x] = (alpha shl 24) or rgb
        }
    }
    val tile = Bitmap.createBitmap(pixels, cell, cell, Bitmap.Config.ARGB_8888).asImageBitmap()
    return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
}

/** The torn-off edge of a sheet of paper: solid, breaking into shrinking dots over [bandPx]. */
@Immutable
class PaperEdge internal constructor(internal val brush: ShaderBrush, val bandPx: Float, val color: Color)

@Composable
fun rememberPaperEdge(color: Color, band: Dp = 30.dp): PaperEdge {
    val density = LocalDensity.current
    val cell = LocalSky.current?.cellPx ?: with(density) { 3.2.dp.toPx() }.roundToInt().coerceAtLeast(3)
    val bandPx = with(density) { band.toPx() }.roundToInt().coerceAtLeast(cell)
    return remember(color, cell, bandPx) {
        val rgb = color.toArgb() and 0x00FFFFFF
        val pixels = IntArray(cell * bandPx)
        for (y in 0 until bandPx) {
            val row = y / cell
            val cy = (row + 0.5f) * cell
            val t = (cy / bandPx).coerceIn(0f, 1f)
            // Large enough at the top to merge into solid paper, shrinking to nothing at the foot.
            val radius = cell * 0.78f * (1f - t).pow(1.15f)
            for (x in 0 until cell) {
                val dx = x + 0.5f - cell / 2f
                val dy = y + 0.5f - cy
                val cover = (radius + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
                pixels[y * cell + x] = ((cover * 255f).roundToInt() shl 24) or rgb
            }
        }
        val tile = Bitmap.createBitmap(pixels, cell, bandPx, Bitmap.Config.ARGB_8888).asImageBitmap()
        PaperEdge(ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Clamp)), bandPx.toFloat(), color)
    }
}

/** Paper from the top of this scope down to [edge], then the dissolve below it. */
fun DrawScope.drawPaperTo(edge: Float, paper: PaperEdge) {
    if (edge > 0f) drawRect(paper.color, size = Size(size.width, edge))
    translate(0f, edge) { drawRect(paper.brush, size = Size(size.width, paper.bandPx)) }
}
