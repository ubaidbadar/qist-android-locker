package pk.qist.device.auth

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CustomerSigninServiceTest {
    @Test
    fun `sign-in review is not a launcher app and requires a signed caller`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, SigninActivity::class.java)
        val info = context.packageManager.getActivityInfo(component, 0)
        assertTrue(info.exported)
        assertEquals("pk.qist.device.permission.CUSTOMER_AUTH", info.permission)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val activities = context.packageManager.queryIntentActivities(launcher, 0)
        assertFalse(activities.any { it.activityInfo.name == component.className })
    }

    @Test
    fun `an unauthenticated Binder caller cannot initiate signing or obtain a success callback`() {
        val controller = Robolectric.buildService(CustomerSigninService::class.java).create()
        try {
            val service = ICustomerSignin.Stub.asInterface(controller.get().onBind(Intent()))
            var called = false
            val callback = object : ICustomerSigninCallback.Stub() {
                override fun onResult(approved: Boolean, error: String) {
                    called = true
                }
            }
            assertThrows(SecurityException::class.java) {
                service.authenticate("a".repeat(24), "n".repeat(43), callback)
            }
            assertFalse(called)
        } finally {
            controller.destroy()
        }
    }
}
