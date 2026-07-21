package com.corewatch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.corewatch.monitor.DeviceInfo
import com.corewatch.monitor.IoChannel
import com.corewatch.monitor.LiveMetrics
import com.corewatch.monitor.MetricsReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private const val LIVE_INTERVAL_MS = 1_000L
private const val HISTORY_BASE_INTERVAL_SEC = 5
private const val HISTORY_MAX_POINTS = 720   // ~1h @5s before the timeline is compressed
private const val MIN_ARCHIVE_SEC = 30       // recording is explicit now; only drop a fat-fingered start→stop
private const val MAX_HISTORY_SESSIONS = 200 // keep the newest N completed sessions on disk
private const val PREFS = "corewatch"
private const val KEY_RECORDING = "recording_active" // survives an OS kill so recording resumes, not ends

/**
 * Process-scoped telemetry collector. Lives beyond any single Activity so a foreground service can
 * keep it running in the background; the [MonitorViewModel] simply exposes its state to the UI.
 *
 * [start] is idempotent. The whole-session recorder (history + battery aggregates) runs in
 * [scope] independent of UI subscription; the per-second [metrics] flow pauses when nobody observes
 * it (battery-friendly), which is fine because the recorder is what accumulates the session.
 */
object SessionCollector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var reader: MetricsReader
    private lateinit var appContext: Context

    /** Device identity + SoC facts; read once when collection first starts. */
    lateinit var deviceInfo: DeviceInfo
        private set

    /** Live values for the tiles — sampled every second while observed. */
    val metrics: StateFlow<LiveMetrics> = flow {
        while (currentCoroutineContext().isActive) {
            emit(reader.sample())
            delay(LIVE_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), LiveMetrics.EMPTY)

    // ---- Whole-session history (charts). ----
    val cpuHistory = mutableStateListOf<Float>()
    val ramHistory = mutableStateListOf<Float>()
    val tempHistory = mutableStateListOf<Float>()
    val powerHistory = mutableStateListOf<Float>() // battery draw in W, ≥0 on battery; NaN while charging
    val diskReadHistory = mutableStateListOf<Float>()  // device-wide disk read B/s; NaN when unsupported/first tick
    val diskWriteHistory = mutableStateListOf<Float>() // device-wide disk write B/s; NaN when unsupported/first tick

    /**
     * Series indices marking a discontinuity: the point where a previous (killed) session ended and
     * a resumed one picks up. The charts draw an explicit "app was stopped / resumed" break here, so
     * a restored session never masquerades as continuous recording.
     */
    val gapIndices = mutableStateListOf<Int>()

    var ramTotalBytes by mutableLongStateOf(0L)
        private set
    var historyIntervalSec by mutableIntStateOf(HISTORY_BASE_INTERVAL_SEC)
        private set

    // Wall-clock start of the active recording; used to file the run into History on stop. 0 = idle.
    private var sessionStartMillis = 0L
    // Wall-clock time of the last recorded tick; the end time when recovering an app-killed recording.
    private var lastTickMillis = 0L

    /** True while a session is actively recording (foreground service + notification present). */
    var isRecording by mutableStateOf(false)
        private set

    /** Start of the active recording, for the UI's elapsed timer; 0 when idle. */
    val recordingStartMillis: Long get() = if (isRecording) sessionStartMillis else 0L

    /** False when the OS blocks /proc/stat for the app: no CPU load available, so the CPU series and
     *  charts use clock (MHz/GHz) instead of load (%). Constant per device. */
    val cpuLoadSupported: Boolean get() = ::reader.isInitialized && reader.cpuLoadSupported

    /** False when the OS blocks /proc/diskstats for the app: no disk throughput, so the Storage card
     *  shows capacity only and the disk charts are omitted. Constant per device. */
    val diskIoSupported: Boolean get() = ::reader.isInitialized && reader.diskIoSupported

    // ---- Whole-session battery aggregates (since collection started). ----
    private var batteryTempMinC by mutableStateOf<Float?>(null)
    private var batteryTempMaxC by mutableStateOf<Float?>(null)
    private var batteryEnergyMwh by mutableFloatStateOf(0f)
    private var batteryDischargeSec by mutableIntStateOf(0)

    val batterySession: BatterySession
        get() = BatterySession(
            minTempC = batteryTempMinC,
            maxTempC = batteryTempMaxC,
            energyMwh = batteryEnergyMwh,
            avgPowerW = if (batteryDischargeSec > 0) {
                (batteryEnergyMwh / 1000f) / (batteryDischargeSec / 3600f)
            } else {
                null
            },
        )

    private var recorderJob: Job? = null

    /**
     * Initialise the reader + device facts for the live tiles. If a recording was in progress when
     * the process died (the persisted [KEY_RECORDING] flag is set), resume it — so an OS kill during
     * a game continues the same session (with a gap seam) instead of ending it. Otherwise archive any
     * stray orphaned snapshot. Idempotent; `@Synchronized` because the service (on a START_STICKY
     * respawn) and the ViewModel can both reach here on a fresh process.
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (::reader.isInitialized) return
        appContext = context.applicationContext
        reader = MetricsReader(appContext)
        deviceInfo = DeviceInfo.read(appContext, reader)
        if (recordingFlag()) resumeRecording() else recoverOrphanedRecording()
    }

    /**
     * Begin an explicit recording: accumulate the history series + battery aggregates, persisting a
     * snapshot each tick so a kill loses at most one interval. Idempotent. The caller owns the
     * foreground service / notification that keeps this alive in the background.
     */
    @Synchronized
    fun startRecording(context: Context) {
        ensureInitialized(context)
        if (recorderJob?.isActive == true) return
        sessionStartMillis = System.currentTimeMillis()
        lastTickMillis = sessionStartMillis
        isRecording = true
        setRecordingFlag(true)
        beginRecorderLoop()
    }

    /**
     * Continue a recording that was interrupted by a process/OS kill: reload the persisted series and
     * pick up recording where it left off, with a gap seam marking the interruption. Falls back to a
     * fresh recording if the flag is set but no snapshot survived.
     */
    private fun resumeRecording() {
        val snap = SessionStore.load(appContext)
        if (snap != null && snap.cpu.isNotEmpty()) {
            historyIntervalSec = snap.historyIntervalSec.coerceAtLeast(HISTORY_BASE_INTERVAL_SEC)
            sessionStartMillis = snap.startEpochMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            lastTickMillis = snap.lastTickEpochMillis.takeIf { it > 0L } ?: sessionStartMillis
            ramTotalBytes = snap.ramTotalBytes
            gapIndices.addAll(snap.gaps)
            cpuHistory.addAll(snap.cpu)
            ramHistory.addAll(snap.ram)
            tempHistory.addAll(snap.temp)
            powerHistory.addAll(snap.power)
            diskReadHistory.addAll(snap.diskRead)
            diskWriteHistory.addAll(snap.diskWrite)
            batteryTempMinC = snap.battMinTempC
            batteryTempMaxC = snap.battMaxTempC
            batteryEnergyMwh = snap.battEnergyMwh
            batteryDischargeSec = snap.battElapsedSec
            // A break between the last recorded point and where recording resumes now.
            val boundary = cpuHistory.size
            if (boundary > 0) gapIndices.add(boundary)
        } else {
            sessionStartMillis = System.currentTimeMillis()
            lastTickMillis = sessionStartMillis
        }
        isRecording = true
        beginRecorderLoop()
    }

    private fun beginRecorderLoop() {
        if (recorderJob?.isActive == true) return
        recorderJob = scope.launch {
            while (isActive) {
                val sample = withContext(Dispatchers.IO) { reader.sample(IoChannel.RECORDER) }
                lastTickMillis = System.currentTimeMillis()
                ramTotalBytes = sample.ramTotalBytes
                // CPU series holds load % where the OS exposes /proc/stat (a meaningful, always-moving
                // trace, unlike a capped/pinned clock); on devices that block it for apps, fall back to
                // peak clock (MHz) so the chart still renders. cpuLoadSupported picks the axis/format.
                cpuHistory.add(
                    if (reader.cpuLoadSupported) sample.cpu.overallLoad?.let { it * 100f } ?: Float.NaN
                    else sample.cpu.currentMaxMhz?.toFloat() ?: Float.NaN,
                )
                ramHistory.add(sample.ramUsedBytes.toFloat())
                tempHistory.add(sample.battery.tempC ?: Float.NaN)
                // Power *draw* only exists on battery; while charging it's N/A (NaN → chart break).
                val drawW = sample.battery.drawW
                powerHistory.add(drawW ?: Float.NaN)
                // Disk throughput is NaN when the OS blocks the counters or on the first tick (no
                // baseline); appended every tick regardless so all series stay index-aligned with the
                // gap markers and decimation.
                diskReadHistory.add(sample.disk.aggReadBytesPerSec)
                diskWriteHistory.add(sample.disk.aggWriteBytesPerSec)

                sample.battery.tempC?.let { t ->
                    batteryTempMinC = batteryTempMinC?.let { min(it, t) } ?: t
                    batteryTempMaxC = batteryTempMaxC?.let { max(it, t) } ?: t
                }
                // Energy + time accrue only while discharging, so Energy = drawn-from-battery and
                // Avg = average draw, not diluted by charging / idle-plugged stretches.
                if (drawW != null) {
                    batteryEnergyMwh += drawW * (historyIntervalSec / 3600f) * 1000f
                    batteryDischargeSec += historyIntervalSec
                }

                if (cpuHistory.size > HISTORY_MAX_POINTS) {
                    decimate(cpuHistory)
                    decimate(ramHistory)
                    decimate(tempHistory)
                    decimate(powerHistory)
                    decimate(diskReadHistory)
                    decimate(diskWriteHistory)
                    remapGapsAfterDecimate(cpuHistory.size)
                    historyIntervalSec *= 2
                }
                // Persist after each tick so a kill loses at most one interval of data.
                withContext(Dispatchers.IO) { SessionStore.save(appContext, snapshot()) }
                delay(historyIntervalSec * 1_000L)
            }
        }
    }

    private fun recordingFlag(): Boolean =
        ::appContext.isInitialized &&
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RECORDING, false)

    private fun setRecordingFlag(active: Boolean) {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RECORDING, active).apply()
    }

    /**
     * Stop the active recording and clear it (on disk too). Called on an explicit stop — the in-app
     * Stop control, the notification's Stop, a swipe-away, or app exit. A run that lasted long enough
     * is finalized into the History archive first. No-op if not recording.
     */
    @Synchronized
    fun stopRecording() {
        if (recorderJob == null && !isRecording) return
        recorderJob?.cancel()
        recorderJob = null
        archiveIfWorthKeeping()
        isRecording = false
        setRecordingFlag(false)
        cpuHistory.clear()
        ramHistory.clear()
        tempHistory.clear()
        powerHistory.clear()
        diskReadHistory.clear()
        diskWriteHistory.clear()
        ramTotalBytes = 0L
        historyIntervalSec = HISTORY_BASE_INTERVAL_SEC
        batteryTempMinC = null
        batteryTempMaxC = null
        batteryEnergyMwh = 0f
        batteryDischargeSec = 0
        gapIndices.clear()
        sessionStartMillis = 0L
        lastTickMillis = 0L
        if (::appContext.isInitialized) SessionStore.clear(appContext)
    }

    /**
     * On an explicit stop, file the just-finished run into the History archive — but only if it ran
     * at least [MIN_ARCHIVE_SEC] (so momentary app-opens don't clutter the log), then prune to the
     * newest [MAX_HISTORY_SESSIONS].
     */
    private fun archiveIfWorthKeeping() {
        if (!::appContext.isInitialized || sessionStartMillis == 0L || cpuHistory.isEmpty()) return
        val end = System.currentTimeMillis()
        if ((end - sessionStartMillis) / 1000L < MIN_ARCHIVE_SEC) return
        HistoryStore.archive(
            appContext,
            HistorySession(
                id = sessionStartMillis,
                startEpochMillis = sessionStartMillis,
                endEpochMillis = end,
                cpuMaxMhz = if (::deviceInfo.isInitialized) deviceInfo.maxClockMhz else null,
                snapshot = snapshot(),
            ),
        )
        HistoryStore.prune(appContext, MAX_HISTORY_SESSIONS)
    }

    private fun snapshot() = SessionSnapshot(
        historyIntervalSec = historyIntervalSec,
        ramTotalBytes = ramTotalBytes,
        cpu = cpuHistory.toList(),
        ram = ramHistory.toList(),
        temp = tempHistory.toList(),
        power = powerHistory.toList(),
        diskRead = diskReadHistory.toList(),
        diskWrite = diskWriteHistory.toList(),
        battMinTempC = batteryTempMinC,
        battMaxTempC = batteryTempMaxC,
        battEnergyMwh = batteryEnergyMwh,
        battElapsedSec = batteryDischargeSec,
        gaps = gapIndices.toList(),
        startEpochMillis = sessionStartMillis,
        lastTickEpochMillis = lastTickMillis,
    )

    /**
     * A session snapshot left on disk means a recording was in progress when the app was killed. In
     * the recording model an app kill *ends* the session, so we archive it (if it ran long enough)
     * rather than resuming — then clear it. Recording always starts off after this.
     */
    private fun recoverOrphanedRecording() {
        val snap = SessionStore.load(appContext) ?: return
        SessionStore.clear(appContext)
        if (snap.cpu.isEmpty()) return
        val start = snap.startEpochMillis
        val end = snap.lastTickEpochMillis.takeIf { it > 0L } ?: start
        if (start <= 0L || (end - start) / 1000L < MIN_ARCHIVE_SEC) return
        HistoryStore.archive(
            appContext,
            HistorySession(
                id = start,
                startEpochMillis = start,
                endEpochMillis = end,
                cpuMaxMhz = if (::deviceInfo.isInitialized) deviceInfo.maxClockMhz else null,
                snapshot = snap,
            ),
        )
        HistoryStore.prune(appContext, MAX_HISTORY_SESSIONS)
    }

    /** Series were halved (step 2), so every gap index maps to i/2; drop any that fall out of range. */
    private fun remapGapsAfterDecimate(newSize: Int) {
        val mapped = gapIndices.map { it / 2 }.distinct().filter { it in 1 until newSize }
        gapIndices.clear()
        gapIndices.addAll(mapped)
    }

    /** Halve a series by keeping every other point — preserves whole-session coverage, bounded. */
    private fun decimate(series: MutableList<Float>) {
        val kept = ArrayList<Float>(series.size / 2 + 1)
        for (i in series.indices step 2) kept.add(series[i])
        series.clear()
        series.addAll(kept)
    }
}

/** Whole-session battery aggregates since collection started. */
data class BatterySession(
    val minTempC: Float?,
    val maxTempC: Float?,
    val avgPowerW: Float?,
    val energyMwh: Float,
)
