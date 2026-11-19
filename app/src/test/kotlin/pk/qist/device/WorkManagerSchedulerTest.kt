package pk.qist.device

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The emergency APK must not wait for connectivity to process a locally revoked enrollment. */
class WorkManagerSchedulerTest {
    @Test
    fun `the emergency one-shot recovery work has no internet requirement`() {
        assertEquals(NetworkType.NOT_REQUIRED, immediateCheckInConstraints(emergency = true).requiredNetworkType)
    }

    @Test
    fun `normal one-shot check-ins still require connectivity`() {
        assertEquals(NetworkType.CONNECTED, immediateCheckInConstraints(emergency = false).requiredNetworkType)
    }
}
