/*
 * Eidolon type: one variable grotesque pulled three ways, and a mincho for the second voice.
 *
 * Google Sans Flex has a roundness axis; it is kept at zero, and the width axis does the talking
 * instead: narrow and tight for anything set large, wide capitals for the small print that
 * annotates it. The island overlay, the volume panel and the keyboard keep Continuum's Libron.
 */

package dev.continuum.fx.ui.eidolon

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.continuum.fx.R

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int, width: Float, opticalSize: Float? = null): Font =
    Font(
        R.font.google_sans_flex,
        weight = FontWeight(weight),
        variationSettings =
            FontVariation.Settings(
                *listOfNotNull(
                    FontVariation.weight(weight),
                    FontVariation.width(width),
                    FontVariation.Setting("ROND", 0f),
                    opticalSize?.let { FontVariation.Setting("opsz", it) },
                ).toTypedArray(),
            ),
    )

private fun flexFamily(width: Float, opticalSize: Float?, vararg weights: Int): FontFamily =
    FontFamily(weights.map { flex(it, width, opticalSize) })

/** Running text, row names, buttons. */
val Grotesk: FontFamily = flexFamily(100f, null, 400, 500, 600, 700)

/** Anything set large: narrowed, cut for display sizes, tracked tight where it is used. */
val GroteskTight: FontFamily = flexFamily(88f, 96f, 400, 500, 600, 700)

/** Small print: wide capitals for notes, section names and numbers in the margin. */
val GroteskWide: FontFamily = flexFamily(130f, 9f, 400, 500, 600, 700)

/** The second voice. A subset of Shippori Mincho holding the kana and the kanji the app sets. */
val Mincho: FontFamily = FontFamily(Font(R.font.shippori_mincho_subset, FontWeight.Medium))
