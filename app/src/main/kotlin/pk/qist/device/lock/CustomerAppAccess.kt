package pk.qist.device.lock

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import pk.qist.device.R

internal const val CUSTOMER_APP_PACKAGE = "pk.qist.customer"

/** Only Qist or its Google Play listing can open; no arbitrary browser fallback while locked. */
internal fun Context.openCustomerApp(installed: Boolean) {
    val intent = if (installed) {
        packageManager.getLaunchIntentForPackage(CUSTOMER_APP_PACKAGE)
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$CUSTOMER_APP_PACKAGE"))
            .setPackage("com.android.vending")
    }
    if (intent == null) {
        Toast.makeText(this, R.string.lock_customer_app_unavailable, Toast.LENGTH_LONG).show()
        return
    }
    // Keep navigation in the existing locked task on older versions.
    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            startActivity(intent, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle())
        } else {
            startActivity(intent)
        }
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.lock_customer_app_unavailable, Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, R.string.lock_customer_app_unavailable, Toast.LENGTH_LONG).show()
    }
}
