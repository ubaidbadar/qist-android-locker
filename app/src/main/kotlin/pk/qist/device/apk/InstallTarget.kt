package pk.qist.device.apk

import android.content.BroadcastReceiver
import pk.qist.device.WorkScheduler
import pk.qist.device.customer.CustomerInstallReceiver
import pk.qist.device.data.DeviceState
import pk.qist.device.updates.LockerInstallReceiver

enum class InstallTarget(val packageName: String, val directory: String) {
    CUSTOMER(AppInstallState.CUSTOMER_PACKAGE, "customer-apk"),
    LOCKER("pk.qist.device", "locker-apk");

    fun state(device: DeviceState): AppInstallState = if (this == CUSTOMER) device.customerApp else device.lockerApp

    fun replace(device: DeviceState, value: AppInstallState): DeviceState =
        if (this == CUSTOMER) device.copy(customerApp = value) else device.copy(lockerApp = value)

    fun receiver(): Class<out BroadcastReceiver> =
        if (this == CUSTOMER) CustomerInstallReceiver::class.java else LockerInstallReceiver::class.java

    fun schedule(scheduler: WorkScheduler, wifiOnly: Boolean, delayMillis: Long = 0) {
        if (this == CUSTOMER) scheduler.scheduleCustomerApp(wifiOnly, delayMillis) else scheduler.lockerUpdateNow()
    }

    fun continueDownload(scheduler: WorkScheduler, wifiOnly: Boolean) {
        if (this == CUSTOMER) scheduler.continueCustomerApp(wifiOnly) else scheduler.lockerUpdateNow()
    }
}
