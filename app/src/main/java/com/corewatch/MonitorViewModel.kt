package com.corewatch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.corewatch.monitor.DeviceInfo
import com.corewatch.monitor.LiveMetrics
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin adapter over [SessionCollector]. Collection is process-scoped (so a foreground service can
 * keep it alive in the background); this just exposes the collector's state to the UI and makes
 * sure it's running whenever the screen is shown.
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    init {
        SessionCollector.start(app)
    }

    val deviceInfo: DeviceInfo get() = SessionCollector.deviceInfo
    val metrics: StateFlow<LiveMetrics> get() = SessionCollector.metrics
    val cpuHistory: List<Float> get() = SessionCollector.cpuHistory
    val ramHistory: List<Float> get() = SessionCollector.ramHistory
    val tempHistory: List<Float> get() = SessionCollector.tempHistory
    val ramTotalBytes: Long get() = SessionCollector.ramTotalBytes
    val historyIntervalSec: Int get() = SessionCollector.historyIntervalSec
    val batterySession: BatterySession get() = SessionCollector.batterySession
}
