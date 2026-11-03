package pk.qist.device

import android.app.Application
import android.content.Context

/** Holds the one [AppContainer]. Everything in it is created on first use, so a cold start does almost nothing. */
class QistApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** The app's [AppContainer], from any component that has a Context. */
val Context.container: AppContainer
    get() = (applicationContext as QistApp).container
