package pk.qist.device.checkin

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pk.qist.device.AppInfo
import pk.qist.device.WorkScheduler
import pk.qist.device.apk.AppInstallReport
import pk.qist.device.apk.AppInstallState
import pk.qist.device.data.AckRequest
import pk.qist.device.data.ApiResult
import pk.qist.device.data.CheckInRequest
import pk.qist.device.data.CheckInResponse
import pk.qist.device.data.DeviceApi
import pk.qist.device.data.DeviceState
import pk.qist.device.data.Enrolment
import pk.qist.device.data.LockState
import pk.qist.device.deviceadmin.DevicePolicy
import pk.qist.device.deviceadmin.PolicyResult

/**
 * One check-in: report in, save the lock screen, carry out each command in order and acknowledge it. A command
 * whose acknowledgement does not get through stays "delivered" on the server and comes back next time; carrying
 * it out again is harmless, so nothing has to be queued on the phone.
 */
class CheckIn(
    private val api: DeviceApi,
    private val store: DataStore<DeviceState>,
    private val policy: DevicePolicy,
    private val runner: CommandRunner,
    private val scheduler: WorkScheduler,
    private val appInfo: AppInfo,
    /** Only enabled in the one-device emergency build; never enabled in normal production builds. */
    private val emergencyResetOnUnauthorized: Boolean = false
) {
    /** The periodic and the on-demand check-in may start together; they take turns. */
    private val mutex = Mutex()

    suspend fun run(): CheckInOutcome = mutex.withLock {
        val state = store.data.first()
        val enrolled = when (val enrolment = state.enrolment) {
            is Enrolment.Enrolled -> enrolment

            is Enrolment.Releasing -> return confirmRelease(enrolment)

            Enrolment.Revoked -> {
                // The previous production build may already have stopped check-ins after its first 401.
                // The recovery APK must still be able to clear policies when it is installed over that state.
                if (emergencyResetOnUnauthorized) return emergencyRelease()
                return CheckInOutcome.NotEnrolled
            }

            else -> return CheckInOutcome.NotEnrolled
        }
        val request =
            CheckInRequest(
                appInfo.appVersion,
                state.lockState,
                state.pushToken.takeIf {
                    it.isNotBlank()
                },
                state.customerApp.report
            )
        when (val result = api.checkIn(enrolled.credential, request)) {
            is ApiResult.Success -> apply(enrolled.credential, state, result.value)
            ApiResult.Unauthorized -> standDown()
            ApiResult.NetworkError, is ApiResult.ServerError -> CheckInOutcome.RetryLater
        }
    }

    private suspend fun apply(credential: String, before: DeviceState, response: CheckInResponse): CheckInOutcome {
        store.updateData { it.copy(lockScreen = response.lockScreen, checkInSeconds = response.checkInSeconds) }
        if (response.checkInSeconds != before.checkInSeconds) scheduler.scheduleCheckIns(response.checkInSeconds)
        policy.protect()

        // Only store metadata here. APK download/installation is a separate delayed job, after commands finish.
        store.updateData {
            val previous = it.customerApp
            val release = response.customerAppRelease
            val changed = previous.release != release
            it.copy(
                customerApp = previous.copy(
                    release = release,
                    notBefore = previous.notBefore.takeIf { time -> time > 0 }
                        ?: (System.currentTimeMillis() + AppInstallState.SETUP_DELAY_MILLIS),
                    report = if (release ==
                        null
                    ) {
                        AppInstallReport("not_configured")
                    } else if (changed) {
                        AppInstallReport()
                    } else {
                        previous.report
                    },
                    blockedSha256 = if (changed) null else previous.blockedSha256
                )
            )
        }

        if (response.commands.isEmpty()) {
            reconcile(before.lockState, response.lockState)
            scheduleCustomerApp(before.customerApp.release != response.customerAppRelease)
            return CheckInOutcome.Done
        }
        for (command in response.commands.map { it.toCommand() }) {
            val outcome = carryOut(credential, command)
            if (outcome != CheckInOutcome.Done) return outcome
        }
        scheduleCustomerApp(before.customerApp.release != response.customerAppRelease)
        return CheckInOutcome.Done
    }

    private suspend fun scheduleCustomerApp(replace: Boolean) {
        val state = store.data.first()
        val app = state.customerApp
        val release = app.release ?: return
        if (state.enrolment !is Enrolment.Enrolled) return
        scheduler.scheduleCustomerApp(release.wifiOnly, app.notBefore - System.currentTimeMillis(), replace)
    }

    private suspend fun carryOut(credential: String, command: Command): CheckInOutcome {
        val result = runner.run(command)
        if (command is Command.Release && result == PolicyResult.Applied) {
            val enrolled = store.data.first().enrolment as? Enrolment.Enrolled ?: return CheckInOutcome.NotEnrolled
            val releasing = Enrolment.Releasing(enrolled.deviceId, credential, command.id)
            store.updateData { it.copy(enrolment = releasing) }
            return confirmRelease(releasing)
        }
        val ack = when (result) {
            PolicyResult.Applied -> AckRequest(AckRequest.Outcome.APPLIED)
            is PolicyResult.Failed -> AckRequest(AckRequest.Outcome.FAILED, result.reason)
        }
        return when (api.acknowledge(credential, command.id, ack)) {
            ApiResult.Unauthorized -> standDown()

            is ApiResult.Success -> CheckInOutcome.Done

            // Not confirmed: the server delivers the command again next time.
            ApiResult.NetworkError, is ApiResult.ServerError -> CheckInOutcome.Done
        }
    }

    /**
     * Tells the server the release is done, then finishes it. A 409 means the server already has the confirmation
     * (an earlier attempt got through), and a 401 means it no longer knows the phone; either way the shop already
     * decided to release, so the phone is handed back. Anything else is retried on the next run.
     */
    private suspend fun confirmRelease(releasing: Enrolment.Releasing): CheckInOutcome {
        val ack = AckRequest(AckRequest.Outcome.APPLIED)
        return when (val result = api.acknowledge(releasing.credential, releasing.commandId, ack)) {
            is ApiResult.Success, ApiResult.Unauthorized -> finishRelease()
            is ApiResult.ServerError -> if (result.status == CONFLICT) finishRelease() else CheckInOutcome.RetryLater
            ApiResult.NetworkError -> CheckInOutcome.RetryLater
        }
    }

    /**
     * With no commands waiting, the server's lock state is the truth (it only changes when the phone confirms a
     * command). This repairs a phone that lost its local state.
     */
    private suspend fun reconcile(local: LockState, server: LockState) {
        if (local == server) return
        when (server) {
            LockState.LOCKED -> runner.lock()
            LockState.UNLOCKED -> runner.unlock()
        }
    }

    /**
     * The server no longer knows this phone (for example, its test database was deleted).
     * Normal builds only unlock and stand down. The explicitly opted-in emergency build instead removes every
     * Qist-owned restriction so the owner can factory-reset this one recovery device.
     *
     * Keep the Device Owner registration itself: Android's factory-reset flow clears it as part of the reset.
     * Do not call the deprecated, test-only clearDeviceOwnerApp() API here.
     */
    private suspend fun standDown(): CheckInOutcome {
        if (emergencyResetOnUnauthorized) return emergencyRelease()

        runner.unlock()
        store.updateData { it.copy(enrolment = Enrolment.Revoked) }
        scheduler.cancelCheckIns()
        return CheckInOutcome.StoodDown
    }

    /**
     * Emergency-only local hand-back. DevicePolicy.release() verifies that Qist's restrictions are gone before
     * local credentials are discarded. It is safe to retry after a process interruption.
     */
    private suspend fun emergencyRelease(): CheckInOutcome = when (policy.release()) {
        PolicyResult.Applied -> {
            store.updateData { DeviceState(enrolment = Enrolment.Revoked) }
            scheduler.cancelCheckIns()
            CheckInOutcome.StoodDown
        }

        is PolicyResult.Failed -> CheckInOutcome.RetryLater
    }

    /**
     * The server has the release confirmation: forget the credential, give up Device Owner, and only then stop
     * check-ins. Cancelling check-ins stops the very job running this code, so it must come last.
     */
    private suspend fun finishRelease(): CheckInOutcome = when (policy.relinquish()) {
        PolicyResult.Applied -> {
            // Persist completion only after Android confirms this package is no longer Device Owner.
            store.updateData { DeviceState(enrolment = Enrolment.Released) }
            // Cancelling check-ins stops the very job running this code, so it must come last.
            scheduler.cancelCheckIns()
            CheckInOutcome.Released
        }

        is PolicyResult.Failed -> {
            // Keep Enrolment.Releasing and periodic check-ins so the hand-back can be retried safely.
            CheckInOutcome.RetryLater
        }
    }

    private companion object {
        const val CONFLICT = 409
    }
}
