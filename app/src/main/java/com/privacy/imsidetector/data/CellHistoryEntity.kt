package com.privacy.imsidetector.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per unique (cellId, lac, mcc, mnc) tuple ever observed.
 *
 * confidence: exponentially-weighted trust score, improvement #2.
 * Rises each time we see this cell again, decays slowly over time
 * (decay applied lazily in ScoringEngine, not stored as a cron job).
 */
@Entity(tableName = "known_cells")
data class CellHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cellId: Long,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val networkType: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val timesSeen: Int,
    val confidence: Double,           // 0.0 - 1.0
    val avgLatitude: Double?,
    val avgLongitude: Double?,
    val geohash: String?,             // coarse location bucket, improvement #4
    val typicalTimingAdvance: Int?,
    val neighborCellIdsCsv: String,   // comma-separated cellIds typically seen alongside this one
    val environmentFingerprintCsv: String = ""  // hashed BT/WiFi identifiers typically seen here
)

/** Raw point-in-time observation log, used for handover-frequency and downgrade checks. */
@Entity(tableName = "observations")
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val cellId: Long,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val networkType: String,
    val signalStrengthDbm: Int?,
    val timingAdvance: Int?,
    val neighborCellIdsCsv: String,
    val latitude: Double?,
    val longitude: Double?,
    val geohash: String?
)

/** Alert log, improvement #7 (explainability) — stores the reasons, not just a number. */
@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val score: Int,
    val reasonsCsv: String,
    val cellId: Long,
    val latitude: Double?,
    val longitude: Double?,
    val userMarkedFalsePositive: Boolean = false   // improvement #3, feedback loop
)
