package com.sp.core.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sp.core.util.BatchExfil

class BatchFlushWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        BatchExfil.flush(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "BatchFlushWorker"
        fun enqueue(ctx: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<BatchFlushWorker>()
                .setTag(TAG)
                .build()
            androidx.work.WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
