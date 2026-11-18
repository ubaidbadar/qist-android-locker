package pk.qist.device.deviceadmin

import android.app.Instrumentation
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real framework DevicePolicyManager on a clean emulator.
 *
 * Device Owner provisioning is performed through the shell identity exposed by UiAutomation. The test APK must
 * be installed alongside the target APK, and the emulator must have no accounts or existing Device Owner.
 */
@RunWith(AndroidJUnit4::class)
class DeviceOwnerReleaseTest {
    @Test
    fun releaseClearsQistPoliciesAndVerifiesDeviceOwnerRemoval() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val admin = QistAdminReceiver.component(context)
        val packageName = context.packageName

        provisionDeviceOwnerIfNeeded(instrumentation, dpm, admin, packageName)
        assertTrue("Qist must be Device Owner before release", dpm.isDeviceOwnerApp(packageName))

        // Match production's restriction set, including its release-build USB-debugging policy.
        val policy = DpmDevicePolicy(context, dpm, admin, blockUsbDebugging = true)
        assertEquals(PolicyResult.Applied, policy.protect())

        val lockHome = ComponentName(packageName, "$packageName.lock.LockHome")
        context.packageManager.setComponentEnabledSetting(
            lockHome,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        dpm.addPersistentPreferredActivity(
            admin,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            lockHome
        )

        val networkRestrictions = buildList {
            add(UserManager.DISALLOW_CONFIG_WIFI)
            add(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(UserManager.DISALLOW_AIRPLANE_MODE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(UserManager.DISALLOW_CHANGE_WIFI_STATE)
            }
        }
        networkRestrictions.forEach { dpm.addUserRestriction(admin, it) }
        dpm.setLockTaskPackages(admin, arrayOf(packageName))

        val expectedRestrictions = buildList {
            add(UserManager.DISALLOW_FACTORY_RESET)
            add(UserManager.DISALLOW_SAFE_BOOT)
            add(UserManager.DISALLOW_ADD_USER)
            add(UserManager.DISALLOW_DEBUGGING_FEATURES)
            addAll(networkRestrictions)
        }
        val beforeRelease = dpm.getUserRestrictions(admin)
        expectedRestrictions.forEach { restriction ->
            assertTrue("Qist should have applied $restriction", beforeRelease.getBoolean(restriction))
        }
        assertTrue("Qist should be uninstall-blocked before repayment", dpm.isUninstallBlocked(admin, packageName))
        assertTrue("Qist should be Device Owner until release is complete", dpm.isDeviceOwnerApp(packageName))

        assertEquals(PolicyResult.Applied, policy.release())

        val afterRelease = dpm.getUserRestrictions(admin)
        expectedRestrictions.forEach { restriction ->
            assertFalse("Qist restriction should be cleared: $restriction", afterRelease.getBoolean(restriction))
        }
        assertFalse("Qist must no longer be uninstall-blocked", dpm.isUninstallBlocked(admin, packageName))
        assertTrue("Qist lock-task allow-list must be empty", dpm.getLockTaskPackages(admin).isEmpty())
        assertEquals(
            "Qist Home alias must be disabled",
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(lockHome)
        )

        val resolvedHome = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.let { ComponentName(it.packageName, it.name) }
        assertFalse("Qist must no longer be the preferred Home activity", resolvedHome == lockHome)

        // The effective user restrictions must also be clear while Qist is still Device Owner.
        val effectiveAfterRelease = userManager.getUserRestrictions()
        expectedRestrictions.forEach { restriction ->
            assertFalse(
                "Factory reset and other Qist restrictions must be effective=false before hand-back: $restriction",
                effectiveAfterRelease.getBoolean(restriction)
            )
        }

        // Clearing restrictions is not the same as relinquishing Device Owner; verify both separately.
        assertTrue("Device Owner must remain until the explicit hand-back step", dpm.isDeviceOwnerApp(packageName))
        assertEquals(PolicyResult.Applied, policy.relinquish())
        assertFalse("Android must confirm Qist is no longer Device Owner", dpm.isDeviceOwnerApp(packageName))

        // Idempotency matters if the process is killed after Android clears ownership but before local state saves.
        assertEquals(PolicyResult.Applied, policy.relinquish())

        val effectiveRestrictions = userManager.getUserRestrictions()
        expectedRestrictions.forEach { restriction ->
            assertFalse(
                "No Qist restriction should remain after hand-back: $restriction",
                effectiveRestrictions.getBoolean(restriction)
            )
        }
    }

    private fun provisionDeviceOwnerIfNeeded(
        instrumentation: Instrumentation,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String
    ) {
        if (dpm.isDeviceOwnerApp(packageName)) return

        val output = shell(instrumentation, "dpm set-device-owner ${admin.flattenToShortString()}")
        assertTrue("Could not provision Qist as Device Owner. Shell output: $output", output.contains("Success"))
        assertTrue("Android did not report Qist as Device Owner after provisioning", dpm.isDeviceOwnerApp(packageName))
    }

    private fun shell(instrumentation: Instrumentation, command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
