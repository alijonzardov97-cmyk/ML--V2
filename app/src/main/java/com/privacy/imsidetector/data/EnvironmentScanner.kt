package com.privacy.imsidetector.data

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.security.MessageDigest

/**
 * Improvement: Bluetooth/Wi-Fi correlation signal. IMSI-catchers are
 * sometimes co-located with other specific hardware; if a brand-new
 * BT/Wi-Fi environment appears at the exact same time as a new/anomalous
 * cell, that's a (weak, contextual — never standalone) extra data point.
 *
 * Privacy: MAC addresses are SHA-256 hashed before ever touching memory
 * or disk. Raw MACs are never stored, logged, or transmitted.
 */
class EnvironmentScanner(private val context: Context) {

    fun scanWifiFingerprint(): Set<String> {
        if (!hasWifiScanPermission()) return emptySet()
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.scanResults.map { hash(it.BSSID) }.toSet()
        } catch (_: SecurityException) {
            emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun scanBluetoothFingerprint(): Set<String> {
        if (!hasBluetoothScanPermission()) return emptySet()
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptySet()
            if (!adapter.isEnabled) return emptySet()
            // Bonded/paired devices only — an active BLE scan needs a
            // callback-based flow that doesn't fit a synchronous snapshot;
            // bonded-device presence is still a meaningful, zero-extra-
            // permission-risk signal for "did my usual environment change".
            adapter.bondedDevices?.map { hash(it.address) }?.toSet() ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun currentFingerprint(): Set<String> = scanWifiFingerprint() + scanBluetoothFingerprint()

    private fun hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun hasWifiScanPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // legacy BT permission covered by manifest-level BLUETOOTH
        }
    }
}
