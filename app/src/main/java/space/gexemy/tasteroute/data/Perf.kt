package space.gexemy.tasteroute.data

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.os.Build

enum class DeviceClass { LOW, MID, HIGH }

/**
 * One place decides how expensive the UI is allowed to be. Read at composition time by anything
 * that would otherwise cost a phone frames: shadows, clip paths, image bitmap config, how many
 * placeholder rows are drawn while loading, how long a transition runs.
 *
 * THE CLASS IS MEASURED, NOT GUESSED — changed 2026-08-27, and this is the point of the file now.
 * The static inputs available before the first frame cannot separate a mid-range phone from a
 * flagship: a Galaxy A53 reports eight cores and a 256MB heap, and so does a device three times
 * quicker, so the old rule (`heapMb >= 256 && cores >= 8`) classified it HIGH and handed it every
 * expensive path in the app at once — full-length transitions, a scale on every tab change,
 * shimmer, staggered entries, ARGB_8888 bitmaps and a quarter of the heap held as images. That is
 * the sluggishness. [observeFrames] watches what the phone actually manages and drops a class when
 * it is missing its deadline, which is a fact no spec sheet carries.
 */
object Perf {

    /** Frames to watch before deciding. About four seconds of use at 60Hz. */
    private const val SAMPLE_FRAMES = 240

    /** Over this share of frames past the deadline, the phone is not keeping up. */
    private const val JANK_FRACTION = 0.18

    /** A gap longer than this is the app idle between interactions, not the app being slow. */
    private const val IDLE_GAP_NANOS = 250_000_000L

    private const val TAG = "TasteRoutePerf"

    var deviceClass: DeviceClass = DeviceClass.MID
        private set

    private var animatorScale: Float = 1f
    private var sampling = false

    /**
     * The OS "Remove animations" switch. Respecting it is not a nicety: it is how people who get
     * motion sick turn this off, and an app that animates anyway is the one they uninstall. It is
     * also free performance on a phone whose owner has already asked for it.
     */
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

        // What this handset was measured at last time beats what its spec sheet implies, but it
        // can only ever lower the class: a phone that kept up once is not thereby a flagship, and
        // a measurement taken while the app sat idle must not promote anything.
        val measured = runCatching { DeviceClass.valueOf(Prefs.getString(Prefs.PERF_CLASS)) }.getOrNull()
        deviceClass = if (measured != null && measured < guess) measured else guess

        animatorScale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }


    /**
     * Samples real frame intervals and drops a class when too many arrive late.
     *
     * Only a DOWNGRADE is written. An unremarkable sample proves nothing — the app may have been
     * sitting on a static screen — so a quiet run leaves the stored class alone and measures again
     * next launch, which eventually catches a sample taken during a scroll. The in-process change
     * reaches anything composed after it; the persisted one is what makes the next launch open
     * already tuned.
     *
     * @param refreshHz the panel's own rate, so a 120Hz screen is held to 120Hz rather than being
     *   let off at 60 and feeling every bit as choppy as the complaint described.
     */
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

    /** Path clipping and shadow work; off on LOW, where it is the first thing to drop frames. */
    val richMotion: Boolean get() = deviceClass != DeviceClass.LOW && !reducedMotion

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
