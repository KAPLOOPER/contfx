package dev.continuum.fx.lab

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * What the Lab's page did around its engine, kept here beside it so it's tested with it: Target
 * Studio's recipe, and a fit that matches left and right before it fits the pair to the target.
 */

/** Target Studio's Live model: the hall, your seat and the levels. */
data class StudioModel(
    val r: Double = 20.0,
    val playPhon: Double = 70.0,
    val livePhon: Double = 90.0,
    val projPeak: Double = 3.5,
    val Tmid: Double = 2.0,
    val BR: Double = 1.2,
) {
    fun params() = LiveParams(r = r, playPhon = playPhon, livePhon = livePhon, projPeak = projPeak, Tmid = Tmid, BR = BR)
}

/**
 * Target Studio's taste: shelves, bumps, a tilt, and the separation carve, added ([sep] 1)
 * or taken away (-1) from the target it starts from.
 */
data class StudioTaste(
    val bassG: Double = 0.0,
    val bassF: Double = 105.0,
    val bodyG: Double = 0.0,
    val earG: Double = 0.0,
    val earF: Double = 2800.0,
    val presG: Double = 0.0,
    val airG: Double = 0.0,
    val airF: Double = 11000.0,
    val tilt: Double = 0.0,
    val sep: Double = 0.0,
)

object Studio {
    private val shape0 by lazy { Lab.liveShape() }

    /**
     * One of your targets, reshaped: the Live model's change from its defaults (a bigger hall, a
     * nearer seat, a quieter evening), then your taste, then the carve. With every slider at
     * rest it's the target as it came in, re 500 Hz.
     */
    fun target(base: DoubleArray, m: StudioModel, t: StudioTaste): DoubleArray {
        val fs = 192000.0
        val model = if (m == StudioModel()) DoubleArray(Lab.N) else Lab.sub(Lab.liveShape(m.params()), shape0)
        val parts = arrayOf(
            Lab.filterDb("LSC", t.bassF, t.bassG, 0.7, fs),
            Lab.filterDb("PK", 320.0, t.bodyG, 0.9, fs),
            Lab.filterDb("PK", t.earF, t.earG, 1.2, fs),
            Lab.filterDb("PK", 5000.0, t.presG, 1.1, fs),
            Lab.filterDb("HSC", t.airF, t.airG, 0.7, fs),
        )
        val tilt = DoubleArray(Lab.N) { t.tilt * Lab.log2(Lab.F[it] / 1000) }
        // Levelled before the carve, so the carve's dip at 500 Hz shows as the carve does.
        return Lab.add(Lab.norm(Lab.add(base, model, *parts, tilt), 500.0), Lab.carveDb(t.sep))
    }
}

/** A measurement as the fitter sees it: its channels, their power average and their spread. */
class Device(val label: String, val rig: String, val chs: List<DoubleArray>, val chLabels: List<String>) {
    val y: DoubleArray = Lab.powerAverage(chs)
    val spread: DoubleArray? = Lab.spread(chs)
    val nch: Int get() = chs.size

    /** Which channels are left and right, when there's one of each. */
    val lr: Pair<Int, Int>? = run {
        if (chs.size < 2) return@run null
        val l = chLabels.indexOfFirst { it.startsWith("l", ignoreCase = true) }
        val r = chLabels.indexOfFirst { it.startsWith("r", ignoreCase = true) }
        if (l < 0 || r < 0 || l == r) null else l to r
    }
}

/** How the Lab fits, and its defaults. */
data class FitSettings(
    val fs: Double = 48000.0,
    val fmax: Double = 16000.0,
    val maxFilters: Int = 8,
    val maxBoost: Double = 6.0,
    /** Fit what the ear resolves (ERB smoothing). */
    val perceptual: Boolean = true,
    /** Peaks above the target count more than dips below it. */
    val asym: Boolean = true,
    /** Treat treble as uncertain. */
    val robustHF: Boolean = true,
    /** Trust bands where left and right agree. */
    val useSpread: Boolean = true,
    /** Match left and right. */
    val matchLR: Boolean = true,
)

class LrCheck(val before: Mismatch, val after: Mismatch?, val applied: Boolean)

