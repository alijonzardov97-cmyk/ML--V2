package com.privacy.imsidetector.analysis

import com.privacy.imsidetector.data.AlertEntity
import com.privacy.imsidetector.data.CellHistoryDao
import com.privacy.imsidetector.data.CellHistoryEntity
import com.privacy.imsidetector.data.CellObservation
import com.privacy.imsidetector.data.CellObserver
import com.privacy.imsidetector.data.MovementMode
import com.privacy.imsidetector.data.ObservationEntity
import com.privacy.imsidetector.data.PlaceProfile
import com.privacy.imsidetector.data.PlaceProfileClassifier
import com.privacy.imsidetector.network.OpenCellIdClient
import kotlin.math.abs
import kotlin.math.min

/**
 * Combines several independently-weak signals into one score.
 * Every check degrades gracefully when its input field is null
 * (missing on this chipset) or unavailable (no network) rather
 * than throwing or assuming zero.
 */
class ScoringEngine(private val dao: CellHistoryDao) {

    private val placeClassifier = PlaceProfileClassifier(dao)

    suspend fun evaluate(obs: CellObservation, movementMode: MovementMode = MovementMode.STATIONARY): ThreatScore {
        var score = 0
        val reasons = mutableListOf<String>()

        val geohash = obs.geohash
        val known = dao.findKnownCell(obs.cellId, obs.lac)

        // --- 1. Unknown cell for this place ---
        if (known == null) {
            score += 2
            reasons += "Неизвестная сота (первое наблюдение)"

            // Improvement #5: ask OpenCellID whether this cell is known
            // *anywhere* — a cell unknown to us AND to the wider community
            // is a stronger anomaly than one that's simply new to this user.
            when (OpenCellIdClient.lookup(obs.mcc, obs.mnc, obs.lac, obs.cellId)) {
                OpenCellIdClient.LookupResult.UnknownGlobally -> {
                    score += 2
                    reasons += "Сота неизвестна и во внешней базе (OpenCellID)"
                }
                OpenCellIdClient.LookupResult.KnownGlobally -> {
                    // known elsewhere, just new to this user/place — don't add score
                }
                OpenCellIdClient.LookupResult.Unavailable -> {
                    // no key / offline / timeout — signal simply not used
                }
            }
        } else if (geohash != null && known.geohash != null && known.geohash != geohash) {
            val profile = placeClassifier.classify(known.geohash)
            val weight = when (profile) {
                PlaceProfile.PRIMARY_PLACE -> 2   // known cell showing up away from the user's main place — more surprising
                PlaceProfile.SECONDARY_PLACE -> 1
                PlaceProfile.TRANSIT -> 0          // cells drifting while just passing through is normal, not scored
            }
            if (weight > 0) {
                score += weight
                reasons += "Известная сота, но необычное для неё место"
            }
        }

        // --- 1b. Bluetooth/Wi-Fi environment correlation (contextual only, never standalone-decisive) ---
        if (obs.environmentFingerprint.isNotEmpty() && known != null && known.environmentFingerprintCsv.isNotBlank()) {
            val expectedEnv = known.environmentFingerprintCsv.split(",").toSet()
            val overlap = obs.environmentFingerprint.intersect(expectedEnv).size
            val ratio = overlap.toDouble() / expectedEnv.size
            if (ratio < 0.2) {
                score += 1
                reasons += "Незнакомое Bluetooth/Wi-Fi окружение одновременно с аномалией соты"
            }
        }

        // --- 2. Unexplained technology downgrade ---
        val last = dao.getLastObservation()
        if (last != null) {
            val prevRank = CellObserver.networkTypeRank(last.networkType)
            val currRank = CellObserver.networkTypeRank(obs.networkType)
            val elapsedMs = obs.timestamp - last.timestamp
            if (prevRank - currRank >= 2 && elapsedMs < 5 * 60_000) {
                score += 4
                reasons += "Необъяснимый откат сети (${last.networkType} → ${obs.networkType})"
            }
        }

        // --- 3. Neighbor cell list consistency ---
        if (known != null && known.neighborCellIdsCsv.isNotBlank()) {
            val expected = known.neighborCellIdsCsv.split(",").mapNotNull { it.toLongOrNull() }.toSet()
            if (expected.isNotEmpty()) {
                val overlap = obs.neighborCellIds.toSet().intersect(expected).size
                val ratio = overlap.toDouble() / expected.size
                if (ratio < 0.3) {
                    score += 3
                    reasons += "Аномальный список соседних сот"
                }
            }
        }

        // --- 4. Timing Advance sanity check (only if chipset exposes it) ---
        if (obs.timingAdvance != null && known?.typicalTimingAdvance != null) {
            if (abs(obs.timingAdvance - known.typicalTimingAdvance) > TA_DELTA_THRESHOLD) {
                score += 2
                reasons += "Подозрительное отклонение Timing Advance"
            }
        }

        // --- 5. Handover frequency (threshold scales with movement mode —
        //         frequent handovers are expected in a moving vehicle) ---
        val handoverThreshold = when (movementMode) {
            MovementMode.VEHICLE -> HANDOVER_COUNT_THRESHOLD * 3
            MovementMode.WALKING -> HANDOVER_COUNT_THRESHOLD * 2
            MovementMode.STATIONARY -> HANDOVER_COUNT_THRESHOLD
        }
        val distinctCellsLastFiveMin = dao.countDistinctCellsSince(obs.timestamp - 5 * 60_000)
        if (distinctCellsLastFiveMin >= handoverThreshold) {
            score += 2
            reasons += "Аномально частая смена соты"
        }

        // --- Improvement #3: feedback loop dampening ---
        // If the user has repeatedly marked alerts for this exact cell as
        // false positives, trust their judgement and reduce the score
        // instead of nagging them again for the same known-safe cell.
        val totalAlerts = dao.countAlertsForCell(obs.cellId)
        if (totalAlerts >= MIN_ALERTS_BEFORE_DAMPENING) {
            val falsePositives = dao.countFalsePositivesForCell(obs.cellId)
            val fpRatio = falsePositives.toDouble() / totalAlerts
            if (fpRatio >= FP_DAMPENING_RATIO) {
                val reduced = (score * (1 - fpRatio)).toInt()
                if (reduced < score) {
                    reasons += "Снижено доверием к прошлой обратной связи (${(fpRatio * 100).toInt()}% ложных срабатываний для этой соты)"
                }
                score = reduced
            }
        }

        persist(obs, known)

        val finalScore = min(score, 10)
        if (finalScore >= ThreatScore.ALERT_THRESHOLD) {
            dao.insertAlert(
                AlertEntity(
                    timestamp = obs.timestamp,
                    score = finalScore,
                    reasonsCsv = reasons.joinToString(" | "),
                    cellId = obs.cellId,
                    latitude = obs.latitude,
                    longitude = obs.longitude
                )
            )
        }

        return ThreatScore(score = finalScore, reasons = reasons)
    }

