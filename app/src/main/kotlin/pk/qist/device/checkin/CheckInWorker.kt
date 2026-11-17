package pk.qist.device.checkin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException
import kotlinx.coroutines.flow.first
import pk.qist.device.container
import pk.qist.device.data.LockState

/** Runs one [CheckIn]; WorkManager retries with back-off when the server cannot be reached. */
class CheckInWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = applicationContext.container.checkIn.run()
        if (outcome != CheckInOutcome.RetryLater) applicationContext.container.customerSignin.refresh()
        if (outcome == CheckInOutcome.Done) {
            try {
                val container = applicationContext.container
                container.scheduler.scheduleLockerUpdates()
                val state = container.store.data.first()
                if (state.lockState == LockState.UNLOCKED &&
                    state.lockerApp.report.detailCode == "waiting_for_unlock"
                ) {
                    container.scheduler.lockerUpdateNow()
                }
            } catch (_: IOException) {
                // Optional updates never turn a completed finance command into a failed check-in.
            } catch (_: IllegalStateException) {
                // The periodic update job will recover scheduler initialization failures.
            }
        }
        return when (outcome) {
            CheckInOutcome.Done, CheckInOutcome.NotEnrolled, CheckInOutcome.StoodDown, CheckInOutcome.Released ->
                Result.success()

            CheckInOutcome.RetryLater -> Result.retry()
        }
    }
}
