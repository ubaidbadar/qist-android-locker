package pk.qist.device.updates

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pk.qist.device.apk.ApkDownload
import pk.qist.device.apk.InstallTarget
import pk.qist.device.apk.ManagedApkInstaller
import pk.qist.device.data.DeviceState
import pk.qist.device.data.Enrolment
import pk.qist.device.data.LockState
import pk.qist.device.deviceadmin.QistAdminReceiver
import pk.qist.device.fakes.InMemoryStore
import pk.qist.device.fakes.RecordingScheduler

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LockerUpdaterTest {
    @Test
    fun `a replaced staged upload does not accumulate APK files or alter finance state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(DevicePolicyManager::class.java)).setDeviceOwner(
            QistAdminReceiver.component(context)
        )
        val directory = File(context.noBackupFilesDir, "locker-apk").apply { mkdirs() }
        val superseded = File(directory, "${"a".repeat(64)}.apk.part").apply { writeText("old staged APK") }
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag", "\"new-upload\"").header("Content-Length", "6")
                .body("badapk".toResponseBody()).build()
        }.build()
        val initial = DeviceState(enrolment = Enrolment.Enrolled("d1", "d1.secret"), lockState = LockState.LOCKED)
        val store = InMemoryStore(initial)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val installer = ManagedApkInstaller(
            context,
            store,
            RecordingScheduler(),
            ApkDownload(client),
            dispatcher,
            InstallTarget.LOCKER
        )
        val updater = LockerUpdater(
            context,
            store,
            CloudflareApkSource(client),
            "https://example.com/qist.apk".toHttpUrl(),
            installer,
            dispatcher,
            reserveSpace = { _, _ -> true }
        )
        assertFalse(updater.run()) // Invalid archive is rejected, never installed.
        assertEquals(2, calls)
        assertFalse(superseded.exists())
        assertEquals(initial.enrolment, store.value.enrolment)
        assertEquals(initial.lockState, store.value.lockState)
        assertEquals(initial.customerApp, store.value.customerApp)
        assertEquals("invalid_release", store.value.lockerApp.report.detailCode)
    }

    @Test
    fun `insufficient storage retries without downloading or changing finance state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(DevicePolicyManager::class.java)).setDeviceOwner(
            QistAdminReceiver.component(context)
        )
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag", "\"new-upload\"").header("Content-Length", "6")
                .body("badapk".toResponseBody()).build()
        }.build()
        val initial = DeviceState(enrolment = Enrolment.Enrolled("d1", "d1.secret"), lockState = LockState.LOCKED)
        val store = InMemoryStore(initial)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val installer = ManagedApkInstaller(
            context,
            store,
            RecordingScheduler(),
            ApkDownload(client),
            dispatcher,
            InstallTarget.LOCKER
        )
        val updater = LockerUpdater(
            context,
            store,
            CloudflareApkSource(client),
            "https://example.com/qist.apk".toHttpUrl(),
            installer,
            dispatcher,
            reserveSpace = { _, _ -> false }
        )
        assertTrue(updater.run())
        assertEquals(1, calls) // Metadata only; no archive bytes consume the remaining disk.
        assertEquals("storage", store.value.lockerApp.report.detailCode)
        assertEquals(initial.enrolment, store.value.enrolment)
        assertEquals(initial.lockState, store.value.lockState)
    }
}
