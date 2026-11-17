package pk.qist.device.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.launch
import pk.qist.device.container

/** A private callback; the persisted session id is checked again by the installer. */
class LockerInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                context.container.lockerInstaller.complete(id, status)
            } finally {
                pending.finish()
            }
        }
    }
}
