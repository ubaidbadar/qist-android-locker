package pk.qist.device.auth

import android.app.Application
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CustomerSigninServiceTest {
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
