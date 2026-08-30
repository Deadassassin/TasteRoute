import sys, os
ROOT = os.path.expanduser("~/mnt/TasteRoute/app/src/main/java/space/gexemy/tasteroute")

edits = []   # (relpath, old, new, count)
writes = []  # (relpath, content)

def E(p, old, new, n=1): edits.append((p, old, new, n))
def W(p, c): writes.append((p, c))

# ---------------------------------------------------------------- Prefs keys
E("data/Prefs.kt",
'    const val NIM_MODEL_AT = "nim_model_at"\n',
'''    const val NIM_MODEL_AT = "nim_model_at"
    const val NIM_MODEL_DEAD = "nim_model_dead"
    const val PERF_CLASS = "perf_class"
''')

# ---------------------------------------------------------------- NimClient
N = "data/NimClient.kt"

E(N,
'''data class ModelProbe(val model: String, val millis: Long, val error: String? = null) {
    val ok: Boolean get() = error == null
}''',
'''data class ModelProbe(
    val model: String,
    val millis: Long,
    val error: String? = null,
    /** HTTP 404 — this id is not served at all, as distinct from being slow or failing today. */
    val notFound: Boolean = false,
) {
    val ok: Boolean get() = error == null
}''')

E(N,
'''    private var chosenModel: String? = null
    private val failed = mutableSetOf<String>()''',
'''    private var chosenModel: String? = null

    /** Slow or unhappy this session. A fresh race clears it. */
    private val failed = mutableSetOf<String>()

    /**
     * Ids NVIDIA answered 404 for. A 404 is not weather: that name is not served, and it will not
     * be served in five minutes either. Kept across launches so a retired id costs one request
     * ever rather than one per app open, and forgiven only when the catalogue lists it again.
     */
    private val dead = mutableSetOf<String>()''')

E(N,
'''    val activeModel: String
        get() = pinned ?: chosenModel ?: candidates.firstOrNull { it !in failed }
            ?: candidates.firstOrNull() ?: "meta/llama-3.2-3b-instruct"''',
'''    val activeModel: String
        get() = pinned
            ?: chosenModel?.takeIf { it !in dead }
            ?: candidates.firstOrNull { it !in failed && it !in dead }
            ?: candidates.firstOrNull { it !in dead }
            ?: candidates.firstOrNull()
            ?: "meta/llama-3.2-3b-instruct"''')

E(N,
'''        val saved = Prefs.getString(Prefs.NIM_MODEL_CHOICE)
        if (saved.isNotBlank() && saved in candidates) chosenModel = saved
    }''',
'''        Prefs.getString(Prefs.NIM_MODEL_DEAD)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .let(dead::addAll)
        val saved = Prefs.getString(Prefs.NIM_MODEL_CHOICE)
        if (saved.isNotBlank() && saved in candidates && saved !in dead) chosenModel = saved
    }''')

