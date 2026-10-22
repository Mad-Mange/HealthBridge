package se.healthbridge.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import se.healthbridge.app.appContainer

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val result = applicationContext.appContainer.syncEngine.sync()
        SyncNotifier.onResult(applicationContext, result)
        return when (result.outcome) {
            SyncEngine.Outcome.SUCCESS, SyncEngine.Outcome.SKIPPED -> Result.success()
            SyncEngine.Outcome.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            SyncEngine.Outcome.FAILED -> Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
