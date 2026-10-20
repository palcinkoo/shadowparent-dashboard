package com.sp.watchdog

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        HealthCheck.run(this); jobFinished(params, false); return false
    }
    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 0x5F71
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val builder = JobInfo.Builder(JOB_ID,
                ComponentName(ctx, WatchdogJob::class.java))
                .setPersisted(true)
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            try { js.schedule(builder.build()) } catch (_: Throwable) {}
        }
    }
}