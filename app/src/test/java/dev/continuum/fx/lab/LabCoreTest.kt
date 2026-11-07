package dev.continuum.fx.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The Lab's engine against Kira Lab itself: the Python pipeline's curves (parity.json), and what
 * the Lab's own JavaScript computes on the same inputs (golden.json, written by running the
 * page's core in node). Plus the checks from the Lab's own test file (tests/core.test.mjs).
 */
@Suppress("UNCHECKED_CAST")
class LabCoreTest {
    private fun text(name: String): String =
        requireNotNull(javaClass.getResource("/lab/$name")) { "missing test resource lab/$name" }.readText()

    private val ref by lazy { LabRef.parse(text("refdata.json")) }
    private val parity by lazy { MiniJson.parse(text("parity.json")) as Map<String, Any?> }
    private val golden by lazy { MiniJson.parse(text("golden.json")) as Map<String, Any?> }

    private fun arr(v: Any?): DoubleArray = (v as List<Any?>).map { (it as Number).toDouble() }.toDoubleArray()
    private fun obj(v: Any?) = v as Map<String, Any?>
    private fun num(v: Any?) = (v as Number).toDouble()
    private fun maxDiff(a: DoubleArray, b: DoubleArray): Double {
        var m = 0.0
        for (i in 0 until Lab.N) m = max(m, abs(a[i] - b[i]))
        return m
    }

    private val live21 by lazy { Lab.add(Lab.liveModel(ref, LiveParams(rig = "711")), Lab.filtersDb(Lab.CARVE, 192000.0)) }
    private fun curve(name: String): DoubleArray = Lab.parseCurveText(text("fixtures/$name")).let { Lab.resample(it.f, it.y) }
    private val mest by lazy { curve("rew-mest-mkii.txt") }
    private fun opts(
        meas: DoubleArray = mest,
        target: DoubleArray = live21,
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
     * The same fit as the JavaScript's. Identical to 1e-11 for the first few filters; after that
     * the optimiser's ordinary floating-point sensitivity shows (the JavaScript moves by as much
     * when its own log2 is computed another way), so filters agree to within a percent or so.
     */
    private fun assertSameFit(label: String, js: Map<String, Any?>, r: FitResult) {
        val want = filtersOf(js["filters"])
        assertEquals("$label: filter count (${r.filters} vs $want)", want.size, r.filters.size)
        want.zip(r.filters).forEachIndexed { i, (a, b) ->
            assertEquals("$label filter ${i + 1} type", a.type, b.type)
            assertEquals("$label filter ${i + 1} fc", a.fc, b.fc, a.fc * 0.01)
            assertEquals("$label filter ${i + 1} gain", a.gain, b.gain, 0.15)
            assertEquals("$label filter ${i + 1} q", a.q, b.q, 0.02 + a.q * 0.05)
        }
        assertEquals("$label preamp", num(js["preamp"]), r.preamp, 0.15)
        assertEquals("$label rms after", num(obj(js["after"])["rms"]), r.after.rms, 0.02)
        assertEquals("$label rms before", num(obj(js["before"])["rms"]), r.before.rms, 0.002)
    }

    @Test
    fun theGridIsAutoEqs() {
        assertEquals(695, Lab.N)
        assertEquals(20.0, Lab.F[0], 1e-9)
        assertEquals(20 * Math.pow(1.01, 694.0), Lab.F[694], 1e-6)
        for (k in listOf("kira711", "detail", "pref", "lush", "kiraoe", "harmanie", "harmanoe", "diamond")) assertEquals(k, 695, ref.refs.getValue(k).size)
        assertEquals(695, ref.d5128iem!!.size)
    }

    @Test
    fun theLiveModelMatchesThePythonPipeline() {
        val cases = listOf(
            LiveParams(rig = "711") to "live2_711",
            LiveParams(rig = "5128") to "live2_5128",
            LiveParams(rig = "5128iem") to "live2_5128iem",
            LiveParams(rig = "711", projPeak = 0.0, livePhon = 85.0, playPhon = 75.0) to "live1_711",
        )
        for ((p, key) in cases) {
            val d = maxDiff(Lab.liveModel(ref, p), arr(parity[key]))
            assertTrue("$key: max |Kotlin - Python| $d dB", d < 0.02)
        }
        val d = maxDiff(live21, arr(parity["live21_711"]))
        assertTrue("live21: $d dB", d < 0.03)
        assertEquals(-1.0, Lab.at(live21, 500.0), 0.1)
    }

