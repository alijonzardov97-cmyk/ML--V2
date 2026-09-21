package com.privacy.imsidetector.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Improvement #5 (external base as a *secondary* signal, not primary):
 * asks OpenCellID whether a cell is known at all, anywhere. Used only to
 * raise confidence that a cell genuinely unknown to *us* is also unknown
 * to the wider community (stronger anomaly) versus merely new to this
 * user (weaker anomaly, already handled by the local baseline).
 *
 * Fully optional: no API key configured, no network, or a timeout all
 * resolve to Unknown — ScoringEngine must never block or fail on this.
 */
object OpenCellIdClient {

    /** Set your own free API key from opencellid.org, or leave blank to disable this signal entirely. */
    private const val API_KEY = ""
    private const val TIMEOUT_MS = 2500L

    sealed class LookupResult {
        object KnownGlobally : LookupResult()
        object UnknownGlobally : LookupResult()
        object Unavailable : LookupResult()   // no key, no network, timeout — never treated as suspicious
    }

    suspend fun lookup(mcc: Int, mnc: Int, lac: Int, cellId: Long): LookupResult {
        if (API_KEY.isBlank()) return LookupResult.Unavailable

        return try {
            withTimeout(TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val url = URL(
                        "https://opencellid.org/cell/get?key=$API_KEY&mcc=$mcc&mnc=$mnc" +
                            "&lac=$lac&cellid=$cellId&format=json"
                    )
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = TIMEOUT_MS.toInt()
                    conn.readTimeout = TIMEOUT_MS.toInt()
                    conn.requestMethod = "GET"

                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    val json = JSONObject(body)
                    // OpenCellID returns an error object when the cell isn't found
                    if (json.has("error")) LookupResult.UnknownGlobally else LookupResult.KnownGlobally
                }
            }
        } catch (_: TimeoutCancellationException) {
            LookupResult.Unavailable
        } catch (_: Exception) {
            LookupResult.Unavailable
        }
    }
}
