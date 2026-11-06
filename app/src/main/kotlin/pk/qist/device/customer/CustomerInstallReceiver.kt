package pk.qist.device.customer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.launch
import pk.qist.device.container

/** Explicit, non-exported PendingIntent target: only the matching saved installation session is accepted. */
class CustomerInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (id < 0) return
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                context.container.customerInstaller.complete(id, status)
            } finally {
                pending.finish()
            }
        }
    }
}
