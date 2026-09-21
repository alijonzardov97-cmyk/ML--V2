package com.privacy.imsidetector.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.privacy.imsidetector.R
import com.privacy.imsidetector.analysis.ScoringEngine
import com.privacy.imsidetector.data.AppDatabase
import com.privacy.imsidetector.data.CellObservation
import com.privacy.imsidetector.data.ExportManager
import com.privacy.imsidetector.notification.NotificationHelper
import com.privacy.imsidetector.service.DetectorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var alertsAdapter: AlertsAdapter
    private lateinit var emptyStateText: TextView
    private var serviceRunning = false

    private val requiredPermissions = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        emptyStateText = findViewById(R.id.emptyStateText)

        val recyclerView = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.alertsRecyclerView)
        alertsAdapter = AlertsAdapter { alert ->
            CoroutineScope(Dispatchers.IO).launch {
                AppDatabase.getInstance(this@MainActivity).cellHistoryDao().markFalsePositive(alert.id)
                loadRecentAlerts()
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = alertsAdapter

        findViewById<Button>(R.id.toggleServiceButton).setOnClickListener { toggleService() }
        findViewById<Button>(R.id.requestPermissionsButton).setOnClickListener {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
        findViewById<Button>(R.id.simulateAlertButton).setOnClickListener { simulateAlert() }
        findViewById<Button>(R.id.exportCsvButton).setOnClickListener { exportHistory() }
        findViewById<Button>(R.id.panicWipeButton).setOnClickListener { confirmPanicWipe() }

        refreshStatus()
        loadRecentAlerts()
    }

    override fun onResume() {
        super.onResume()
        loadRecentAlerts()
    }

    private fun toggleService() {
        if (!allPermissionsGranted()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
            return
        }
        val intent = Intent(this, DetectorService::class.java)
        if (!serviceRunning) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            stopService(intent)
        }
        serviceRunning = !serviceRunning
        refreshStatus()
    }

    /** Improvement #8: pushes a synthetic observation through the real
     *  ScoringEngine pipeline (DB writes, dampening, alert log,
     *  notification) instead of just faking a notification. */
    private fun simulateAlert() {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getInstance(this@MainActivity).cellHistoryDao()
            val engine = ScoringEngine(dao)

            val fakeObs = CellObservation(
                timestamp = System.currentTimeMillis(),
                cellId = 999_999_001L,
                lac = 65535,
                mcc = 999,
                mnc = 99,
                networkType = "GSM",
                signalStrengthDbm = -60,
                neighborCellIds = emptyList(),
                timingAdvance = null,
                latitude = null,
                longitude = null
            )

            val result = engine.evaluate(fakeObs)
            withContext(Dispatchers.Main) {
                NotificationHelper.ensureChannels(this@MainActivity)
                NotificationHelper.showThreatAlert(this@MainActivity, result)
                loadRecentAlerts()
            }
        }
    }

    private fun exportHistory() {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getInstance(this@MainActivity).cellHistoryDao()
            val uri = ExportManager(this@MainActivity).exportAlertsCsv(dao)
            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Поделиться историей"))
            }
        }
    }

    /** Panic wipe: clears all local history (known cells, observations, alerts)
     *  after explicit confirmation — irreversible. */
    private fun confirmPanicWipe() {
        AlertDialog.Builder(this)
            .setTitle("Экстренная очистка данных")
            .setMessage("Вся локальная история (известные соты, наблюдения, алерты) будет безвозвратно удалена. Продолжить?")
            .setPositiveButton("Удалить") { _, _ -> performPanicWipe() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun performPanicWipe() {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getInstance(this@MainActivity).cellHistoryDao()
            dao.clearAlerts()
            dao.clearObservations()
            dao.clearKnownCells()
            withContext(Dispatchers.Main) { loadRecentAlerts() }
        }
    }

    private fun refreshStatus() {
        if (serviceRunning) {
            statusText.text = "Мониторинг активен"
            statusDot.setBackgroundResource(R.drawable.dot_active)
        } else {
            statusText.text = "Сервис не запущен"
            statusDot.setBackgroundResource(R.drawable.dot_inactive)
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadRecentAlerts() {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getInstance(this@MainActivity).cellHistoryDao()
            val alerts = dao.getRecentAlerts()
            withContext(Dispatchers.Main) {
                alertsAdapter.submitList(alerts)
                emptyStateText.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
