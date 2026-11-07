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

    // Equalizer APO's sections for one side: "Channel: L", "Channel: R", back to "Channel: all".
    private val channelRegex = Regex("""^Channel\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

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
        // Which side the lines are for: "all", "L", "R", or another channel this EQ can't hold.
        var section = "all"
        val sideBands = mapOf("L" to mutableListOf<EqBand>(), "R" to mutableListOf<EqBand>())
        val sideLevel = mutableMapOf("L" to 0.0, "R" to 0.0)
        var skipped = false

        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("GraphicEQ", ignoreCase = true)) {
                sawGraphic = true
                return@forEachIndexed
            }
            channelRegex.find(line)?.let {
                val names = it.groupValues[1].trim().uppercase().split(Regex("""[\s,]+""")).filter { n -> n.isNotEmpty() }.toSet()
                section = when {
                    names.isEmpty() || "ALL" in names || names.containsAll(listOf("L", "R")) -> "all"
                    names == setOf("L") -> "L"
                    names == setOf("R") -> "R"
                    else -> "other"
                }
                return@forEachIndexed
            }
            if (section == "other") {
                skipped = true
                return@forEachIndexed
            }
            if (copyRegex.containsMatchIn(line)) {
                flipLeft = flipped(line, 'L')
                flipRight = flipped(line, 'R')
                return@forEachIndexed
            }
            preampRegex.find(line)?.let {
                if (section == "all") preamp += number(it.groupValues[1])
                else sideLevel[section] = (sideLevel[section] ?: 0.0) + number(it.groupValues[1])
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
            val band = EqBand(
                type = type,
                frequency = freq,
                gainDb = if (type.usesGain) gain else 0.0,
                q = q,
                enabled = enabled,
            )
            if (section == "all") bands += band else sideBands.getValue(section) += band
        }
        if (skipped) warnings += "Filters for channels other than left and right were left out."

        val left = SideTrim(sideLevel.getValue("L"), sideBands.getValue("L")).takeIf { !it.isEmpty }
        val right = SideTrim(sideLevel.getValue("R"), sideBands.getValue("R")).takeIf { !it.isEmpty }
        if (bands.isEmpty() && left == null && right == null) {
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
        return Result(
            EqProfile(name = cleanName(name), preampDb = preamp, bands = bands, polarity = polarity, left = left, right = right),
            warnings,
            null,
        )
    }

    /** Writes the profile in the same format, so it can go back into squig.link or Equalizer APO. */
    fun export(profile: EqProfile): String = buildString {
        append("Preamp: ").append(fmt(profile.preampDb, 1)).append(" dB\n")
        if (profile.polarity != Polarity.Normal) {
            val l = if (profile.polarity.left < 0) "-1*L" else "L"
            val r = if (profile.polarity.right < 0) "-1*R" else "R"
            append("Copy: L=").append(l).append(" R=").append(r).append('\n')
        }
        var n = 0
        fun filter(b: EqBand) {
            append("Filter ").append(++n).append(": ")
            append(if (b.enabled) "ON " else "OFF ")
            append(b.type.code).append(" Fc ").append(fmt(b.frequency, if (b.frequency < 100) 1 else 0)).append(" Hz")
            if (b.type.usesGain) append(" Gain ").append(fmt(b.gainDb, 1)).append(" dB")
            append(" Q ").append(fmt(b.q, 2)).append('\n')
        }
        profile.bands.forEach { filter(it) }
        if (profile.sided) {
            // Each side's own trim, as Equalizer APO sections after the shared filters.
            for ((label, trim) in listOf("L" to profile.left, "R" to profile.right)) {
                if (trim == null || trim.isEmpty) continue
                append("Channel: ").append(label).append('\n')
                if (abs(trim.levelDb) >= 0.05) append("Preamp: ").append(fmt(trim.levelDb, 1)).append(" dB\n")
                trim.bands.forEach { filter(it) }
            }
            append("Channel: all\n")
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