# refreshCandidates -> server list + NVIDIA catalogue
E(N,
'''    private suspend fun refreshCandidates() {
        if (!GexemyClient.isConfigured) return
        val live = runCatching { GexemyClient.aiModels() }.getOrNull()
            ?.models.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (live.isEmpty() || live == candidates) return

        candidates = live
        Prefs.put(Prefs.NIM_MODELS_LIVE, live.joinToString(","))
        failed.clear()
        Log.i(TAG, "Live models from the server: ${live.joinToString(", ")}")
        // Held in a local: Kotlin will not smart-cast a mutable member property, so `chosenModel
        // !in live` is a String? against a List<String> and does not compile.
        val current = chosenModel
        if (current != null && current !in live) {
            Log.i(TAG, "$current is no longer served; re-racing")
            chosenModel = null
            Prefs.remove(Prefs.NIM_MODEL_CHOICE, Prefs.NIM_MODEL_AT)
        }
    }''',
'''    private suspend fun refreshCandidates() {
        val live = if (GexemyClient.isConfigured) {
            runCatching { GexemyClient.aiModels() }.getOrNull()
                ?.models.orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        var next = live.ifEmpty { candidates }

        // Then NVIDIA's own catalogue, which is asked even when our server answered: our list is
        // curated by hand and an id can be retired between two deploys of it.
        val catalog = catalogModels()
        if (catalog != null) {
            // A listing is a second chance: an id that is published again stops being written off.
            dead.retainAll { it !in catalog }
            val served = next.filter { it in catalog }
            // An empty intersection means the catalogue is telling us something we do not
            // understand — a renamed field, a scoped key — not that there are no models. Keep the
            // list we had rather than emptying it on a shape change.
            if (served.isNotEmpty()) {
                dead += next - served.toSet()
                next = served
            }
        }
        Prefs.put(Prefs.NIM_MODEL_DEAD, dead.joinToString(","))

        if (next != candidates) {
            candidates = next
            Prefs.put(Prefs.NIM_MODELS_LIVE, next.joinToString(","))
            failed.clear()
            Log.i(TAG, "Candidates: ${next.joinToString(", ")}")
        }
        // Held in a local: Kotlin will not smart-cast a mutable member property, so `chosenModel
        // !in next` is a String? against a List<String> and does not compile.
        val current = chosenModel
        if (current != null && (current !in candidates || current in dead)) {
            Log.i(TAG, "$current is no longer served; re-racing")
            chosenModel = null
            Prefs.remove(Prefs.NIM_MODEL_CHOICE, Prefs.NIM_MODEL_AT)
        }
    }

    /**
     * The ids NVIDIA itself publishes, or null when the catalogue could not be read.
     *
     * This is the fix for the 404s rather than a tidy-up of them. A 404 means the name in the
     * request is not one this endpoint serves, and the endpoint will list the names it does serve
     * for the asking — so intersecting the candidate list with it removes the entire class of
     * failure before a single ranking request is made. It does NOT prove a listed model still
     * answers (NVIDIA went on listing meta/llama-3.1-8b-instruct for days after it stopped), which
     * is why [probeModels] still races: the catalogue rules out names, the race rules out
     * latency, and neither check substitutes for the other.
     *
     * Failure is silent and changes nothing. A catalogue we cannot read is not evidence that a
     * model is gone, and treating it as such would blank the ranker on every phone at once.
     */
    private suspend fun catalogModels(): Set<String>? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        runCatching {
            val raw = httpGet(
                url = "${BuildConfig.NIM_BASE_URL}/models",
                headers = mapOf("Authorization" to "Bearer ${BuildConfig.NIM_API_KEY}"),
                accept = "application/json",
                readTimeoutMs = PROBE_TIMEOUT_MS,
                connectTimeoutMs = RANK_CONNECT_MS,
            )
            AppJson.decodeFromString(ModelList.serializer(), raw).data.map { it.id.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }''')

# probeModels: skip dead, record 404s
E(N,
'''        val results = candidates.map { candidate ->''',
'''        // Nothing is learned by pinging a name the catalogue does not carry, and every probe of
        // one is a round trip the person waits through on first open.
        val pool = candidates.filter { it !in dead }.ifEmpty { candidates }
        val results = pool.map { candidate ->''')

E(N,
'''                    onFailure = { error ->
                        val elapsed = System.currentTimeMillis() - startedAt
                        ModelProbe(candidate, elapsed, describe(error, (error as? HttpException)?.code, elapsed))
                    },''',
'''                    onFailure = { error ->
                        val elapsed = System.currentTimeMillis() - startedAt
                        val code = (error as? HttpException)?.code
                        ModelProbe(candidate, elapsed, describe(error, code, elapsed, candidate), code == 404)
                    },''')

E(N,
'''        lastProbe = results
        results.firstOrNull { it.ok }?.let { winner ->''',
'''        lastProbe = results
        val gone = results.filter { it.notFound }.map { it.model }
        if (gone.isNotEmpty()) {
            dead += gone
            Prefs.put(Prefs.NIM_MODEL_DEAD, dead.joinToString(","))
        }
        results.firstOrNull { it.ok }?.let { winner ->''')

# markDead alongside demoteActiveModel
E(N,
'''    /** Drops the current model for this session and moves to the next untried candidate. */
    private fun demoteActiveModel() {''',
'''    /**
     * Writes an id off for good and returns the next one worth trying, or null when the list is
     * exhausted.
     *
     * What this replaces is the actual bug behind "it just gives a lot of 404s": the old path
     * stepped forward one candidate and then rethrew, so a list holding three retired ids cost
     * three failed searches to walk, and because the set of failures lived only in memory the
     * walk started again from the top at the next app open. Nothing ever concluded that a name
     * was gone. Now one request establishes it, permanently, and the caller retries immediately.
     */
    private fun markDead(model: String): String? {
        if (pinned != null) return null
        dead += model
        failed -= model
        Prefs.put(Prefs.NIM_MODEL_DEAD, dead.joinToString(","))
        val next = candidates.firstOrNull { it !in dead && it !in failed }
            ?: candidates.firstOrNull { it !in dead }
        if (next != null) {
            chosenModel = next
            Prefs.put(Prefs.NIM_MODEL_CHOICE, next)
            // Cleared, not set: this is what is left after a failure rather than a considered
            // pick, so the next open races again instead of trusting it for a day.
            Prefs.remove(Prefs.NIM_MODEL_AT)
        } else {
            chosenModel = null
            Prefs.remove(Prefs.NIM_MODEL_CHOICE, Prefs.NIM_MODEL_AT)
        }
        Log.w(TAG, "$model is not served (404) — ${next?.let { "switching to $it" } ?: "no candidates left"}")
        return next
    }

    /** Drops the current model for this session and moves to the next untried candidate. */
    private fun demoteActiveModel() {''')

