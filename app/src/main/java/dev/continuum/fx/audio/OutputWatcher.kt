package dev.continuum.fx.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where sound is going: the phone's speaker, wired headphones, a Bluetooth device. */
data class OutputDevice(
    /** Stable across reconnects, used to remember which EQ goes with it. */
    val key: String,
    val name: String,
    val kind: Kind,
) {
    enum class Kind { Speaker, Wired, Bluetooth, Usb, Other }
}

/**
 * Follows the audio output, so an EQ can belong to a pair of headphones: plug them in or
 * connect them and their EQ comes on by itself. While processing, the engine reports the
 * exact device its sound is routed to; otherwise the most likely one is worked out from
 * what's connected, the way Android routes media.
 */
object OutputWatcher {
    private val _current = MutableStateFlow<OutputDevice?>(null)
    val current: StateFlow<OutputDevice?> = _current.asStateFlow()

    /** Each time the EQ switched by itself, with what it switched to, for a short note on screen. */
    val switched = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var audio: AudioManager? = null
    @Volatile
    private var routed: AudioDeviceInfo? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start(context: Context) {
        if (audio != null) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        audio = am
        am.registerAudioDeviceCallback(callback, main)
        refresh()
    }

    /** The engine's own output device, which is exactly where processed sound is going. */
    fun routedTo(device: AudioDeviceInfo?) {
        routed = device
        main.post { refresh() }
    }

    private fun refresh() {
        val am = audio ?: return
        val outputs = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (_: Exception) {
            emptyList()
        }
        val exact = routed?.takeIf { r -> outputs.any { it.id == r.id } }
        val device = exact ?: outputs.filter { rank(it) > 0 }.maxByOrNull { rank(it) }
        val next = device?.let { describe(it) }
        if (next != _current.value) _current.value = next
    }

    /** How Android prefers outputs for media: headphones over Bluetooth over the speaker. */
    private fun rank(d: AudioDeviceInfo): Int = when (d.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 6
        AudioDeviceInfo.TYPE_USB_HEADSET -> 6
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 5
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> 4
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID -> 4
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> 3
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_DOCK -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }

    private fun describe(d: AudioDeviceInfo): OutputDevice {
        val product = d.productName?.toString()?.trim().orEmpty()
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
                OutputDevice("speaker", "Phone speaker", OutputDevice.Kind.Speaker)
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                OutputDevice("wired", "Wired headphones", OutputDevice.Kind.Wired)
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                OutputDevice("usb:$product", product.ifEmpty { "USB audio" }, OutputDevice.Kind.Usb)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID ->
                OutputDevice("bt:$product", product.ifEmpty { "Bluetooth" }, OutputDevice.Kind.Bluetooth)
            else -> OutputDevice("other:${d.type}:$product", product.ifEmpty { "Audio output" }, OutputDevice.Kind.Other)
        }
    }
}
