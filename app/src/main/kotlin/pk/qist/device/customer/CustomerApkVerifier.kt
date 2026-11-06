package pk.qist.device.customer

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Whole-file hashes pin the approved signed artifact on every supported Android version. */
class CustomerApkVerifier(private val packages: PackageManager) {
    fun validateRelease(release: CustomerAppRelease) {
        val url = release.apkUrl.toHttpUrlOrNull()
        require(release.packageName == CustomerAppState.PACKAGE)
        require(release.versionCode in 1..2_100_000_000L && release.minSdk >= 24)
        require(release.sizeBytes in 1..200L * 1024 * 1024)
        require(HASH.matches(release.sha256) && HASH.matches(release.signingCertificateSha256))
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
    }

    fun installedVersion(release: CustomerAppRelease): Long? = try {
        val info = packages.getPackageInfo(release.packageName, 0)
        val signatures = PackageInfoCompat.getSignatures(packages, release.packageName)
        require(signatures.size == 1 && digest(signatures.single().toByteArray()) == release.signingCertificateSha256) {
            "Unexpected installed signing certificate"
        }
        PackageInfoCompat.getLongVersionCode(info)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun verify(file: File, release: CustomerAppRelease) {
        require(file.length() == release.sizeBytes)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            var count = input.read(buffer)
            while (count >= 0) {
                hash.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        require(hex(hash.digest()) == release.sha256) { "Unexpected APK hash" }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val info = requireNotNull(packages.getPackageArchiveInfo(file.path, flags))
        require(
            info.packageName == release.packageName && PackageInfoCompat.getLongVersionCode(info) == release.versionCode
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signatures = requireNotNull(info.signingInfo).apkContentsSigners
            require(
                signatures.size == 1 && digest(signatures.single().toByteArray()) == release.signingCertificateSha256
            )
        }
        // Android 7/8: no deprecated GET_SIGNATURES. The release tool verifies the certificate before publishing
        // the pinned whole-file hash; PackageInstaller verifies APK signatures and update compatibility as well.
    }

    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        val HASH = Regex("[a-f0-9]{64}")
    }
}
