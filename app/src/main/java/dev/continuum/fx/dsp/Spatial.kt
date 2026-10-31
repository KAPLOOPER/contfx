package dev.continuum.fx.dsp

import kotlin.math.min
import kotlin.math.pow

/**
 * Spatial audio for headphones, on the captured stereo: the bass split off and left as it was,
 * the rest opened up into the setup's speakers (straight through for a stereo pair), each
 * speaker heard at both ears through the room and the measured head, plus a quiet tail.
 *
 * Works in blocks of [BLOCK] samples, so it delays the sound by that (5 ms) whenever it's in
 * use, plus another block when the mix is split into sources and space. A new setup with the same speakers
 * fades its filters in over a block; a different layout dips to the plain sound and back.
 */
internal class Spatial(private val sampleRate: Double) {

    /** Everything one setup needs, built off the audio thread by [plan]. */
    class Plan internal constructor(
        val setup: SpatialSetup,
        internal val filters: Convolver.Filters,
        internal val directDelay: Int,
        internal val tailDelay: Int,
    ) {
        internal val speakers get() = setup.speakers.size

        /** Whether the mix is split into sources and space first (it costs a block of delay). */
        internal val decompose get() = setup.layout != SpatialLayout.Stereo || setup.space > 0.005f

        /** Plans that can follow each other with just a filter crossfade. */
        internal fun sameShape(o: Plan) = o.setup.layout == setup.layout && o.setup.bassHz == setup.bassHz && o.decompose == decompose
    }

    private val convolver = Convolver(BLOCK, MAX_SPEAKERS, MAX_PARTITIONS)
    private val upmix = Upmix(BLOCK, sampleRate)
    private val tail = Tail(sampleRate)

    private var active: Plan? = null

    @Volatile
    private var requested: Plan? = null

    @Volatile
    private var requestSerial = 0
    private var seenSerial = 0
    private var swapTo: Plan? = null
    private var swapping = false

    private var wet = 0f
    private var wetTarget = 0f
    private val wetStep = BLOCK / (sampleRate.toFloat() * 0.06f)

    private var lowL: Lr4? = null
    private var lowR: Lr4? = null
    private var highL: Lr4? = null
    private var highR: Lr4? = null

    // Blocks.
    private val inBlock = FloatArray(2 * BLOCK)
    private var inFill = 0
    private val outRing = FloatArray(2 * RING)
    private var outRead = 0
    private var outCount = 0

    private val l = FloatArray(BLOCK)
    private val r = FloatArray(BLOCK)
    private val hl = FloatArray(BLOCK)
    private val hr = FloatArray(BLOCK)
    private val feeds = Array(MAX_SPEAKERS) { FloatArray(BLOCK) }
    private val ears = Array(2) { FloatArray(BLOCK) }
    private val tailL = FloatArray(BLOCK)
    private val tailR = FloatArray(BLOCK)
    private val bypass = Array(2) { FloatArray(BLOCK) }

    // The space that skips the speakers waits for their direct sound, as the bass does.
    private val spaceRingL = FloatArray(LOW_RING)
    private val spaceRingR = FloatArray(LOW_RING)
    private var spacePos = 0

    // The bass waits as long as the speakers' direct sound takes.
    private val lowRingL = FloatArray(LOW_RING)
    private val lowRingR = FloatArray(LOW_RING)
    private var lowPos = 0
    private var lowDelay = 0
    private var lowDelayNext = 0

    init {
        clearOutput()
    }

    /** Switches to [plan] (null: spatial audio off). Any thread. */
    fun set(plan: Plan?) {
        requested = plan
        requestSerial++
    }

    /** Whether this still has sound to finish (fading out), so it must keep being run. */
    val busy: Boolean get() = active != null || wet > 0f || requested != null

