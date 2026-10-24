package dev.continuum.fx.dsp

import java.util.UUID

/**
 * Filter shapes supported by the parametric EQ. [code] is the Equalizer APO
 * keyword used when exporting, which is also what squig.link and AutoEQ write.
 */
enum class FilterType(val code: String, val shortLabel: String, val label: String, val usesGain: Boolean) {
    PEAK("PK", "PK", "Peak", true),
    LOW_SHELF("LSC", "LS", "Low shelf", true),
    HIGH_SHELF("HSC", "HS", "High shelf", true),
    LOW_PASS("LPQ", "LP", "Low pass", false),
    HIGH_PASS("HPQ", "HP", "High pass", false),
    NOTCH("NO", "NO", "Notch", false),
    BAND_PASS("BP", "BP", "Band pass", false),
    ALL_PASS("AP", "AP", "All pass", false);

    companion object {
        /** Maps every spelling used by Equalizer APO, AutoEQ and squig.link. */
        fun fromCode(code: String): FilterType? = when (code.uppercase()) {
            "PK", "PEQ", "PEAK", "MODAL" -> PEAK
            "LS", "LSC", "LSQ", "LOWSHELF" -> LOW_SHELF
            "HS", "HSC", "HSQ", "HIGHSHELF" -> HIGH_SHELF
            "LP", "LPQ", "LOWPASS" -> LOW_PASS
            "HP", "HPQ", "HIGHPASS" -> HIGH_PASS
            "NO", "NOTCH" -> NOTCH
            "BP", "BANDPASS" -> BAND_PASS
            "AP", "ALLPASS" -> ALL_PASS
            else -> null
        }
    }
}

data class EqBand(
    val type: FilterType = FilterType.PEAK,
    val frequency: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 0.707,
    val enabled: Boolean = true,
)

data class EqProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val preampDb: Double = 0.0,
    val bands: List<EqBand> = emptyList(),
) {
    companion object {
        fun flat(): EqProfile = EqProfile(name = "Flat")
    }
}

object EqLimits {
    const val MIN_FREQ = 20.0
    const val MAX_FREQ = 20000.0
    const val MIN_GAIN = -24.0
    const val MAX_GAIN = 24.0
    const val MIN_Q = 0.1
    const val MAX_Q = 10.0
    const val MIN_PREAMP = -30.0
    const val MAX_PREAMP = 12.0
}
