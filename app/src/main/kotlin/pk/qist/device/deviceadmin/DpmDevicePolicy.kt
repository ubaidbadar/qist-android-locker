package pk.qist.device.deviceadmin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.telecom.TelecomManager
import pk.qist.device.lock.LockActivity

/** [DevicePolicy] through the framework's DevicePolicyManager. Every call needs this app to be Device Owner. */
class DpmDevicePolicy(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val blockUsbDebugging: Boolean
) : DevicePolicy {
    private val packageName = admin.packageName

    /** The manifest alias that makes the lock screen the Home screen; enabled only while locked. */
    private val lockHome = ComponentName(packageName, "$packageName.lock.LockHome")

    /** Restrictions held from enrolment until release. */
    private val restrictions = buildList {
        add(UserManager.DISALLOW_FACTORY_RESET)
        add(UserManager.DISALLOW_SAFE_BOOT)
        add(UserManager.DISALLOW_ADD_USER)
        if (blockUsbDebugging) add(UserManager.DISALLOW_DEBUGGING_FEATURES)
    }

    override fun protect() = asOwner { applyProtections() }

    override fun lock() = asOwner {
        applyProtections()
        restrictNetworkChanges()
        // Only these apps may run while locked: ours, and the dialer so emergency calls and "Call branch" work.
        dpm.setLockTaskPackages(admin, lockTaskPackages())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The power menu stays, for its Emergency button; status bar, Home and Recents are hidden.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        }
        setLockHome(enabled = true)
        context.startActivity(Intent(context, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun unlock() = asOwner { removeLock() }

    /**
     * Remove every restriction Qist itself applies, then verify the resulting policy state before reporting success.
     * This prevents the server from treating a partially released phone as ready for customer hand-back.
     */
    override fun release(): PolicyResult {
        if (!dpm.isDeviceOwnerApp(packageName)) return PolicyResult.Failed(NOT_OWNER)
        return try {
            removeLock()
            restrictions.forEach { dpm.clearUserRestriction(admin, it) }
            dpm.setUninstallBlocked(admin, packageName, false)

            val allQistRestrictions = (restrictions + networkRestrictions()).distinct()
            val appliedRestrictions = dpm.getUserRestrictions(admin)
            val restrictionsRemain = allQistRestrictions.any { appliedRestrictions.getBoolean(it) }
            // DevicePolicyManager reports this admin's policies; Settings uses the effective
            // restrictions from UserManager, which may differ when another source applies one.
            val userManager = context.getSystemService(UserManager::class.java)
                ?: return PolicyResult.Failed(EFFECTIVE_RESTRICTIONS_UNVERIFIED)
            val effectiveRestrictions = userManager.userRestrictions
            val effectiveRestrictionsRemain = allQistRestrictions.any { effectiveRestrictions.getBoolean(it) }
            val uninstallBlocked = dpm.isUninstallBlocked(admin, packageName)
            val lockTaskPackagesRemain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                dpm.getLockTaskPackages(admin).isNotEmpty()
            val lockHomeEnabled =
                context.packageManager.getComponentEnabledSetting(lockHome) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val qistStillDefaultHome = context.packageManager
                .resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.let { ComponentName(it.packageName, it.name) == lockHome } == true

            when {
                restrictionsRemain -> PolicyResult.Failed(RESTRICTIONS_REMAIN)
                effectiveRestrictionsRemain -> PolicyResult.Failed(EFFECTIVE_RESTRICTIONS_REMAIN)
                uninstallBlocked -> PolicyResult.Failed(UNINSTALL_STILL_BLOCKED)
                lockTaskPackagesRemain -> PolicyResult.Failed(LOCK_TASK_STILL_CONFIGURED)
                lockHomeEnabled || qistStillDefaultHome -> PolicyResult.Failed(LOCK_HOME_STILL_CONFIGURED)
                else -> PolicyResult.Applied
            }
        } catch (_: SecurityException) {
            PolicyResult.Failed(REFUSED)
        }
    }

    /**
     * Android deprecates clearDeviceOwnerApp and documents it as test-only/best-effort. Keep the call isolated,
     * idempotent, and verify the framework's owner state instead of assuming a non-throwing call succeeded.
     */
    @Suppress("DEPRECATION")
    override fun relinquish(): PolicyResult {
        if (!dpm.isDeviceOwnerApp(packageName)) return PolicyResult.Applied
        return try {
            dpm.clearDeviceOwnerApp(packageName)
            if (dpm.isDeviceOwnerApp(packageName)) {
                PolicyResult.Failed(OWNER_REMAINS)
            } else {
                PolicyResult.Applied
            }
        } catch (_: SecurityException) {
            PolicyResult.Failed(REFUSED)
        }
    }

    private fun applyProtections() {
        restrictions.forEach { dpm.addUserRestriction(admin, it) }
        dpm.setUninstallBlocked(admin, packageName, true)
    }

    /**
     * Keep a locked phone reachable: users cannot change Wi-Fi networks, cellular settings or airplane mode.
     * On Android 13+ also block Wi-Fi on/off toggles. Existing network connections are left unchanged.
     * These policy restrictions cannot prevent signal loss, SIM removal or an exhausted data plan.
     */
    private fun restrictNetworkChanges() {
        networkRestrictions().forEach { dpm.addUserRestriction(admin, it) }
    }

    private fun networkRestrictions() = buildList {
        add(UserManager.DISALLOW_CONFIG_WIFI)
        add(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(UserManager.DISALLOW_AIRPLANE_MODE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(UserManager.DISALLOW_CHANGE_WIFI_STATE)
        }
    }

    private fun removeLock() {
        // Restore normal connectivity controls without disturbing the current Wi-Fi or mobile connection.
        networkRestrictions().forEach { dpm.clearUserRestriction(admin, it) }
        setLockHome(enabled = false)
        // Emptying the allow-list also ends kiosk mode if the lock screen is still showing.
        dpm.setLockTaskPackages(admin, emptyArray())
    }

    private fun setLockHome(enabled: Boolean) {
        if (enabled) {
            setComponent(lockHome, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            dpm.addPersistentPreferredActivity(admin, homeFilter(), lockHome)
        } else {
            dpm.clearPackagePersistentPreferredActivities(admin, packageName)
            setComponent(lockHome, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }
    }

    private fun setComponent(component: ComponentName, state: Int) =
        context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)

    private fun lockTaskPackages(): Array<String> {
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return listOfNotNull(packageName, dialer, SYSTEM_PHONE).distinct().toTypedArray()
    }

    private fun homeFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** Runs [block] only when this app is Device Owner, turning a refusal into [PolicyResult.Failed]. */
    private inline fun asOwner(block: () -> Unit): PolicyResult {
        if (!dpm.isDeviceOwnerApp(packageName)) return PolicyResult.Failed(NOT_OWNER)
        return try {
            block()
            PolicyResult.Applied
        } catch (_: SecurityException) {
            PolicyResult.Failed(REFUSED)
        }
    }

    private companion object {
        /** Hosts the emergency dialer on most phones. */
        const val SYSTEM_PHONE = "com.android.phone"
        const val NOT_OWNER = "app is not device owner"
        const val REFUSED = "android refused the policy"
        const val OWNER_REMAINS = "android still reports this app as device owner"
        const val RESTRICTIONS_REMAIN = "android did not clear all Qist restrictions"
        const val EFFECTIVE_RESTRICTIONS_REMAIN = "android still enforces device restrictions"
        const val EFFECTIVE_RESTRICTIONS_UNVERIFIED = "android restrictions could not be verified"
        const val UNINSTALL_STILL_BLOCKED = "android still blocks Qist uninstall"
        const val LOCK_TASK_STILL_CONFIGURED = "android still has Qist lock-task packages configured"
        const val LOCK_HOME_STILL_CONFIGURED = "android still has Qist configured as the Home app"
    }
}
