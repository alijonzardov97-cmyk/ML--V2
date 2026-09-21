package com.privacy.imsidetector.data

import kotlin.math.sqrt

enum class MovementMode { STATIONARY, WALKING, VEHICLE }

/**
 * Replaces the previous crude boolean "isMoving" with three states,
 * using a short sliding window of accelerometer magnitude variance.
 * Vehicle motion is smoother/faster-changing than walking's rhythmic
 * bounce, so a simple variance-band heuristic (no ML needed) separates
 * them well enough to matter for handover-frequency scoring: frequent
 * cell changes are expected in a vehicle, suspicious if stationary.
 */
class MovementClassifier(private val windowSize: Int = 20) {

    private val magnitudes = ArrayDeque<Float>()

    fun onAccelSample(x: Float, y: Float, z: Float) {
        val magnitude = sqrt(x * x + y * y + z * z)
        magnitudes.addLast(magnitude)
        if (magnitudes.size > windowSize) magnitudes.removeFirst()
    }

    fun currentMode(): MovementMode {
        if (magnitudes.size < windowSize / 2) return MovementMode.STATIONARY
        val mean = magnitudes.average()
        val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size

        return when {
            variance < STATIONARY_VARIANCE_THRESHOLD -> MovementMode.STATIONARY
            variance < VEHICLE_VARIANCE_THRESHOLD -> MovementMode.WALKING
            else -> MovementMode.VEHICLE
        }
    }

    companion object {
        private const val STATIONARY_VARIANCE_THRESHOLD = 0.3
        private const val VEHICLE_VARIANCE_THRESHOLD = 2.5
    }
}