/** A fit, with left and right matched when they needed it. */
class LabFit(
    val main: FitResult,
    val lr: LrCheck?,
    val chans: List<ChannelFix>?,
    /** L − R after matching, on the grid. */
    val balAfter: DoubleArray?,
    private val preampLR: Double,
) {
    /** The preamp to use: with sides, it covers the loudest side. */
    val preamp: Double get() = if (chans != null) preampLR else main.preamp

    fun apoText(): String = Lab.apoText(main.filters, preamp, chans)
}

object LabFitter {
    /** What gets fitted: the pair to the target, then (when matching) each side to the pair. */
    fun plan(dev: Device, target: DoubleArray, s: FitSettings, alive: () -> Boolean = { true }): List<FitOptions> {
        val main = FitOptions(
            meas = dev.y, target = target, spread = dev.spread, useSpread = s.useSpread, fs = s.fs, maxFilters = s.maxFilters,
            maxBoost = s.maxBoost, fmin = 20.0, fmax = s.fmax, perceptual = s.perceptual, asym = s.asym, robustHF = s.robustHF, alive = alive,
        )
        val ix = (if (s.matchLR) dev.lr else null) ?: return listOf(main)
        // Each side to the pair's average: a level trim and up to 3 filters, meeting in the middle.
        val sides = listOf(ix.first, ix.second).map { k ->
            FitOptions(
                meas = dev.chs[k], target = dev.y, spread = null, useSpread = false, fs = s.fs, maxFilters = 3, maxBoost = 6.0,
                fmin = 20.0, fmax = min(s.fmax, 10000.0), perceptual = true, asym = false, robustHF = true, alive = alive,
            )
        }
        return listOf(main) + sides
    }

    fun finish(dev: Device, s: FitSettings, results: List<FitResult>): LabFit {
        val main = results[0]
        val ix = (if (s.matchLR) dev.lr else null) ?: return LabFit(main, null, null, null, main.preamp)
        val l = dev.chs[ix.first]
        val r = dev.chs[ix.second]
        val before = Lab.lrMismatch(l, r)
        // Small, narrow differences: inaudible, or measurement noise.
        if (before.max < 2 && abs(before.mid) < 0.75 || results.size < 3) {
            return LabFit(main, LrCheck(before, null, false), null, null, main.preamp)
        }
        val rl = results[1]
        val rr = results[2]
        val lvL = Lab.jsRound(rl.level * 10) / 10
        val lvR = Lab.jsRound(rr.level * 10) / 10
        fun withEq(ch: DoubleArray, lv: Double, res: FitResult) = DoubleArray(Lab.N) { ch[it] + lv + res.eq[it] }
        val lm = withEq(l, lvL, rl)
        val rm = withEq(r, lvR, rr)
        val after = Lab.lrMismatch(lm, rm)
        val chans = listOf(ChannelFix("L", lvL, rl.filters), ChannelFix("R", lvR, rr.filters))
        var mx = 0.0
        for (i in 0 until Lab.N) mx = maxOf(mx, main.eq[i] + lvL + rl.eq[i], main.eq[i] + lvR + rr.eq[i])
        val preampLR = -ceil(max(0.0, mx) * 10) / 10
        return LabFit(main, LrCheck(before, after, true), chans, Lab.sub(lm, rm), preampLR)
    }

    fun fit(dev: Device, target: DoubleArray, s: FitSettings): LabFit = finish(dev, s, plan(dev, target, s).map { Lab.fitEq(it) })

    /** The rig a measurement or target belongs with: "711", "5128" or "other". */
    fun rigFamily(r: String?): String = when {
        r != null && r.startsWith("5128") -> "5128"
        r == "other" -> "other"
        else -> "711"
    }
}

/** Ids and names in the Lab's library. */
object LabIds {
    fun slug(s: String, now: Long = System.currentTimeMillis()): String {
        val base = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "item" }
        return base + "-" + now.toString(36)
    }

    /** Values kept to 0.01 dB, as the Lab keeps them. */
    fun r2(y: DoubleArray): DoubleArray = DoubleArray(y.size) { (Lab.jsRound(y[it] * 100) / 100) }

    fun hz(hz: Double): String = if (hz >= 1000) {
        val k = hz / 1000
        val s = if (hz >= 10000) "${k.roundToInt()}" else Lab.fixed(k, 1).removeSuffix(".0")
        "$s kHz"
    } else "${hz.roundToInt()} Hz"
}
