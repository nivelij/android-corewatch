package com.corewatch

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.corewatch.monitor.DeviceInfo
import com.corewatch.monitor.DiskBenchmark
import com.corewatch.monitor.DiskBenchmarkState
import com.corewatch.monitor.LiveMetrics
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Thin adapter over [SessionCollector]. The collector is process-scoped; opening the app initialises
 * it for the live tiles, but recording (history capture + the foreground service/notification) only
 * runs between [startRecording] and [stopRecording].
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    init {
        SessionCollector.ensureInitialized(app)
        // If the app was reopened while a recording is still in progress (resumed after an OS kill),
        // make sure the foreground service is up so it keeps running once we background again.
        if (SessionCollector.isRecording) {
            ContextCompat.startForegroundService(app, Intent(app, MonitoringService::class.java))
        }
    }

    val deviceInfo: DeviceInfo get() = SessionCollector.deviceInfo
    val metrics: StateFlow<LiveMetrics> get() = SessionCollector.metrics
    val cpuHistory: List<Float> get() = SessionCollector.cpuHistory
    val ramHistory: List<Float> get() = SessionCollector.ramHistory
    val tempHistory: List<Float> get() = SessionCollector.tempHistory
    val powerHistory: List<Float> get() = SessionCollector.powerHistory
    val diskReadHistory: List<Float> get() = SessionCollector.diskReadHistory
    val diskWriteHistory: List<Float> get() = SessionCollector.diskWriteHistory
    val gapIndices: List<Int> get() = SessionCollector.gapIndices
    val ramTotalBytes: Long get() = SessionCollector.ramTotalBytes
    val historyIntervalSec: Int get() = SessionCollector.historyIntervalSec
    /** False when the OS blocks /proc/stat: CPU charts show clock (GHz) instead of load (%). */
    val cpuLoadSupported: Boolean get() = SessionCollector.cpuLoadSupported
    /** False when the OS blocks /proc/diskstats: the Storage card shows capacity only, no throughput. */
    val diskIoSupported: Boolean get() = SessionCollector.diskIoSupported
    val batterySession: BatterySession get() = SessionCollector.batterySession

    // ---- On-demand disk speed test. ----
    /** State of the storage speed test (observable — reading it recomposes the Storage card). */
    var diskBenchmarkState: DiskBenchmarkState by mutableStateOf(DiskBenchmarkState.Idle)
        private set

    /** Run the write/read benchmark on every mounted volume, off the main thread. Ignored if already
     *  running. Works regardless of [diskIoSupported] — this is the fallback that shows a real speed
     *  number where the passive counters are SELinux-blocked. */
    fun runDiskBenchmark() {
        if (diskBenchmarkState is DiskBenchmarkState.Running) return
        diskBenchmarkState = DiskBenchmarkState.Running
        viewModelScope.launch {
            diskBenchmarkState = try {
                DiskBenchmarkState.Done(DiskBenchmark.run(getApplication()))
            } catch (e: Exception) {
                DiskBenchmarkState.Failed(e.message ?: "Speed test failed")
            }
        }
    }

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
