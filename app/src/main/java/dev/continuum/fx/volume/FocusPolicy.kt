package dev.continuum.fx.volume

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusInfo
import android.media.AudioManager
import android.media.audiopolicy.AudioPolicy
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lives in the helper process (the shell user, through Shizuku). While any app plays alongside,
 * it registers as the phone's audio focus policy: every app's request for focus comes here and
 * is answered by [FocusRules], which run focus as Android does except for apps playing alongside.
 * Android's own way of keeping an app from pausing others (refusing its focus) stops most music
 * and video apps from playing at all, which is why it's done this way instead.
 *
 * Every request is answered, whatever happens: an app waits only a moment for an answer and
 * treats silence as a refusal, so a fault here answers yes. When the helper goes, Android
 * takes focus back by itself.
 */
internal class FocusPolicy(
    private val context: Context,
    /** Lowers (true) or restores (false) everything a uid plays, under a navigation prompt. */
    private val duck: (uid: Int, on: Boolean) -> Unit,
) {
    private val thread = HandlerThread("ContinuumFocus").apply { start() }
    private val handler = Handler(thread.looper)
    private val am = context.getSystemService(AudioManager::class.java)
    private val rules = FocusRules()
    private val infos = HashMap<String, AudioFocusInfo>()
    private var policy: AudioPolicy? = null

    /**
     * The apps playing alongside. Registers the policy when the first is added and lets Android
     * have focus back when the last goes. Returns "" when it's done, or why not.
     */
    fun setAlongside(packages: Set<String>): String {
        var result = "ERROR: timed out"
        val done = CountDownLatch(1)
        handler.post {
            result = try {
                rules.alongside = packages
                when {
                    packages.isEmpty() -> {
                        release()
                        ""
                    }
                    policy != null -> ""
                    else -> register()
                }
            } catch (t: Throwable) {
                "ERROR: ${t.javaClass.simpleName}: ${t.message}"
            }
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        return result
    }

    /** Lets go of everything (the helper is going). */
    fun shutdown() {
        val done = CountDownLatch(1)
        handler.post {
            try {
                release()
            } catch (_: Throwable) {
            }
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun register(): String {
        val builderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val builder = builderClass.getConstructor(Context::class.java).newInstance(context)
        builderClass.getMethod("setLooper", Looper::class.java).invoke(builder, thread.looper)
        builderClass.getMethod("setAudioPolicyFocusListener", AudioPolicy.AudioPolicyFocusListener::class.java).invoke(builder, Listener())
        builderClass.getMethod("setIsAudioFocusPolicy", Boolean::class.javaPrimitiveType).invoke(builder, true)
        val p = builderClass.getMethod("build").invoke(builder) as AudioPolicy
        val status = AudioManager::class.java.getMethod("registerAudioPolicy", AudioPolicy::class.java).invoke(am, p) as Int
        if (status != 0) return "ERROR: Android refused (status $status)"
        policy = p
        return ""
    }

    private fun release() {
        val p = policy ?: return
        policy = null
        perform(rules.clear())
        infos.clear()
        try {
            AudioManager::class.java.getMethod("unregisterAudioPolicy", AudioPolicy::class.java).invoke(am, p)
        } catch (_: Throwable) {
            try {
                AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", AudioPolicy::class.java).invoke(am, p)
            } catch (t: Throwable) {
                Log.w(TAG, "Couldn't unregister the focus policy", t)
            }
        }
    }

    /** Requests and releases, on [thread]. */
    inner class Listener : AudioPolicy.AudioPolicyFocusListener() {
        override fun onAudioFocusRequest(afi: AudioFocusInfo, requestResult: Int) {
            val client = try {
                afi.clientId
            } catch (_: Throwable) {
                null
            }
            try {
                val ask = askOf(afi)
                infos[ask.client] = afi
                perform(rules.request(ask))
            } catch (t: Throwable) {
                Log.w(TAG, "Focus request failed in the rules; granting it", t)
                answer(afi, FocusRules.GRANTED)
            }
            prune(client)
        }

        override fun onAudioFocusAbandon(afi: AudioFocusInfo) {
            try {
                perform(rules.abandon(afi.clientId))
            } catch (t: Throwable) {
                Log.w(TAG, "Focus release failed in the rules", t)
            }
            try {
                infos.remove(afi.clientId)
            } catch (_: Throwable) {
            }
        }
    }

    private fun askOf(afi: AudioFocusInfo): FocusAsk {
        val usage = try {
            (afi.javaClass.getMethod("getAttributes").invoke(afi) as AudioAttributes).usage
        } catch (_: Throwable) {
            AudioAttributes.USAGE_MEDIA
        }
        val sdk = try {
            afi.sdkTarget
        } catch (_: Throwable) {
            34
        }
        return FocusAsk(
            client = afi.clientId,
            pkg = afi.packageName.orEmpty(),
            uid = afi.clientUid,
            gain = afi.gainRequest,
            flags = afi.flags,
            usage = usage,
            sdk = sdk,
        )
    }

    private fun perform(actions: List<FocusAction>) {
        for (a in actions) {
            try {
                when (a) {
                    is FocusAction.Reply -> infos[a.client]?.let { answer(it, a.result) }
                    is FocusAction.Dispatch -> infos[a.client]?.let { tell(it, a.change) }
                    is FocusAction.Duck -> duck(a.uid, true)
                    is FocusAction.Unduck -> duck(a.uid, false)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Couldn't carry out $a", t)
            }
        }
    }

    private fun answer(afi: AudioFocusInfo, result: Int) {
        val p = policy ?: return
        try {
            AudioManager::class.java
                .getMethod("setFocusRequestResult", AudioFocusInfo::class.java, Int::class.javaPrimitiveType, AudioPolicy::class.java)
                .invoke(am, afi, result, p)
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't answer a focus request", t)
        }
    }

    private fun tell(afi: AudioFocusInfo, change: Int) {
        val p = policy ?: return
        AudioManager::class.java
            .getMethod("dispatchAudioFocusChange", AudioFocusInfo::class.java, Int::class.javaPrimitiveType, AudioPolicy::class.java)
            .invoke(am, afi, change, p)
    }

    /** Forgets the details of clients that no longer hold or wait for focus. */
    private fun prune(keep: String?) {
        if (infos.size < 48) return
        val live = rules.holders().toSet() + rules.waiting()
        infos.keys.retainAll { it in live || it == keep }
    }

    private companion object {
        const val TAG = "ContinuumFocus"
    }
}
