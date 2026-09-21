package com.privacy.imsidetector.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.privacy.imsidetector.analysis.ScoringEngine
import com.privacy.imsidetector.data.AppDatabase
import com.privacy.imsidetector.data.CellObserver
import com.privacy.imsidetector.notification.NotificationHelper

/**
 * Fallback for when aggressive battery-optimization or the OS kills the
 * foreground DetectorService. WorkManager's periodic minimum is 15 minutes,
 * so this is intentionally a coarse safety net, not the primary detector —
 * the foreground service remains the main real-time path.
 */
class PeriodicCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val observer = CellObserver(applicationContext)
            val engine = ScoringEngine(AppDatabase.getInstance(applicationContext).cellHistoryDao())

            val obs = observer.getCurrentObservation() ?: return Result.success()
            val result = engine.evaluate(obs)

            if (result.isAlertWorthy) {
                NotificationHelper.ensureChannels(applicationContext)
                NotificationHelper.showThreatAlert(applicationContext, result)
            }
            Result.success()
        } catch (_: Exception) {
            // Never crash-loop the fallback worker; just skip this cycle.
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "imsi_detector_periodic_fallback"
    }
}
