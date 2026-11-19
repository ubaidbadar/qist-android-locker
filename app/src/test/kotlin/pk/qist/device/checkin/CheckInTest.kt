package pk.qist.device.checkin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import pk.qist.device.AppInfo
import pk.qist.device.apk.ApkRelease
import pk.qist.device.apk.AppInstallReport
import pk.qist.device.apk.AppInstallState
import pk.qist.device.data.AckRequest
import pk.qist.device.data.AckRequest.Outcome.APPLIED
import pk.qist.device.data.AckRequest.Outcome.FAILED
import pk.qist.device.data.ApiResult
import pk.qist.device.data.CheckInResponse
import pk.qist.device.data.CommandDto
import pk.qist.device.data.DeviceState
import pk.qist.device.data.Enrolment
import pk.qist.device.data.LockScreen
import pk.qist.device.data.LockState
import pk.qist.device.deviceadmin.PolicyResult
import pk.qist.device.fakes.FakeDeviceApi
import pk.qist.device.fakes.FakeDevicePolicy
import pk.qist.device.fakes.InMemoryStore
import pk.qist.device.fakes.RecordingScheduler

class CheckInTest {
    @Test
    fun `customer download is deferred while a lock is still handled and acknowledged`() = runTest {
        val release =
            ApkRelease(
                AppInstallState.CUSTOMER_PACKAGE,
                2,
                24,
                "https://example.com/customer.apk",
                10,
                "a".repeat(64),
                "b".repeat(64)
            )
        val store = InMemoryStore(
            enrolled.copy(
                customerApp = AppInstallState(
                    notBefore =
                        System.currentTimeMillis() + 900_000
                )
            )
        )
        api.checkInResult =
            ApiResult.Success(
                CheckInResponse(LockState.UNLOCKED, LockScreen(), listOf(CommandDto("lock1", "lock")), 900, release)
            )

        assertEquals(CheckInOutcome.Done, checkIn(store).run())
        assertEquals(LockState.LOCKED, store.value.lockState)
        assertEquals(listOf("lock1" to AckRequest(APPLIED)), api.acks)
        assertEquals(listOf("customer app wifi=true delayed=true"), scheduler.requests)
        assertEquals(AppInstallReport(), store.value.customerApp.report)
    }
    private val api = FakeDeviceApi()
    private val policy = FakeDevicePolicy()
    private val scheduler = RecordingScheduler()
    private val enrolled = DeviceState(enrolment = Enrolment.Enrolled("d1", "d1.secret"), checkInSeconds = 900)

    private fun checkIn(store: InMemoryStore, emergencyResetOnUnauthorized: Boolean = false) =
        CheckIn(
            api,
            store,
            policy,
            CommandRunner(store, policy),
            scheduler,
            AppInfo("1.0", "14"),
            emergencyResetOnUnauthorized
        )

    private fun respond(
        vararg commands: Pair<String, String>,
        lockState: LockState = LockState.UNLOCKED,
        seconds: Long = 900
    ) {
        api.checkInResult = ApiResult.Success(
            CheckInResponse(
                lockState = lockState,
                lockScreen = LockScreen(locked = lockState == LockState.LOCKED, amountDue = 150_000, message = "Pay"),
                commands = commands.map { (id, type) -> CommandDto(id, type) },
                checkInSeconds = seconds
            )
        )
    }

    @Test
    fun `a phone that is not enrolled does not call the server`() = runTest {
        assertEquals(CheckInOutcome.NotEnrolled, checkIn(InMemoryStore()).run())
        assertEquals(0, api.checkIns.size)
    }

    @Test
    fun `check-in sends the credential and the phone's lock state, and saves the lock screen`() = runTest {
        val store = InMemoryStore(enrolled)
        respond()

        assertEquals(CheckInOutcome.Done, checkIn(store).run())

        val (credential, request) = api.checkIns.single()
        assertEquals("d1.secret", credential)
        assertEquals(LockState.UNLOCKED, request.lockState)
        assertEquals(150_000L, store.value.lockScreen?.amountDue)
        assertEquals(listOf("protect"), policy.calls)
    }

