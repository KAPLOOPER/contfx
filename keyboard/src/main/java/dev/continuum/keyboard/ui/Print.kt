/*
 * The Print look: the keyboard printed the way Continuum Kit's poster is.
 *
 * The keys are a sheet of grainy paper with the letters inked on it, bare. Wherever the keyboard
 * would put bright ink (the runner, shift and send while they're on, the word space will use),
 * a window is cut through the paper instead, and behind it is the poster's sky: one slow field
 * of cloud, broken into five inks with an ordered dither and laid under a dot screen, so it reads
 * as a halftone plate. It drifts on a few frames a second while you type, and holds still when
 * you stop.
 *
 * Everything here is the material; KeyboardView does the drawing, with the same runner it always
 * has.
 */

package dev.continuum.keyboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.continuum.keyboard.R
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The poster's type, cut to Latin for the keyboard: Google Sans Flex with its roundness off. */
class PrintType(context: Context) {
    /** Letters and words: the grotesque at its natural width. */
    val body: Typeface = flex(context, 480, 100f)

    /** The word space will use, and anything that wants weight. */
    val strong: Typeface = flex(context, 640, 100f)

    /** Small print: wide capitals, for space, send and the sheet keys. */
    val wide: Typeface = flex(context, 600, 132f)

    private fun flex(context: Context, weight: Int, width: Float): Typeface =
        try {
            val font = Font.Builder(context.resources, R.font.google_sans_flex_latin)
                .setFontVariationSettings("'wght' $weight, 'wdth' $width")
                .setWeight(weight.coerceIn(1, 1000))
                .build()
            Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build()
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
}

/**
 * The sky behind the windows: a field of cloud one cell per pixel, scaled up unfiltered with a
 * dot screen over it. [shader] paints it; it's printed off the main thread, and a new print
 * shows the next time the keyboard draws.
 */
class PrintSky(private val cellPx: Int, private val inks: IntArray, private val ground: Int) {
    private var cols = 1
    private var rows = 1
    private var bitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var pixels = IntArray(1)
    private var cloud = FloatArray(1)

    /** The sky, with its dot screen, ready to fill a path with. Null until it's been sized. */
    var shader: Shader? = null
        private set

