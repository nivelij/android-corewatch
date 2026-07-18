package com.corewatch

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.corewatch.monitor.DeviceInfo
import com.corewatch.monitor.LiveMetrics
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin adapter over [SessionCollector]. The collector is process-scoped; opening the app initialises
 * it for the live tiles, but recording (history capture + the foreground service/notification) only
 * runs between [startRecording] and [stopRecording].
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    init {
        SessionCollector.ensureInitialized(app)
    }

    val deviceInfo: DeviceInfo get() = SessionCollector.deviceInfo
    val metrics: StateFlow<LiveMetrics> get() = SessionCollector.metrics
    val cpuHistory: List<Float> get() = SessionCollector.cpuHistory
    val ramHistory: List<Float> get() = SessionCollector.ramHistory
    val tempHistory: List<Float> get() = SessionCollector.tempHistory
    val powerHistory: List<Float> get() = SessionCollector.powerHistory
    val gapIndices: List<Int> get() = SessionCollector.gapIndices
    val ramTotalBytes: Long get() = SessionCollector.ramTotalBytes
    val historyIntervalSec: Int get() = SessionCollector.historyIntervalSec
    val batterySession: BatterySession get() = SessionCollector.batterySession

    // ---- Recording control. ----
    /** True while a session is being recorded (observable — reading it recomposes the UI). */
    val isRecording: Boolean get() = SessionCollector.isRecording

    /** Start of the active recording (for the elapsed timer); 0 when idle. */
    val recordingStartMillis: Long get() = SessionCollector.recordingStartMillis

    /** Begin recording and bring up the foreground service + persistent notification. */
    fun startRecording() {
        val app = getApplication<Application>()
        SessionCollector.startRecording(app)
        ContextCompat.startForegroundService(app, Intent(app, MonitoringService::class.java))
    }

    /** Stop recording (archiving it), and tear down the service + notification. */
    fun stopRecording() {
        val app = getApplication<Application>()
        SessionCollector.stopRecording()
        app.stopService(Intent(app, MonitoringService::class.java))
    }

    // ---- Completed-session History (archived on explicit stop). ----
    /** Header-only summaries for the History list, newest first. Reads disk — call off the main thread. */
    fun historySummaries(): List<HistorySummary> = HistoryStore.list(getApplication())

    /** Full archived session (with series) for the detail view. Reads disk — call off the main thread. */
    fun loadHistorySession(id: Long): HistorySession? = HistoryStore.load(getApplication(), id)
}