# chat(): retry down the list instead of failing the request
E(N,
'''        if (!isConfigured) throw NimException("NIM_API_KEY missing from local.properties")
        val startedAt = System.currentTimeMillis()
        try {
            val json = rawChat(messages, maxTokens, model, connectTimeoutMs, readTimeoutMs, temperature, requireJson)
            Backoff.clear(backoffKey)
            lastFailure = null
            Log.i(TAG, "$backoffKey OK ($model in ${System.currentTimeMillis() - startedAt}ms)")
            return json
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startedAt
            val code = (e as? HttpException)?.code
            // A model that times out or doesn't exist is the wrong model, not a broken service.
            if (e is java.net.SocketTimeoutException || code == 404) demoteActiveModel()
            Backoff.trip(
                backoffKey,
                if (code != null && code in CONFIG_CODES) CONFIG_BACKOFF_MS else FAILURE_BACKOFF_MS,
            )
            fail(describe(e, code, elapsed))
            throw e
        }
    }''',
'''        if (!isConfigured) throw NimException("NIM_API_KEY missing from local.properties")
        var attempt = model
        // Walks the list once, never loops: every 404 removes an id permanently, so this
        // terminates even if the whole list has been retired underneath us.
        while (true) {
            val startedAt = System.currentTimeMillis()
            try {
                val json = rawChat(messages, maxTokens, attempt, connectTimeoutMs, readTimeoutMs, temperature, requireJson)
                Backoff.clear(backoffKey)
                lastFailure = null
                Log.i(TAG, "$backoffKey OK ($attempt in ${System.currentTimeMillis() - startedAt}ms)")
                return json
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startedAt
                val code = (e as? HttpException)?.code
                // A 404 names a model, not a service. Switch and try again inside the same call:
                // the person asked once, and should not spend a failed search discovering that an
                // id compiled into this APK was retired after it was built. Note that the breaker
                // is NOT tripped on this path — silencing the ranker for half an hour over a name
                // we have already replaced is how a working model went unused.
                if (code == 404) {
                    val next = markDead(attempt)
                    if (next != null) {
                        attempt = next
                        continue
                    }
                }
                if (e is java.net.SocketTimeoutException) demoteActiveModel()
                Backoff.trip(
                    backoffKey,
                    if (code != null && code in CONFIG_CODES) CONFIG_BACKOFF_MS else FAILURE_BACKOFF_MS,
                )
                fail(describe(e, code, elapsed, attempt))
                throw e
            }
        }
    }''')