    /** Improvement #2: confidence rises on repeat sightings, is never reset to 0. */
    private suspend fun persist(obs: CellObservation, known: CellHistoryEntity?) {
        dao.insertObservation(
            ObservationEntity(
                timestamp = obs.timestamp,
                cellId = obs.cellId,
                lac = obs.lac,
                mcc = obs.mcc,
                mnc = obs.mnc,
                networkType = obs.networkType,
                signalStrengthDbm = obs.signalStrengthDbm,
                timingAdvance = obs.timingAdvance,
                neighborCellIdsCsv = obs.neighborCellIdsCsv,
                latitude = obs.latitude,
                longitude = obs.longitude,
                geohash = obs.geohash
            )
        )

        if (known == null) {
            dao.upsertKnownCell(
                CellHistoryEntity(
                    cellId = obs.cellId,
                    lac = obs.lac,
                    mcc = obs.mcc,
                    mnc = obs.mnc,
                    networkType = obs.networkType,
                    firstSeenAt = obs.timestamp,
                    lastSeenAt = obs.timestamp,
                    timesSeen = 1,
                    confidence = INITIAL_CONFIDENCE,
                    avgLatitude = obs.latitude,
                    avgLongitude = obs.longitude,
                    geohash = obs.geohash,
                    typicalTimingAdvance = obs.timingAdvance,
                    neighborCellIdsCsv = obs.neighborCellIdsCsv,
                    environmentFingerprintCsv = obs.environmentFingerprintCsv
                )
            )
        } else {
            val newConfidence = min(1.0, known.confidence + CONFIDENCE_GAIN_PER_SIGHTING)
            dao.updateKnownCell(
                known.copy(
                    lastSeenAt = obs.timestamp,
                    timesSeen = known.timesSeen + 1,
                    confidence = newConfidence,
                    typicalTimingAdvance = obs.timingAdvance ?: known.typicalTimingAdvance,
                    neighborCellIdsCsv = mergeNeighbors(known.neighborCellIdsCsv, obs.neighborCellIdsCsv),
                    environmentFingerprintCsv = mergeStringSets(known.environmentFingerprintCsv, obs.environmentFingerprintCsv)
                )
            )
        }
    }

    private fun mergeNeighbors(existingCsv: String, newCsv: String): String {
        val existing = existingCsv.split(",").mapNotNull { it.toLongOrNull() }.toMutableSet()
        existing += newCsv.split(",").mapNotNull { it.toLongOrNull() }
        return existing.joinToString(",")
    }

    private fun mergeStringSets(existingCsv: String, newCsv: String): String {
        val existing = existingCsv.split(",").filter { it.isNotBlank() }.toMutableSet()
        existing += newCsv.split(",").filter { it.isNotBlank() }
        // cap to avoid unbounded growth for very stable environments
        return existing.take(MAX_ENV_FINGERPRINT_ENTRIES).joinToString(",")
    }

    companion object {
        private const val INITIAL_CONFIDENCE = 0.2
        private const val CONFIDENCE_GAIN_PER_SIGHTING = 0.05
        private const val TA_DELTA_THRESHOLD = 5
        private const val HANDOVER_COUNT_THRESHOLD = 6
        private const val MIN_ALERTS_BEFORE_DAMPENING = 3
        private const val FP_DAMPENING_RATIO = 0.5
        private const val MAX_ENV_FINGERPRINT_ENTRIES = 40
    }
}
