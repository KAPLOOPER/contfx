package dev.continuum.fx.audio

import android.os.IBinder
import android.os.ParcelFileDescriptor

/**
 * Reads a system service's dump, the same text `dumpsys` prints. This needs the
 * DUMP permission, which is granted once over ADB or through Shizuku.
 */
object ServiceDump {

    fun read(service: String, args: Array<String> = emptyArray()): String? {
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, service) as? IBinder ?: return null
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            binder.dumpAsync(writeEnd.fileDescriptor, args)
            writeEnd.close()
            ParcelFileDescriptor.AutoCloseInputStream(readEnd).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            null
        }
    }
}