    @Test
    fun `a lock command locks the phone and is acknowledged as applied`() = runTest {
        val store = InMemoryStore(enrolled)
        respond("c1" to "lock")

        checkIn(store).run()

        assertEquals(LockState.LOCKED, store.value.lockState)
        assertEquals(listOf("protect", "lock"), policy.calls)
        assertEquals(listOf("c1" to AckRequest(APPLIED)), api.acks)
    }

    @Test
    fun `an unlock command unlocks the phone and keeps the protections`() = runTest {
        val store = InMemoryStore(enrolled.copy(lockState = LockState.LOCKED))
        respond("c2" to "unlock", lockState = LockState.LOCKED)

        checkIn(store).run()

        assertEquals(LockState.UNLOCKED, store.value.lockState)
        assertEquals(listOf("protect", "unlock"), policy.calls)
        assertEquals(listOf("c2" to AckRequest(APPLIED)), api.acks)
    }

    @Test
    fun `commands run in the order the server sent them`() = runTest {
        val store = InMemoryStore(enrolled)
        respond("c1" to "lock", "c2" to "unlock")

        checkIn(store).run()

        assertEquals(LockState.UNLOCKED, store.value.lockState)
        assertEquals(listOf("c1", "c2"), api.acks.map { it.first })
    }

    @Test
    fun `a lock Android refuses is acknowledged as failed and the phone stays unlocked`() = runTest {
        val store = InMemoryStore(enrolled)
        policy.result = PolicyResult.Failed("app is not device owner")
        respond("c1" to "lock")

        checkIn(store).run()

        assertEquals(LockState.UNLOCKED, store.value.lockState)
        assertEquals(listOf("c1" to AckRequest(FAILED, "app is not device owner")), api.acks)
    }

    @Test
    fun `an unknown command type is acknowledged as failed instead of ignored`() = runTest {
        respond("c9" to "wipe")

        checkIn(InMemoryStore(enrolled)).run()

        assertEquals(FAILED, api.acks.single().second.result)
        assertEquals(listOf("protect"), policy.calls)
    }

    @Test
    fun `release clears protections, confirms, then gives up device owner and stops check-ins`() = runTest {
        val store = InMemoryStore(enrolled.copy(lockState = LockState.LOCKED))
        respond("c3" to "release", lockState = LockState.LOCKED)

        assertEquals(CheckInOutcome.Released, checkIn(store).run())

        assertEquals(listOf("protect", "release", "relinquish"), policy.calls)
        assertEquals(listOf("c3" to AckRequest(APPLIED)), api.acks)
        assertEquals(DeviceState(enrolment = Enrolment.Released), store.value)
        assertEquals(listOf("cancel check-ins"), scheduler.requests)
    }

    @Test
    fun `release keeps device owner until the server has the confirmation`() = runTest {
        val store = InMemoryStore(enrolled)
        respond("c3" to "release")
        api.ackResult = ApiResult.NetworkError

        assertEquals(CheckInOutcome.RetryLater, checkIn(store).run())

        assertEquals(listOf("protect", "release"), policy.calls)
        assertEquals(Enrolment.Releasing("d1", "d1.secret", "c3"), store.value.enrolment)
    }

    @Test
    fun `an interrupted release is confirmed and finished on the next run, never protected again`() = runTest {
        val store = InMemoryStore(enrolled.copy(enrolment = Enrolment.Releasing("d1", "d1.secret", "c3")))

        assertEquals(CheckInOutcome.Released, checkIn(store).run())

        assertEquals(listOf("relinquish"), policy.calls)
        assertEquals(listOf("c3" to AckRequest(APPLIED)), api.acks)
        assertEquals(0, api.checkIns.size)
        assertEquals(Enrolment.Released, store.value.enrolment)
    }

    @Test
    fun `a release the server already confirmed (409) is finished`() = runTest {
        val store = InMemoryStore(enrolled.copy(enrolment = Enrolment.Releasing("d1", "d1.secret", "c3")))
        api.ackResult = ApiResult.ServerError(409)

        assertEquals(CheckInOutcome.Released, checkIn(store).run())
        assertEquals(listOf("relinquish"), policy.calls)
    }

