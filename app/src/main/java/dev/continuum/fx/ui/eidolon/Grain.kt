/*
 * Paper grain. Every screen is printed on the same sheet, so the grain goes on last, over
 * everything, the way it would be if the page had been scanned.
 */

package dev.continuum.fx.ui.eidolon

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Random
import kotlin.math.abs

internal object Grain {
    private const val TILE = 384

    private val paper: ImageBitmap by lazy { build(dark = false) }
    private val film: ImageBitmap by lazy { build(dark = true) }

    fun tile(dark: Boolean): ImageBitmap = if (dark) film else paper

    /**
     * A tile of transparent noise: each pixel is a faint fleck of black or white, with a few
     * heavier flecks of fibre (on paper) or dust (on film) scattered through it. Seeded, so the
     * sheet is the same sheet every launch.
     */
    private fun build(dark: Boolean): ImageBitmap {
        val random = Random(if (dark) 0x0E1D0L else 0x151A4DL)
        val pixels = IntArray(TILE * TILE)
        val fine = if (dark) 20f else 15f
        for (i in pixels.indices) {
            val g = random.nextGaussian().toFloat()
            val alpha = (abs(g) * fine).toInt().coerceAtMost(72)
            val tone = if (g < 0f) 0x000000 else 0xFFFFFF
            pixels[i] = (alpha shl 24) or tone
        }
        val flecks = if (dark) 150 else 210
        repeat(flecks) {
            val x = random.nextInt(TILE)
            val y = random.nextInt(TILE)
            val alpha = if (dark) 60 + random.nextInt(70) else 70 + random.nextInt(110)
            val tone = if (dark) 0xFFFFFF else 0x101014
            val length = if (random.nextInt(5) == 0) 2 + random.nextInt(3) else 1
            val dx = random.nextInt(3) - 1
            val dy = if (dx == 0) 1 else random.nextInt(3) - 1
            for (step in 0 until length) {
                val px = (x + dx * step).mod(TILE)
                val py = (y + dy * step).mod(TILE)
                pixels[py * TILE + px] = (alpha shl 24) or tone
            }
        }
        return Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

/** Lays grain over this node and everything inside it. */
@Composable
fun Modifier.paperGrain(strength: Float = 1f): Modifier {
    val dark = Eidolon.colors.isDark
    return drawWithCache {
        val brush = ShaderBrush(ImageShader(Grain.tile(dark), TileMode.Repeated, TileMode.Repeated))
        onDrawWithContent {
            drawContent()
            drawRect(brush, alpha = strength.coerceIn(0f, 1f))
        }
    }
}
