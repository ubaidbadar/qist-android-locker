package pk.qist.device.customer

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pk.qist.device.apk.ApkDownload
import pk.qist.device.apk.ApkRelease
import pk.qist.device.apk.AppInstallState

class ApkDownloadTest {
    @Test
    fun `a completed time budget preserves progress for a fresh continuation job`() = runTest {
        val file = file("abc")
        assertFalse(ApkDownload(OkHttpClient(), runNanos = 0).download(release(), file))
        assertEquals("abc", file.readText())
        assertEquals(0, server.requestCount)
    }

    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val download = ApkDownload(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())

    @Before fun start() = server.start()

    @After fun close() = server.close()

    private fun release() = ApkRelease(
        AppInstallState.CUSTOMER_PACKAGE,
        2,
        24,
        server.url("/app.apk").toString(),
        6,
        "a".repeat(64),
        "b".repeat(64)
    )

    private fun file(content: String) = File(temporary.root, "app.part").apply { writeText(content) }

    @Test
    fun `a retry requests only the missing bytes and never sends a device credential`() = runTest {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 3-5/6").body("def").build())
        val file = file("abc")
        assertTrue(download.download(release(), file))
        assertEquals("abcdef", file.readText())
        val request = server.takeRequest()
        assertEquals("bytes=3-5", request.headers["Range"])
        assertEquals(null, request.headers["Authorization"])
    }

    @Test
    fun `a server ignoring ranges replaces rather than appends to a partial file`() = runTest {
        server.enqueue(MockResponse.Builder().body("abcdef").build())
        val file = file("abc")
        assertTrue(download.download(release(), file))
        assertEquals("abcdef", file.readText())
    }

    @Test
    fun `a wrong content range is rejected before corrupting the partial file`() = runTest {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-2/6").body("def").build())
        val file = file("abc")
        try {
            download.download(release(), file)
            throw AssertionError("Expected rejected range")
        } catch (_: SecurityException) {
            assertEquals("abc", file.readText())
        }
    }

    @Test
    fun `temporary server failure preserves the partial download`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        val file = file("abc")
        try {
            download.download(release(), file)
            throw AssertionError("Expected retryable failure")
        } catch (_: IOException) {
            assertEquals("abc", file.readText())
        }
    }

    @Test
    fun `an already complete download makes no network request`() = runTest {
        assertTrue(download.download(release(), file("abcdef")))
        assertEquals(0, server.requestCount)
    }
}