# chatStream(): same
E(N,
'''        if (!isConfigured) throw NimException("NIM_API_KEY missing from local.properties")
        val startedAt = System.currentTimeMillis()
        try {
            val text = rawChatStream(messages, maxTokens, model, connectTimeoutMs, readTimeoutMs, temperature, onPartial)
            if (text.isBlank()) throw NimException("Model streamed an empty reply")
            Backoff.clear(backoffKey)
            lastFailure = null
            Log.i(TAG, "$backoffKey OK streamed ($model in ${System.currentTimeMillis() - startedAt}ms)")
            return text
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startedAt
            val code = (e as? HttpException)?.code
            if (e is java.net.SocketTimeoutException || code == 404) demoteActiveModel()
            Backoff.trip(
                backoffKey,
                if (code != null && code in CONFIG_CODES) CONFIG_BACKOFF_MS else FAILURE_BACKOFF_MS,
            )
            fail(describe(e, code, elapsed))
            throw e
        }
    }''',
'''        if (!isConfigured) throw NimException("NIM_API_KEY missing from local.properties")
        var attempt = model
        while (true) {
            val startedAt = System.currentTimeMillis()
            try {
                val text = rawChatStream(messages, maxTokens, attempt, connectTimeoutMs, readTimeoutMs, temperature, onPartial)
                if (text.isBlank()) throw NimException("Model streamed an empty reply")
                Backoff.clear(backoffKey)
                lastFailure = null
                Log.i(TAG, "$backoffKey OK streamed ($attempt in ${System.currentTimeMillis() - startedAt}ms)")
                return text
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startedAt
                val code = (e as? HttpException)?.code
                // Retrying mid-reply is safe here because a 404 arrives before any token does —
                // the stream never opened, so there is nothing on screen to contradict.
                if (code == 404) {
                    val next = markDead(attempt)
                    if (next != null) {
                        attempt = next
                        continue
                    }
                }
                if (e is java.net.SocketTimeoutException) demoteActiveModel()
                Backoff.trip(
                    backoffKey,
                    if (code != null && code in CONFIG_CODES) CONFIG_BACKOFF_MS else FAILURE_BACKOFF_MS,
                )
                fail(describe(e, code, elapsed, attempt))
                throw e
            }
        }
    }''')

# describe(): name the model that actually failed, not the replacement
E(N,
'''    private fun describe(error: Throwable, code: Int?, elapsedMs: Long): String = when {''',
'''    private fun describe(
        error: Throwable,
        code: Int?,
        elapsedMs: Long,
        // Passed in rather than read from [activeModel]: by the time this runs the failed id has
        // usually already been replaced, so reading the active one named the wrong model in every
        // message the settings screen showed.
        model: String = activeModel,
    ): String = when {''')

E(N, '''        code == 404 -> "No such model (HTTP 404): $activeModel"''',
     '''        code == 404 -> "No such model (HTTP 404): $model"''')

E(N, '''        error is java.net.SocketTimeoutException ->
            "$activeModel didn't finish within ${elapsedMs}ms"''',
     '''        error is java.net.SocketTimeoutException ->
            "$model didn't finish within ${elapsedMs}ms"''')

E(N,
'''    private data class StreamChunk(val choices: List<StreamChoice> = emptyList())''',
'''    private data class StreamChunk(val choices: List<StreamChoice> = emptyList())

    /** Only the ids are read; the catalogue carries pricing and modality fields we do not use. */
    @Serializable
    private data class ModelList(val data: List<Entry> = emptyList()) {
        @Serializable
        data class Entry(val id: String = "")
    }''')

# ---------------------------------------------------------------- app / activity
E("../../../../../../app/src/main/java/space/gexemy/tasteroute/TasteRouteApplication.kt".replace("x","x"),
  None, None, 0)  # placeholder removed below
edits.pop()


E("TasteRouteApplication.kt",
'''        Perf.init(this)
        Prefs.init(this)''',
'''        // Prefs first: Perf reads the class measured on this handset last time, and a default it
        // has to re-derive is a first launch that animates like a flagship on a phone that isn't.
        Prefs.init(this)
        Perf.init(this)''')

E("MainActivity.kt",
'''import space.gexemy.tasteroute.data.NimClient''',
'''import space.gexemy.tasteroute.data.NimClient
import space.gexemy.tasteroute.data.Perf''')

E("MainActivity.kt",
'''        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {''',
'''        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Started here and not in Application: the frame budget is the panel's own refresh rate,
        // and there is no display to ask before an Activity exists.
        @Suppress("DEPRECATION")
        Perf.observeFrames(windowManager.defaultDisplay?.refreshRate ?: 60f)
        setContent {''')

# ---------------------------------------------------------------- Motion
M = "ui/theme/Motion.kt"

E(M,
'''private val enterMs: Int get() = if (Perf.deviceClass == DeviceClass.LOW) 220 else 300
private val exitMs: Int get() = if (Perf.deviceClass == DeviceClass.LOW) 160 else 220''',
'''/**
 * Shortened on 2026-08-27. 300ms is inside the Material range but it is the top of it, and a push
 * that takes a third of a second reads as the phone thinking rather than as the screen moving.
 * The complaint was "sluggish", and on a mid-range panel the last 60ms of a slide is exactly the
 * part that arrives late — cutting it removes the stutter and the wait in one go.
 */
private val enterMs: Int
    get() = when (Perf.deviceClass) {
        DeviceClass.LOW -> 180
        DeviceClass.MID -> 220
        DeviceClass.HIGH -> 260
    }

private val exitMs: Int
    get() = when (Perf.deviceClass) {
        DeviceClass.LOW -> 140
        DeviceClass.MID -> 170
        DeviceClass.HIGH -> 200
    }''')

