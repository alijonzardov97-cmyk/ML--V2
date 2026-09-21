package com.privacy.imsidetector.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CellHistoryDao {

    // --- known_cells: the locally-learned baseline ---

    @Query("SELECT * FROM known_cells WHERE cellId = :cellId AND lac = :lac LIMIT 1")
    suspend fun findKnownCell(cellId: Long, lac: Int): CellHistoryEntity?

    @Query("SELECT * FROM known_cells WHERE geohash = :geohash")
    suspend fun getCellsForGeohash(geohash: String): List<CellHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnownCell(entity: CellHistoryEntity): Long

    @Update
    suspend fun updateKnownCell(entity: CellHistoryEntity)

    // --- observations: raw time series for handover / downgrade checks ---

    @Insert
    suspend fun insertObservation(entity: ObservationEntity)

    @Query("SELECT * FROM observations ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastObservation(): ObservationEntity?

    @Query("SELECT COUNT(*) FROM observations WHERE timestamp >= :sinceMillis")
    suspend fun countObservationsSince(sinceMillis: Long): Int

    @Query(
        """SELECT COUNT(DISTINCT cellId) FROM observations
           WHERE timestamp >= :sinceMillis"""
    )
    suspend fun countDistinctCellsSince(sinceMillis: Long): Int

    // --- alerts ---

    @Insert
    suspend fun insertAlert(entity: AlertEntity): Long

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentAlerts(): List<AlertEntity>

    @Query("UPDATE alerts SET userMarkedFalsePositive = 1 WHERE id = :alertId")
    suspend fun markFalsePositive(alertId: Long)

    @Query("SELECT COUNT(*) FROM alerts WHERE cellId = :cellId")
    suspend fun countAlertsForCell(cellId: Long): Int

    @Query("SELECT COUNT(*) FROM alerts WHERE cellId = :cellId AND userMarkedFalsePositive = 1")
    suspend fun countFalsePositivesForCell(cellId: Long): Int

    // --- Place profiles: which geohash buckets does this user frequent most ---
    @Query(
        """SELECT geohash FROM known_cells
           WHERE geohash IS NOT NULL
           GROUP BY geohash
           ORDER BY SUM(timesSeen) DESC
           LIMIT :topN"""
    )
    suspend fun getTopGeohashes(topN: Int): List<String>

    // --- Panic wipe ---
    @Query("DELETE FROM known_cells")
    suspend fun clearKnownCells()

    @Query("DELETE FROM observations")
    suspend fun clearObservations()

    @Query("DELETE FROM alerts")
    suspend fun clearAlerts()
}
