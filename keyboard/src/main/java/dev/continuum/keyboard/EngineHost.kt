package dev.continuum.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.continuum.keyboard.engine.KeyMap
import dev.continuum.keyboard.engine.Learned
import dev.continuum.keyboard.engine.NextWords
import dev.continuum.keyboard.engine.Suggester
import dev.continuum.keyboard.engine.SwipeDecoder
import dev.continuum.keyboard.engine.Words
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the word engine on its own thread: loads the word lists once, answers requests for
 * suggestions and swipes, and saves what's been learned. Results come back on the main
 * thread, and a newer request makes older pending ones moot.
 */
class EngineHost(context: Context) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "continuum-words") }
    private val main = Handler(Looper.getMainLooper())
    private val learnedFile = File(appContext.filesDir, "learned_words.txt")
    /** One counter per kind of request, so a new swipe doesn't cancel suggestions and vice versa. */
    private val generations = Array(4) { AtomicInteger() }

    val learned = Learned()

    @Volatile
    var suggester: Suggester? = null
        private set

    @Volatile
    var swipe: SwipeDecoder? = null
        private set

    private var pendingKeys: KeyMap? = null

    init {
        worker.execute {
            val started = System.currentTimeMillis()
            try {
                if (learnedFile.exists()) learnedFile.bufferedReader().use { learned.read(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read learned words", e)
            }
            try {
                val words = appContext.assets.open("words/en_words.txt").bufferedReader().use { Words.read(it) }
                val next = appContext.assets.open("words/en_next.txt").bufferedReader().use { NextWords.read(it) }
                val s = Suggester(words, next, learned)
                s.keys = pendingKeys
                suggester = s
                swipe = SwipeDecoder(words)
                Log.i(TAG, "Words ready: ${words.size} in ${System.currentTimeMillis() - started} ms")
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't load words", e)
            }
        }
    }

    /** Where the letters are now, for corrections and swipes. */
    fun setKeys(keys: KeyMap?) {
        worker.execute {
            pendingKeys = keys
            suggester?.keys = keys
        }
    }

    /**
     * Runs [task] on the engine thread and hands its result to [done] on the main thread,
     * unless a newer request on the same [channel] came in meanwhile.
     */
    fun <T> ask(channel: Int, task: (Suggester) -> T, done: (T) -> Unit) {
        val generation = generations[channel]
        val ticket = generation.incrementAndGet()
        worker.execute {
            if (ticket != generation.get()) return@execute
            val s = suggester ?: return@execute
            val result = try {
                task(s)
            } catch (e: Exception) {
                Log.w(TAG, "Engine request failed", e)
                return@execute
            }
            main.post { if (ticket == generation.get()) done(result) }
        }
    }

    /** Runs [task] on the engine thread without cancelling anything. */
    fun later(task: (Suggester?) -> Unit) {
        worker.execute {
            try {
                task(suggester)
            } catch (e: Exception) {
                Log.w(TAG, "Engine task failed", e)
            }
        }
    }

    /** Runs [task] now on the calling thread, waiting for the engine if it's busy. */
    fun <T> now(task: (Suggester) -> T): T? {
        val s = suggester ?: return null
        return try {
            worker.submit<T> { task(s) }.get()
        } catch (e: Exception) {
            null
        }
    }

    /** Drops pending results on [channel] so nothing stale lands in the strip. */
    fun cancel(channel: Int) {
        generations[channel].incrementAndGet()
    }

    fun save() {
        worker.execute {
            if (!learned.changed) return@execute
            try {
                val tmp = File(learnedFile.path + ".tmp")
                tmp.bufferedWriter().use { learned.write(it) }
                tmp.renameTo(learnedFile)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save learned words", e)
            }
        }
    }

    fun forgetEverything() {
        worker.execute {
            learned.clear()
            learnedFile.delete()
        }
    }

    companion object {
        private const val TAG = "ContinuumWords"
        const val SUGGEST = 0
        const val SWIPE = 1

        @Volatile
        private var shared: EngineHost? = null

        /** One engine for the keyboard and settings. */
        fun get(context: Context): EngineHost =
            shared ?: synchronized(this) { shared ?: EngineHost(context).also { shared = it } }
    }
}
