package com.privacy.imsidetector.analysis

data class ThreatScore(
    val score: Int,
    val reasons: List<String>
) {
    val isAlertWorthy: Boolean get() = score >= ALERT_THRESHOLD

    companion object {
        const val ALERT_THRESHOLD = 6
    }
}
