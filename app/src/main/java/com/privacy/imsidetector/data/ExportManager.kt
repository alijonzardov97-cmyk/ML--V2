package com.privacy.imsidetector.data

import android.content.Context
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the local history as plain CSV so the user can inspect it
 * externally (spreadsheet, Python) or hand it to someone else. Files are
 * written to app-private external storage and shared only via a
 * FileProvider content:// URI — never a raw file:// path.
 */
class ExportManager(private val context: Context) {

    suspend fun exportAlertsCsv(dao: CellHistoryDao): Uri {
        val alerts = dao.getRecentAlerts()
        val header = "timestamp,score,reasons,cellId,latitude,longitude,falsePositive"
        val rows = alerts.joinToString("\n") { a ->
            listOf(
                isoTime(a.timestamp),
                a.score.toString(),
                "\"${a.reasonsCsv.replace("\"", "'")}\"",
                a.cellId.toString(),
                a.latitude?.toString() ?: "",
                a.longitude?.toString() ?: "",
                a.userMarkedFalsePositive.toString()
            ).joinToString(",")
        }
        return writeAndShare("alerts_${fileTimestamp()}.csv", "$header\n$rows")
    }

    private fun isoTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    private fun writeAndShare(fileName: String, content: String): Uri {
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
