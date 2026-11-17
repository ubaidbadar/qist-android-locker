package pk.qist.device.data

import kotlinx.serialization.Serializable
import pk.qist.device.apk.AppInstallReport

@Serializable
data class CheckInRequest(
    val appVersion: String,
    val lockState: LockState,
    val pushToken: String? = null,
    val customerApp: AppInstallReport? = null
)
