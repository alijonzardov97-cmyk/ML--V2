package com.privacy.imsidetector.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat

/**
 * Wraps TelephonyManager + LocationManager. Every field that is not
 * guaranteed by the public API (timingAdvance in particular) is nullable
 * and the rest of the pipeline (ScoringEngine) must treat null as
 * "signal unavailable on this chipset", never as "value is zero".
 */
class CellObserver(private val context: Context) {

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun getCurrentObservation(): CellObservation? {
        if (!hasPhoneStatePermission()) return null

        val allCellInfo: List<CellInfo> = try {
            telephonyManager.allCellInfo ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        val serving = allCellInfo.firstOrNull { it.isRegistered } ?: return null
        val neighbors = allCellInfo.filterNot { it.isRegistered }

        val location = getBestEffortLocation()

        return CellObservation(
            timestamp = System.currentTimeMillis(),
            cellId = extractCellId(serving) ?: return null,
            lac = extractLac(serving) ?: -1,
            mcc = extractMcc(serving) ?: -1,
            mnc = extractMnc(serving) ?: -1,
            networkType = mapNetworkType(serving),
            signalStrengthDbm = extractSignalStrength(serving),
            neighborCellIds = neighbors.mapNotNull { extractCellId(it) },
            timingAdvance = extractTimingAdvance(serving),
            latitude = location?.latitude,
            longitude = location?.longitude
        )
    }

    // --- Coarse-first location strategy (improvement #4) ---
    // Fine GPS is requested by the caller (DetectorService) only when
    // the running score is already elevated; by default we just read
    // whatever the network/passive provider last cached, at no extra
    // battery cost.
    @SuppressLint("MissingPermission")
    private fun getBestEffortLocation(): Location? {
        if (!hasLocationPermission()) return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (p in providers) {
            try {
                locationManager.getLastKnownLocation(p)?.let { return it }
            } catch (_: SecurityException) {
                // provider not permitted, skip
            } catch (_: IllegalArgumentException) {
                // provider not available on this device
            }
        }
        return null
    }

    private fun hasPhoneStatePermission() =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // --- Field extraction, one branch per CellInfo subtype ---
    // Android's CellInfo hierarchy has no shared accessor for cellId/lac/tac,
    // so this boilerplate is unavoidable without a third-party wrapper.

    private fun extractCellId(info: CellInfo): Long? = when (info) {
        is CellInfoLte -> info.cellIdentity.ci.takeIf { it != Int.MAX_VALUE }?.toLong()
        is CellInfoGsm -> info.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }?.toLong()
        is CellInfoWcdma -> info.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }?.toLong()
        is CellInfoNr -> (info.cellIdentity as? CellIdentityNr)?.nci?.takeIf { it != Long.MAX_VALUE }
        else -> null
    }

    private fun extractLac(info: CellInfo): Int? = when (info) {
        is CellInfoLte -> info.cellIdentity.tac.takeIf { it != Int.MAX_VALUE }
        is CellInfoGsm -> info.cellIdentity.lac.takeIf { it != Int.MAX_VALUE }
        is CellInfoWcdma -> info.cellIdentity.lac.takeIf { it != Int.MAX_VALUE }
        is CellInfoNr -> (info.cellIdentity as? CellIdentityNr)?.tac?.takeIf { it != Int.MAX_VALUE }
        else -> null
    }

    private fun extractMcc(info: CellInfo): Int? = when (info) {
        is CellInfoLte -> info.cellIdentity.mccString?.toIntOrNull()
        is CellInfoGsm -> info.cellIdentity.mccString?.toIntOrNull()
        is CellInfoWcdma -> info.cellIdentity.mccString?.toIntOrNull()
        is CellInfoNr -> (info.cellIdentity as? CellIdentityNr)?.mccString?.toIntOrNull()
        else -> null
    }

    private fun extractMnc(info: CellInfo): Int? = when (info) {
        is CellInfoLte -> info.cellIdentity.mncString?.toIntOrNull()
        is CellInfoGsm -> info.cellIdentity.mncString?.toIntOrNull()
        is CellInfoWcdma -> info.cellIdentity.mncString?.toIntOrNull()
        is CellInfoNr -> (info.cellIdentity as? CellIdentityNr)?.mncString?.toIntOrNull()
        else -> null
    }

    private fun extractSignalStrength(info: CellInfo): Int? = when (info) {
        is CellInfoLte -> info.cellSignalStrength.dbm
        is CellInfoGsm -> info.cellSignalStrength.dbm
        is CellInfoWcdma -> info.cellSignalStrength.dbm
        is CellInfoNr -> info.cellSignalStrength.dbm
        else -> null
    }

    /**
     * Timing Advance: only reliably exposed for LTE, and only on some
     * chipsets/Android versions. Treat absence as "unavailable", not "0".
     */
    private fun extractTimingAdvance(info: CellInfo): Int? {
        if (info !is CellInfoLte) return null
        return try {
            val ta = info.cellSignalStrength.timingAdvance
            if (ta == CellInfo.UNAVAILABLE) null else ta
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapNetworkType(info: CellInfo): String = when (info) {
        is CellInfoNr -> "NR"
        is CellInfoLte -> "LTE"
        is CellInfoWcdma -> "UMTS"
        is CellInfoGsm -> "GSM"
        else -> "UNKNOWN"
    }

    /** Ranking used by ScoringEngine to detect an unexplained downgrade. */
    companion object {
        fun networkTypeRank(type: String): Int = when (type) {
            "NR" -> 4
            "LTE" -> 3
            "UMTS" -> 2
            "GSM" -> 1
            else -> 0
        }
    }
}