E(M,
'''fun pushEnter(): EnterTransition =
    slideInHorizontally(tween(enterMs, easing = Emphasized)) { (it * LEAD).toInt() } +
        fadeIn(tween(enterMs / 2, easing = Emphasized))

fun pushExit(): ExitTransition =
    slideOutHorizontally(tween(enterMs, easing = Emphasized)) { -(it * TRAIL).toInt() } +
        fadeOut(tween(enterMs / 2, easing = EmphasizedOut))

fun popEnter(): EnterTransition =
    slideInHorizontally(tween(exitMs, easing = Emphasized)) { -(it * TRAIL).toInt() } +
        fadeIn(tween(exitMs, easing = Emphasized))

fun popExit(): ExitTransition =
    slideOutHorizontally(tween(exitMs, easing = EmphasizedOut)) { (it * LEAD).toInt() } +
        fadeOut(tween(exitMs, easing = EmphasizedOut))''',
'''fun pushEnter(): EnterTransition =
    if (Perf.reducedMotion) EnterTransition.None
    else slideInHorizontally(tween(enterMs, easing = Emphasized)) { (it * LEAD).toInt() } +
        fadeIn(tween(enterMs / 2, easing = Emphasized))

fun pushExit(): ExitTransition =
    if (Perf.reducedMotion) ExitTransition.None
    else slideOutHorizontally(tween(enterMs, easing = Emphasized)) { -(it * TRAIL).toInt() } +
        fadeOut(tween(enterMs / 2, easing = EmphasizedOut))

fun popEnter(): EnterTransition =
    if (Perf.reducedMotion) EnterTransition.None
    else slideInHorizontally(tween(exitMs, easing = Emphasized)) { -(it * TRAIL).toInt() } +
        fadeIn(tween(exitMs, easing = Emphasized))

fun popExit(): ExitTransition =
    if (Perf.reducedMotion) ExitTransition.None
    else slideOutHorizontally(tween(exitMs, easing = EmphasizedOut)) { (it * LEAD).toInt() } +
        fadeOut(tween(exitMs, easing = EmphasizedOut))''')

E(M,
'''fun tabEnter(): EnterTransition {
    val spec = tween<Float>(200, delayMillis = 70, easing = Emphasized)
    val fade = fadeIn(spec)
    return if (Perf.richMotion) fade + scaleIn(spec, initialScale = 0.97f) else fade
}

fun tabExit(): ExitTransition = fadeOut(tween(90, easing = EmphasizedOut))''',
'''fun tabEnter(): EnterTransition {
    if (Perf.reducedMotion) return EnterTransition.None
    val spec = tween<Float>(if (Perf.deviceClass == DeviceClass.HIGH) 170 else 140, delayMillis = 50, easing = Emphasized)
    val fade = fadeIn(spec)
    // Scale is a third animated property on a screen that is already measuring and laying itself
    // out, so it is spent only where there are frames going spare. A tab switch that took 270ms
    // end to end now takes 190, which is the difference between a transition and a wait.
    return if (Perf.deviceClass == DeviceClass.HIGH) fade + scaleIn(spec, initialScale = 0.97f) else fade
}

fun tabExit(): ExitTransition =
    if (Perf.reducedMotion) ExitTransition.None else fadeOut(tween(70, easing = EmphasizedOut))''')

# ---------------------------------------------------------------- Perf (full)
W("data/Perf.kt", '''package space.gexemy.tasteroute.data

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
''')

