package pk.qist.device.data

import kotlinx.serialization.Serializable
import pk.qist.device.customer.CustomerAppState

/** Everything the app remembers, in one small encrypted file. Changed only through `DataStore.updateData`. */
@Serializable
data class DeviceState(
    val customerApp: CustomerAppState = CustomerAppState(),
    val enrolment: Enrolment = Enrolment.NotProvisioned,
    val lockState: LockState = LockState.UNLOCKED,
    val pushToken: String = "",
    val lockScreen: LockScreen? = null,
    val checkInSeconds: Long = DEFAULT_CHECK_IN_SECONDS
) {
    companion object {
        /** Matches the server's DEVICE_CHECKIN_SECONDS, and WorkManager's minimum period. */
        const val DEFAULT_CHECK_IN_SECONDS = 15L * 60
    }
}