    /** Processes interleaved stereo in place. */
    fun process(buffer: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val take = min(frames - i, BLOCK - inFill)
            System.arraycopy(buffer, 2 * i, inBlock, 2 * inFill, 2 * take)
            inFill += take
            i += take
            if (inFill == BLOCK) {
                runBlock()
                inFill = 0
            }
        }
        for (f in 0 until frames) {
            buffer[2 * f] = outRing[2 * outRead]
            buffer[2 * f + 1] = outRing[2 * outRead + 1]
            outRead = (outRead + 1) % RING
            outCount--
        }
    }

    /** Starts clean: what's queued becomes a block of silence, as on a fresh start. */
    fun clearOutput() {
        outRing.fill(0f)
        outRead = 0
        outCount = BLOCK
        inFill = 0
    }

    private fun runBlock() {
        takeRequest()
        for (s in 0 until BLOCK) {
            l[s] = inBlock[2 * s]
            r[s] = inBlock[2 * s + 1]
        }
        val plan = active
        if (plan != null || wet > 0f) {
            if (plan != null) render(plan)
            // Glide between the plain sound and the spatial one.
            val start = wet
            wet = if (wetTarget > wet) min(wetTarget, wet + wetStep) else maxOf(wetTarget, wet - wetStep)
            for (s in 0 until BLOCK) {
                val w = start + (wet - start) * (s + 1) / BLOCK
                val el = if (plan != null) ears[0][s] else 0f
                val er = if (plan != null) ears[1][s] else 0f
                l[s] = l[s] * (1f - w) + el * w
                r[s] = r[s] * (1f - w) + er * w
            }
            if (wet <= 0f && wetTarget <= 0f) finishFadeOut()
        }
        var w = (outRead + outCount) % RING
        for (s in 0 until BLOCK) {
            outRing[2 * w] = l[s]
            outRing[2 * w + 1] = r[s]
            w = (w + 1) % RING
        }
        outCount += BLOCK
    }

    private fun takeRequest() {
        val serial = requestSerial
        if (serial == seenSerial) return
        seenSerial = serial
        val next = requested
        val now = active
        when {
            next == null -> {
                swapTo = null
                swapping = now != null
                wetTarget = 0f
            }
            now == null -> start(next)
            next.sameShape(now) && !swapping -> {
                active = next
                convolver.set(next.filters)
                upmix.configure(next.setup.speakers, next.setup.width, next.setup.space)
                configureTail(next)
                lowDelayNext = lowDelayFor(next)
            }
            else -> {
                // A different layout: down to the plain sound, then up on the new one.
                swapTo = next
                swapping = true
                wetTarget = 0f
            }
        }
    }

    private fun finishFadeOut() {
        val next = swapTo
        swapTo = null
        swapping = false
        if (next != null) start(next) else {
            active = null
            convolver.set(null)
        }
    }

    private fun start(plan: Plan) {
        active = plan
        convolver.reset()
        convolver.set(plan.filters)
        upmix.reset()
        upmix.configure(plan.setup.speakers, plan.setup.width, plan.setup.space)
        tail.reset()
        configureTail(plan)
        val hz = plan.setup.bassHz
        if (hz > 0) {
            lowL = Lr4(hz.toDouble(), sampleRate, high = false)
            lowR = Lr4(hz.toDouble(), sampleRate, high = false)
            highL = Lr4(hz.toDouble(), sampleRate, high = true)
            highR = Lr4(hz.toDouble(), sampleRate, high = true)
        } else {
            lowL = null; lowR = null; highL = null; highR = null
        }
        lowRingL.fill(0f)
        lowRingR.fill(0f)
        spaceRingL.fill(0f)
        spaceRingR.fill(0f)
        lowDelay = lowDelayFor(plan)
        lowDelayNext = lowDelay
        wetTarget = 1f
    }

    private fun lowDelayFor(plan: Plan) = (plan.directDelay + if (plan.decompose) BLOCK else 0).coerceIn(0, LOW_RING - BLOCK - 1)

    private fun configureTail(plan: Plan) {
        val room = plan.setup.room
        val size = when (room) {
            RoomKind.None -> 0.6f
            RoomKind.Studio -> 0.7f
            RoomKind.Living -> 0.9f
            RoomKind.Cinema -> 1.6f
        }
        tail.configure(plan.setup.decay, size, plan.tailDelay, if (room == RoomKind.None) 6000f else room.dullHz)
    }

    private fun render(plan: Plan) {
        val setup = plan.setup
        val split = lowL != null
        val ll = lowL
        val lr = lowR
        val hiL = highL
        val hiR = highR
        for (s in 0 until BLOCK) {
            if (split) {
                val x = l[s].toDouble()
                val y = r[s].toDouble()
                hl[s] = hiL!!.run(x).toFloat()
                hr[s] = hiR!!.run(y).toFloat()
                lowRingL[lowPos] = ll!!.run(x).toFloat()
                lowRingR[lowPos] = lr!!.run(y).toFloat()
            } else {
                hl[s] = l[s]
                hr[s] = r[s]
                lowRingL[lowPos] = 0f
                lowRingR[lowPos] = 0f
            }
            lowPos = (lowPos + 1) % LOW_RING
        }

        val n = plan.speakers
        if (plan.decompose) {
            upmix.process(hl, hr, feeds, bypass)
        } else {
            System.arraycopy(hl, 0, feeds[0], 0, BLOCK)
            System.arraycopy(hr, 0, feeds[1], 0, BLOCK)
            bypass[0].fill(0f)
            bypass[1].fill(0f)
        }
        convolver.process(feeds, n, ears)

        // The space that skips the speakers, in at the moment their direct sound arrives.
        val spaceDelay = plan.directDelay.coerceIn(0, LOW_RING - BLOCK - 1)
        for (s in 0 until BLOCK) {
            spaceRingL[spacePos] = bypass[0][s]
            spaceRingR[spacePos] = bypass[1][s]
            var rd = spacePos - spaceDelay
            if (rd < 0) rd += LOW_RING
            ears[0][s] += spaceRingL[rd]
            ears[1][s] += spaceRingR[rd]
            spacePos = (spacePos + 1) % LOW_RING
        }

        val tailDb = setup.tailDb
        if (tailDb != null) {
            tailL.fill(0f)
            tailR.fill(0f)
            for ((i, sp) in setup.speakers.withIndex()) {
                val f = feeds[i]
                when {
                    sp.angle < -1f -> for (s in 0 until BLOCK) tailL[s] += f[s]
                    sp.angle > 1f -> for (s in 0 until BLOCK) tailR[s] += f[s]
                    else -> for (s in 0 until BLOCK) {
                        tailL[s] += 0.7f * f[s]
                        tailR[s] += 0.7f * f[s]
                    }
                }
            }
            tail.process(tailL, tailR, ears[0], ears[1], BLOCK, 10f.pow(tailDb / 20f))
        }

        // The bass, back in at the moment the speakers' sound arrives.
        val from = lowDelay
        val to = lowDelayNext
        val start = lowPos - BLOCK
        for (s in 0 until BLOCK) {
            var a = start + s - from
            while (a < 0) a += LOW_RING
            val ax = lowRingL[a % LOW_RING]
            val ay = lowRingR[a % LOW_RING]
            if (from == to) {
                ears[0][s] += ax
                ears[1][s] += ay
            } else {
                var b = start + s - to
                while (b < 0) b += LOW_RING
                val t = (s + 1f) / BLOCK
                ears[0][s] += ax * (1f - t) + lowRingL[b % LOW_RING] * t
                ears[1][s] += ay * (1f - t) + lowRingR[b % LOW_RING] * t
            }
        }
        lowDelay = to
    }

    companion object {
        const val BLOCK = 256
        const val MAX_SPEAKERS = 7
        /** Direct sound and reflections up to about 170 ms. */
        const val MAX_PARTITIONS = 32
        private const val RING = 4096
        private const val LOW_RING = 2048

        /** Builds what [setup] needs, with [head]. Takes a moment: not on the audio thread. */
        fun plan(head: Head, setup: SpatialSetup): Plan {
            val built = Room.build(head, setup, BLOCK * MAX_PARTITIONS)
            val irs = Array(built.filters.size) { s -> built.filters[s] }
            return Plan(setup, Convolver.prepare(BLOCK, irs), built.directDelay, built.tailDelay)
        }
    }
}
