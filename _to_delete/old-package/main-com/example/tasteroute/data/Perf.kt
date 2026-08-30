package com.example.tasteroute.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build

enum class DeviceClass { LOW, MID, HIGH }

/**
 * One place decides how expensive the UI is allowed to be. Read at composition time by anything
 * that would otherwise cost a low-end phone frames: shadows, clip paths, image bitmap config,
 * how many placeholder rows are drawn while loading.
 *
 * Resolved once in Application.onCreate — these inputs cannot change while the process lives.
 */
object Perf {

    var deviceClass: DeviceClass = DeviceClass.MID
        private set

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = am?.isLowRamDevice == true
        val heapMb = am?.memoryClass ?: 128
        val cores = Runtime.getRuntime().availableProcessors()
        val old = Build.VERSION.SDK_INT < 26

        deviceClass = when {
            lowRam || heapMb <= 96 || cores <= 4 || old -> DeviceClass.LOW
            heapMb >= 256 && cores >= 8 -> DeviceClass.HIGH
            else -> DeviceClass.MID
        }
    }

    /** Path clipping and shadow work; off on LOW, where it is the first thing to drop frames. */
    val richMotion: Boolean get() = deviceClass != DeviceClass.LOW

    /** RGB_565 halves bitmap memory. Food photos are noisy enough to hide the banding. */
    val lowColorImages: Boolean get() = deviceClass == DeviceClass.LOW

    /** Fraction of the app's heap Coil may hold. The default 25% evicts real work on small heaps. */
    val imageCacheFraction: Double
        get() = when (deviceClass) {
            DeviceClass.LOW -> 0.10
            DeviceClass.MID -> 0.18
            DeviceClass.HIGH -> 0.25
        }

    val cardElevationDp: Int get() = if (deviceClass == DeviceClass.LOW) 0 else 1

    /** Skeleton rows to draw before the first result lands. */
    val skeletonCount: Int get() = if (deviceClass == DeviceClass.LOW) 3 else 5

    /** Places handed to the local scorer. Scoring is O(n) but allocates per candidate. */
    val candidateBudget: Int get() = if (deviceClass == DeviceClass.LOW) 35 else 60
}
