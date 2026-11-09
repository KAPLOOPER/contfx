package dev.continuum.fx.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The Lab's engine against the Lab's original web version, on made-up curves only: golden.json is what the
 * Lab's own JavaScript computes from them (its page's core, run in node), so the port is checked
 * without any real measurement or target in the repo. Plus the checks from the Lab's own tests.
 */
@Suppress("UNCHECKED_CAST")
class LabCoreTest {
    private val golden by lazy {
        val text = requireNotNull(javaClass.getResource("/lab/golden.json")) { "missing test resource lab/golden.json" }.readText()
        MiniJson.parse(text) as Map<String, Any?>
    }

    private fun arr(v: Any?): DoubleArray = (v as List<Any?>).map { (it as Number).toDouble() }.toDoubleArray()
    private fun obj(v: Any?) = v as Map<String, Any?>
    private fun num(v: Any?) = (v as Number).toDouble()
    private fun maxDiff(a: DoubleArray, b: DoubleArray): Double {
        var m = 0.0
        for (i in 0 until Lab.N) m = max(m, abs(a[i] - b[i]))
        return m
    }

    private val texts by lazy { obj(golden["texts"]) }
    /** The made-up target, and it with the separation carve. */
    private val t0 by lazy { arr(golden["T0"]) }
    private val t1 by lazy { arr(golden["T1"]) }
    /** The made-up IEM, read back from its REW-style text as the Lab reads it. */
    private val meas by lazy { Lab.parseCurveText(texts["rew"] as String).let { Lab.resample(it.f, it.y) } }

    private fun opts(
        meas: DoubleArray = this.meas,
        target: DoubleArray = t1,
        maxFilters: Int = 8,
        maxBoost: Double = 6.0,
        perceptual: Boolean = true,
        asym: Boolean = true,
        robustHF: Boolean = true,
        fs: Double = 48000.0,
        fmax: Double = 16000.0,
    ) = FitOptions(meas, target, null, false, fs, maxFilters, maxBoost, 20.0, fmax, perceptual, asym, robustHF)

    private fun filtersOf(v: Any?): List<LabFilter> = (v as List<Any?>).map {
        val f = obj(it)
        LabFilter(f["type"] as String, num(f["fc"]), num(f["gain"]), num(f["q"]))
    }

    /**
     * The same fit as the JavaScript's. The optimiser is sensitive to the last bit of floating
     * point, which differs between processors (an ARM machine lands a narrow filter's Q a few
     * percent away from an x86 one), so what's compared is what's heard: the same filters at
     * nearly the same places, and the curve they make within a fraction of a decibel.
     */
    private fun assertSameFit(label: String, js: Map<String, Any?>, r: FitResult) {
        val want = filtersOf(js["filters"])
        assertEquals("$label: filter count (${r.filters} vs $want)", want.size, r.filters.size)
        want.zip(r.filters).forEachIndexed { i, (a, b) ->
            assertEquals("$label filter ${i + 1} type", a.type, b.type)
            assertEquals("$label filter ${i + 1} fc", a.fc, b.fc, a.fc * 0.03)
        }
        val eq = arr(js["eq"])
        var d = 0.0
        for (i in 0 until Lab.N) if (Lab.F[i] <= 16000) d = max(d, abs(eq[i] - r.eq[i]))
        assertTrue("$label: the EQ differs from the JavaScript's by up to $d dB", d < 0.5)
        assertEquals("$label preamp", num(js["preamp"]), r.preamp, 0.2)
        assertEquals("$label rms after", num(obj(js["after"])["rms"]), r.after.rms, 0.02)
        // "Before" is measured at the level the fit settles on, so it moves with that level.
        assertEquals("$label rms before", num(obj(js["before"])["rms"]), r.before.rms, 0.01)
    }

    @Test
    fun theGridIsAutoEqs() {
        assertEquals(695, Lab.N)
        assertEquals(20.0, Lab.F[0], 1e-9)
        assertEquals(20 * Math.pow(1.01, 694.0), Lab.F[694], 1e-6)
    }

    @Test
    fun theLiveModelsShapeMatchesTheLab() {
        val g = obj(golden["shape"])
        assertTrue(maxDiff(Lab.liveShape(), arr(g["def"])) < 1e-6)
        assertTrue(maxDiff(Lab.liveShape(LiveParams(r = 8.0, playPhon = 64.0, livePhon = 96.0, projPeak = 5.0, Tmid = 2.6, BR = 1.35)), arr(g["custom"])) < 1e-6)
        assertTrue(maxDiff(Lab.liveShape(LiveParams(projPeak = 0.0, livePhon = 85.0, playPhon = 75.0)), arr(g["v1"])) < 1e-6)
        assertEquals(0.0, Lab.at(Lab.liveShape(), 500.0), 1e-9)
    }

