package com.corewatch

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * On-disk archive of *completed* sessions — the "History" log. A session is finalized here exactly
 * once, when the user explicitly stops monitoring (double-back exit or the notification's Stop);
 * process/OS kills never reach this, they just resume the live session (see [SessionCollector]).
 *
 * Each session is one self-contained file `history/<startEpochMillis>.cws`, reusing the same tiny
 * float-series encoding as [SessionStore] plus a fixed header carrying the wall-clock span and the
 * aggregates the list needs — so building the list reads only headers, and the full series load only
 * when a session's detail is opened.
 */
internal object HistoryStore {

    private const val DIR = "history"
    private const val EXT = ".cws"
    private const val VERSION = 1
    private const val MAX_SERIES_POINTS = 100_000 // sanity bound when reading a possibly-corrupt file

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }
    private fun file(context: Context, id: Long) = File(dir(context), "$id$EXT")

    /** Persist a finished session. Atomic swap so a crash mid-write can't corrupt an entry. */
    fun archive(context: Context, s: HistorySession) {
        val snap = s.snapshot
        val target = file(context, s.id)
        val tmp = File(target.parentFile, "${s.id}$EXT.tmp")
        try {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                // --- header: everything the list needs, read without touching the series ---
                out.writeLong(s.startEpochMillis)
                out.writeLong(s.endEpochMillis)
                out.writeInt(snap.cpu.size)
                out.writeInt(snap.historyIntervalSec)
                out.writeFloat(snap.battMaxTempC ?: Float.NaN)
                out.writeFloat(snap.battMinTempC ?: Float.NaN)
                out.writeFloat(snap.battEnergyMwh)
                out.writeInt(snap.battElapsedSec)
                out.writeInt(s.cpuMaxMhz ?: -1)
                out.writeLong(snap.ramTotalBytes)
                // --- body: the four series + resume-gap indices, for the detail charts ---
                writeSeries(out, snap.cpu)
                writeSeries(out, snap.ram)
                writeSeries(out, snap.temp)
                writeSeries(out, snap.power)
                out.writeInt(snap.gaps.size)
                for (g in snap.gaps) out.writeInt(g)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            runCatching { tmp.delete() }
        }
    }

    /** Header-only summaries for the list, newest first. */
    fun list(context: Context): List<HistorySummary> {
        val files = dir(context).listFiles { f -> f.name.endsWith(EXT) } ?: return emptyList()
        return files.mapNotNull { readSummary(it) }.sortedByDescending { it.startEpochMillis }
    }

    /** Full session (with series) for the detail view; null if missing/corrupt. */
    fun load(context: Context, id: Long): HistorySession? {
        val f = file(context, id)
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != VERSION) return null
                val start = inp.readLong()
                val end = inp.readLong()
                inp.readInt() // sampleCount (derivable from the series below)
                val interval = inp.readInt()
                val peak = inp.readFloat().takeIf { !it.isNaN() }
                val min = inp.readFloat().takeIf { !it.isNaN() }
                val energy = inp.readFloat()
                val dischargeSec = inp.readInt()
                val cpuMax = inp.readInt().takeIf { it >= 0 }
                val ramTotal = inp.readLong()
                val cpu = readSeries(inp) ?: return null
                val ram = readSeries(inp) ?: return null
                val temp = readSeries(inp) ?: return null
                val power = readSeries(inp) ?: return null
                val gaps = readInts(inp) ?: return null
                HistorySession(
                    id = start,
                    startEpochMillis = start,
                    endEpochMillis = end,
                    cpuMaxMhz = cpuMax,
                    snapshot = SessionSnapshot(
                        interval, ramTotal, cpu, ram, temp, power, min, peak, energy, dischargeSec, gaps, start,
                    ),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context, id: Long) {
        runCatching { file(context, id).delete() }
    }

    /** Keep the [keep] newest sessions; drop the rest so the archive stays bounded. */
    fun prune(context: Context, keep: Int) {
        val files = dir(context).listFiles { f -> f.name.endsWith(EXT) } ?: return
        if (files.size <= keep) return
        files.sortedByDescending { it.nameWithoutExtension.toLongOrNull() ?: 0L }
            .drop(keep)
            .forEach { runCatching { it.delete() } }
    }

    private fun readSummary(f: File): HistorySummary? = try {
        DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readInt() != VERSION) return null
            val start = inp.readLong()
            val end = inp.readLong()
            val count = inp.readInt()
            val interval = inp.readInt()
            val peak = inp.readFloat().takeIf { !it.isNaN() }
            val min = inp.readFloat().takeIf { !it.isNaN() }
            val energy = inp.readFloat()
            val dischargeSec = inp.readInt()
            HistorySummary(
                id = start,
                startEpochMillis = start,
                endEpochMillis = end,
                sampleCount = count,
                intervalSec = interval,
                peakTempC = peak,
                minTempC = min,
                energyMwh = energy,
                avgPowerW = if (dischargeSec > 0) (energy / 1000f) / (dischargeSec / 3600f) else null,
            )
        }
    } catch (_: Exception) {
        null
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

/** Lightweight per-session row for the History list (read from the file header only). */
data class HistorySummary(
    val id: Long,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val sampleCount: Int,
    val intervalSec: Int,
    val peakTempC: Float?,
    val minTempC: Float?,
    val energyMwh: Float,
    val avgPowerW: Float?,
) {
    /** Wall-clock span of the session in seconds. */
    val durationSec: Int get() = ((endEpochMillis - startEpochMillis) / 1000L).toInt().coerceAtLeast(0)
}

/** A fully-loaded archived session: its wall-clock span plus everything the charts need. */
data class HistorySession(
    val id: Long,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val cpuMaxMhz: Int?,
    val snapshot: SessionSnapshot,
) {
    val durationSec: Int get() = ((endEpochMillis - startEpochMillis) / 1000L).toInt().coerceAtLeast(0)
}
