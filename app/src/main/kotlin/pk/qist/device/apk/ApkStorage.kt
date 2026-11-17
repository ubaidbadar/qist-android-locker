package pk.qist.device.apk

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

/** Reserve room for both the downloaded archive and Android's installation copy before writing either. */
internal fun reserveApkSpace(context: Context, directory: File, bytes: Long): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return directory.usableSpace >= bytes
    val storage = context.getSystemService(StorageManager::class.java)
    return try {
        val volume = storage.getUuidForPath(directory)
        if (storage.getAllocatableBytes(volume) < bytes) {
            false
        } else {
            storage.allocateBytes(volume, bytes)
            true
        }
    } catch (_: IOException) {
        false
    }
}
