package pk.qist.device.updates

import kotlinx.serialization.Serializable

/** ETags identify a Cloudflare object, never its signing authority. */
@Serializable
data class LockerUpdateState(
    val sourceUrl: String? = null,
    val checkedETag: String? = null,
    val partialETag: String? = null,
    val partialSize: Long = 0,
    val status: String = "pending"
)
