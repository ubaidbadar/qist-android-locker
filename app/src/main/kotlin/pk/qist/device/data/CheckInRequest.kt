package pk.qist.device.data

import kotlinx.serialization.Serializable
import pk.qist.device.customer.CustomerAppReport

@Serializable
data class CheckInRequest(
    val appVersion: String,
    val lockState: LockState,
    val pushToken: String? = null,
    val customerApp: CustomerAppReport? = null
)