    @Test
    fun theLiveModelMatchesTheLabsJavaScript() {
        val g = obj(golden["live"])
        val cases = mapOf(
            "v2_711" to Lab.liveModel(ref, LiveParams(rig = "711")),
            "v2_5128" to Lab.liveModel(ref, LiveParams(rig = "5128")),
            "v2_5128iem" to Lab.liveModel(ref, LiveParams(rig = "5128iem")),
            "v1_711" to Lab.liveModel(ref, LiveParams(rig = "711", projPeak = 0.0, livePhon = 85.0, playPhon = 75.0)),
            "v21_711" to live21,
            "v21_5128iem" to Lab.add(Lab.liveModel(ref, LiveParams(rig = "5128iem")), Lab.carveDb(1.0)),
            "custom" to Lab.liveModel(ref, LiveParams(rig = "711", r = 8.0, playPhon = 64.0, livePhon = 96.0, projPeak = 5.0, Tmid = 2.6, BR = 1.35)),
        )
        for ((k, y) in cases) {
            val d = maxDiff(y, arr(g[k]))
            assertTrue("$k: $d dB", d < 1e-6)
        }
        val (_, peak) = Lab.keyValues(Lab.liveModel(ref, LiveParams(rig = "711")))
        assertEquals(8.1, peak.second, 0.15)
        assertEquals(2700.0, peak.first, 150.0)
        val gk = obj(golden["key"])
        assertEquals(num((gk["peak"] as List<Any?>)[1]), peak.second, 1e-9)
    }

    @Test
    fun theStudioMatchesTheLab() {
        val g = obj(golden["studio"])
        assertTrue(maxDiff(Studio.target(ref, StudioModel(), StudioTaste()), arr(g["def"])) < 1e-6)
        val custom = Studio.target(
            ref,
            StudioModel(rig = "5128iem", r = 12.0, playPhon = 76.0),
            StudioTaste(bassG = 3.5, bassF = 140.0, bodyG = -1.0, earG = -2.0, earF = 3100.0, presG = 1.5, airG = 2.0, airF = 9000.0, tilt = -0.3, sep = 1.5),
        )
        assertTrue(maxDiff(custom, arr(g["custom"])) < 1e-6)
        // The default studio target is Kira Live v2.1 itself.
        assertTrue(maxDiff(Studio.target(ref, StudioModel(), StudioTaste()), LabTargets.curve(ref, LabTargets.BY_ID.getValue("b:live21"))) < 1e-9)
    }

    @Test
    fun readsEveryFileTheLabReads() {
        val g = obj(golden["parse"])
        for ((name, minPts) in listOf("rew-mest-mkii.txt" to 400, "squig-ej01.txt" to 100, "tab-maybemest.txt" to 100, "autoeq-live-v2.csv" to 600)) {
            val c = Lab.parseCurveText(text("fixtures/$name"))
            val want = obj(g[name])
            assertTrue("$name: ${c.f.size} points", c.f.size >= minPts)
            assertEquals(name, num(want["n"]).toInt(), c.f.size)
            assertEquals(num(want["f0"]), c.f[0], 1e-12)
            assertEquals(num(want["f1"]), c.f.last(), 1e-9)
            assertEquals(num(want["ysum"]), c.y.sum(), 1e-6)
            assertTrue(c.f[0] <= 21 && c.f.last() >= 19000)
            assertTrue(maxDiff(Lab.resample(c.f, c.y), arr(want["grid"])) < 1e-9)
        }
        val odd = Lab.parseCurveText("20,5;80,25\n1000,0\t90,1\n* x\n# y\n50 3\n50 4\n  60   -1.5e0  \n")
        val wantOdd = obj(golden["parseOdd"])
        assertEquals(arr(wantOdd["f"]).toList(), odd.f.toList())
        assertEquals(arr(wantOdd["y"]).toList(), odd.y.toList())
        assertEquals(listOf(20.5, 1000.0), Lab.parseCurveText("20,5;80,25\n1000,0\t90,1\n").f.toList())
        assertEquals(listOf(20.0, 30.0), Lab.parseCurveText("﻿20 1\n30 2\n").f.toList())
    }

    @Test
    fun leftAndRightFilesPairUp() {
        val g = obj(golden["split"])
        for ((file, v) in g) {
            val want = obj(v)
            assertEquals(file, want["name"] to want["channel"], Lab.splitChannelName(file))
        }
    }

