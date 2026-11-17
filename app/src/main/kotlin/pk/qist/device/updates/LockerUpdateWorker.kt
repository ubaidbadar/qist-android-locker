package pk.qist.device.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pk.qist.device.container

class LockerUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (applicationContext.container.lockerUpdater.run()) Result.retry() else Result.success()
}
