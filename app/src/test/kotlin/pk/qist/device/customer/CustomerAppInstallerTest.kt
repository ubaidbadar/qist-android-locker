package pk.qist.device.customer

import android.app.Application
import android.content.Context
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pk.qist.device.data.DeviceState
import pk.qist.device.data.Enrolment
import pk.qist.device.fakes.InMemoryStore
import pk.qist.device.fakes.RecordingScheduler

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CustomerAppInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler = RecordingScheduler()
    private val release =
        CustomerAppRelease(
            CustomerAppState.PACKAGE,
            2,
            24,
            "https://example.com/customer.apk",
            10,
            "a".repeat(64),
            "b".repeat(64)
        )
    private val enrolled = Enrolment.Enrolled("d1", "d1.secret")

    private fun installer(store: InMemoryStore) =
        CustomerAppInstaller(context, store, scheduler, ApkDownload(OkHttpClient()), dispatcher)

    @Test
    fun `released devices do not download or re-enroll`() = runTest(dispatcher) {
        val store = InMemoryStore(DeviceState(enrolment = Enrolment.Released, customerApp = CustomerAppState(release)))
        assertFalse(installer(store).run())
        assertEquals(Enrolment.Released, store.value.enrolment)
    }

    @Test
    fun `initial grace period retries without starting a download`() = runTest(dispatcher) {
        val app = CustomerAppState(release, notBefore = System.currentTimeMillis() + 900_000)
        val store = InMemoryStore(DeviceState(enrolment = enrolled, customerApp = app))
        assertTrue(installer(store).run())
        assertEquals("waiting_for_setup", store.value.customerApp.report.detailCode)
        assertEquals(enrolled, store.value.enrolment)
    }

    @Test
    fun `a missing device owner is reported without an install prompt or a retry loop`() = runTest(dispatcher) {
        Settings.Global.putInt(context.contentResolver, Settings.Global.DEVICE_PROVISIONED, 1)
        val store = InMemoryStore(DeviceState(enrolment = enrolled, customerApp = CustomerAppState(release)))
        assertFalse(installer(store).run())
        assertEquals("not_device_owner", store.value.customerApp.report.detailCode)
        assertEquals(release.sha256, store.value.customerApp.blockedSha256)
    }

    @Test
    fun `stale install callbacks cannot overwrite another session`() = runTest(dispatcher) {
        val app = CustomerAppState(release, sessionId = 12, sessionRelease = release)
        val store = InMemoryStore(DeviceState(enrolment = enrolled, customerApp = app))
        installer(store).complete(11, PackageInstaller.STATUS_SUCCESS)
        assertEquals(app, store.value.customerApp)
        assertTrue(scheduler.requests.isEmpty())
        installer(store).complete(12, PackageInstaller.STATUS_SUCCESS)
        assertEquals(PackageInstaller.STATUS_SUCCESS, store.value.customerApp.sessionStatus)
        assertEquals("pending", store.value.customerApp.report.status)
        assertEquals(listOf("customer app wifi=true delayed=false"), scheduler.requests)
    }

    @Test
    fun `a callback after release cannot restart customer installation`() = runTest(dispatcher) {
        val store = InMemoryStore(DeviceState(enrolment = Enrolment.Released))
        installer(store).complete(12, PackageInstaller.STATUS_SUCCESS)
        assertTrue(scheduler.requests.isEmpty())
        assertEquals(DeviceState(enrolment = Enrolment.Released), store.value)
    }
}
