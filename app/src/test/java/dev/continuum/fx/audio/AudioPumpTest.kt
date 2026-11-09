package dev.continuum.fx.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioPumpTest {
    @Test
    fun shortWritesDrainTheSameProcessedChunk() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val heard = mutableListOf<Float>()
        var reads = 0
        var processed = 0
        lateinit var pump: AudioPump
        pump = AudioPump(3, read = { buffer ->
            reads++
            buffer.indices.forEach { buffer[it] = it.toFloat() }
            buffer.size
        }, process = { buffer, frames ->
            assertEquals(3, frames)
            processed++
            buffer.indices.forEach { buffer[it] += 10f }
        }, write = { buffer, offset, count ->
            calls += offset to count
            heard += buffer.slice(offset until offset + 2)
            if (offset == 4) pump.stop()
            2
        }, onFailure = { throw AssertionError(it) })
        pump.run()
        assertEquals(listOf(0 to 6, 2 to 4, 4 to 2), calls)
        assertEquals(listOf(10f, 11f, 12f, 13f, 14f, 15f), heard)
        assertEquals(1, reads)
        assertEquals(1, processed)
    }

    @Test
    fun invalidReadsFailOnceWithoutProcessingOrWriting() {
        for (result in listOf(-6, -3, 1, 5, 8)) {
            val failures = mutableListOf<String>()
            var reads = 0
            val pump = AudioPump(3, read = { reads++; result },
                process = { _, _ -> throw AssertionError("processed invalid read $result") },
                write = { _, _, _ -> throw AssertionError("wrote invalid read $result") },
                onFailure = failures::add)
            pump.run()
            assertEquals("read $result", 1, failures.size)
            assertEquals(1, reads)
        }
    }

    @Test
    fun invalidWritesFailWithoutSpinningOrReadingAnotherChunk() {
        for (result in listOf(-6, -3, 1, 8)) {
            var reads = 0
            var writes = 0
            val failures = mutableListOf<String>()
            AudioPump(3, read = { reads++; 6 }, process = { _, _ -> },
                write = { _, _, _ -> writes++; result }, onFailure = failures::add).run()
            assertEquals("write $result", 1, failures.size)
            assertEquals(1, reads)
            assertEquals(1, writes)
        }
    }

    @Test
    fun aReadThatBringsNothingIsTriedAgain() {
        var reads = 0
        var rests = 0
        var processed = 0
        lateinit var pump: AudioPump
        pump = AudioPump(2, read = { reads++; if (reads <= 2) 0 else 4 },
            process = { _, frames -> assertEquals(2, frames); processed++ },
            write = { _, _, count -> pump.stop(); count },
            onFailure = { throw AssertionError(it) }, idle = { rests++ })
        pump.run()
        assertEquals(3, reads)
        assertEquals(2, rests)
        assertEquals(1, processed)
    }

    @Test
    fun aWriteThatTakesNothingIsTriedForAMomentThenSaid() {
        // Takes nothing twice, then all of it: nothing dropped, nothing said.
        var calls = 0
        lateinit var pump: AudioPump
        pump = AudioPump(2, read = { 4 }, process = { _, _ -> },
            write = { _, _, count -> calls++; if (calls <= 2) 0 else { pump.stop(); count } },
            onFailure = { throw AssertionError(it) }, idle = {})
        pump.run()
        assertEquals(3, calls)
        // Takes nothing for good: said once, without reading another chunk.
        var reads = 0
        var writes = 0
        val failures = mutableListOf<String>()
        AudioPump(2, read = { reads++; 4 }, process = { _, _ -> },
            write = { _, _, _ -> writes++; 0 }, onFailure = failures::add, idle = {}).run()
        assertEquals(1, failures.size)
        assertEquals(1, reads)
        assertEquals(51, writes)
    }

    @Test
    fun aFailureAfterAPartialWriteDoesNotReplaySamples() {
        val offsets = mutableListOf<Int>()
        val failures = mutableListOf<String>()
        AudioPump(3, read = { 6 }, process = { _, _ -> }, write = { _, offset, _ ->
            offsets += offset
            if (offset == 0) 2 else -6
        }, onFailure = failures::add).run()
        assertEquals(listOf(0, 2), offsets)
        assertEquals(1, failures.size)
    }

    @Test
    fun exceptionsFromEveryStageAreReported() {
        for (stage in listOf("read", "process", "write")) {
            val failures = mutableListOf<String>()
            AudioPump(2, read = { if (stage == "read") error(stage) else 4 },
                process = { _, _ -> if (stage == "process") error(stage) },
                write = { _, _, _ -> error(stage) }, onFailure = failures::add).run()
            assertEquals(listOf(stage), failures)
        }
    }

    @Test
    fun deliberateStopSuppressesTheErrorThatUnblocksAWrite() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val failures = mutableListOf<String>()
        val pump = AudioPump(2, read = { 4 }, process = { _, _ -> }, write = { _, _, _ ->
            entered.countDown()
            check(unblock.await(2, TimeUnit.SECONDS))
            -3
        }, onFailure = failures::add)
        val worker = Thread(pump::run)
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pump.stop()
        } finally {
            unblock.countDown()
            worker.join(2000)
        }
        assertFalse(worker.isAlive)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun stoppingDuringReadDoesNotProcessOrReportItsError() {
        lateinit var pump: AudioPump
        pump = AudioPump(2, read = { pump.stop(); -3 },
            process = { _, _ -> throw AssertionError("processed after stop") },
            write = { _, _, _ -> throw AssertionError("wrote after stop") },
            onFailure = { throw AssertionError(it) })
        pump.run()
    }

    @Test
    fun stoppingBeforeRunDoesNotTouchAudio() {
        val pump = AudioPump(2, read = { throw AssertionError("read after stop") },
            process = { _, _ -> }, write = { _, _, _ -> 0 }, onFailure = { throw AssertionError(it) })
        pump.stop()
        pump.run()
    }

    @Test
    fun cleanupIsReverseOrderedAndIdempotentEvenIfAReleaseThrows() {
        val released = mutableListOf<String>()
        val cleanup = AudioCleanup()
        cleanup.add { released += "record" }
        cleanup.add { released += "track"; error("already dead") }
        cleanup.close()
        cleanup.close()
        assertEquals(listOf("track", "record"), released)
    }

    @Test
    fun partialStartupClosesOnlyAcquiredResources() {
        val released = mutableListOf<String>()
        val cleanup = AudioCleanup()
        try {
            cleanup.add { released += "record" }
            error("track construction failed")
        } catch (_: IllegalStateException) {
            cleanup.close()
        }
        assertEquals(listOf("record"), released)
    }
}
