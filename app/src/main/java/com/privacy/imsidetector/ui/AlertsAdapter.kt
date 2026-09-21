package com.privacy.imsidetector.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.privacy.imsidetector.R
import com.privacy.imsidetector.data.AlertEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertsAdapter(
    private val onLongPressMarkFalsePositive: (AlertEntity) -> Unit
) : RecyclerView.Adapter<AlertsAdapter.AlertViewHolder>() {

    private var items: List<AlertEntity> = emptyList()
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    fun submitList(newItems: List<AlertEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alert, parent, false)
        return AlertViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class AlertViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val severityBar: View = itemView.findViewById(R.id.severityBar)
        private val scoreText: android.widget.TextView = itemView.findViewById(R.id.scoreText)
        private val timeText: android.widget.TextView = itemView.findViewById(R.id.timeText)
        private val reasonsText: android.widget.TextView = itemView.findViewById(R.id.reasonsText)
        private val falsePositiveHint: android.widget.TextView = itemView.findViewById(R.id.falsePositiveHint)

        fun bind(alert: AlertEntity) {
            val severityColorRes = when {
                alert.score >= 8 -> R.color.severity_high
                alert.score >= 6 -> R.color.severity_medium
                else -> R.color.severity_low
            }
            val color = ContextCompat.getColor(itemView.context, severityColorRes)
            severityBar.setBackgroundColor(color)
            scoreText.setTextColor(color)
            scoreText.text = "Score ${alert.score}"
            timeText.text = dateFormat.format(Date(alert.timestamp))
            reasonsText.text = alert.reasonsCsv.replace(" | ", "\n")

            if (alert.userMarkedFalsePositive) {
                falsePositiveHint.text = "Отмечено как ложная тревога"
            } else {
                falsePositiveHint.text = "Долгое нажатие — отметить как ложную тревогу"
            }

            itemView.setOnLongClickListener {
                onLongPressMarkFalsePositive(alert)
                true
            }
        }
    }
}