    @Test
    fun theStudioReshapesYourTarget() {
        // At rest it's your target as it came in, re 500 Hz.
        assertTrue(maxDiff(Studio.target(t1, StudioModel(), StudioTaste()), Lab.norm(t1, 500.0)) < 1e-9)
        // Separation 1 adds the carve, and -1 takes it away again (to within the 24 Hz
        // shelf, whose cut isn't quite its boost turned over).
        assertTrue(maxDiff(Studio.target(t0, StudioModel(), StudioTaste(sep = 1.0)), Lab.add(Lab.norm(t0, 500.0), Lab.carveDb(1.0))) < 1e-9)
        val back = Studio.target(t1, StudioModel(), StudioTaste(sep = -1.0))
        assertTrue(maxDiff(back, Lab.add(Lab.norm(t1, 500.0), Lab.carveDb(-1.0))) < 1e-9)
        assertTrue(maxDiff(Lab.norm(back, 500.0), Lab.norm(t0, 500.0)) < 0.15)
        // From the Live model's own shape, every slider does as the Lab's studio does.
        val custom = Studio.target(
            Lab.liveShape(),
            StudioModel(r = 12.0, playPhon = 76.0),
            StudioTaste(bassG = 3.5, bassF = 140.0, bodyG = -1.0, earG = -2.0, earF = 3100.0, presG = 1.5, airG = 2.0, airF = 9000.0, tilt = -0.3, sep = 1.5),
        )
        assertTrue(maxDiff(custom, arr(obj(golden["studio"])["custom"])) < 1e-6)
        val (_, peak) = Lab.keyValues(t1)
        assertEquals(num((obj(golden["key"])["peak"] as List<Any?>)[1]), peak.second, 1e-9)
    }

    @Test
    fun readsEveryKindOfFileTheLabReads() {
        val g = obj(golden["parse"])
        for (k in listOf("rew", "squig", "csv", "comma")) {
            val c = Lab.parseCurveText(texts[k] as String)
            val want = obj(g[k])
            assertEquals(k, num(want["n"]).toInt(), c.f.size)
            assertEquals(k, num(want["f0"]), c.f[0], 1e-12)
            assertEquals(k, num(want["f1"]), c.f.last(), 1e-9)
            assertEquals(k, num(want["ysum"]), c.y.sum(), 1e-6)
            assertTrue(k, maxDiff(Lab.resample(c.f, c.y), arr(want["grid"])) < 1e-9)
        }
        assertTrue(maxDiff(meas, arr(golden["meas"])) < 1e-9)
        val odd = Lab.parseCurveText("20,5;80,25\n1000,0\t90,1\n* x\n# y\n50 3\n50 4\n  60   -1.5e0  \n")
        val wantOdd = obj(golden["parseOdd"])
        assertEquals(arr(wantOdd["f"]).toList(), odd.f.toList())
        assertEquals(arr(wantOdd["y"]).toList(), odd.y.toList())
        assertEquals(listOf(20.5, 1000.0), Lab.parseCurveText("20,5;80,25\n1000,0\t90,1\n").f.toList())
        assertEquals(listOf(20.0, 30.0), Lab.parseCurveText("﻿20 1\n30 2\n").f.toList())
    }

    @Test
    fun leftAndRightFilesPairUp() {
        for ((file, v) in obj(golden["split"])) {
            val want = obj(v)
            assertEquals(file, want["name"] to want["channel"], Lab.splitChannelName(file))
        }
    }

    @Test
    fun smoothingMatchesTheLab() {
        val g = obj(golden["smooth"])
        assertTrue(maxDiff(Lab.smooth(meas, 1.0 / 24), arr(g["s24"])) < 1e-9)
        assertTrue(maxDiff(Lab.smooth(meas, 1.0 / 3), arr(g["s3"])) < 1e-9)
    }

    @Test
    fun aFitIsTheLabsFit() {
        val t0ms = System.currentTimeMillis()
        val r = Lab.fitEq(opts())
        val ms = System.currentTimeMillis() - t0ms
        val boost = r.eq.max()
        assertTrue("${r.filters.size} filters", r.filters.size <= 8)
        assertTrue("max boost $boost", boost <= 6.005)
        assertEquals(-ceil(max(0.0, boost) * 10) / 10, r.preamp, 1e-12)
        assertTrue("rms ${r.before.rms} -> ${r.after.rms}", r.after.rms < r.before.rms / 3)
        assertTrue("$ms ms", ms < 10000)
        val g = obj(obj(golden["fit"])["main"])
        assertSameFit("main", g, r)
        // The files, written from the JavaScript's own result, come out character for character.
        assertEquals(golden["apo"], Lab.apoText(filtersOf(g["filters"]), num(g["preamp"])))
        assertEquals(golden["graphic"], Lab.graphicEqText(arr(g["eq"]), num(g["preamp"])))
    }

    @Test
    fun theLevelIsFreeSoADeliberateDipIsKept() {
        val r = Lab.fitEq(opts(meas = t0, maxFilters = 6))
        val want = Lab.carveDb(1.0)
        var s = 0.0
        var n = 0
        for (i in 0 until Lab.N) if (Lab.F[i] <= 2000) { s += r.eq[i] - want[i]; n++ }
        val off = s / n
        var ss = 0.0
        n = 0
        for (i in 0 until Lab.N) if (Lab.F[i] >= 30 && Lab.F[i] <= 2000) { val e = r.eq[i] - want[i] - off; ss += e * e; n++ }
        val rms = Math.sqrt(ss / n)
        assertTrue("shape error $rms dB", rms < 0.3)
        assertSameFit("level", obj(obj(golden["fit"])["level"]), r)
    }

