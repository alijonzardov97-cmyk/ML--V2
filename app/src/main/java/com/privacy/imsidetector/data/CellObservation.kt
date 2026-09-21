package com.privacy.imsidetector.data

data class CellObservation(
    val timestamp: Long,
    val cellId: Long,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val networkType: String,        // "LTE", "NR", "UMTS", "GSM", "UNKNOWN"
    val signalStrengthDbm: Int?,
    val neighborCellIds: List<Long>,
    val timingAdvance: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val environmentFingerprint: Set<String> = emptySet()
) {
    val neighborCellIdsCsv: String get() = neighborCellIds.joinToString(",")
    val environmentFingerprintCsv: String get() = environmentFingerprint.joinToString(",")
    val geohash: String? get() =
        if (latitude != null && longitude != null) GeoHash.encode(latitude, longitude, precision = 6)
        else null
}

/** Minimal geohash implementation — no external dependency needed. Precision 6 ≈ ~1.2km cell. */
object GeoHash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int = 6): String {
        var latInterval = doubleArrayOf(-90.0, 90.0)
        var lonInterval = doubleArrayOf(-180.0, 180.0)
        val geohash = StringBuilder()
        var isEven = true
        var bit = 0
        var ch = 0

        while (geohash.length < precision) {
            if (isEven) {
                val mid = (lonInterval[0] + lonInterval[1]) / 2
                if (lon > mid) { ch = ch or (1 shl (4 - bit)); lonInterval[0] = mid } else lonInterval[1] = mid
            } else {
                val mid = (latInterval[0] + latInterval[1]) / 2
                if (lat > mid) { ch = ch or (1 shl (4 - bit)); latInterval[0] = mid } else latInterval[1] = mid
            }
            isEven = !isEven
            if (bit < 4) bit++ else {
                geohash.append(BASE32[ch]); bit = 0; ch = 0
            }
        }
        return geohash.toString()
    }
}
