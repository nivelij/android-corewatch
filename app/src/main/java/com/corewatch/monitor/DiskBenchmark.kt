package com.corewatch.monitor

import android.content.Context
import android.os.SystemClock
import android.os.StatFs
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.random.Random

/** One volume's measured sequential speed, bytes/sec. */
data class DiskBenchmarkResult(
    val label: String,
    val kind: StorageKind,
    val writeBytesPerSec: Float,
    /** Sequential read of the just-written file. Note: without root there's no way to drop the OS
     *  page cache, so this can read high (the data is still cached) — the write figure is the
     *  trustworthy one. */
    val readBytesPerSec: Float,
)

/** UI state for the on-demand speed test. */
sealed interface DiskBenchmarkState {
    data object Idle : DiskBenchmarkState
    data object Running : DiskBenchmarkState
    data class Done(val results: List<DiskBenchmarkResult>) : DiskBenchmarkState
    data class Failed(val message: String) : DiskBenchmarkState
}

/**
 * On-demand storage speed test — the fallback for showing a real read/write number on devices where
 * SELinux blocks the passive /proc/diskstats counters (i.e. most stock Android). It writes a temp
 * file to each mounted volume's app-private dir, fsyncs it (so the write actually reaches the device
 * rather than sitting in page cache), reads it back, times both, and deletes the file. Active by
 * nature: it perturbs the device, so it's user-triggered, never part of the recording loop.
 */
object DiskBenchmark {

    private const val TEMP_NAME = "cw_speedtest.tmp"
    private const val TOTAL_BYTES = 64L * 1024 * 1024   // 64 MB per volume — enough to be stable, quick even on SD
    private const val CHUNK = 4 * 1024 * 1024           // 4 MB write/read buffer
    private const val MIN_FREE_BYTES = TOTAL_BYTES + 32L * 1024 * 1024 // skip a volume without headroom

    /** Benchmark every mounted volume (internal + microSD). Runs on IO; safe to call from a VM scope. */
    suspend fun run(context: Context): List<DiskBenchmarkResult> = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val results = ArrayList<DiskBenchmarkResult>()
        ctx.getExternalFilesDirs(null).forEachIndexed { i, dir ->
            dir ?: return@forEachIndexed
            val sv = runCatching { sm.getStorageVolume(dir) }.getOrNull()
            val removable = sv?.isRemovable ?: (i > 0)
            val kind = if (removable) StorageKind.REMOVABLE else StorageKind.INTERNAL
            val label = sv?.getDescription(ctx)?.takeIf { it.isNotBlank() }
                ?: if (i == 0) "Internal" else "microSD"
            runCatching { benchmarkDir(dir, kind, label) }.getOrNull()?.let { results.add(it) }
        }
        results
    }

    /** Write+read a temp file in [dir]; returns null if the volume lacks free space. Always deletes
     *  the temp file, even on failure. */
    private fun benchmarkDir(dir: File, kind: StorageKind, label: String): DiskBenchmarkResult? {
        if (StatFs(dir.path).availableBytes < MIN_FREE_BYTES) return null
        val file = File(dir, TEMP_NAME)
        try {
            file.delete() // clear any stale temp left by a previously-killed run
            val buf = ByteArray(CHUNK).also { Random.nextBytes(it) } // random → no compression/dedupe inflation

            val writeStart = SystemClock.elapsedRealtimeNanos()
            FileOutputStream(file).use { fos ->
                var written = 0L
                while (written < TOTAL_BYTES) {
                    fos.write(buf)
                    written += buf.size
                }
                fos.flush()
                fos.fd.sync() // force to the physical device, so we time real write throughput
            }
            val writeSec = (SystemClock.elapsedRealtimeNanos() - writeStart) / 1e9f

            val readStart = SystemClock.elapsedRealtimeNanos()
            var readTotal = 0L
            FileInputStream(file).use { fis ->
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    readTotal += n
                }
            }
            val readSec = (SystemClock.elapsedRealtimeNanos() - readStart) / 1e9f

            return DiskBenchmarkResult(
                label = label,
                kind = kind,
                writeBytesPerSec = if (writeSec > 0f) TOTAL_BYTES / writeSec else 0f,
                readBytesPerSec = if (readSec > 0f) readTotal / readSec else 0f,
            )
        } finally {
            file.delete() // always clean up
        }
    }
}
