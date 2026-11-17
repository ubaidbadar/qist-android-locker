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
import pk.qist.device.apk.ApkDownload
import pk.qist.device.apk.ApkRelease
import pk.qist.device.apk.AppInstallState
import pk.qist.device.apk.InstallTarget
import pk.qist.device.apk.ManagedApkInstaller
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
        ApkRelease(
            AppInstallState.CUSTOMER_PACKAGE,
            2,
            24,
            "https://example.com/customer.apk",
            10,
            "a".repeat(64),
            "b".repeat(64)
        )
    private val enrolled = Enrolment.Enrolled("d1", "d1.secret")

    private fun installer(store: InMemoryStore) =
        ManagedApkInstaller(context, store, scheduler, ApkDownload(OkHttpClient()), dispatcher)

    @Test
    fun `released devices do not download or re-enroll`() = runTest(dispatcher) {
        val store = InMemoryStore(DeviceState(enrolment = Enrolment.Released, customerApp = AppInstallState(release)))
        assertFalse(installer(store).run())
        assertEquals(Enrolment.Released, store.value.enrolment)
    }

    @Test
    fun `initial grace period retries without starting a download`() = runTest(dispatcher) {
        val app = AppInstallState(release, notBefore = System.currentTimeMillis() + 900_000)
        val store = InMemoryStore(DeviceState(enrolment = enrolled, customerApp = app))
        assertTrue(installer(store).run())
        assertEquals("waiting_for_setup", store.value.customerApp.report.detailCode)
        assertEquals(enrolled, store.value.enrolment)
    }

    @Test
    fun `a missing device owner is reported without an install prompt or a retry loop`() = runTest(dispatcher) {
        Settings.Global.putInt(context.contentResolver, Settings.Global.DEVICE_PROVISIONED, 1)
        val store = InMemoryStore(DeviceState(enrolment = enrolled, customerApp = AppInstallState(release)))
        assertFalse(installer(store).run())
        assertEquals("not_device_owner", store.value.customerApp.report.detailCode)
        assertEquals(release.sha256, store.value.customerApp.blockedSha256)
    }

    @Test
    fun `stale install callbacks cannot overwrite another session`() = runTest(dispatcher) {
        val app = AppInstallState(release, sessionId = 12, sessionRelease = release)
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

    @Test
    fun `locker callbacks cannot change customer install state or finance state`() = runTest(dispatcher) {
        val customer = AppInstallState(release, sessionId = 12)
        val locker = AppInstallState(release.copy(packageName = "pk.qist.device"), sessionId = 13)
        val initial = DeviceState(
            enrolment = enrolled,
            customerApp = customer,
            lockerApp = locker,
            lockState = pk.qist.device.data.LockState.LOCKED
        )
        val store = InMemoryStore(initial)
        val self =
            ManagedApkInstaller(
                context,
                store,
                scheduler,
                ApkDownload(OkHttpClient()),
                dispatcher,
                InstallTarget.LOCKER
            )
        self.complete(12, PackageInstaller.STATUS_SUCCESS)
        assertEquals(initial, store.value)
        self.complete(13, PackageInstaller.STATUS_SUCCESS)
        assertEquals(customer, store.value.customerApp)
        assertEquals(PackageInstaller.STATUS_SUCCESS, store.value.lockerApp.sessionStatus)
        assertEquals(initial.enrolment, store.value.enrolment)
        assertEquals(initial.lockState, store.value.lockState)
        assertEquals(listOf("locker update now"), scheduler.requests)
    }

    @Test
    fun `self update waits while finance lock is active and keeps enrollment intact`() = runTest(dispatcher) {
        Settings.Global.putInt(context.contentResolver, Settings.Global.DEVICE_PROVISIONED, 1)
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.admin.DevicePolicyManager::class.java))
            .setDeviceOwner(pk.qist.device.deviceadmin.QistAdminReceiver.component(context))
        val initial =
            DeviceState(
                enrolment = enrolled,
                lockerApp = AppInstallState(release.copy(packageName = "pk.qist.device")),
                lockState = pk.qist.device.data.LockState.LOCKED
            )
        val store = InMemoryStore(initial)
        val self =
            ManagedApkInstaller(
                context,
                store,
                scheduler,
                ApkDownload(OkHttpClient()),
                dispatcher,
                InstallTarget.LOCKER
            )
        assertTrue(self.run())
        assertEquals("waiting_for_unlock", store.value.lockerApp.report.detailCode)
        assertEquals(initial.customerApp, store.value.customerApp)
        assertEquals(initial.enrolment, store.value.enrolment)
        assertEquals(initial.lockState, store.value.lockState)
        assertEquals(null, store.value.lockerApp.sessionId)
    }
}
