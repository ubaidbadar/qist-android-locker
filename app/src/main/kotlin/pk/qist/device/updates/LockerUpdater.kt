package pk.qist.device.updates

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.datastore.core.DataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import pk.qist.device.apk.ApkRelease
import pk.qist.device.apk.ApkVerifier
import pk.qist.device.apk.AppInstallReport
import pk.qist.device.apk.ManagedApkInstaller
import pk.qist.device.data.DeviceState
import pk.qist.device.data.Enrolment

/** APK download and signature checks run in their own worker, independently of finance commands. */
class LockerUpdater(
    private val context: Context,
    private val store: DataStore<DeviceState>,
    private val source: CloudflareApkSource,
    private val url: HttpUrl,
    private val installer: ManagedApkInstaller,
    private val io: CoroutineDispatcher
) {
    private val mutex = Mutex()
    private val directory = File(context.noBackupFilesDir, "locker-apk")

    suspend fun run(): Boolean = withContext(io) {
        mutex.withLock {
            val before = store.data.first()
            if (before.enrolment !is Enrolment.Enrolled ||
                !context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
            ) {
                return@withLock false
            }
            var remote: RemoteApk? = null
            try {
                val remembered = before.lockerUpdate.takeIf { it.sourceUrl == url.toString() }
                remote = source.probe(url, remembered?.checkedETag)
                if (remote != null) {
                    directory.mkdirs()
                    val partial = File(directory, "download.part")
                    if (remembered?.partialETag != remote.etag ||
                        remembered.partialSize != remote.size
                    ) {
                        partial.delete()
                    }
                    store.updateData {
                        if (it.enrolment != before.enrolment) {
                            it
                        } else {
                            it.copy(
                                lockerApp = it.lockerApp.copy(release = null),
                                lockerUpdate = LockerUpdateState(
                                    url.toString(),
                                    partialETag = remote.etag,
                                    partialSize = remote.size,
                                    status = "downloading"
                                )
                            )
                        }
                    }
                    if (!source.download(remote, partial)) return@withLock true
                    currentCoroutineContext().ensureActive()
                    val release = inspect(partial)
                    if (release == null) {
                        partial.delete()
                        store.updateData { state ->
                            if (state.enrolment != before.enrolment) {
                                state
                            } else {
                                state.copy(
                                    lockerApp = state.lockerApp.copy(
                                        release = null,
                                        report = AppInstallReport("installed")
                                    ),
                                    lockerUpdate = LockerUpdateState(
                                        url.toString(),
                                        checkedETag = remote.etag,
                                        status = "up_to_date"
                                    )
                                )
                            }
                        }
                        return@withLock false
                    }
                    val complete = File(directory, "${release.sha256}.apk.part")
                    if (!partial.renameTo(complete)) throw IOException("Could not retain the verified update")
                    store.updateData {
                        if (it.enrolment != before.enrolment) {
                            it
                        } else {
                            it.copy(
                                lockerApp = it.lockerApp.copy(
                                    release = release,
                                    blockedSha256 = null,
                                    report = AppInstallReport()
                                ),
                                lockerUpdate = LockerUpdateState(
                                    url.toString(),
                                    checkedETag = remote.etag,
                                    status = "verified"
                                )
                            )
                        }
                    }
                }
                val retry = installer.run()
                retry && store.data.first().lockerApp.report.detailCode != "waiting_for_unlock"
            } catch (_: IOException) {
                true
            } catch (_: IllegalArgumentException) {
                reject(remote?.etag, before.enrolment)
                false
            } catch (_: SecurityException) {
                reject(remote?.etag, before.enrolment)
                false
            }
        }
    }

    private fun inspect(file: File): ApkRelease? {
        val packages = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val info = requireNotNull(packages.getPackageArchiveInfo(file.path, flags))
        require(info.packageName == context.packageName)
        val installed = packages.getPackageInfo(context.packageName, 0)
        val signatures = PackageInfoCompat.getSignatures(packages, context.packageName)
        require(signatures.size == 1) { "A single installed Locker signer is required" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val release = ApkRelease(
            context.packageName,
            PackageInfoCompat.getLongVersionCode(info),
            requireNotNull(info.applicationInfo).minSdkVersion,
            url.toString(),
            file.length(),
            hex(digest.digest()),
            hex(MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())),
            wifiOnly = false
        )
        val verifier = ApkVerifier(packages, context.packageName)
        verifier.validateRelease(release)
        verifier.verify(file, release)
        // A same/older published APK is remembered but can never downgrade or reinstall this device.
        if (release.versionCode <= PackageInfoCompat.getLongVersionCode(installed)) return null
        require(release.minSdk <= Build.VERSION.SDK_INT) { "Unsupported Android version" }
        return release
    }

    private suspend fun reject(etag: String?, enrolled: Enrolment) {
        File(directory, "download.part").delete()
        store.updateData {
            if (it.enrolment != enrolled) {
                it
            } else {
                it.copy(
                    lockerApp = it.lockerApp.copy(
                        release = null,
                        report = AppInstallReport(
                            "needs_attention",
                            detailCode = "invalid_release"
                        )
                    ),
                    lockerUpdate = LockerUpdateState(
                        url.toString(),
                        checkedETag = etag,
                        status = "not_newer_or_invalid"
                    )
                )
            }
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
