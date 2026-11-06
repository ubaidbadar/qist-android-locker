package pk.qist.device.customer

import kotlinx.serialization.Serializable

@Serializable
data class CustomerAppRelease(
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val wifiOnly: Boolean = true
)

@Serializable
data class CustomerAppReport(
    val status: String = "pending",
    val versionCode: Long? = null,
    val detailCode: String? = null
)

/** Persisted with the encrypted device state, independently of enrollment and lock state. */
@Serializable
data class CustomerAppState(
    val release: CustomerAppRelease? = null,
    val notBefore: Long = 0,
    val report: CustomerAppReport = CustomerAppReport(),
    val blockedSha256: String? = null,
    val sessionId: Int? = null,
    val sessionRelease: CustomerAppRelease? = null,
    val sessionStatus: Int? = null,
    val sessionStartedAt: Long = 0
) {
    companion object {
        const val PACKAGE = "pk.qist.customer"
        const val SETUP_DELAY_MILLIS = 15L * 60 * 1000
    }
}
