package pk.qist.device.data

import kotlinx.serialization.Serializable
import pk.qist.device.customer.CustomerAppRelease

@Serializable
data class CheckInResponse(
    val lockState: LockState,
    val lockScreen: LockScreen,
    val commands: List<CommandDto>,
    val checkInSeconds: Long,
    val customerAppRelease: CustomerAppRelease? = null
)
