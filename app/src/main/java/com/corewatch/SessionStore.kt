package com.corewatch

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * On-disk snapshot of the whole session, so an OS kill (e.g. Android reclaiming memory for a
 * foreground game) never loses collected data — the snapshot is reloaded when the app/service next
 * starts. A graceful exit clears it (see [SessionCollector.stop]).
 *
 * Storage is deliberately tiny and self-contained: three bounded float series (≤720 points each)
 * plus the sampling interval and battery aggregates — a few KB, rewritten atomically each tick.
 * Points are keyed by position (index × interval), not per-sample timestamps.
 */
internal object SessionStore {

    private const val FILE = "session.bin"
    // v4: the `power` series changed from signed watts (− draining) to positive draw with NaN while
    // charging, and battEnergy/battElapsed became discharge-only. Bumping invalidates pre-change
    // snapshots so a killed old session restores as empty instead of being misread.
    // v5: added startEpochMillis (wall-clock session start) so a resumed run keeps its real start
    // time — needed to file it into the daily History archive on the eventual explicit stop.
    // v6: added lastTickEpochMillis so a recording orphaned by an app kill can be recovered and
    // archived on next launch with an accurate end time.
    private const val VERSION = 6
    private const val MAX_SERIES_POINTS = 100_000 // sanity bound when reading a possibly-corrupt file

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun save(context: Context, s: SessionSnapshot) {
        val target = file(context)
        val tmp = File(target.parentFile, "$FILE.tmp")
        try {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeLong(s.startEpochMillis)
                out.writeLong(s.lastTickEpochMillis)
                out.writeInt(s.historyIntervalSec)
                out.writeLong(s.ramTotalBytes)
                out.writeFloat(s.battMinTempC ?: Float.NaN)
                out.writeFloat(s.battMaxTempC ?: Float.NaN)
                out.writeFloat(s.battEnergyMwh)
                out.writeInt(s.battElapsedSec)
                writeSeries(out, s.cpu)   // peak clock (MHz) per tick
                writeSeries(out, s.ram)   // used bytes per tick
                writeSeries(out, s.temp)  // battery °C per tick
                writeSeries(out, s.power) // battery draw W (≥0 on battery, NaN charging) per tick
                out.writeInt(s.gaps.size) // indices where the process was killed then resumed
                for (g in s.gaps) out.writeInt(g)
            }
            // Atomic swap so a crash mid-write can't corrupt the last good snapshot.
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            runCatching { tmp.delete() }
        }
    }

    fun load(context: Context): SessionSnapshot? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != VERSION) return null
                val startEpochMillis = inp.readLong()
                val lastTickEpochMillis = inp.readLong()
                val interval = inp.readInt()
                val ramTotal = inp.readLong()
                val min = inp.readFloat().takeIf { !it.isNaN() }
                val max = inp.readFloat().takeIf { !it.isNaN() }
                val energy = inp.readFloat()
                val elapsed = inp.readInt()
                val cpu = readSeries(inp) ?: return null
                val ram = readSeries(inp) ?: return null
                val temp = readSeries(inp) ?: return null
                val power = readSeries(inp) ?: return null
                val gaps = readInts(inp) ?: return null
                SessionSnapshot(interval, ramTotal, cpu, ram, temp, power, min, max, energy, elapsed, gaps, startEpochMillis, lastTickEpochMillis)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun writeSeries(out: DataOutputStream, series: List<Float>) {
        out.writeInt(series.size)
        for (v in series) out.writeFloat(v)
    }

    private fun readSeries(inp: DataInputStream): List<Float>? {
        val n = inp.readInt()
        if (n < 0 || n > MAX_SERIES_POINTS) return null
        val list = ArrayList<Float>(n)
        repeat(n) { list.add(inp.readFloat()) }
        return list
    }

    private fun readInts(inp: DataInputStream): List<Int>? {
        val n = inp.readInt()
        if (n < 0 || n > MAX_SERIES_POINTS) return null
        val list = ArrayList<Int>(n)
        repeat(n) { list.add(inp.readInt()) }
        return list
    }
}

/** Everything needed to restore a session's charts and its "Session" battery footer. */
data class SessionSnapshot(
    val historyIntervalSec: Int,
    val ramTotalBytes: Long,
    val cpu: List<Float>,
    val ram: List<Float>,
    val temp: List<Float>,
    val power: List<Float>,
    val battMinTempC: Float?,
    val battMaxTempC: Float?,
    val battEnergyMwh: Float,
    val battElapsedSec: Int,
    /** Series indices where the process was killed and later resumed (chart discontinuities). */
    val gaps: List<Int>,
    /** Wall-clock time the session began; preserved across kill/resume. 0 if unknown (legacy). */
    val startEpochMillis: Long = 0L,
    /** Wall-clock time of the most recent recorded tick; used as the end time when recovering an
     *  app-killed recording. 0 if unknown (legacy). */
    val lastTickEpochMillis: Long = 0L,
)
