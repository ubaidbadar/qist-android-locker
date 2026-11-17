package pk.qist.device.updates

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class RemoteApk(val url: HttpUrl, val etag: String, val size: Long)

/** Direct APK uploads work without a second manifest. ETags prevent mixing bytes from two uploads. */
class CloudflareApkSource(private val client: OkHttpClient, private val runNanos: Long = RUN_NANOS) {
    fun probe(url: HttpUrl, knownETag: String?): RemoteApk? {
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        val request = Request.Builder().url(url).head().header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .apply { if (knownETag != null) header("If-None-Match", knownETag) }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304 && knownETag != null) return null
            if (!response.isSuccessful) throw IOException("APK metadata is unavailable")
            val etag = response.header("ETag")
            require(etag != null && etag.length in 1..200 && !etag.startsWith("W/")) { "A strong APK ETag is required" }
            val size = response.header("Content-Length")?.toLongOrNull()
            require(size != null && size in 1..MAX_APK_BYTES) { "Invalid APK size" }
            return RemoteApk(url, etag, size)
        }
    }

    suspend fun download(remote: RemoteApk, file: File): Boolean {
        if (file.length() > remote.size) file.delete()
        val deadline = System.nanoTime() + runNanos
        while (file.length() < remote.size) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() >= deadline) return false
            val offset = file.length()
            val end = minOf(remote.size - 1, offset + CHUNK_BYTES - 1)
            val request = Request.Builder().url(remote.url)
                .header("Cache-Control", "no-cache").header("Accept-Encoding", "identity")
                .header("If-Match", remote.etag).header("Range", "bytes=$offset-$end").build()
            client.newCall(request).execute().use { response ->
                if (response.code == 412) throw IOException("The APK changed during download")
                if (response.header("ETag") != remote.etag) throw IOException("The APK changed during download")
                val start: Long
                val length: Long
                when (response.code) {
                    206 -> {
                        val range = RANGE.matchEntire(response.header("Content-Range").orEmpty())
                            ?: throw SecurityException("Invalid APK range")
                        val (from, to, total) = range.destructured
                        val last = to.toLongOrNull() ?: throw SecurityException("Invalid APK range")
                        require(
                            from.toLongOrNull() == offset && last in offset..end && total.toLongOrNull() == remote.size
                        )
                        start = offset
                        length = last - offset + 1
                    }

                    200 -> {
                        start = 0
                        length = remote.size
                    }

                    else -> throw IOException("APK download is unavailable")
                }
                require(response.body.contentLength() == -1L || response.body.contentLength() == length)
                RandomAccessFile(file, "rw").use { output ->
                    if (start == 0L) output.setLength(0)
                    output.seek(start)
                    response.body.byteStream().use { input ->
                        var remaining = length
                        val buffer = ByteArray(16 * 1024)
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) throw IOException("Incomplete APK download")
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        require(input.read() == -1) { "APK exceeded the expected size" }
                    }
                }
            }
        }
        return true
    }

    companion object {
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val CHUNK_BYTES = 256L * 1024
        private const val RUN_NANOS = 4L * 60 * 1_000_000_000
        private val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}
