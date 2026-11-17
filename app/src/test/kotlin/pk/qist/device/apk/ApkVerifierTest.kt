package pk.qist.device.apk

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ApkVerifierTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packages = context.packageManager
    private val signer = Signature("1234")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun info(name: String, signer: Signature) = PackageInfo().apply {
        packageName = name
        setLongVersionCode(4)
        signingInfo = SigningInfo().also {
            shadowOf(it).setSignatures(arrayOf(signer))
            shadowOf(it).setPastSigningCertificates(arrayOf(signer))
        }
    }
    private fun release(file: File) = ApkRelease(
        "pk.qist.device",
        4,
        24,
        "https://example.com/qist.apk",
        file.length(),
        hash(file.readBytes()),
        hash(signer.toByteArray())
    )

    @Test fun `whole file hash package version and signer must all match`() {
        val file = temporary.newFile().apply { writeText("signed APK bytes provided by archive parser fixture") }
        val release = release(file)
        val verifier = ApkVerifier(packages, "pk.qist.device")
        shadowOf(packages).setPackageArchiveInfo(file.path, info("pk.qist.device", signer))
        verifier.verify(file, release)
        for (invalid in listOf(
            info("other.package", signer),
            info("pk.qist.device", Signature("5678")),
            info("pk.qist.device", signer).apply { setLongVersionCode(5) }
        )) {
            shadowOf(packages).setPackageArchiveInfo(file.path, invalid)
            try {
                verifier.verify(file, release)
                throw AssertionError("Expected archive rejection")
            } catch (
                _: IllegalArgumentException
            ) { /* Matching hash alone does not establish identity. */ }
        }
        file.appendText("corruption")
        try {
            verifier.verify(file, release)
            throw AssertionError("Expected corrupt file rejection")
        } catch (
            _: IllegalArgumentException
        ) { /* Never commit damaged bytes. */ }
    }

    @Test fun `installed signing identity cannot be replaced by release metadata`() {
        val file = temporary.newFile().apply { writeText("apk") }
        val release = release(file)
        shadowOf(packages).installPackage(info("pk.qist.device", signer))
        val verifier = ApkVerifier(packages, "pk.qist.device")
        assertEquals(4L, verifier.installedVersion(release))
        try {
            verifier.installedVersion(release.copy(signingCertificateSha256 = hash(Signature("5678").toByteArray())))
            throw AssertionError("Expected installed signer rejection")
        } catch (_: IllegalArgumentException) { /* A download cannot authorize a signer change. */ }
    }
}
