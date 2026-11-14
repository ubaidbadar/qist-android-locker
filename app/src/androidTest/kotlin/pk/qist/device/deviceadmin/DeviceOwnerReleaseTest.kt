package pk.qist.device.deviceadmin

import android.app.admin.DevicePolicyManager
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs only on a fresh Android Emulator after adb has provisioned this package as Device Owner.
 * This exercises the real framework DevicePolicyManager, not Robolectric shadows or a fake.
 */
@RunWith(AndroidJUnit4::class)
class DeviceOwnerReleaseTest {
    @Test
    fun releaseClearsQistPoliciesAndRelinquishesDeviceOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = QistAdminReceiver.component(context)
        val packageName = context.packageName

        assertTrue("Qist must be provisioned as Device Owner before this test", dpm.isDeviceOwnerApp(packageName))

        val policy = DpmDevicePolicy(context, dpm, admin, blockUsbDebugging = false)

        assertEquals(PolicyResult.Applied, policy.protect())
        assertTrue(dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_FACTORY_RESET))
        assertTrue(dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_SAFE_BOOT))
        assertTrue(dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_ADD_USER))
        assertTrue(dpm.isUninstallBlocked(admin, packageName))

        // Recreate the restrictions that are applied while the phone is locked.
        val networkRestrictions = listOf(
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_AIRPLANE_MODE,
            UserManager.DISALLOW_CHANGE_WIFI_STATE
        )
        networkRestrictions.forEach { dpm.addUserRestriction(admin, it) }
        dpm.setLockTaskPackages(admin, arrayOf(packageName))

        assertEquals(PolicyResult.Applied, policy.release())

        val afterRelease = dpm.getUserRestrictions(admin)
        listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER
        ).plus(networkRestrictions).forEach { restriction ->
            assertFalse("Restriction should be cleared: $restriction", afterRelease.getBoolean(restriction))
        }
        assertFalse("Qist must no longer be uninstall-blocked", dpm.isUninstallBlocked(admin, packageName))
        assertEquals(emptyList<String>(), dpm.getLockTaskPackages(admin).toList())

        // Release must remove policies but keep Device Owner until the final hand-back step.
        assertTrue("Release must not relinquish Device Owner prematurely", dpm.isDeviceOwnerApp(packageName))
        assertEquals(PolicyResult.Applied, policy.relinquish())
        assertFalse("Device Owner should be cleared after relinquish", dpm.isDeviceOwnerApp(packageName))
    }
}
