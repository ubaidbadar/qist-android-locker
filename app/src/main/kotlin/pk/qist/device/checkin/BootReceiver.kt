package pk.qist.device.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pk.qist.device.container

/**
 * Checks in once after every reboot. A locked phone needs nothing here: the lock screen is the Home screen
 * until unlocked, so it is the first thing that opens.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.container.scheduler.checkInNow()
            context.container.scheduler.scheduleLockerUpdates()
            context.container.scheduler.lockerUpdateNow()
        }
    }
}