    private var time = START
    private var printing = false
    private var lastPrint = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Sizes the sky to cover [w] by [h] pixels, and prints its first frame straight away. */
    fun size(w: Int, h: Int) {
        val c = max(1, (w + cellPx - 1) / cellPx)
        val r = max(1, (h + cellPx - 1) / cellPx)
        if (c == cols && r == rows && shader != null) return
        cols = c
        rows = r
        bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        pixels = IntArray(cols * rows)
        cloud = FloatArray(cols * rows)
        print(time)
        bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows)
        val sky = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(cellPx.toFloat(), cellPx.toFloat()) })
            if (Build.VERSION.SDK_INT >= 33) filterMode = BitmapShader.FILTER_MODE_NEAREST
        }
        shader = ComposeShader(sky, dotScreen(cellPx, ground), PorterDuff.Mode.SRC_OVER)
    }

    /**
     * Moves the sky on a frame, if it's been long enough since the last: called on each frame the
     * keyboard is moving, so the cloud drifts while you type. [redraw] runs once the new frame is in.
     */
    fun drift(now: Long, redraw: () -> Unit) {
        if (printing || shader == null || now - lastPrint < FRAME_MS) return
        printing = true
        lastPrint = now
        time += DRIFT
        val t = time
        worker.execute {
            print(t)
            main.post {
                bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows)
                printing = false
                redraw()
            }
        }
    }

    private fun print(t: Float) {
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val drift = fbm(x * 0.045f + t, y * 0.075f + 3.1f, 4)
                val billow = fbm(x * 0.11f + t * 1.6f + 40f, y * 0.16f + 9f, 3)
                cloud[y * cols + x] = ((drift * 0.75f + billow * 0.25f - 0.47f) * 5.2f).coerceIn(0f, 1f)
            }
        }
        val top = inks.size - 1
        for (y in 0 until rows) {
            val bayer = BAYER[y and 3]
            for (x in 0 until cols) {
                val i = y * cols + x
                val v = LIT_BASE + cloud[i] * LIT_LIFT + bayer[x and 3]
                pixels[i] = inks[floor(v).toInt().coerceIn(0, top)]
            }
        }
    }

    companion object {
        private const val START = 2.4f
        private const val DRIFT = 0.013f
        private const val FRAME_MS = 150L

        // As the poster's windows: ultramarine, the cloud reaching periwinkle and only rarely
        // white, so paper-white letters laid over a window stay readable.
        private const val LIT_BASE = 1.0f
        private const val LIT_LIFT = 1.62f

        /** One thread prints every sky: there's only ever one keyboard showing. */
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "print-sky").apply { isDaemon = true } }

        private val BAYER: Array<FloatArray> =
            arrayOf(
                floatArrayOf(0f, 8f, 2f, 10f),
                floatArrayOf(12f, 4f, 14f, 6f),
                floatArrayOf(3f, 11f, 1f, 9f),
                floatArrayOf(15f, 7f, 13f, 5f),
            ).map { row -> FloatArray(4) { row[it] / 16f } }.toTypedArray()

        // The poster's own lattice of noise, seeded the same, so it's the same sky.
        private val LATTICE: FloatArray = Random(7L).let { random -> FloatArray(256 * 256) { random.nextFloat() } }

        private fun lattice(x: Int, y: Int): Float = LATTICE[((y and 255) shl 8) or (x and 255)]

        private fun valueNoise(x: Float, y: Float): Float {
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

        private fun fbm(x0: Float, y0: Float, octaves: Int): Float {
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

        /** The halftone screen: a cell-sized tile, opaque in [ground] but for a round hole. */
        fun dotScreen(cell: Int, ground: Int): Shader {
            val rgb = ground and 0x00FFFFFF
            val px = IntArray(cell * cell)
            val mid = (cell - 1) / 2f
            val radius = cell * 0.46f
            for (y in 0 until cell) {
                for (x in 0 until cell) {
                    val dx = x - mid
                    val dy = y - mid
                    val hole = (radius + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
                    val alpha = ((1f - hole) * 255f).roundToInt()
                    px[y * cell + x] = (alpha shl 24) or rgb
                }
            }
            val tile = Bitmap.createBitmap(px, cell, cell, Bitmap.Config.ARGB_8888)
            return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}

/** Paper grain: faint flecks of black or white, a few heavier fibres (on paper) or dust (on film). */
object PrintGrain {
    private const val TILE = 256
    private var paper: Shader? = null
    private var film: Shader? = null

    fun shader(dark: Boolean): Shader =
        (if (dark) film else paper) ?: build(dark).also { if (dark) film = it else paper = it }

    private fun build(dark: Boolean): Shader {
        val random = Random(if (dark) 0x0E1D0L else 0x151A4DL)
        val px = IntArray(TILE * TILE)
        val fine = if (dark) 20f else 15f
        for (i in px.indices) {
            val g = random.nextGaussian().toFloat()
            val alpha = (abs(g) * fine).toInt().coerceAtMost(72)
            px[i] = (alpha shl 24) or (if (g < 0f) 0x000000 else 0xFFFFFF)
        }
        repeat(if (dark) 70 else 100) {
            val x = random.nextInt(TILE)
            val y = random.nextInt(TILE)
            val alpha = if (dark) 60 + random.nextInt(70) else 70 + random.nextInt(110)
            val tone = if (dark) 0xFFFFFF else 0x101014
            val length = if (random.nextInt(5) == 0) 2 + random.nextInt(3) else 1
            val dx = random.nextInt(3) - 1
            val dy = if (dx == 0) 1 else random.nextInt(3) - 1
            for (step in 0 until length) {
                px[((y + dy * step).mod(TILE)) * TILE + (x + dx * step).mod(TILE)] = (alpha shl 24) or tone
            }
        }
        val tile = Bitmap.createBitmap(px, TILE, TILE, Bitmap.Config.ARGB_8888)
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}