    @Test
    fun `failed Device Owner removal stays pending and does not stop check-ins`() = runTest {
        val store = InMemoryStore(enrolled)
        policy.relinquishResult = PolicyResult.Failed("android refused the policy")
        respond("c4" to "release")

        assertEquals(CheckInOutcome.RetryLater, checkIn(store).run())

        assertEquals(listOf("protect", "release", "relinquish"), policy.calls)
        assertEquals(Enrolment.Releasing("d1", "d1.secret", "c4"), store.value.enrolment)
        assertEquals(emptyList<String>(), scheduler.requests)

        // A later retry succeeds after Android confirms that Device Owner has actually been cleared.
        policy.relinquishResult = PolicyResult.Applied
        assertEquals(CheckInOutcome.Released, checkIn(store).run())

        assertEquals(listOf("protect", "release", "relinquish", "relinquish"), policy.calls)
        assertEquals(Enrolment.Released, store.value.enrolment)
        assertEquals(listOf("cancel check-ins"), scheduler.requests)
        assertEquals(listOf("c4", "c4"), api.acks.map { it.first })
    }

    @Test
    fun `release completes even though stopping check-ins stops the running job`() = runTest {
        val store = InMemoryStore(enrolled)
        respond("c3" to "release")
        val stopping = RecordingScheduler(cancelStopsCaller = true)
        val checkIn = CheckIn(api, store, policy, CommandRunner(store, policy), stopping, AppInfo("1.0", "14"))

        runCatching { checkIn.run() }

        assertEquals(listOf("protect", "release", "relinquish"), policy.calls)
        assertEquals(Enrolment.Released, store.value.enrolment)
    }

    @Test
    fun `a 401 unlocks the phone, forgets nothing secret on screen, and stops check-ins without releasing`() = runTest {
        val store = InMemoryStore(enrolled.copy(lockState = LockState.LOCKED))
        api.checkInResult = ApiResult.Unauthorized

        assertEquals(CheckInOutcome.StoodDown, checkIn(store).run())

        assertEquals(Enrolment.Revoked, store.value.enrolment)
        assertEquals(LockState.UNLOCKED, store.value.lockState)
        assertEquals(listOf("unlock"), policy.calls)
        assertEquals(listOf("cancel check-ins"), scheduler.requests)
    }

    @Test
    fun `emergency 401 clears Qist policies before stopping check-ins`() = runTest {
        val store = InMemoryStore(enrolled.copy(lockState = LockState.LOCKED))
        api.checkInResult = ApiResult.Unauthorized

        assertEquals(CheckInOutcome.StoodDown, checkIn(store, emergencyResetOnUnauthorized = true).run())

        // DeviceState is reset only after DevicePolicy confirms every Qist-owned restriction was cleared.
        assertEquals(DeviceState(enrolment = Enrolment.Revoked), store.value)
        assertEquals(listOf("release"), policy.calls)
        assertEquals(listOf("cancel check-ins"), scheduler.requests)
    }

    @Test
    fun `emergency build clears policies when the previous build already marked the device revoked`() = runTest {
        val revoked = DeviceState(enrolment = Enrolment.Revoked, lockState = LockState.LOCKED)
        val store = InMemoryStore(revoked)

        assertEquals(CheckInOutcome.StoodDown, checkIn(store, emergencyResetOnUnauthorized = true).run())

        assertEquals(0, api.checkIns.size)
        assertEquals(DeviceState(enrolment = Enrolment.Revoked), store.value)
        assertEquals(listOf("release"), policy.calls)
        assertEquals(listOf("cancel check-ins"), scheduler.requests)
    }

    @Test
    fun `emergency 401 retries if Android refuses policy cleanup`() = runTest {
        val store = InMemoryStore(enrolled)
        val emergencyCheckIn = checkIn(store, emergencyResetOnUnauthorized = true)
        api.checkInResult = ApiResult.Unauthorized
        policy.result = PolicyResult.Failed("android refused the policy")

        assertEquals(CheckInOutcome.RetryLater, emergencyCheckIn.run())
        assertEquals(enrolled, store.value)
        assertEquals(listOf("release"), policy.calls)
        assertEquals(emptyList<String>(), scheduler.requests)

        // The next check-in retries the same idempotent cleanup; it does not discard the credential prematurely.
        policy.result = PolicyResult.Applied
        assertEquals(CheckInOutcome.StoodDown, emergencyCheckIn.run())
        assertEquals(DeviceState(enrolment = Enrolment.Revoked), store.value)
        assertEquals(listOf("release", "release"), policy.calls)
        assertEquals(listOf("cancel check-ins"), scheduler.requests)
    }

