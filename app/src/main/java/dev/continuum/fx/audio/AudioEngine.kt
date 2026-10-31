package dev.continuum.fx.audio

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import dev.continuum.fx.dsp.StageMode
import dev.continuum.fx.dsp.SpatialSetup
import dev.continuum.fx.dsp.SpatialLibrary
import dev.continuum.fx.dsp.Spatial
import android.content.Context
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
import dev.continuum.fx.data.Library
import dev.continuum.fx.dsp.DynamicsMode
import dev.continuum.fx.dsp.EqProfile
import dev.continuum.fx.dsp.EqResponse
import dev.continuum.fx.dsp.FxChain
import dev.continuum.fx.dsp.FxContext
import dev.continuum.fx.dsp.FxSettings
import dev.continuum.fx.dsp.FxTone
import dev.continuum.fx.dsp.Limiter
import dev.continuum.fx.dsp.ParametricEq
import kotlin.math.max
import kotlin.math.pow

/**
 * Captures what other apps play, runs it through the DSP chain and plays it back.
 * The original copies are silenced separately by [SessionWatcher].
 */
class AudioEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val onFailure: (String) -> Unit,
) {
    private val sampleRate = EqResponse.SAMPLE_RATE.toInt()
    private val framesPerChunk = 480 // 10 ms at 48 kHz

    private val eq = ParametricEq(channels = 2, sampleRate = sampleRate.toDouble())
    private val chain = FxChain(sampleRate.toDouble())
    private val limiter = Limiter(sampleRate.toDouble())
    private val spatial = Spatial(sampleRate.toDouble())

    /** Spatial plans take a moment to build: done here, never on the audio thread. */
    private val planner = Executors.newSingleThreadExecutor { r -> Thread(r, "ContinuumSpatial") }
    @Volatile
    private var spatialSetup: SpatialSetup? = null
    private val spatialGeneration = AtomicInteger()

    /** Whether the spatial stage is in the chain (from switching on until its fade-out ends). */
    @Volatile
    private var spatialWanted = false
    private var spatialEngaged = false

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    /**
     * Uses the EQ for what's [playing] (an app's own, when everything playing is linked to the same
     * one, else the active EQ), or none when it's switched off, with the effects on top. While
     * [compare] is held the EQ and effects step aside but their overall loudness stays, so the two
     * can be judged on sound alone: otherwise whichever is louder tends to win.
     */
    fun setSound(library: Library, fx: FxSettings, ctx: FxContext, compare: Boolean, playing: List<String>, spaces: SpatialLibrary) {
        val c = ctx.copy(direct = false)
        // Spatial audio is for headphones, and steps aside with the other effects while comparing.
        val space = if (!compare && fx.enabled && ctx.onHeadphones(fx)) spaces.setupFor(playing) else null
        setSpatial(space)
        // It places the sound itself: crossfeed and stage would only blur it.
        val effects = if (space != null) fx.copy(crossfeedOn = false, stage = StageMode.Off) else fx
        val tone = FxTone.bands(fx, c)
        val playingProfile = library.profileForPlaying(playing)
        val profile = if (library.eqEnabled) playingProfile else null
        // Polarity belongs to the earphones, not the sound: it stays with the EQ switched off and
        // while comparing.
        val polarity = playingProfile.polarity
        val both = when {
            profile != null -> FxTone.withTone(profile, tone)
            tone.isNotEmpty() -> EqProfile(name = "Effects", bands = tone)
            else -> null
        }
        if (compare) {
            eq.setProfile(both?.let { EqProfile(name = "Original", preampDb = EqResponse.loudnessDb(it)) }, polarity)
            chain.set(FxChain.Params())
            limiter.soft = false
        } else {
            eq.setProfile(both, polarity)
            chain.set(effects, c)
            limiter.soft = fx.enabled && fx.dynamics == DynamicsMode.Pure
        }
    }

    private fun setSpatial(setup: SpatialSetup?) {
        if (setup == spatialSetup) return
        spatialSetup = setup
        EngineStatus.spatialSetup.value = setup?.name
        val generation = spatialGeneration.incrementAndGet()
        if (setup == null) {
            spatial.set(null)
            return
        }
        spatialWanted = true
        planner.execute {
            // Dragging a speaker asks for a new plan many times a second: build only the latest.
            if (spatialGeneration.get() != generation) return@execute
            val head = Heads.get(context, setup.head) ?: return@execute
            val plan = try {
                Spatial.plan(head, setup)
            } catch (_: Exception) {
                return@execute
            }
            // A newer setup came along while this one was building.
            if (spatialGeneration.get() == generation) spatial.set(plan)
        }
    }

    // RECORD_AUDIO is checked before the service is started.
    @SuppressLint("MissingPermission")
    fun start() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val inFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(inFormat)
            .setBufferSizeInBytes(max(minIn, framesPerChunk * 2 * 4 * 4))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Couldn't open audio capture")
        }

        val outFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val outAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            // Never let our own output be captured again, or it would loop.
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()
        val minOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val out = AudioTrack.Builder()
            .setAudioAttributes(outAttributes)
            .setAudioFormat(outFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minOut, framesPerChunk * 2 * 4 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        record = rec
        track = out
        // Exactly where processed sound goes, so the right EQ can follow the headphones.
        out.addOnRoutingChangedListener(
            AudioRouting.OnRoutingChangedListener { router -> OutputWatcher.routedTo(router.routedDevice) },
            Handler(Looper.getMainLooper()),
        )
        rec.startRecording()
        out.play()
        OutputWatcher.routedTo(out.routedDevice)
        running = true
        thread = Thread({ loop(rec, out) }, "ContinuumAudio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop(rec: AudioRecord, out: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = FloatArray(framesPerChunk * 2)
        while (running) {
            val read = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read < 0) {
                if (running) onFailure("Audio capture stopped unexpectedly (error $read)")
                break
            }
            if (read == 0) continue
            val frames = read / 2
            eq.process(buffer, frames)
            chain.process(buffer, frames)
            spatialStage(buffer, frames)
            boost(buffer, frames)
            limiter.process(buffer, frames)
            if (LiveAudio.listening.value) LiveAudio.tap.push(buffer, frames, 2)
            out.write(buffer, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
        }
    }

    /**
     * The spatial stage, while it's wanted or still fading out. It delays the sound by a few
     * milliseconds while in, so it's only in the chain when needed.
     */
    private fun spatialStage(buffer: FloatArray, frames: Int) {
        if (!spatialEngaged) {
            if (!spatialWanted) return
            spatial.clearOutput()
            spatialEngaged = true
        }
        spatial.process(buffer, frames)
        if (!spatial.busy && spatialSetup == null) {
            spatialEngaged = false
            spatialWanted = false
        }
    }

    private var boostDb = 0f
    private var boostTarget = 1f
    private var boostGain = 1f

    /**
     * The volume panel's boost: extra gain ahead of the limiter, which keeps the peaks clean.
     * A change glides across one chunk, so stepping it never clicks.
     */
    private fun boost(buffer: FloatArray, frames: Int) {
        val db = EngineStatus.boostDb.value
        if (db != boostDb) {
            boostDb = db
            boostTarget = if (db <= 0f) 1f else 10f.pow(db / 20f)
        }
        val start = boostGain
        val end = boostTarget
        if (start == 1f && end == 1f) return
        if (start == end) {
            for (i in 0 until frames * 2) buffer[i] *= end
            return
        }
        for (f in 0 until frames) {
            val g = start + (end - start) * (f + 1) / frames
            buffer[2 * f] *= g
            buffer[2 * f + 1] *= g
        }
        boostGain = end
    }

    fun stop() {
        running = false
        planner.shutdownNow()
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        thread?.join(500)
        thread = null
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {
        }
        record?.release()
        track?.release()
        record = null
        track = null
        OutputWatcher.routedTo(null)
    }
}
