package dev.continuum.fx.dsp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Reads and writes parametric EQ in the Equalizer APO text format, which is what
 * squig.link's "Export" and AutoEQ's "ParametricEQ.txt" produce:
 *
 *     Preamp: -6.4 dB
 *     Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
 *     Filter 2: ON PK Fc 2500 Hz Gain -3.1 dB Q 1.41
 */
object EqImport {

    data class Result(
        val profile: EqProfile?,
        val warnings: List<String>,
        val error: String?,
    )

    private const val NUM = """[-+]?\d+(?:[.,]\d+)?"""
    private val preampRegex = Regex("""^Preamp\s*:\s*($NUM)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterRegex = Regex(
        """^Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Za-z]+)(?:\s+(\d+)\s*dB)?\s+Fc\s+($NUM)\s*(k?)Hz(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val gainRegex = Regex("""Gain\s+($NUM)\s*dB""", RegexOption.IGNORE_CASE)
    private val qRegex = Regex("""(?:^|\s)Q\s+($NUM)""", RegexOption.IGNORE_CASE)
    private val bwRegex = Regex("""BW\s+Oct\s+($NUM)""", RegexOption.IGNORE_CASE)

    // Equalizer APO turns a channel over with Copy, e.g. "Copy: L=-1*L R=-1*R" (or "L=-L").
    private val copyRegex = Regex("""^Copy\s*:""", RegexOption.IGNORE_CASE)
    private fun flipped(line: String, ch: Char) =
        Regex("""(?:^|[\s:])$ch\s*=\s*-\s*(?:1(?:[.,]0+)?\s*\*\s*)?$ch(?![A-Za-z])""", RegexOption.IGNORE_CASE).containsMatchIn(line)

    fun parse(text: String, name: String = "Imported EQ"): Result {
        val warnings = mutableListOf<String>()
        val bands = mutableListOf<EqBand>()
        var preamp = 0.0
        var sawPreamp = false
        var sawGraphic = false
        var flipLeft = false
        var flipRight = false

        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("GraphicEQ", ignoreCase = true)) {
                sawGraphic = true
                return@forEachIndexed
            }
            if (copyRegex.containsMatchIn(line)) {
                flipLeft = flipped(line, 'L')
                flipRight = flipped(line, 'R')
                return@forEachIndexed
            }
            preampRegex.find(line)?.let {
                preamp += number(it.groupValues[1])
                sawPreamp = true
                return@forEachIndexed
            }
            val m = filterRegex.find(line) ?: return@forEachIndexed
            val enabled = m.groupValues[1].equals("ON", ignoreCase = true)
            val code = m.groupValues[2]
            val type = FilterType.fromCode(code)
            if (type == null) {
                warnings += "Line ${index + 1}: the $code filter type isn't supported yet, so it was skipped."
                return@forEachIndexed
            }
            var freq = number(m.groupValues[4])
            if (m.groupValues[5].equals("k", ignoreCase = true)) freq *= 1000.0
            if (freq <= 0.0) {
                warnings += "Line ${index + 1}: the frequency must be above 0 Hz, so it was skipped."
                return@forEachIndexed
            }
            val rest = m.groupValues[6]
            val slopeDb = m.groupValues[3].toIntOrNull()
            val gain = gainRegex.find(rest)?.let { number(it.groupValues[1]) } ?: 0.0
            val q = qRegex.find(rest)?.let { number(it.groupValues[1]) }
                ?: bwRegex.find(rest)?.let { bandwidthToQ(number(it.groupValues[1])) }
                ?: defaultQ(type, slopeDb)
            bands += EqBand(
                type = type,
                frequency = freq,
                gainDb = if (type.usesGain) gain else 0.0,
                q = q,
                enabled = enabled,
            )
        }

        if (bands.isEmpty()) {
            val error = if (sawGraphic) {
                "This is a graphic EQ file. In squig.link, export the parametric EQ instead."
            } else if (sawPreamp) {
                "This file only has a preamp and no filters."
            } else {
                "No parametric EQ filters were found. Make sure it's an Equalizer APO or squig.link export."
            }
            return Result(null, warnings, error)
        }
        val polarity = when {
            flipLeft && flipRight -> Polarity.Inverted
            flipLeft -> Polarity.LeftInverted
            flipRight -> Polarity.RightInverted
            else -> Polarity.Normal
        }
        return Result(EqProfile(name = cleanName(name), preampDb = preamp, bands = bands, polarity = polarity), warnings, null)
    }

    /** Writes the profile in the same format, so it can go back into squig.link or Equalizer APO. */
    fun export(profile: EqProfile): String = buildString {
        append("Preamp: ").append(fmt(profile.preampDb, 1)).append(" dB\n")
        if (profile.polarity != Polarity.Normal) {
            val l = if (profile.polarity.left < 0) "-1*L" else "L"
            val r = if (profile.polarity.right < 0) "-1*R" else "R"
            append("Copy: L=").append(l).append(" R=").append(r).append('\n')
        }
        profile.bands.forEachIndexed { i, b ->
            append("Filter ").append(i + 1).append(": ")
            append(if (b.enabled) "ON " else "OFF ")
            append(b.type.code).append(" Fc ").append(fmt(b.frequency, if (b.frequency < 100) 1 else 0)).append(" Hz")
            if (b.type.usesGain) append(" Gain ").append(fmt(b.gainDb, 1)).append(" dB")
            append(" Q ").append(fmt(b.q, 2)).append('\n')
        }
    }

    /** Equalizer APO's bandwidth-in-octaves to Q conversion. */
    fun bandwidthToQ(octaves: Double): Double {
        val p = 2.0.pow(octaves)
        return sqrt(p) / (p - 1.0)
    }

    private fun defaultQ(type: FilterType, slopeDb: Int?): Double = when (type) {
        FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> if (slopeDb == 6) 0.5 else 0.7071
        FilterType.PEAK -> 1.0
        else -> 0.7071
    }

    private fun number(s: String): Double = s.replace(',', '.').toDouble()

    private fun fmt(v: Double, decimals: Int): String {
        val clean = if (abs(v) < 0.5 * 10.0.pow(-decimals)) 0.0 else v
        return String.format(Locale.US, "%.${decimals}f", clean)
    }

    private fun cleanName(raw: String): String {
        val n = raw.substringAfterLast('/').substringBeforeLast('.').trim()
            .replace(Regex("""\s*\(?ParametricEQ\)?\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return n.ifEmpty { "Imported EQ" }
    }
}
