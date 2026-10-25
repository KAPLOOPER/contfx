/*
 * The key handling here (holds as a latch, lost releases, the screen sleeping mid-press and
 * power-button chords) is adapted from Volume++ by noel-digital-fan
 * (https://github.com/noel-digital-fan/volume_plus_plus), MIT License. See THIRD_PARTY_NOTICES.md.
 */
package dev.continuum.fx.volume

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The volume keys, Continuum's way: this accessibility service catches them before Android's
 * panel does, moves the volume itself and shows the ink panel. It listens to the two volume
 * keys and nothing else, and reads nothing on screen; window changes only say which app is in
 * front, so the camera can keep its shutter key.
 */
class VolumeService : AccessibilityService() {

    private var host: VolumeHost? = null
    private var scope: CoroutineScope? = null
    private val handler = Handler(Looper.getMainLooper())
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /** The app in front, from window changes. */
    private var foreground: String? = null
    private var cameraPackages: Set<String> = emptySet()

    /** Whether the press under way is ours. */
    private var consuming = false

    // ---- The hold: a latch every way out of a press must clear. ----
    private var heldDirection = 0
    private var repeatDelay = REPEAT_START_MS
    private var lastKeyDownAt = 0L
    private var sawAutoRepeat = false
    private var holdStartedAt = 0L

    // ---- The power key, on the builds that pass it through at all. ----
    private var powerHeld = false
    private var powerDownAt = 0L
    private var powerUpAt = 0L

