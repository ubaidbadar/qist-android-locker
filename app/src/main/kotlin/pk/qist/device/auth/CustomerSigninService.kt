package pk.qist.device.auth

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pk.qist.device.container

/** The calling identity is checked during the Binder transaction, before any coroutine suspension. */
class CustomerSigninService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = object : ICustomerSignin.Stub() {
        override fun authenticate(requestId: String, nonce: String, callback: ICustomerSigninCallback) {
            enforceCallingPermission(PERMISSION, "Only the signed Qist Customer app may request sign-in.")
            val caller = Binder.getCallingUid()
            val packages = packageManager.getPackagesForUid(caller).orEmpty()
            if (CUSTOMER !in packages ||
                packageManager.checkSignatures(packageName, CUSTOMER) != PackageManager.SIGNATURE_MATCH
            ) {
                throw SecurityException("Untrusted sign-in caller")
            }
            scope.launch {
                val error = try {
                    applicationContext.container.customerSignin.local(requestId, nonce)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Never return API responses, credentials or cryptographic material over IPC.
                    "Could not verify sign-in on this phone. Unlock it or use account recovery."
                }
                try {
                    callback.onResult(error == null, error.orEmpty())
                } catch (_: RemoteException) {
                    // The requester went away. Its unclaimed challenge expires on the backend.
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CUSTOMER = "pk.qist.customer"
        const val PERMISSION = "pk.qist.device.permission.CUSTOMER_AUTH"
    }
}
