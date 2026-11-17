package pk.qist.device.apk

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Bounded, resumable HTTP downloads. Each run yields before WorkManager's execution deadline. */
class ApkDownload(private val client: OkHttpClient, private val runNanos: Long = RUN_NANOS) {
    suspend fun download(release: ApkRelease, file: File): Boolean {
        if (file.length() > release.sizeBytes) file.delete()
        val deadline = System.nanoTime() + runNanos
        while (file.length() < release.sizeBytes) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() >= deadline) return false
            val offset = file.length()
            val end = minOf(release.sizeBytes - 1, offset + CHUNK_BYTES - 1)
            val request = Request.Builder().url(release.apkUrl)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$offset-$end")
                .build()
            client.newCall(request).execute().use { response ->
                val start: Long
                val expected: Long
                when (response.code) {
                    206 -> {
                        val range = parseRange(response.header("Content-Range"))
                        requireRange(
                            range != null && range.first == offset && range.second <= end &&
                                range.third == release.sizeBytes
                        )
                        start = offset
                        expected = requireNotNull(range).second - offset + 1
                    }

                    200 -> {
                        // Servers without Range support can still install; never append a full response to a partial APK.
                        start = 0
                        expected = release.sizeBytes
                    }

                    else -> throw IOException("APK download HTTP ${response.code}")
                }
                val length = response.body.contentLength()
                requireRange(length == -1L || length == expected)
                RandomAccessFile(file, "rw").use { output ->
                    if (start == 0L) output.setLength(0)
                    output.seek(start)
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var remaining = expected
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            if (System.nanoTime() >= deadline) return false
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) throw IOException("Incomplete APK download")
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        requireRange(input.read() == -1)
                    }
                }
            }
        }
        return true
    }

    private fun requireRange(valid: Boolean) {
        if (!valid) throw SecurityException("Unexpected APK range or size")
    }

    private fun parseRange(value: String?): Triple<Long, Long, Long>? {
        val match = RANGE.matchEntire(value ?: return null) ?: return null
        val (start, end, total) = match.destructured
        val first = start.toLongOrNull() ?: return null
        val last = end.toLongOrNull() ?: return null
        val size = total.toLongOrNull() ?: return null
        return if (last >= first) Triple(first, last, size) else null
    }

    private companion object {
        const val CHUNK_BYTES = 256L * 1024
        const val RUN_NANOS = 4L * 60 * 1_000_000_000
        val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}
