package com.privacy.imsidetector.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.privacy.imsidetector.analysis.ThreatScore

object NotificationHelper {

    private const val CHANNEL_SERVICE = "detector_service"
    private const val CHANNEL_ALERTS = "detector_alerts"
    const val SERVICE_NOTIFICATION_ID = 1001
    private var alertIdCounter = 2000

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "Фоновый мониторинг сети",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, "Тревоги детектора",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    fun buildServiceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("Мониторинг сети активен")
            .setContentText("Анализ параметров сотовой сети в фоне")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

    /** Improvement #7: explainable alert — shows the actual reasons, not just a number. */
    fun showThreatAlert(context: Context, result: ThreatScore) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val body = result.reasons.joinToString("\n")
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle("Подозрительная активность сети (score ${result.score})")
            .setContentText(result.reasons.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(alertIdCounter++, notification)
    }

    fun notifySilentSms(context: Context, sender: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle("Обнаружено silent SMS")
            .setContentText("Отправитель: ${sender ?: "неизвестен"}")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(alertIdCounter++, notification)
    }
}