    private val repeat = object : Runnable {
        override fun run() {
            if (heldDirection == 0) return
            // The keys stop reaching us the moment the screen sleeps, so a release then never comes.
            if (power?.isInteractive == false) {
                releaseHold()
                return
            }
            // Nothing has said the key is still down for many repeat periods: the release was lost.
            if (sawAutoRepeat && SystemClock.uptimeMillis() - lastKeyDownAt > STALE_HOLD_MS) {
                releaseHold()
                return
            }
            // Some devices send no repeats at all, so a lost release there needs a hard ceiling.
            if (SystemClock.uptimeMillis() - holdStartedAt > MAX_HOLD_MS) {
                releaseHold()
                return
            }
            step(heldDirection, fromRepeat = true)
            repeatDelay = (repeatDelay - REPEAT_STEP_MS).coerceAtLeast(REPEAT_MIN_MS)
            handler.postDelayed(this, repeatDelay)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        VolumeSettings.init(this)
        VolumeModel.start(this)
        VolumeHelper.init(this)
        AppVolumes.start(this)
        Booster.init(this)
        releaseHold()
        forgetPowerKey()
        cameraPackages = findCameraApps()
        host?.detach()
        host = VolumeHost(this).also { it.attach() }
        VolumeStatus.serviceOn.value = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            // Switching the panel off hands the keys straight back and folds it away.
            VolumeSettings.state.map { it.enabled }.distinctUntilChanged().collect { on -> if (!on) host?.hide() }
        }
        s.launch {
            // Moving the panel to the other side moves its windows too.
            VolumeSettings.state.map { it.side }.distinctUntilChanged().collect { host?.reposition() }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_POWER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    powerHeld = true
                    powerDownAt = SystemClock.uptimeMillis()
                    // The volume key came first: that press was half of a chord, so put it back.
                    if (heldDirection != 0 && SystemClock.uptimeMillis() - holdStartedAt <= CHORD_WINDOW_MS) {
                        val dir = heldDirection
                        releaseHold()
                        step(-dir, fromRepeat = true, quiet = true)
                    }
                }
                KeyEvent.ACTION_UP -> {
                    powerHeld = false
                    powerUpAt = SystemClock.uptimeMillis()
                }
            }
            return false
        }
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!up && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        // Decided once per press, so a press is either wholly ours or wholly Android's.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) consuming = wantsKeys()
        if (!consuming) {
            releaseHold()
            return false
        }
        if (powerChordActive()) {
            releaseHold()
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                lastKeyDownAt = SystemClock.uptimeMillis()
                if (event.repeatCount == 0) {
                    heldDirection = if (up) 1 else -1
                    repeatDelay = REPEAT_START_MS
                    sawAutoRepeat = false
                    holdStartedAt = SystemClock.uptimeMillis()
                    step(heldDirection, fromRepeat = false)
                    handler.removeCallbacks(repeat)
                    handler.postDelayed(repeat, FIRST_REPEAT_MS)
                } else {
                    sawAutoRepeat = true
                }
            }
            KeyEvent.ACTION_UP -> {
                releaseHold()
                consuming = false
                // Up is where a press ends, so it's also where the panel starts counting to leave.
                host?.released()
                return true
            }
        }
        return true
    }

    /** Whether the volume keys are ours right now, or Android's (or the app in front's). */
    private fun wantsKeys(): Boolean {
        val prefs = VolumeSettings.state.value
        if (!prefs.enabled || host == null) return false
        // A ringing call: the keys silence it, which is Android's to do.
        if (VolumeModel.ringing()) return false
        // The camera's shutter.
        if (prefs.cameraKeys && foreground != null && foreground in cameraPackages) return false
        // Casting: the keys move the cast device's volume.
        if (VolumeModel.castingRemotely(this)) return false
        return true
    }

    /** One volume step from a key, and the panel to show it. */
    private fun step(dir: Int, fromRepeat: Boolean, quiet: Boolean = false) {
        val snap = VolumeModel.state.value
        val stream = snap.keyStream
        val prefs = VolumeSettings.state.value
        var moved = false
        val boosted = stream == VolStream.Media && Booster.onKey(dir, fromRepeat, snap[VolStream.Media], prefs)
        if (boosted) {
            moved = Booster.lastKeyMoved
        } else {
            when (VolumeModel.step(stream, dir)) {
                StepResult.Moved -> moved = true
                StepResult.AtEnd -> Unit
                StepResult.SystemAsked -> {
                    // Android's hearing warning is up; the panel steps aside for it.
                    host?.hide()
                    releaseHold()
                    return
                }
            }
        }
        if (quiet) return
        if (moved && prefs.haptics) tick()
        host?.show(stream, expanded = prefs.openExpanded)
    }

    private fun releaseHold() {
        heldDirection = 0
        sawAutoRepeat = false
        handler.removeCallbacks(repeat)
    }

    private fun forgetPowerKey() {
        powerHeld = false
        powerDownAt = 0L
        powerUpAt = 0L
    }

    private fun powerChordActive(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (powerHeld && now - powerDownAt > MAX_POWER_HOLD_MS) powerHeld = false
        return powerHeld || (powerUpAt != 0L && now - powerUpAt < CHORD_TAIL_MS)
    }

    private fun tick() {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
        } catch (_: Exception) {
        }
    }

    /** Apps that answer the camera button: their volume keys are a shutter. */
    private fun findCameraApps(): Set<String> = try {
        listOf(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.INTENT_ACTION_VIDEO_CAMERA)
            .flatMap { action -> packageManager.queryIntentActivities(Intent(action), 0).map { it.activityInfo.packageName } }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Our own panel and the system's windows don't count as the app in front.
        if (pkg == packageName || pkg == "com.android.systemui") return
        foreground = pkg
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        host?.reposition()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        releaseHold()
        forgetPowerKey()
        // A boost lives in the audio system, not here: never leave one behind with nothing to undo it.
        Booster.clear()
        scope?.cancel()
        scope = null
        host?.detach()
        host = null
        if (instance === this) instance = null
        VolumeStatus.serviceOn.value = false
    }

    /** Shows the panel without a key press: from the app or the Quick Settings tile. */
    fun showPanel(expanded: Boolean) {
        VolumeModel.refresh()
        host?.show(VolumeModel.state.value.keyStream, expanded, key = false)
    }

    companion object {
        /** The running service, for the tile and the app's preview. */
        @Volatile
        var instance: VolumeService? = null
            private set

        private const val FIRST_REPEAT_MS = 250L
        private const val REPEAT_START_MS = 150L
        private const val REPEAT_STEP_MS = 12L
        private const val REPEAT_MIN_MS = 40L
        private const val STALE_HOLD_MS = 500L
        private const val MAX_HOLD_MS = 10_000L
        private const val CHORD_WINDOW_MS = 150L
        private const val CHORD_TAIL_MS = 200L
        private const val MAX_POWER_HOLD_MS = 2_000L
    }
}
