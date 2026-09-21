package com.privacy.imsidetector.data

enum class PlaceProfile { PRIMARY_PLACE, SECONDARY_PLACE, TRANSIT }

/**
 * Improvement: separate baselines for "home/work" versus "in transit"
 * instead of one flat geohash lookup. The two most-visited geohash
 * buckets (by cumulative sightings) are treated as the user's primary
 * places — an unusual cell there is more suspicious than the same
 * unusual cell somewhere the user only ever passes through.
 */
class PlaceProfileClassifier(private val dao: CellHistoryDao) {

    suspend fun classify(geohash: String?): PlaceProfile {
        if (geohash == null) return PlaceProfile.TRANSIT
        val topGeohashes = dao.getTopGeohashes(topN = 2)
        return when {
            topGeohashes.isEmpty() -> PlaceProfile.TRANSIT
            topGeohashes.getOrNull(0) == geohash -> PlaceProfile.PRIMARY_PLACE
            topGeohashes.getOrNull(1) == geohash -> PlaceProfile.SECONDARY_PLACE
            else -> PlaceProfile.TRANSIT
        }
    }
}
