package pk.qist.device.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pk.qist.device.container

class LockerUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // An update job from the previous installation may still be queued.
        // An emergency APK must never replace itself from the common distribution URL.
        if (pk.qist.device.BuildConfig.EMERGENCY_RESET_ON_UNAUTHORIZED) return Result.success()
        return if (applicationContext.container.lockerUpdater.run()) Result.retry() else Result.success()
    }
}
