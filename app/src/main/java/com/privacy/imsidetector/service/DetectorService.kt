package com.privacy.imsidetector.service

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.work.*
import com.privacy.imsidetector.analysis.ScoringEngine
import com.privacy.imsidetector.data.AppDatabase
import com.privacy.imsidetector.data.CellObserver
import com.privacy.imsidetector.data.EnvironmentScanner
import com.privacy.imsidetector.data.MovementClassifier
import com.privacy.imsidetector.data.MovementMode
import com.privacy.imsidetector.notification.NotificationHelper
import com.privacy.imsidetector.work.PeriodicCheckWorker
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Improvement #1 (adaptive polling): interval shrinks when the device is
 * moving (accelerometer variance above threshold) or when the last score
 * was already elevated, and grows back to the idle interval otherwise —
 * trades a bit of latency for meaningfully less battery drain at rest.
 */
class DetectorService : Service(), SensorEventListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var observer: CellObserver
    private lateinit var engine: ScoringEngine
    private lateinit var sensorManager: SensorManager
    private lateinit var environmentScanner: EnvironmentScanner
    private val movementClassifier = MovementClassifier()

    @Volatile private var lastScore = 0
    private var pollingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        observer = CellObserver(this)
        engine = ScoringEngine(AppDatabase.getInstance(this).cellHistoryDao())
        environmentScanner = EnvironmentScanner(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            NotificationHelper.buildServiceNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        startPollingLoop()
        scheduleFallbackWorker()
        return START_STICKY
    }

    /** Improvement: WorkManager safety net in case the OS kills this foreground service. */
    private fun scheduleFallbackWorker() {
        val request = PeriodicWorkRequestBuilder<PeriodicCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            PeriodicCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun startPollingLoop() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                runCatching {
                    val obs = observer.getCurrentObservation()
                    if (obs != null) {
                        val withEnvironment = obs.copy(environmentFingerprint = environmentScanner.currentFingerprint())
                        val movementMode = movementClassifier.currentMode()
                        val result = engine.evaluate(withEnvironment, movementMode)
                        lastScore = result.score
                        if (result.isAlertWorthy) {
                            NotificationHelper.showThreatAlert(this@DetectorService, result)
                        }
                    }
                }
                delay(currentIntervalMs())
            }
        }
    }

    /** Faster polling when moving or already suspicious; slower at idle to save battery. */
    private fun currentIntervalMs(): Long = when {
        lastScore >= 4 -> FAST_INTERVAL_MS
        movementClassifier.currentMode() != MovementMode.STATIONARY -> MEDIUM_INTERVAL_MS
        else -> IDLE_INTERVAL_MS
    }

    override fun onSensorChanged(event: SensorEvent) {
        movementClassifier.onAccelSample(event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        pollingJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val IDLE_INTERVAL_MS = 60_000L
        private const val MEDIUM_INTERVAL_MS = 20_000L
        private const val FAST_INTERVAL_MS = 8_000L
    }
}