    @Test
    fun `emergency mode does not release an enrolled phone merely for being offline`() = runTest {
        val store = InMemoryStore(enrolled)
        api.checkInResult = ApiResult.NetworkError

        assertEquals(CheckInOutcome.RetryLater, checkIn(store, emergencyResetOnUnauthorized = true).run())

        assertEquals(enrolled, store.value)
        assertEquals(emptyList<String>(), policy.calls)
        assertEquals(emptyList<String>(), scheduler.requests)
    }

    @Test
    fun `emergency mode does not release an enrolled phone after a transient server failure`() = runTest {
        val store = InMemoryStore(enrolled)
        api.checkInResult = ApiResult.ServerError(503)

        assertEquals(CheckInOutcome.RetryLater, checkIn(store, emergencyResetOnUnauthorized = true).run())

        assertEquals(enrolled, store.value)
        assertEquals(emptyList<String>(), policy.calls)
        assertEquals(emptyList<String>(), scheduler.requests)
    }

    @Test
    fun `failed local cleanup of an already revoked phone is retried without losing state`() = runTest {
        val previouslyRevoked = enrolled.copy(enrolment = Enrolment.Revoked)
        val store = InMemoryStore(previouslyRevoked)
        val recovery = checkIn(store, emergencyResetOnUnauthorized = true)
        policy.result = PolicyResult.Failed("android still enforces device restrictions")

        assertEquals(CheckInOutcome.RetryLater, recovery.run())
        assertEquals(previouslyRevoked, store.value)
        assertEquals(emptyList<String>(), scheduler.requests)
        assertEquals(0, api.checkIns.size)

        policy.result = PolicyResult.Applied
        assertEquals(CheckInOutcome.StoodDown, recovery.run())
        assertEquals(DeviceState(enrolment = Enrolment.Revoked), store.value)
        assertEquals(listOf("release", "release"), policy.calls)
        assertEquals(listOf("cancel check-ins"), scheduler.requests)
    }

    @Test
    fun `normal builds never perform local emergency release of a previously revoked phone`() = runTest {
        val store = InMemoryStore(enrolled.copy(enrolment = Enrolment.Revoked))

        assertEquals(CheckInOutcome.NotEnrolled, checkIn(store).run())

        assertEquals(emptyList<String>(), policy.calls)
        assertEquals(0, api.checkIns.size)
        assertEquals(emptyList<String>(), scheduler.requests)
    }

    @Test
    fun `emergency build does not release a phone that was never enrolled`() = runTest {
        val store = InMemoryStore()

        assertEquals(CheckInOutcome.NotEnrolled, checkIn(store, emergencyResetOnUnauthorized = true).run())

        assertEquals(emptyList<String>(), policy.calls)
        assertEquals(0, api.checkIns.size)
    }

    @Test
    fun `a 401 on an acknowledgement also stands down`() = runTest {
        val store = InMemoryStore(enrolled)
        respond("c1" to "lock", "c2" to "unlock")
        api.ackResult = ApiResult.Unauthorized

        assertEquals(CheckInOutcome.StoodDown, checkIn(store).run())

        assertEquals(LockState.UNLOCKED, store.value.lockState)
        assertEquals(listOf("c1"), api.acks.map { it.first })
    }

    @Test
    fun `network errors change nothing on the phone`() = runTest {
        val store = InMemoryStore(enrolled.copy(lockState = LockState.LOCKED))
        api.checkInResult = ApiResult.NetworkError

        assertEquals(CheckInOutcome.RetryLater, checkIn(store).run())

        assertEquals(enrolled.copy(lockState = LockState.LOCKED), store.value)
        assertEquals(emptyList<String>(), policy.calls)
    }

    @Test
    fun `with no commands, the phone follows the server's lock state`() = runTest {
        val store = InMemoryStore(enrolled)
        respond(lockState = LockState.LOCKED)

        checkIn(store).run()

        assertEquals(LockState.LOCKED, store.value.lockState)
        assertEquals(listOf("protect", "lock"), policy.calls)
        assertEquals(0, api.acks.size)
    }

    @Test
    fun `a new check-in period from the server reschedules check-ins`() = runTest {
        respond(seconds = 1800)

        checkIn(InMemoryStore(enrolled)).run()

        assertEquals(listOf("every 1800 s"), scheduler.requests)
    }
}