    @Test
    fun cutsOnlyStillHelps() {
        val r = Lab.fitEq(opts(maxFilters = 12, maxBoost = 0.0))
        assertTrue(r.eq.max() <= 0.005)
        assertTrue(r.after.rms < r.before.rms)
        assertSameFit("cuts", obj(obj(golden["fit"])["cuts"]), r)
    }

    @Test
    fun everyOptionRunsAsTheLabsDoes() {
        val variants = obj(golden["fit"])["variants"] as List<Any?>
        val made = listOf(opts(perceptual = false), opts(asym = false), opts(robustHF = false), opts(maxFilters = 3), opts(fs = 44100.0, fmax = 20000.0))
        for ((k, o) in made.withIndex()) {
            val r = Lab.fitEq(o)
            assertTrue(r.filters.size <= o.maxFilters && r.eq.max() <= 6.005 && r.filters.all { it.fc.isFinite() && it.gain.isFinite() && it.q > 0 })
            assertSameFit("variant $k", obj(obj(variants[k])["r"]), r)
        }
    }

    @Test
    fun leftAndRightAreMatchedAsTheLabMatchesThem() {
        val g = obj(golden["pair"])
        val dev = Device("Pair", "711", listOf(meas, arr(g["R"])), listOf("L", "R"))
        assertEquals(0 to 1, dev.lr)
        val fit = LabFitter.fit(dev, t1, FitSettings())
        assertSameFit("pair", obj(g["main"]), fit.main)
        val lr = fit.lr!!
        assertTrue(lr.applied)
        assertEquals(num(obj(g["before"])["max"]), lr.before.max, 1e-9)
        assertEquals(num(obj(g["after"])["max"]), lr.after!!.max, 0.05)
        val chans = fit.chans!!
        val want = g["chans"] as List<Any?>
        for (k in 0..1) {
            val w = obj(want[k])
            assertEquals(w["label"], chans[k].label)
            assertEquals(num(w["level"]), chans[k].level, 0.1)
            val wf = filtersOf(w["filters"])
            assertEquals("side $k filters", wf.size, chans[k].filters.size)
            wf.zip(chans[k].filters).forEach { (a, b) ->
                assertEquals(a.type, b.type)
                assertEquals(a.fc, b.fc, a.fc * 0.03)
                assertEquals(a.gain, b.gain, 0.3)
            }
        }
        assertEquals(num(g["preampLR"]), fit.preamp, 0.15)
        assertEquals((g["apo"] as String).lines().size, fit.apoText().lines().size)
        val plain = LabFitter.fit(dev, t1, FitSettings(matchLR = false))
        assertNull(plain.chans)
        assertNull(plain.lr)
    }

    @Test
    fun theFilesItWrites() {
        val r = Lab.fitEq(opts())
        val t = Lab.apoText(r.filters, r.preamp).trim().split("\n")
        assertTrue(Regex("^Preamp: -?\\d+\\.\\d dB$").matches(t[0]))
        assertEquals(r.filters.size + 1, t.size)
        t.drop(1).forEachIndexed { i, l ->
            assertTrue(l, Regex("^Filter ${i + 1}: ON (PK|LSC|HSC) Fc \\d+ Hz Gain -?\\d+\\.\\d dB Q \\d+\\.\\d{2}$").matches(l))
        }
        val chans = listOf(
            ChannelFix("L", 1.9, listOf(LabFilter("PK", 771.0, 1.5, 1.01))),
            ChannelFix("R", -1.2, listOf(LabFilter("LSC", 213.0, 1.7, 0.5))),
        )
        val jsFilters = filtersOf(obj(obj(golden["fit"])["main"])["filters"])
        assertEquals(golden["apoLR"], Lab.apoText(jsFilters, -6.8, chans))
        val lines = Lab.apoText(r.filters, -6.8, chans).trim().split("\n")
        val n = r.filters.size
        assertEquals("Channel: L", lines[n + 1])
        assertEquals("Preamp: 1.9 dB", lines[n + 2])
        assertEquals("Channel: all", lines.last())
        assertEquals(golden["txt"], Lab.targetTxt(t1))
        assertEquals(golden["csv"], Lab.targetCsv(t1))
        assertEquals(696, Lab.targetCsv(t1).trim().split("\n").size)
        assertEquals("-0.0", Lab.fixed(-0.04, 1))
        assertEquals("0.0", Lab.fixed(-0.0, 1))
        assertEquals("1.00", Lab.fixed(1.005, 2))
    }
}

/** Just enough JSON to read golden.json, with nothing from Android. */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = P(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "Trailing text at ${p.i}" }
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                i++ // :
                m[k] = value()
                ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return a }
            while (true) {
                a += value()
                ws()
                if (s[i++] == ']') return a
            }
        }
        fun str(): String {
            i++
            val b = StringBuilder()
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') { b.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            }
            i++
            return b.toString()
        }
        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
