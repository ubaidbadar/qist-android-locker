package pk.qist.device.customer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pk.qist.device.container

class CustomerAppWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (applicationContext.container.customerInstaller.run()) Result.retry() else Result.success()
}
