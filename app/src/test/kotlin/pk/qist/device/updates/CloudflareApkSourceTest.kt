package pk.qist.device.updates

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudflareApkSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val source = CloudflareApkSource(OkHttpClient())

    @Before fun start() = server.start()

    @After fun close() = server.close()
    private fun partial() = File(temporary.root, "app.part").apply { writeText("abc") }
    private fun remote() = RemoteApk(server.url("/locker.apk"), "\"upload-v2\"", 6)
    private fun response(code: Int, etag: String = "\"upload-v2\"", range: String? = null, body: String = "def") =
        MockResponse.Builder().code(code).addHeader("ETag", etag).apply {
            if (range != null) addHeader("Content-Range", range)
        }.body(body).build()

    @Test fun `HEAD uses HTTPS and validators without sending enrollment secrets`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("HEAD", request.method)
            assertEquals("\"old\"", request.header("If-None-Match"))
            assertEquals("identity", request.header("Accept-Encoding"))
            assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK").code(200)
                .header("ETag", "\"new\"").header("Content-Length", "6").body("".toResponseBody()).build()
        }.build()
        assertEquals(
            RemoteApk("https://example.com/qist.apk".toHttpUrl(), "\"new\"", 6),
            CloudflareApkSource(client).probe("https://example.com/qist.apk".toHttpUrl(), "\"old\"")
        )
        try {
            CloudflareApkSource(client).probe("http://example.com/qist.apk".toHttpUrl(), null)
            throw AssertionError("Expected HTTPS requirement")
        } catch (_: IllegalArgumentException) { /* No insecure metadata request. */ }
    }

    @Test fun `unchanged metadata avoids downloading an APK`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Unchanged").code(304)
                .body("".toResponseBody()).build()
        }.build()
        assertNull(CloudflareApkSource(client).probe("https://example.com/qist.apk".toHttpUrl(), "\"old\""))
    }

    @Test fun `resumes only matching bytes with If-Match and no device token`() = runTest {
        server.enqueue(response(206, range = "bytes 3-5/6"))
        val file = partial()
        assertTrue(source.download(remote(), file))
        assertEquals("abcdef", file.readText())
        val request = server.takeRequest()
        assertEquals("bytes=3-5", request.headers["Range"])
        assertEquals("\"upload-v2\"", request.headers["If-Match"])
        assertNull(request.headers["Authorization"])
    }

    @Test fun `changed upload cannot append bytes to previous upload`() = runTest {
        for (reply in listOf(response(412), response(206, etag = "\"new\"", range = "bytes 3-5/6"))) {
            server.enqueue(reply)
            val file = partial()
            try {
                source.download(remote(), file)
                throw AssertionError("Expected changed upload rejection")
            } catch (
                _: IOException
            ) {
                assertEquals("abc", file.readText())
            }
        }
    }

    @Test fun `wrong range is rejected before partial file corruption`() = runTest {
        server.enqueue(response(206, range = "bytes 0-2/6"))
        val file = partial()
        try {
            source.download(remote(), file)
            throw AssertionError("Expected range rejection")
        } catch (
            _: IllegalArgumentException
        ) {
            assertEquals("abc", file.readText())
        }
    }

    @Test fun `server ignoring range replaces partial instead of appending`() = runTest {
        server.enqueue(response(200, body = "abcdef"))
        val file = partial()
        assertTrue(source.download(remote(), file))
        assertEquals("abcdef", file.readText())
    }

    @Test fun `time budget yields without discarding download progress`() = runTest {
        val file = partial()
        assertFalse(CloudflareApkSource(OkHttpClient(), runNanos = 0).download(remote(), file))
        assertEquals("abc", file.readText())
        assertEquals(0, server.requestCount)
    }
}
