package com.corewatch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization ("unrestricted") allowlist helpers. Being on the allowlist means Android /
 * the OEM is far less likely to kill CoreWatch's foreground service to reclaim memory for a game,
 * so background capture survives longer. It's never a hard guarantee under extreme memory pressure —
 * that's what disk persistence is for.
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** The direct "allow unrestricted battery use?" system dialog for this app. */
@SuppressLint("BatteryLife")
fun ignoreBatteryOptimizationsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