# ---------------------------------------------------------------- About (full)
W("ui/settings/AboutScreen.kt", '''package space.gexemy.tasteroute.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.BuildConfig
import space.gexemy.tasteroute.R
import space.gexemy.tasteroute.data.Attribution
import space.gexemy.tasteroute.data.GexemyClient

/**
 * What this app is, where its information comes from, and what it does with yours — written for
 * the person holding the phone.
 *
 * Rewritten 2026-08-27. It used to end with a service block naming the API's version number and
 * the content sources currently switched on, and to label its credits "Built on". None of that is
 * about the app from the outside: a version number is a thing to reproduce a bug against, and
 * "built on" describes the construction rather than the result. Anything a developer needs to
 * read lives in Settings, on purpose.
 *
 * The credits list is still FETCHED, with a hardcoded fallback, and that is not a detail: which
 * sources are switched on is a property of the server, so a static list would credit sources that
 * are not running and omit ones that are.
 */
private val FALLBACK_CREDITS = listOf(
    Attribution("osm", "OpenStreetMap", "https://www.openstreetmap.org/copyright", "Places and map data"),
    Attribution("carto", "CARTO", "https://carto.com/attributions", "Map styling"),
    Attribution("osrm", "OSRM", "https://project-osrm.org", "Driving directions"),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var credits by remember { mutableStateOf(FALLBACK_CREDITS) }

    LaunchedEffect(Unit) {
        if (!GexemyClient.isConfigured) return@LaunchedEffect
        runCatching { GexemyClient.attributions() }.onSuccess { found ->
            // Merge rather than replace: the server knows about content sources, the app knows
            // about the tiles and the router it talks to directly.
            if (found.isNotEmpty()) {
                credits = (found + FALLBACK_CREDITS).distinctBy { it.source }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The two adaptive layers, composed the way a launcher composes them. Referencing
                // @mipmap/ic_launcher here instead would resolve to an AdaptiveIconDrawable on API
                // 26+, which painterResource cannot load — it only understands vectors and bitmaps.
                Box(
                    Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Adaptive layers are 108dp with only the centre 72dp guaranteed visible, so
                    // the launcher scales them by 108/72. Skipping that draws the mark two-thirds
                    // the size it appears on the home screen.
                    Image(
                        painterResource(R.drawable.ic_launcher_background),
                        contentDescription = null,
                        Modifier.fillMaxSize().scale(1.5f),
                    )
                    Image(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        Modifier.fillMaxSize().scale(1.5f),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("TasteRoute", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "TasteRoute learns what you like and finds you somewhere to eat — near you, along a " +
                    "drive, or in a city you are about to visit. Tell it what you are in the mood " +
                    "for and it will do the rest.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Block(
                "Ratings you can place",
                "Stars on a TasteRoute card come from people using TasteRoute, plus openly-licensed " +
                    "reviews. Ratings from Google, Tripadvisor and Yelp are shown separately, each " +
                    "under its own name, so you can see who is saying what. They are never blended " +
                    "into a single number.",
            )

            Block(
                "Allergens",
                "Allergen notes come from other diners and from what venues publish about themselves. " +
                    "One report of a bad experience marks a place as contested however many good " +
                    "reports it has. Treat all of it as a starting point, and always tell the " +
                    "restaurant yourself.",
            )

            Block(
                "Your data",
                "Your location is used to search and to navigate, and is never stored on our servers. " +
                    "Your taste, your saved places and your reviews are kept with your account so " +
                    "they follow you to a new phone. You can delete your account, and everything in " +
                    "it, from Profile → Account.",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column {
                Text("Thanks to", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "TasteRoute is built on work other people share openly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                credits.forEach { credit ->
                    // A row and a divider rather than a filled card each: five stacked surfaces
                    // read as five buttons competing for a tap, when this is a list to be read.
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(credit.label, style = MaterialTheme.typography.bodyLarge)
                            if (credit.note.isNotBlank()) {
                                Text(
                                    credit.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (credit.url.isNotBlank()) {
                            TextButton(onClick = { open(context, credit.url) }) { Text("Open") }
                        }
                    }
                }
            }

            Text(
                "Map data © OpenStreetMap contributors, available under the Open Database License.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Block(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
''')

# ---------------------------------------------------------------- two-phase apply
def main():
    bufs, problems = {}, []
    for path, old, new, n in edits:
        full = os.path.join(ROOT, path)
        if path not in bufs:
            if not os.path.exists(full):
                problems.append("MISSING " + path); continue
            bufs[path] = open(full, encoding="utf-8").read()
        found = bufs[path].count(old)
        if found != n:
            problems.append("%s: expected %d match(es), found %d for:\n    %s" % (path, n, found, old.strip().splitlines()[0][:90]))
            continue
        bufs[path] = bufs[path].replace(old, new, n)
    for path, content in writes:
        if not os.path.exists(os.path.join(ROOT, path)):
            problems.append("MISSING " + path); continue
        bufs[path] = content
    if problems:
        print("PHASE 1 FAILED - nothing written")
        for p in problems: print(" -", p)
        sys.exit(1)
    for path, content in bufs.items():
        with open(os.path.join(ROOT, path), "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        print("wrote", path, len(content), "bytes")
    print("PHASE 2 OK -", len(bufs), "files")

main()