    @Test
    fun smoothingMatchesTheLab() {
        val g = obj(golden["smooth"])
        assertTrue(maxDiff(Lab.smooth(mest, 1.0 / 24), arr(g["s24"])) < 1e-9)
        assertTrue(maxDiff(Lab.smooth(mest, 1.0 / 3), arr(g["s3"])) < 1e-9)
    }

    @Test
    fun mestToLive21IsTheLabsFit() {
        val t0 = System.currentTimeMillis()
        val r = Lab.fitEq(opts())
        val ms = System.currentTimeMillis() - t0
        val boost = r.eq.max()
        assertTrue("${r.filters.size} filters", r.filters.size <= 8)
        assertTrue("max boost $boost", boost <= 6.005)
        assertEquals(-ceil(max(0.0, boost) * 10) / 10, r.preamp, 1e-12)
        assertTrue("rms ${r.before.rms} -> ${r.after.rms}", r.after.rms < 0.6 && r.after.rms < r.before.rms)
        assertTrue("$ms ms", ms < 10000)
        val g = obj(obj(golden["fit"])["main"])
        assertSameFit("main", g, r)
        // The files, written from the JavaScript's own result, come out character for character.
        assertEquals(golden["apo"], Lab.apoText(filtersOf(g["filters"]), num(g["preamp"])))
        assertEquals(golden["graphic"], Lab.graphicEqText(arr(g["eq"]), num(g["preamp"])))
    }

    @Test
    fun theLevelIsFreeSoADeliberateDipIsKept() {
        val r = Lab.fitEq(opts(meas = Lab.liveModel(ref, LiveParams(rig = "711")), maxFilters = 6))
        val want = Lab.filtersDb(Lab.CARVE, 192000.0)
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
        assertEquals(num(obj(obj(golden["fit"])["level"])["level"]), r.level, 0.1)
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
        val made = listOf(
            opts(perceptual = false), opts(asym = false), opts(robustHF = false), opts(maxFilters = 3), opts(fs = 44100.0, fmax = 20000.0),
        )
        for ((k, o) in made.withIndex()) {
            val r = Lab.fitEq(o)
            assertTrue(r.filters.size <= o.maxFilters && r.eq.max() <= 6.005 && r.filters.all { it.fc.isFinite() && it.gain.isFinite() && it.q > 0 })
            assertSameFit("variant $k", obj(obj(variants[k])["r"]), r)
        }
        val ej = Lab.fitEq(opts(meas = curve("squig-ej01.txt"), target = Lab.liveModel(ref, LiveParams(rig = "711")), maxFilters = 10, fmax = 12000.0))
        assertSameFit("ej01", obj(obj(golden["fit"])["ej"]), ej)
    }

    @Test
    fun leftAndRightAreMatchedAsTheLabMatchesThem() {
        val g = obj(golden["pair"])
        val right = arr(g["R"])
        val dev = Device("MEST", "711", listOf(mest, right), listOf("L", "R"))
        assertEquals(0 to 1, dev.lr)
        val fit = LabFitter.fit(dev, live21, FitSettings())
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
                assertEquals(a.fc, b.fc, a.fc * 0.01)
                assertEquals(a.gain, b.gain, 0.15)
            }
        }
        assertEquals(num(g["preampLR"]), fit.preamp, 0.15)
        assertEquals((g["apo"] as String).lines().size, fit.apoText().lines().size)
        // Without matching it's the plain fit.
        val plain = LabFitter.fit(dev, live21, FitSettings(matchLR = false))
        assertEquals(null, plain.chans)
        assertEquals(null, plain.lr)
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
        val lr = Lab.apoText(r.filters, -6.8, chans)
        val jsFilters = filtersOf(obj(obj(golden["fit"])["main"])["filters"])
        assertEquals(golden["apoLR"], Lab.apoText(jsFilters, -6.8, chans))
        val lines = lr.trim().split("\n")
        val n = r.filters.size
        assertEquals("Channel: L", lines[n + 1])
        assertEquals("Preamp: 1.9 dB", lines[n + 2])
        assertEquals("Channel: all", lines.last())
        assertEquals(golden["txt"], Lab.targetTxt(live21))
        assertEquals(golden["csv"], Lab.targetCsv(live21))
        assertEquals(696, Lab.targetCsv(live21).trim().split("\n").size)
        assertEquals("-0.0", Lab.fixed(-0.04, 1))
        assertEquals("0.0", Lab.fixed(-0.0, 1))
        assertEquals("1.00", Lab.fixed(1.005, 2))
    }
}
