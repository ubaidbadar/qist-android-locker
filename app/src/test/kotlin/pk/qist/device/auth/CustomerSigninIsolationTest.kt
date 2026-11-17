package pk.qist.device.auth

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pk.qist.device.data.DeviceState

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CustomerSigninIsolationTest {
    @Test
    fun `an optional sign-in storage failure cannot fail a completed finance check-in`() = runBlocking {
        val store = object : DataStore<DeviceState> {
            override val data: Flow<DeviceState> = flow { throw IOException("Storage unavailable") }
            override suspend fun updateData(transform: suspend (DeviceState) -> DeviceState): DeviceState =
                error("No write expected")
        }
        val manager = CustomerSignin(
            ApplicationProvider.getApplicationContext(),
            store,
            OkHttpClient.Builder().addInterceptor { error("No network call expected") }.build(),
            "https://example.com/api/v1/".toHttpUrl(),
            Json
        )
        manager.refresh()
        assertTrue(manager.requests.value.isEmpty())
        assertNotNull(manager.unavailable.value)
    }
}
