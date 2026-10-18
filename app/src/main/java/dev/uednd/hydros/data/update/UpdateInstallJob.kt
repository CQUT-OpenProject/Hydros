package dev.uednd.hydros.data.update

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Verifies and stages the downloaded APK away from the Activity and survives process death. */
class UpdateInstallJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var worker: Future<*>? = null
    private var stopped: AtomicBoolean? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val stoppedForJob = AtomicBoolean(false)
        stopped = stoppedForJob
        val taskId = params.extras.getString(AndroidUpdateManager.EXTRA_TASK).orEmpty()
        worker = executor.submit {
            try {
                runBlocking { AndroidUpdateManager.get(this@UpdateInstallJob).processTask(taskId) }
            } catch (_: InterruptedException) {
                // onStopJob interrupted the worker; the system reschedules this job.
            } finally {
                if (!stoppedForJob.get()) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped?.set(true)
        worker?.cancel(true)
        worker = null
        return true
    }

    override fun onDestroy() {
        stopped?.set(true)
        worker?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
