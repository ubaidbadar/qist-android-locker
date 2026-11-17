package pk.qist.device.apk

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pk.qist.device.WorkScheduler
import pk.qist.device.data.DeviceState
import pk.qist.device.data.Enrolment

/** The optional APK lifecycle. Enrollment/check-ins never wait for this object to download anything. */
class ManagedApkInstaller(
    private val context: Context,
    private val store: DataStore<DeviceState>,
    private val scheduler: WorkScheduler,
    private val download: ApkDownload,
    private val io: CoroutineDispatcher,
    private val target: InstallTarget = InstallTarget.CUSTOMER
) {
    private val mutex = Mutex()
    private val verifier = ApkVerifier(context.packageManager, target.packageName)
    private val installer = context.packageManager.packageInstaller
    private val directory = File(context.noBackupFilesDir, target.directory)

    /** True asks WorkManager for a retry; permanent problems wait for a corrected release. */
    suspend fun run(): Boolean = withContext(io) {
        mutex.withLock { install() }
    }

    private suspend fun install(): Boolean {
        val state = store.data.first()
        if (state.enrolment !is Enrolment.Enrolled) return false
        val app = target.state(state)
        val release = app.release ?: return false
        if (System.currentTimeMillis() < app.notBefore ||
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVICE_PROVISIONED, 0) == 0
        ) {
            report(release, AppInstallReport(detailCode = "waiting_for_setup"))
            return true
        }
        if (!context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)) {
            report(release, AppInstallReport("needs_attention", detailCode = "not_device_owner"), blocked = true)
            return false
        }
        if (target == InstallTarget.LOCKER && state.lockState == pk.qist.device.data.LockState.LOCKED) {
            report(release, AppInstallReport(detailCode = "waiting_for_unlock"))
            return true
        }
        if (app.blockedSha256 == release.sha256) return false
        try {
            verifier.validateRelease(release)
        } catch (_: IllegalArgumentException) {
            report(release, AppInstallReport("failed", detailCode = "invalid_release"), blocked = true)
            return false
        }
        if (release.minSdk > Build.VERSION.SDK_INT) {
            report(release, AppInstallReport("needs_attention", detailCode = "unsupported_android"), blocked = true)
            return false
        }
        try {
            val version = verifier.installedVersion(release)
            if (version != null && version >= release.versionCode) {
                clearSession()
                directory.deleteRecursively()
                report(release, AppInstallReport("installed", version))
                return false
            }
        } catch (_: IllegalArgumentException) {
            report(release, AppInstallReport("failed", detailCode = "signature_mismatch"), blocked = true)
            return false
        }
        if (app.sessionId != null) {
            when (app.sessionStatus) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    clearSession()
                    report(release, AppInstallReport("needs_attention", detailCode = "user_action"), blocked = true)
                    return false
                }

                PackageInstaller.STATUS_FAILURE_STORAGE -> {
                    clearSession()
                    report(release, AppInstallReport("needs_attention", detailCode = "storage"))
                    return true
                }

                null -> Unit

                PackageInstaller.STATUS_SUCCESS -> clearSession()

                else -> {
                    clearSession()
                    report(release, AppInstallReport("failed", detailCode = "install_failed"), blocked = true)
                    return false
                }
            }
            val session = installer.getSessionInfo(app.sessionId)
            if (app.sessionStatus == null && session != null &&
                System.currentTimeMillis() - app.sessionStartedAt < SESSION_TIMEOUT_MILLIS
            ) {
                return true
            }
            clearSession()
        }
        directory.mkdirs()
        val file = File(directory, "${release.sha256}.apk.part")
        directory.listFiles()?.filter { it != file }?.forEach { it.delete() }
        if (!reserveSpace((release.sizeBytes - file.length()).coerceAtLeast(0) + release.sizeBytes)) {
            report(release, AppInstallReport("needs_attention", detailCode = "storage"))
            return true
        }
        try {
            report(release, AppInstallReport("downloading"))
            if (!download.download(release, file)) {
                target.continueDownload(scheduler, release.wifiOnly)
                return false
            }
            verifier.verify(file, release)
            currentCoroutineContext().ensureActive()
            val current = store.data.first()
            if (current.enrolment != state.enrolment || target.state(current).release != release) return true
            commit(file, release)
            return true // Reconcile even if Android's result callback is lost after process death.
        } catch (_: IOException) {
            clearSession()
            report(release, AppInstallReport(detailCode = "network"))
            return true
        } catch (_: IllegalArgumentException) {
            file.delete()
            clearSession()
            report(release, AppInstallReport("failed", detailCode = "invalid_release"), blocked = true)
            return false
        } catch (_: SecurityException) {
            file.delete()
            clearSession()
            report(release, AppInstallReport("needs_attention", detailCode = "install_failed"), blocked = true)
            return false
        }
    }

    private suspend fun commit(file: File, release: ApkRelease) {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(release.packageName)
            setSize(release.sizeBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        store.updateData {
            target.replace(
                it,
                target.state(it).copy(
                    sessionId = id,
                    sessionRelease = release,
                    sessionStatus = null,
                    sessionStartedAt = System.currentTimeMillis()
                )
            )
        }
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, release.sizeBytes).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            currentCoroutineContext().ensureActive()
            val current = store.data.first()
            if (current.enrolment !is Enrolment.Enrolled || target.state(current).release != release ||
                (target == InstallTarget.LOCKER && current.lockState == pk.qist.device.data.LockState.LOCKED)
            ) {
                session.abandon()
                return
            }
            val intent = Intent(context, target.receiver())
                .setData("qist-install://${target.name.lowercase()}/$id".toUri())
            val flags =
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, id, intent, flags)
            report(release, AppInstallReport("installing"))
            session.commit(callback.intentSender)
        }
    }

    suspend fun complete(sessionId: Int, status: Int) = withContext(io) {
        // A receiver must finish quickly, even if a download is in progress. Verification belongs to the worker.
        val state = store.updateData {
            if (it.enrolment !is Enrolment.Enrolled || target.state(it).sessionId != sessionId) {
                it
            } else {
                target.replace(it, target.state(it).copy(sessionStatus = status))
            }
        }
        val app = target.state(state)
        if (state.enrolment is Enrolment.Enrolled && app.sessionId == sessionId) {
            target.schedule(scheduler, app.release?.wifiOnly ?: true)
        }
    }

    private suspend fun clearSession() {
        val id = target.state(store.data.first()).sessionId
        if (id != null && installer.mySessions.any { it.sessionId == id }) installer.abandonSession(id)
        store.updateData {
            target.replace(
                it,
                target.state(it).copy(
                    sessionId = null,
                    sessionRelease = null,
                    sessionStatus = null,
                    sessionStartedAt = 0
                )
            )
        }
    }

    private fun reserveSpace(bytes: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return directory.usableSpace >= bytes
        val storage = context.getSystemService(StorageManager::class.java)
        return try {
            val volume = storage.getUuidForPath(directory)
            if (storage.getAllocatableBytes(volume) < bytes) {
                false
            } else {
                storage.allocateBytes(volume, bytes)
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun report(release: ApkRelease, value: AppInstallReport, blocked: Boolean = false) {
        store.updateData {
            if (it.enrolment !is Enrolment.Enrolled || target.state(it).release != release) {
                it
            } else {
                target.replace(
                    it,
                    target.state(it).copy(
                        report = value,
                        blockedSha256 = if (blocked) release.sha256 else null
                    )
                )
            }
        }
    }

    private companion object {
        const val SESSION_TIMEOUT_MILLIS = 30L * 60 * 1000
    }
}
