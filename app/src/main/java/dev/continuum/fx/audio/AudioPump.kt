package dev.continuum.fx.audio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The capture loop: read a stereo chunk, process it once, and write all of it before reading
 * the next. Kept apart from Android so its edges can be tested on the JVM.
 *
 * A short write is followed by the rest of the chunk, so nothing is dropped. A read that
 * brings nothing is tried again after a breath, as it always was; a write that takes nothing
 * is too, for a moment, before it counts as stopped. Anything that goes wrong is said once,
 * and a stop asked for isn't mistaken for one.
 */
internal class AudioPump(
    framesPerChunk: Int,
    private val read: (FloatArray) -> Int,
    private val process: (FloatArray, Int) -> Unit,
    private val write: (FloatArray, Int, Int) -> Int,
    private val onFailure: (String) -> Unit,
    /** A short wait between tries that brought nothing. */
    private val idle: () -> Unit = { Thread.sleep(2) },
) {
    private val active = AtomicBoolean(true)
    private val started = AtomicBoolean(false)
    private val buffer = FloatArray(framesPerChunk * CHANNELS)

    init {
        require(framesPerChunk > 0)
    }

    fun stop() {
        active.set(false)
    }

    fun run() {
        check(started.compareAndSet(false, true)) { "An audio pump can only run once" }
        try {
            while (active.get()) {
                val count = read(buffer)
                if (!active.get()) break
                if (count == 0) {
                    idle()
                    continue
                }
                check(count > 0) { "Audio capture stopped unexpectedly (error $count)" }
                check(count <= buffer.size && count % CHANNELS == 0) { "Audio capture returned part of a frame" }
                process(buffer, count / CHANNELS)
                var offset = 0
                var empty = 0
                while (offset < count && active.get()) {
                    val written = write(buffer, offset, count - offset)
                    if (!active.get()) break
                    if (written == 0) {
                        check(++empty <= EMPTY_WRITES) { "Audio playback stopped taking sound" }
                        idle()
                        continue
                    }
                    check(written > 0) { "Audio playback stopped unexpectedly (error $written)" }
                    check(written <= count - offset && written % CHANNELS == 0) { "Audio playback took part of a frame" }
                    empty = 0
                    offset += written
                }
            }
        } catch (e: Exception) {
            // Stopping can unblock a read or write by making it fail: that's not a failure.
            // compareAndSet also means only one failure is said for this run.
            if (active.compareAndSet(true, false)) {
                onFailure(e.message ?: "Audio processing stopped unexpectedly")
            }
        } finally {
            active.set(false)
        }
    }

    private companion object {
        const val CHANNELS = 2

        /** About 100 ms of writes that take nothing, at the 2 ms breath between them. */
        const val EMPTY_WRITES = 50
    }
}

/** Releases what was opened, even halfway through starting: once each, last opened first. */
internal class AudioCleanup {
    private val actions = ArrayList<() -> Unit>()
    private var closed = false

    @Synchronized
    fun add(action: () -> Unit) {
        check(!closed) { "Audio resources have already been closed" }
        actions += action
    }

    fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            actions.reversed().also { actions.clear() }
        }
        pending.forEach { action -> runCatching(action) }
    }
}
