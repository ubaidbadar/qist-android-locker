package pk.qist.device

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import pk.qist.device.checkin.CheckInWorker
import pk.qist.device.customer.CustomerAppWorker
import pk.qist.device.enrolment.EnrolWorker
import pk.qist.device.updates.LockerUpdateWorker

/**
 * [WorkScheduler] on WorkManager: jobs survive reboots, wait for a network, back off on failure and respect Doze,
 * so the app needs no service, wake lock or polling loop. Every job is "unique", so asking twice never runs twice.
 */
class WorkManagerScheduler(private val workManager: () -> WorkManager) : WorkScheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun enrol() {
        val request = OneTimeWorkRequestBuilder<EnrolWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // KEEP: never cancel an enrolment in flight; the token is single-use, so a second attempt could lose it.
        workManager().enqueueUniqueWork(ENROL, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleCheckIns(seconds: Long) {
        val request = PeriodicWorkRequestBuilder<CheckInWorker>(maxOf(seconds, MIN_PERIOD_SECONDS), TimeUnit.SECONDS)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // UPDATE keeps the schedule's timing and only changes the period when the server asks for a new one.
        workManager().enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun checkInNow() {
        val request = OneTimeWorkRequestBuilder<CheckInWorker>()
            .setConstraints(online)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // KEEP: a check-in already waiting or running covers this request.
        workManager().enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelCheckIns() {
        workManager().cancelUniqueWork(LOCKER_UPDATE)
        workManager().cancelUniqueWork(LOCKER_UPDATE_NOW)
        workManager().cancelUniqueWork(CUSTOMER_APP)
        workManager().cancelUniqueWork(PERIODIC)
        workManager().cancelUniqueWork(NOW)
    }

    override fun scheduleCustomerApp(wifiOnly: Boolean, delayMillis: Long, replace: Boolean) {
        workManager().enqueueUniqueWork(
            CUSTOMER_APP,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            customerWork(wifiOnly, delayMillis)
        )
    }

    override fun continueCustomerApp(wifiOnly: Boolean) {
        workManager().enqueueUniqueWork(
            CUSTOMER_APP,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            customerWork(wifiOnly, BACKOFF_SECONDS * 1000)
        )
    }

    override fun scheduleLockerUpdates() {
        val request = PeriodicWorkRequestBuilder<LockerUpdateWorker>(1, TimeUnit.HOURS)
            .setConstraints(updateConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager().enqueueUniquePeriodicWork(LOCKER_UPDATE, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun lockerUpdateNow() {
        val request = OneTimeWorkRequestBuilder<LockerUpdateWorker>()
            .setConstraints(updateConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager().enqueueUniqueWork(LOCKER_UPDATE_NOW, ExistingWorkPolicy.KEEP, request)
    }

    private fun updateConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    private fun customerWork(wifiOnly: Boolean, delayMillis: Long): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        return OneTimeWorkRequestBuilder<CustomerAppWorker>()
            .setConstraints(constraints)
            .setInitialDelay(maxOf(0, delayMillis), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        const val LOCKER_UPDATE = "locker-auto-update"
        const val LOCKER_UPDATE_NOW = "locker-update-now"
        const val ENROL = "enrol"
        const val CUSTOMER_APP = "customer-app-install"
        const val PERIODIC = "check-in"
        const val NOW = "check-in-now"
        const val BACKOFF_SECONDS = 30L
        const val MIN_PERIOD_SECONDS = 15L * 60
    }
}
