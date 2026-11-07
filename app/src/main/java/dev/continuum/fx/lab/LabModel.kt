package dev.continuum.fx.lab

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * What the Lab's page did around its engine, kept here beside it so it's tested with it: the
 * built-in targets, Target Studio's recipe, and a fit that matches left and right before it fits
 * the pair to the target.
 */

/** A built-in target: made by the Live model, or one of the reference curves. */
class Builtin(
    val id: String,
    val name: String,
    val sub: String? = null,
    val short: String? = null,
    /** "kira" or "ref". */
    val group: String,
    val rig: String,
    val model: LiveParams? = null,
    val carve: Double = 0.0,
    val ref: String? = null,
    val arr: String? = null,
) {
    val label: String get() = name + (sub?.let { " ($it)" } ?: "")
}

object LabTargets {
    val BUILTIN = listOf(
        Builtin("b:live21", "Kira Live v2.1", "711 · GRAS", "v2.1 711", "kira", "711", model = LiveParams(rig = "711"), carve = 1.0),
        Builtin("b:live21-5128iem", "Kira Live v2.1", "B&K 5128 · IEM", "v2.1 5128 IEM", "kira", "5128", model = LiveParams(rig = "5128iem"), carve = 1.0),
        Builtin("b:live21-5128", "Kira Live v2.1", "B&K 5128 · over-ear", "v2.1 5128 OE", "kira", "5128", model = LiveParams(rig = "5128"), carve = 1.0),
        Builtin("b:live2", "Kira Live v2", "711 · GRAS", "v2 711", "kira", "711", model = LiveParams(rig = "711")),
        Builtin("b:live2-5128iem", "Kira Live v2", "B&K 5128 · IEM", "v2 5128 IEM", "kira", "5128", model = LiveParams(rig = "5128iem")),
        Builtin("b:live2-5128", "Kira Live v2", "B&K 5128 · over-ear", "v2 5128 OE", "kira", "5128", model = LiveParams(rig = "5128")),
        Builtin("b:live1", "Kira Live v1", "hall only", "v1 711", "kira", "711", model = LiveParams(rig = "711", projPeak = 0.0, livePhon = 85.0, playPhon = 75.0)),
        Builtin("b:lush", "Kira 711 Lush", group = "kira", rig = "711", ref = "lush"),
        Builtin("b:pref", "Kira 711 Preference", group = "kira", rig = "711", ref = "pref"),
        Builtin("b:detail", "Kira 711 Detail-Stage", group = "kira", rig = "711", ref = "detail"),
        Builtin("b:kira711", "Kira 711", group = "kira", rig = "711", ref = "kira711"),
        Builtin("b:kiraoe", "Kira Over-Ear", group = "kira", rig = "GRAS", ref = "kiraoe"),
        Builtin("b:harmanie", "Harman in-ear 2019", group = "ref", rig = "711", ref = "harmanie"),
        Builtin("b:harmanoe", "Harman over-ear 2018", group = "ref", rig = "GRAS", ref = "harmanoe"),
        Builtin("b:diamond", "PEQdB Diamond β", "third-party copy", group = "ref", rig = "711", ref = "diamond"),
        Builtin("b:df711", "Diffuse field", "711 · GRAS", group = "ref", rig = "711", arr = "df711"),
        Builtin("b:df5128", "Diffuse field", "B&K 5128", group = "ref", rig = "5128", arr = "df5128"),
    )
    val BY_ID = BUILTIN.associateBy { it.id }

    fun curve(ref: LabRef, b: Builtin): DoubleArray {
        var y = when {
            b.model != null -> Lab.liveModel(ref, b.model)
            b.ref != null -> ref.refs.getValue(b.ref).copyOf()
            b.arr == "df5128" -> ref.df5128.copyOf()
            else -> ref.df711.copyOf()
        }
        // Kept on Live v2's level: 500 Hz reads -1.0 dB by design.
        if (b.carve != 0.0) y = Lab.add(y, Lab.carveDb(b.carve))
        return y
    }

    /** Every built-in's curve, by id. */
    fun all(ref: LabRef): Map<String, DoubleArray> = BUILTIN.associate { it.id to curve(ref, it) }
}

/** Target Studio's Live model: the hall, your seat and the levels. */
data class StudioModel(
    /** "711", "5128iem" or "5128". */
    val rig: String = "711",
    val r: Double = 20.0,
    val playPhon: Double = 70.0,
    val livePhon: Double = 90.0,
    val projPeak: Double = 3.5,
    val Tmid: Double = 2.0,
    val BR: Double = 1.2,
)

/** Target Studio's taste: shelves, bumps, a tilt, and how much of the v2.1 carve. */
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
    val sep: Double = 1.0,
)

object Studio {
    fun target(ref: LabRef, m: StudioModel, t: StudioTaste): DoubleArray {
        val y = Lab.liveModel(ref, LiveParams(rig = m.rig, r = m.r, playPhon = m.playPhon, livePhon = m.livePhon, projPeak = m.projPeak, Tmid = m.Tmid, BR = m.BR))
        val fs = 192000.0
        val parts = arrayOf(
            Lab.filterDb("LSC", t.bassF, t.bassG, 0.7, fs),
            Lab.filterDb("PK", 320.0, t.bodyG, 0.9, fs),
            Lab.filterDb("PK", t.earF, t.earG, 1.2, fs),
            Lab.filterDb("PK", 5000.0, t.presG, 1.1, fs),
            Lab.filterDb("HSC", t.airF, t.airG, 0.7, fs),
        )
        val tilt = DoubleArray(Lab.N) { t.tilt * Lab.log2(Lab.F[it] / 1000) }
        // Normalised before the carve, so the target stays on Live v2's level like Kira Live v2.1.
        return Lab.add(Lab.norm(Lab.add(y, *parts, tilt), 500.0), Lab.carveDb(t.sep))
    }

    /** The built-in Kira Live v2.1 this studio starts from, for its rig. */
    fun baseId(rig: String) = when (rig) {
        "5128" -> "b:live21-5128"
        "5128iem" -> "b:live21-5128iem"
        else -> "b:live21"
    }

    /** The rig a studio target is saved for. */
    fun rigTag(rig: String) = if (rig == "711") "711" else "5128"
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

/** How the Lab fits. The defaults are Kira Lab's. */
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
