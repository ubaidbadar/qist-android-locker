package pk.qist.device

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import pk.qist.device.push.recordPushToken

/** Holds the one [AppContainer]. Everything in it is created on first use, so a cold start does almost nothing. */
class QistApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Cancel installation work persisted by older locker versions when upgrading.
        WorkManager.getInstance(this).cancelUniqueWork("customer-app-install")
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> recordPushToken(token) }
    }
}

/** The app's [AppContainer], from any component that has a Context. */
val Context.container: AppContainer
    get() = (applicationContext as QistApp).container
