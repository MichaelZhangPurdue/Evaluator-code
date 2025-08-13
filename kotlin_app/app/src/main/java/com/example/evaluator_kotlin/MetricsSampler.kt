package com.example.evaluator_kotlin

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class MetricsSnapshot(
    val frameIndex: Int,
    val fps: Double,
    val appCpuPercent: Double,
    val pssMB: Int,
    val javaHeapMB: Double,
    val nativeHeapMB: Double,
    val inferenceMs: Double? = null,
    val systemRamUsedPercent: Double,
    val appRamPercentOfTotal: Double,
    val gpuBusyPercent: Double? = null,
    val gpuFreqMHz: Int? = null
)

class MetricsSampler(private val context: Context) {
    private var lastWallNs = 0L
    private var lastAppCpuMs = 0L
    private var framesSinceLast = 0
    private val cores = max(Runtime.getRuntime().availableProcessors(), 1)

    fun start() {
        lastWallNs = System.nanoTime()
        lastAppCpuMs = Process.getElapsedCpuTime() // ms of CPU used by this process
        framesSinceLast = 0
    }

    fun sample(frameIndex: Int, inferenceMs: Double? = null): MetricsSnapshot {
        framesSinceLast++
        val nowNs = System.nanoTime()
        val dtNs = nowNs - lastWallNs
        val dtSec = dtNs / 1e9
        val fps = if (dtSec > 0) framesSinceLast / dtSec else 0.0

        // App CPU time since last sample (ms)
        val appCpuMs = Process.getElapsedCpuTime()
        val dAppMs = (appCpuMs - lastAppCpuMs).coerceAtLeast(0L)
        val dWallMs = (dtNs / 1e6).coerceAtLeast(1.0) // avoid /0

        // Approx % of total CPU capacity across all cores
        val appCpuPercent = ((dAppMs / dWallMs) / cores) * 100.0
        val clampedCpu = min(100.0, max(0.0, appCpuPercent))

        // Memory
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0]
        val pssMB = mi.totalPss / 1024

        val rt = Runtime.getRuntime()
        val javaHeapMB = (rt.totalMemory() - rt.freeMemory()).toDouble() / (1024.0 * 1024.0)
        val nativeHeapMB = Debug.getNativeHeapAllocatedSize().toDouble() / (1024.0 * 1024.0)

        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalRamMB = memInfo.totalMem / (1024.0 * 1024.0)
        val availRamMB = memInfo.availMem / (1024.0 * 1024.0)
        val usedRamMB = totalRamMB - availRamMB
        val systemRamUsedPercent = if (totalRamMB > 0) (usedRamMB / totalRamMB) * 100.0 else 0.0
        val appRamPercentOfTotal = if (totalRamMB > 0) (pssMB / totalRamMB) * 100.0 else 0.0

        // GPU stats (best effort; may be null on some devices due to SELinux/permissions)
        val gpuPct = queryGpuBusyPercent()
        val gpuMHz = queryGpuFreqMHz()

        // update window
        lastWallNs = nowNs
        lastAppCpuMs = appCpuMs
        framesSinceLast = 0

        return MetricsSnapshot(
            frameIndex = frameIndex,
            fps = fps,
            appCpuPercent = clampedCpu,
            pssMB = pssMB,
            javaHeapMB = javaHeapMB,
            nativeHeapMB = nativeHeapMB,
            inferenceMs = inferenceMs,
            systemRamUsedPercent = systemRamUsedPercent,
            appRamPercentOfTotal = appRamPercentOfTotal,
            gpuBusyPercent = gpuPct,
            gpuFreqMHz = gpuMHz
        )
    }

    // ---------- Helpers for best-effort GPU stats ----------
    private fun readIntFile(path: String): Int? = try {
        File(path).takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()
    } catch (_: Throwable) { null }

    /** Best-effort device-level GPU % busy (not per-app). */
    private fun queryGpuBusyPercent(): Double? {
        // Qualcomm Adreno (KGSL)
        // Returns 0..100 on some devices
        return readIntFile("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.toDouble()
        // Some devices expose a 0..255 “busy” value (normalize to %)
            ?: readIntFile("/sys/class/kgsl/kgsl-3d0/devfreq/cur_busy")?.let { it / 255.0 * 100.0 }
            // ARM Mali (varies by kernel/vendor)
            ?: readIntFile("/sys/class/devfreq/gpu/load")?.toDouble()
            ?: readIntFile("/sys/devices/platform/mali/utilization")?.toDouble()
    }


    /** Best-effort current GPU frequency in MHz (device-level). */
    private fun queryGpuFreqMHz(): Int? {
        // Qualcomm Adreno current frequency (Hz -> MHz)
        return readIntFile("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")?.let { hz -> hz / 1_000_000 }
        // Mali examples
            ?: readIntFile("/sys/class/devfreq/gpu/cur_freq")?.let { hz -> hz / 1_000_000 }
    }

}
