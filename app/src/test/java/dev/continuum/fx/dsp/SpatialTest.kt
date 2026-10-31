package dev.continuum.fx.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SpatialTest {

    @Test
    fun fftMatchesTheDefinition() {
        val n = 64
        val r = Random(3)
        val x = FloatArray(n) { r.nextFloat() * 2 - 1 }
        val fft = RealFft(n)
        val re = FloatArray(fft.bins)
        val im = FloatArray(fft.bins)
        fft.forward(x, re, im)
        for (k in 0 until fft.bins) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                sr += x[t] * cos(2 * PI * k * t / n)
                si -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals("re[$k]", sr, re[k].toDouble(), 1e-4)
            assertEquals("im[$k]", si, im[k].toDouble(), 1e-4)
        }
        val back = FloatArray(n)
        fft.inverse(re, im, back)
        for (t in 0 until n) assertEquals("x[$t]", x[t].toDouble(), back[t].toDouble(), 1e-5)
    }

    @Test
    fun partitionedConvolutionIsPlainConvolution() {
        val block = 32
        val r = Random(7)
        val h = Array(2) { Array(2) { FloatArray(150) { r.nextFloat() * 2 - 1 } } }
        val conv = Convolver(block, maxInputs = 2, maxPartitions = 8)
        conv.set(Convolver.prepare(block, h))
        val total = block * 12
        val x = Array(2) { FloatArray(total) { r.nextFloat() * 2 - 1 } }
        val got = Array(2) { FloatArray(total) }
        val ins = Array(2) { FloatArray(block) }
        val outs = Array(2) { FloatArray(block) }
        for (b in 0 until total / block) {
            for (i in 0..1) System.arraycopy(x[i], b * block, ins[i], 0, block)
            conv.process(ins, 2, outs)
            for (o in 0..1) System.arraycopy(outs[o], 0, got[o], b * block, block)
        }
        // The first block fades in from silence; after that it must be exact.
        for (o in 0..1) for (t in block until total) {
            var want = 0.0
            for (i in 0..1) for (j in 0 until minOf(150, t + 1)) want += x[i][t - j] * h[i][o][j]
            assertEquals("ear $o at $t", want, got[o][t].toDouble(), 1e-3)
        }
    }

    @Test
    fun newFiltersFadeInWithoutAJump() {
        val block = 64
        val conv = Convolver(block, maxInputs = 1, maxPartitions = 4)
        val one = arrayOf(arrayOf(FloatArray(1) { 1f }, FloatArray(1) { 1f }))
        val half = arrayOf(arrayOf(FloatArray(1) { 0.5f }, FloatArray(1) { 0.5f }))
        conv.set(Convolver.prepare(block, one))
        val ins = arrayOf(FloatArray(block) { 1f })
        val outs = Array(2) { FloatArray(block) }
        repeat(3) { conv.process(ins, 1, outs) }
        assertEquals(1.0, outs[0][block - 1].toDouble(), 1e-4)
        conv.set(Convolver.prepare(block, half))
        conv.process(ins, 1, outs)
        var biggestStep = 0f
        for (s in 1 until block) biggestStep = maxOf(biggestStep, abs(outs[0][s] - outs[0][s - 1]))
        assertTrue("steps $biggestStep", biggestStep < 0.02f)
        assertEquals(0.5, outs[0][block - 1].toDouble(), 1e-4)
    }

    private fun head(name: String = "ku100") = Head.parse(java.io.File("src/main/assets/heads/$name.hrir").readBytes())

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun db(a: Double, b: Double) = 20 * log10(a / b)

    @Test
    fun headsHearTheLeftOnTheLeft() {
        for (name in listOf("ku100", "kemar")) {
            val h = head(name)
            val left = h.at(90f, 0f)
            val right = h.at(-90f, 0f)
            assertTrue("$name: louder in the near ear", rms(left.left) > 2 * rms(left.right))
            assertTrue("$name: mirror", rms(right.right) > 2 * rms(right.left))
            val itd = (left.delayRight - left.delayLeft) / h.sampleRate * 1e6
            assertTrue("$name: ears $itd us apart", itd in 500.0..800.0)
            val ahead = h.at(0f, 0f)
            assertTrue("$name: ahead, together", abs(ahead.delayRight - ahead.delayLeft) < 2f)
            // Blending between grid points keeps the level.
            val between = h.at(32.5f, 7.5f)
            val near = h.at(30f, 0f)
            assertEquals("$name blend", 0.0, db(rms(between.left), rms(near.left)), 2.0)
        }
    }

    /** Runs [setup] on [frames] of stereo from [make] and gives back the output (after the fade-in). */
    private fun run(setup: SpatialSetup, seconds: Double, make: (Int) -> Pair<Float, Float>): Pair<FloatArray, FloatArray> {
        val sp = Spatial(48000.0)
        sp.set(Spatial.plan(head(setup.head.asset.substringAfter("heads/").substringBefore(".hrir")), setup))
        val total = (48000 * seconds).toInt()
        val outL = FloatArray(total)
        val outR = FloatArray(total)
        val buf = FloatArray(960)
        var t = 0
        while (t + 480 <= total) {
            for (f in 0 until 480) {
                val (a, b) = make(t + f)
                buf[2 * f] = a
                buf[2 * f + 1] = b
            }
            sp.process(buf, 480)
            for (f in 0 until 480) {
                outL[t + f] = buf[2 * f]
                outR[t + f] = buf[2 * f + 1]
            }
            t += 480
        }
        return outL to outR
    }

    @Test
    fun switchingOnKeepsTheLoudness() {
        val rnd = Random(11)
        for (setup in listOf(SpatialSetup.STUDIO, SpatialSetup.CINEMA, SpatialSetup.STUDIO.copy(room = RoomKind.None, tail = 0f))) {
            // Uncorrelated (wide) and identical (centred) channels.
            val (wl, wr) = run(setup, 2.0) { rnd.nextFloat() * 0.5f - 0.25f to rnd.nextFloat() * 0.5f - 0.25f }
            val inRms = 0.5 / sqrt(12.0)
            val wide = (db(rms(wl, 48000), inRms) + db(rms(wr, 48000), inRms)) / 2
            val (cl, cr) = run(setup, 2.0) { val v = rnd.nextFloat() * 0.5f - 0.25f; v to v }
            val centre = (db(rms(cl, 48000), inRms) + db(rms(cr, 48000), inRms)) / 2
            println("  ${setup.name} ${setup.room}: wide ${"%.1f".format(wide)} dB, centred ${"%.1f".format(centre)} dB")
            assertEquals("${setup.name} wide", 0.0, wide, 3.0)
            assertEquals("${setup.name} centred", 0.0, centre, 4.0)
            for (x in wl) assertTrue(!x.isNaN())
        }
    }

    @Test
    fun theLeftSpeakerIsHeardOnTheLeft() {
        val rnd = Random(5)
        val (l, r) = run(SpatialSetup.STUDIO.copy(bassHz = 0), 1.5) { rnd.nextFloat() - 0.5f to 0f }
        val ild = db(rms(l, 24000), rms(r, 24000))
        println("  left speaker: left ear ${"%.1f".format(ild)} dB louder")
        assertTrue("ild $ild", ild > 2.0)
    }

    @Test
    fun theUpmixSendsEachPartWhereItBelongs() {
        val up = Upmix(256, 48000.0)
        val layout = SpatialSetup.defaultSpeakers(SpatialLayout.Surround5)
        up.configure(layout, 1f, 0f)
        val rnd = Random(9)
        fun energies(make: () -> Pair<Float, Float>): DoubleArray {
            up.reset()
            val e = DoubleArray(layout.size + 2)
            val a = FloatArray(256)
            val b = FloatArray(256)
            val out = Array(layout.size) { FloatArray(256) }
            val ears = Array(2) { FloatArray(256) }
            repeat(200) { blk ->
                for (i in 0 until 256) {
                    val (x, y) = make()
                    a[i] = x
                    b[i] = y
                }
                up.process(a, b, out, ears)
                if (blk > 20) {
                    for (s in layout.indices) for (v in out[s]) e[s] += v.toDouble() * v
                    for (s in 0..1) for (v in ears[s]) e[layout.size + s] += v.toDouble() * v
                }
            }
            val sum = e.sum()
            return DoubleArray(e.size) { e[it] / sum }
        }
        val names = layout.map { it.role.short }
        val mono = energies { val v = rnd.nextFloat() - 0.5f; v to v }
        println("  centred: " + names.zip(mono.toList()).joinToString { "${it.first} ${"%.2f".format(it.second)}" })
        assertTrue("centre gets a centred voice", mono[2] > 0.8)
        val left = energies { rnd.nextFloat() - 0.5f to 0f }
        println("  hard left: " + names.zip(left.toList()).joinToString { "${it.first} ${"%.2f".format(it.second)}" })
        assertTrue("front left gets a hard-left sound", left[0] > 0.8)
        val wide = energies { rnd.nextFloat() - 0.5f to rnd.nextFloat() - 0.5f }
        println("  space: " + names.zip(wide.toList()).joinToString { "${it.first} ${"%.2f".format(it.second)}" })
        assertTrue("surrounds get the space", wide[3] + wide[4] > 0.4)
        // Partly shared sound (a wide mix) mustn't collapse into the centre speaker.
        val partly = energies { val c = rnd.nextFloat() - 0.5f; c + (rnd.nextFloat() - 0.5f) * 0.8f to c + (rnd.nextFloat() - 0.5f) * 0.8f }
        println("  partly shared: " + names.zip(partly.toList()).joinToString { "${it.first} ${"%.2f".format(it.second)}" })
        assertTrue("centre takes under half", partly[2] < 0.5)
        // With all the space straight to the ears, none of it reaches the speakers.
        up.configure(layout, 1f, 1f)
        val open = energies { rnd.nextFloat() - 0.5f to rnd.nextFloat() - 0.5f }
        println("  space to the ears: " + open.joinToString { "%.2f".format(it) })
        assertTrue("ears get the space", open[5] + open[6] > 0.8)
    }

    /**
     * How alike the ears are, allowing for one hearing it up to 1 ms later (the interaural
     * cross-correlation): 1 is the same, 0 unrelated. Lower sounds wider.
     */
    private fun correlation(a: FloatArray, b: FloatArray, from: Int): Double {
        var aa = 0.0
        var bb = 0.0
        for (i in from until a.size - 48) {
            aa += a[i].toDouble() * a[i]
            bb += b[i].toDouble() * b[i]
        }
        var best = 0.0
        for (lag in -48..48) {
            var ab = 0.0
            for (i in from until a.size - 48) ab += a[i].toDouble() * b[i + lag]
            best = maxOf(best, abs(ab) / sqrt(aa * bb))
        }
        return best
    }

    @Test
    fun wideMixesStayWide() {
        val rnd = Random(4)
        val noise = { rnd.nextFloat() - 0.5f to rnd.nextFloat() - 0.5f }
        val closed = run(SpatialSetup.STUDIO.copy(space = 0f), 1.5) { noise() }
        val open = run(SpatialSetup.STUDIO, 1.5) { noise() }
        // Where the speakers' crosstalk closes things in most: below about 1.5 kHz.
        fun band(x: FloatArray): FloatArray {
            val hp = Bq(Design.highPass(300.0, 48000.0))
            val lp = Bq(Design.lowPass(1500.0, 48000.0))
            return FloatArray(x.size) { lp.run(hp.run(x[it].toDouble())).toFloat() }
        }
        val c0 = correlation(band(closed.first), band(closed.second), 24000)
        val c1 = correlation(band(open.first), band(open.second), 24000)
        println("  ears alike: ${"%.2f".format(c0)} through the speakers alone, ${"%.2f".format(c1)} with half the space straight through")
        assertTrue("wider with the space kept", c1 < c0 - 0.02)
    }

    @Test
    fun aDifferentLayoutDipsAndComesBack() {
        val sp = Spatial(48000.0)
        val h = head()
        sp.set(Spatial.plan(h, SpatialSetup.STUDIO))
        val rnd = Random(2)
        val buf = FloatArray(960)
        var biggest = 0f
        var last = 0f
        repeat(400) { blk ->
            if (blk == 100) sp.set(Spatial.plan(h, SpatialSetup.CINEMA))
            if (blk == 250) sp.set(null)
            for (i in 0 until 960) buf[i] = (rnd.nextFloat() - 0.5f) * 0.2f
            sp.process(buf, 480)
            for (f in 0 until 480) {
                biggest = maxOf(biggest, abs(buf[2 * f]))
                last = buf[2 * f]
            }
        }
        assertTrue("no blow-up: $biggest", biggest < 1f)
        assertTrue("fully off again", !sp.busy)
    }

    @Test
    fun fastEnough() {
        val sp = Spatial(48000.0)
        sp.set(Spatial.plan(head(), SpatialSetup.CINEMA))
        val buf = FloatArray(960)
        val rnd = Random(1)
        for (i in buf.indices) buf[i] = rnd.nextFloat() - 0.5f
        repeat(500) { sp.process(buf, 480) }
        val seconds = 20
        val t0 = System.nanoTime()
        repeat(seconds * 100) { sp.process(buf, 480) }
        val took = (System.nanoTime() - t0) / 1e9
        println("  7.1 cinema: ${"%.1f".format(seconds / took)}x real time on this machine")
        assertTrue(seconds / took > 5)
    }
}
