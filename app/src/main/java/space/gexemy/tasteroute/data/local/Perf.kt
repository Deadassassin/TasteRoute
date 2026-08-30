package space.gexemy.tasteroute.data.local

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.os.Build

enum class DeviceClass { LOW, MID, HIGH }

/**
 * One place decides how expensive the UI is allowed to be.
 */
object Perf {

    private const val SAMPLE_FRAMES = 240
    private const val JANK_FRACTION = 0.18
    private const val IDLE_GAP_NANOS = 250_000_000L
    private const val TAG = "TasteRoutePerf"

    var deviceClass: DeviceClass = DeviceClass.MID
        private set

    private var animatorScale: Float = 1f
    private var sampling = false

    val reducedMotion: Boolean get() = animatorScale < 0.05f

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = am?.isLowRamDevice == true
        val heapMb = am?.memoryClass ?: 128
        val cores = Runtime.getRuntime().availableProcessors()
        val old = Build.VERSION.SDK_INT < 26

        val guess = when {
            lowRam || heapMb <= 96 || cores <= 4 || old -> DeviceClass.LOW
            heapMb >= 256 && cores >= 8 -> DeviceClass.HIGH
            else -> DeviceClass.MID
        }

        val measured = runCatching { DeviceClass.valueOf(Prefs.getString(Prefs.PERF_CLASS)) }.getOrNull()
        deviceClass = if (measured != null && measured < guess) measured else guess

        animatorScale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }

    fun observeFrames(refreshHz: Float) {
        if (sampling || deviceClass == DeviceClass.LOW) return
        sampling = true
        val budgetNanos = (1_000_000_000.0 / refreshHz.coerceIn(50f, 144f) * 1.5).toLong()
        var last = 0L
        var frames = 0
        var late = 0
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (last != 0L) {
                    val delta = frameTimeNanos - last
                    if (delta < IDLE_GAP_NANOS) {
                        frames++
                        if (delta > budgetNanos) late++
                    }
                }
                last = frameTimeNanos
                if (frames < SAMPLE_FRAMES) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    settle(late.toDouble() / frames, refreshHz)
                }
            }
        })
    }

    private fun settle(lateFraction: Double, refreshHz: Float) {
        sampling = false
        if (lateFraction <= JANK_FRACTION) {
            Log.i(TAG, "$deviceClass holds at ${refreshHz.toInt()}Hz (${(lateFraction * 100).toInt()}% late)")
            return
        }
        val next = when (deviceClass) {
            DeviceClass.HIGH -> DeviceClass.MID
            else -> DeviceClass.LOW
        }
        deviceClass = next
        Prefs.put(Prefs.PERF_CLASS, next.name)
        Log.i(TAG, "${(lateFraction * 100).toInt()}% of frames late at ${refreshHz.toInt()}Hz — dropping to $next")
    }

    val richMotion: Boolean get() = deviceClass != DeviceClass.LOW && !reducedMotion
    val lowColorImages: Boolean get() = deviceClass == DeviceClass.LOW
    val imageCacheFraction: Double get() = when (deviceClass) { DeviceClass.LOW -> 0.10; DeviceClass.MID -> 0.18; DeviceClass.HIGH -> 0.25 }
    val cardElevationDp: Int get() = if (deviceClass == DeviceClass.LOW) 0 else 1
    val skeletonCount: Int get() = if (deviceClass == DeviceClass.LOW) 3 else 5
    val candidateBudget: Int get() = if (deviceClass == DeviceClass.LOW) 35 else 60
}
