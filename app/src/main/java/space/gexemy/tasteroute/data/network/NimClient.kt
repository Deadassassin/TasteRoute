package space.gexemy.tasteroute.data.network

import android.util.Log
import space.gexemy.tasteroute.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.local.Prefs
import space.gexemy.tasteroute.data.repository.RecommendationEngine

class NimException(message: String) : Exception(message)

data class Candidate(val record: RestaurantRecord, val distanceMeters: Int)

data class RankedPick(val id: String, val score: Int, val reasoning: String)

/** One model's answer to a ping: how long it took, or why it didn't answer. */
data class ModelProbe(
    val model: String,
    val millis: Long,
    val error: String? = null,
    /** HTTP 404 — this id is not served at all, as distinct from being slow or failing today. */
    val notFound: Boolean = false,
) {
    val ok: Boolean get() = error == null
}

/** One line of the conversation as the model sees it. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/**
 * What the assistant does with a turn.
 *
 * [say] is the reply. [search] is a query for OUR OWN places provider, set only when the turn
 * actually calls for restaurants — the model never names a venue itself, because a model asked to
 * name venues invents them, gets their hours wrong, and puts them in the wrong city.
 */
@Serializable
data class AssistantTurn(
    val say: String = "",
    val search: String = "",
    /**
     * 1-based indices into the nearby list the model was shown. It never produces a name, an
     * address or a rating for a place — only a number, which the app resolves back to the real
     * object it already had. That is the whole reason it is allowed to talk about venues at all.
     */
    val places: List<Int> = emptyList(),
    /**
     * A place name to search IN, when the person asked about somewhere they are not standing —
     * "Las Vegas" from Boulder City, "downtown Reno" from anywhere. A NAME, never coordinates: the
     * app geocodes it with the platform Geocoder and searches there instead of around the device
     * fix. Empty on every ordinary turn. Without this the model's only honest answer to "what's
     * good in Vegas" was that it could only see what was nearby, which is not an answer.
     */
    val area: String = "",
    /** Set only when the person states a lasting preference worth saving to their profile. */
    val profile: ParsedTaste? = null,
)

@Serializable
data class ParsedTaste(
    val cuisines: List<String> = emptyList(),
    val diets: List<String> = emptyList(),
    val vibes: List<String> = emptyList(),
    @SerialName("price_comfort") val priceComfort: Int = 0,
)

/**
 * NVIDIA NIM (OpenAI-compatible). Two jobs, both constrained so the model can only choose from
 * values the app already understands:
 *  - rank(): reorders real places by index, never inventing a name or coordinate.
 *  - parseTaste(): maps free text onto the app's own cuisine/diet/vibe vocabularies.
 *
 * MODEL CHOICE IS MEASURED, NOT ASSUMED. The 70B took 25+ seconds per call on the free tier and
 * timed out on every search — not because it generates slowly but because it queues. Ranking a
 * dozen places by index is not a task that needs a frontier model, so [candidates] is an ordered
 * preference list and [probeModels] races them with a tiny prompt and keeps whichever answers
 * fastest. A candidate that no longer exists simply fails its probe and drops out, which is why
 * the list can carry names without the app having to be certain they are all still published.
 */
object NimClient {

    /** Ranking latency scales with prompt size, and the tail of a distance-sorted list rarely wins. */
    private const val RANK_POOL = 16

    /**
     * The re-rank is off the critical path — the local ordering is already on screen. This only has
     * to be short enough that the list doesn't reshuffle under someone's thumb. With a small model
     * answering in a few seconds it should never be reached.
     */
    private const val RANK_TIMEOUT_MS = 20_000

    /** Probes must be brisk: a model that needs longer than this is not one we want to pick. */
    private const val PROBE_TIMEOUT_MS = 12_000

    /** The shared 5s connect budget suits our own host; a TLS handshake to NVIDIA over mobile
     *  data is a different proposition, and a connect failure costs the whole rank. */
    private const val RANK_CONNECT_MS = 10_000

    /** Indices and scores only, so the ceiling is small — and truncation stops being a risk. */
    private fun rankTokenBudget(limit: Int) = 60 + limit * 16

    /**
     * Conversation is the opposite trade to ranking. Ranking is throttled to almost no output
     * because every token is a second someone spends watching a list they can already see; here the
     * tokens ARE the product, and the person is watching a typing indicator expecting prose.
     */
    private const val CHAT_TOKENS = 420
    private const val CHAT_TIMEOUT_MS = 25_000

    /** How much of the conversation goes back up. Enough to follow a thread, not enough to crawl. */
    private const val CHAT_HISTORY_TURNS = 10

    private const val FAILURE_BACKOFF_MS = 5 * 60_000L

    /**
     * How long a chosen model is trusted before it is raced again. A day is far shorter than the
     * months a model id lives and long enough that nobody pays for the race twice in a sitting.
     * It is a backstop, not the mechanism: a retirement is caught by the server's list, not by
     * waiting this out.
     */
    private const val RECHECK_MS = 24 * 60 * 60_000L

    /** A bad key or a retired model name is configuration, not weather. Stop asking for a while. */
    private const val CONFIG_BACKOFF_MS = 30 * 60_000L
    private val CONFIG_CODES = setOf(400, 401, 403, 404, 422)

    /** Filter Logcat on this tag to read the real cause without opening a settings screen. */
    private const val TAG = "TasteRouteRanker"

    /** The order compiled into this APK. NIM_MODELS in local.properties sets it. */
    private val builtIn: List<String> =
        BuildConfig.NIM_MODELS.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Ordered fastest-first, and deliberately no longer a constant: [refreshCandidates] replaces
     * it with whatever the server says still answers, and [restore] brings the last such answer
     * back at launch. A model can be retired long after an APK ships - meta/llama-3.1-8b-instruct
     * and meta/llama-3.3-70b-instruct both reached end of life on 2026-08-25, taking two of the
     * four ids this app was built with - so a compiled-in list is a guess with an expiry on it.
     */
    var candidates: List<String> = builtIn
        private set

    /** NIM_MODEL, when set, pins the model and skips probing entirely. */
    private val pinned: String? = BuildConfig.NIM_MODEL.trim().ifBlank { null }

    private var chosenModel: String? = null

    /** Slow or unhappy this session. A fresh race clears it. */
    private val failed = mutableSetOf<String>()

    /**
     * Ids NVIDIA answered 404 for. A 404 is not weather: that name is not served, and it will not
     * be served in five minutes either. Kept across launches so a retired id costs one request
     * ever rather than one per app open, and forgiven only when the catalogue lists it again.
     */
    private val dead = mutableSetOf<String>()

    /** The pin, the probed winner, or the first candidate until a probe lands. */
    val activeModel: String
        get() = pinned
            ?: chosenModel?.takeIf { it !in dead }
            ?: candidates.firstOrNull { it !in failed && it !in dead }
            ?: candidates.firstOrNull { it !in dead }
            ?: candidates.firstOrNull()
            ?: "meta/llama-3.2-3b-instruct"

    val isConfigured: Boolean get() = BuildConfig.NIM_API_KEY.isNotBlank()

    /**
     * Why the last call failed, kept so the settings screen can show it without the user having to
     * reproduce the failure on demand. Cleared by the next success.
     */
    var lastFailure: String? = null
        private set

    /** Latest probe results, newest run wins. Rendered in settings; empty until one has run. */
    var lastProbe: List<ModelProbe> = emptyList()
        private set

    private fun fail(reason: String): String {
        lastFailure = reason
        Log.w(TAG, reason)
        return reason
    }

    /**
     * Configured AND not in a failure backoff. The hot path checks this one: after a timeout the
     * app skips the ranker entirely for a few minutes and ships the local ordering instead of
     * making every single search wait out the same failure.
     */
    val isReady: Boolean get() = isConfigured && !Backoff.blocked(Backoff.RANKER)

    /** Same idea for conversation, on its own breaker — see [Backoff.CHAT]. */
    val chatReady: Boolean get() = isConfigured && !Backoff.blocked(Backoff.CHAT)

    fun restore() {
        if (pinned != null) return
        // The last live list the server gave us, so a launch with no connection still starts from
        // what was true rather than from what was compiled in months ago.
        Prefs.getString(Prefs.NIM_MODELS_LIVE)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { candidates = it }
        Prefs.getString(Prefs.NIM_MODEL_DEAD)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .let(dead::addAll)
        val saved = Prefs.getString(Prefs.NIM_MODEL_CHOICE)
        if (saved.isNotBlank() && saved in candidates && saved !in dead) chosenModel = saved
    }

    /**
     * The auto-pick. Runs at every app open, and both halves are off the critical path.
     *
     * The previous version returned the moment anything was stored, which is precisely why a dead
     * model stayed dead: the choice was made once, on first run, and nothing ever revisited it.
     * Now the live list is fetched first - [refreshCandidates] discards a stored pick that has
     * left it - and a race runs when there is no pick, when the pick was just invalidated, or when
     * the last one is a day old and something faster may have appeared since.
     */
    suspend fun ensureModelChosen() {
        if (!isConfigured || pinned != null) return
        refreshCandidates()
        val chosenAt = Prefs.getString(Prefs.NIM_MODEL_AT).toLongOrNull() ?: 0L
        if (chosenModel != null && System.currentTimeMillis() - chosenAt in 0..RECHECK_MS) return
        runCatching { probeModels() }
    }

    /**
     * Asks the server which ids still serve, and drops the stored pick if it is not among them.
     *
     * WHY THE SERVER DECIDES THIS: liveness is a global fact and it is not visible from the
     * catalog. NVIDIA's own /v1/models went on listing meta/llama-3.1-8b-instruct for days after
     * it stopped answering, and the entries carry no status field at all - so the only proof is a
     * real completion, which the server makes once for everybody instead of every phone making
     * its own on every launch. Latency is NOT global, which is why [probeModels] still runs here:
     * a model that is quick from a datacentre is not necessarily quick from this handset.
     *
     * A failed or empty answer changes nothing. A server we cannot reach is not evidence that
     * there are no models, and acting on it would silence the ranker on every device at once.
     */
    private suspend fun refreshCandidates() {
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
    }

    /**
     * Races every candidate with an 12-token ping and keeps the fastest that answers.
     * Deliberately does NOT go through [chat]: a slow candidate we are about to discard must not
     * trip the ranker's circuit breaker or overwrite [lastFailure].
     */
    suspend fun probeModels(): List<ModelProbe> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()

        // Nothing is learned by pinging a name the catalogue does not carry, and every probe of
        // one is a round trip the person waits through on first open.
        val pool = candidates.filter { it !in dead }.ifEmpty { candidates }
        val results = pool.map { candidate ->
            async {
                val startedAt = System.currentTimeMillis()
                runCatching {
                    rawChat(
                        system = "Reply with ONE JSON object and nothing else: {\"ok\":true}",
                        user = "ping",
                        maxTokens = 12,
                        model = candidate,
                        connectTimeoutMs = RANK_CONNECT_MS,
                        readTimeoutMs = PROBE_TIMEOUT_MS,
                    )
                }.fold(
                    onSuccess = { ModelProbe(candidate, System.currentTimeMillis() - startedAt) },
                    onFailure = { error ->
                        val elapsed = System.currentTimeMillis() - startedAt
                        val code = (error as? HttpException)?.code
                        ModelProbe(candidate, elapsed, describe(error, code, elapsed, candidate), code == 404)
                    },
                )
            }
        }.awaitAll().sortedWith(compareBy({ !it.ok }, { it.millis }))

        lastProbe = results
        val gone = results.filter { it.notFound }.map { it.model }
        if (gone.isNotEmpty()) {
            dead += gone
            Prefs.put(Prefs.NIM_MODEL_DEAD, dead.joinToString(","))
        }
        results.firstOrNull { it.ok }?.let { winner ->
            chosenModel = winner.model
            failed.clear()
            Prefs.put(Prefs.NIM_MODEL_CHOICE, winner.model)
            Prefs.put(Prefs.NIM_MODEL_AT, System.currentTimeMillis().toString())
            Backoff.clear(Backoff.RANKER)
            lastFailure = null
            Log.i(TAG, "Fastest model: ${winner.model} at ${winner.millis}ms")
        }
        results.forEach { Log.i(TAG, "probe ${it.model}: ${if (it.ok) "${it.millis}ms" else it.error}") }
        results
    }

    /**
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
    private fun demoteActiveModel() {
        if (pinned != null || candidates.size < 2) return
        val current = activeModel
        failed += current
        val next = candidates.firstOrNull { it !in failed }
        if (next == null) {
            // Everything has failed once; start over rather than getting stuck with nothing.
            failed.clear()
            return
        }
        chosenModel = next
        Prefs.put(Prefs.NIM_MODEL_CHOICE, next)
        // Cleared, not set: a demotion is what is left after a failure rather than a considered
        // pick, so the next open should race again instead of trusting this for a day.
        Prefs.remove(Prefs.NIM_MODEL_AT)
        Log.w(TAG, "Dropping $current, trying $next")
    }

    suspend fun rank(
        candidates: List<Candidate>,
        profile: TasteProfile,
        query: String,
        cityLabel: String?,
        limit: Int,
    ): List<RankedPick> {
        if (candidates.isEmpty()) return emptyList()
        val pool = candidates.take(RANK_POOL)
        val json = chat(
            system = rankSystemPrompt(limit),
            user = rankUserPrompt(pool, profile, query, cityLabel),
            maxTokens = rankTokenBudget(limit),
            connectTimeoutMs = RANK_CONNECT_MS,
        )
        val picks = runCatching { AppJson.decodeFromString(Picks.serializer(), json).picks }
            .getOrElse {
                // Answered, but not with anything we can read. Asking again immediately burns the
                // same tokens for the same result, and the local ordering is already correct.
                Backoff.trip(Backoff.RANKER, FAILURE_BACKOFF_MS)
                throw NimException(fail("Model returned unreadable JSON: ${json.take(160)}"))
            }
        return picks
            .mapNotNull { pick ->
                val c = pool.getOrNull(pick.index - 1) ?: return@mapNotNull null
                RankedPick(
                    id = c.record.id,
                    score = pick.score.coerceIn(1, 100),
                    // Blank by design. The local scorer's reasons are grounded in the listed facts
                    // and cannot drift, so they stay — see RecommendationEngine.
                    reasoning = pick.why.trim(),
                )
            }
            .distinctBy { it.id }
            .take(limit)
    }

    /**
     * One conversational turn. Deliberately NOT the ranker: different budget, different timeout,
     * different circuit breaker, and prose instead of indices.
     */
    suspend fun converse(
        history: List<ChatTurn>,
        profile: TasteProfile,
        allergens: List<String>,
        cityLabel: String?,
        /**
         * The places the app is already showing this person. Without them the model was being
         * asked "what is good near me" with no knowledge of anywhere at all, and its only honest
         * answer was to refuse — while a screenful of real, ranked, enriched places sat one tab
         * away. These are OUR data, so naming one is a lookup, not a hallucination.
         */
        nearby: List<RestaurantResult> = emptyList(),
        /** Called with the prose written so far, every time more of it arrives. */
        onDelta: (String) -> Unit = {},
    ): AssistantTurn {
        val messages = buildList {
            add(Msg("system", conversationSystemPrompt(profile, allergens, cityLabel, nearby)))
            history.takeLast(CHAT_HISTORY_TURNS).forEach {
                add(Msg(if (it.fromUser) "user" else "assistant", it.text.take(600)))
            }
        }
        val raw = try {
            chatStream(
                messages = messages,
                maxTokens = CHAT_TOKENS,
                connectTimeoutMs = RANK_CONNECT_MS,
                readTimeoutMs = CHAT_TIMEOUT_MS,
                // Ranking wants the same answer every time. A conversation that answers
                // identically twice reads as a phone tree.
                temperature = 0.6,
                backoffKey = Backoff.CHAT,
                onPartial = { accumulated -> onDelta(partialSay(accumulated)) },
            )
        } catch (e: Exception) {
            val code = (e as? HttpException)?.code
            // A rejected key or a retired model will not answer any better without streaming, and
            // a timeout would just cost the same wait twice. Anything else — a proxy that mangles
            // SSE, a chunked-transfer oddity — is worth one plain attempt, because a reply that
            // arrives all at once still beats no reply. The animation is the least important part.
            if (e is java.net.SocketTimeoutException || (code != null && code in CONFIG_CODES)) throw e
            Log.w(TAG, "Stream failed (${e.message}); retrying without it")
            Backoff.clear(Backoff.CHAT)
            chat(
                messages = messages,
                maxTokens = CHAT_TOKENS,
                connectTimeoutMs = RANK_CONNECT_MS,
                readTimeoutMs = CHAT_TIMEOUT_MS,
                temperature = 0.6,
                backoffKey = Backoff.CHAT,
                // A conversation that answers in plain sentences has answered. Demanding JSON here
                // threw away good replies and showed an error instead — the rule belongs to the
                // ranker, where prose genuinely cannot be used.
                requireJson = false,
            )
        }
        return parseTurn(raw)
    }

    /**
     * The prose written so far, read out of a JSON envelope that is still being written.
     *
     * The reply is `{"say":"...` and the sentence inside it is the only part a person may ever
     * see, so a partial generation cannot simply be printed — the first frame would be a brace.
     * This walks the string escape by escape and stops at whatever exists, which is exactly the
     * behaviour that makes the envelope invisible while it fills in.
     */
    internal fun partialSay(raw: String): String {
        val text = raw.replace(FENCE, "")
        val opener = SAY_OPEN.find(text)
            // No envelope yet. Whatever sits before the first brace is the model talking, and that
            // is a perfectly good reply; from the brace onwards it is the envelope, and no part of
            // an envelope may ever reach the screen — not even for the one frame before it closes.
            ?: return text.substringBefore('{').trim()
        val out = StringBuilder()
        var i = opener.range.last + 1
        loop@ while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> {
                    when (text.getOrNull(i + 1)) {
                        'n' -> out.append('\n')
                        't' -> out.append(' ')
                        'r' -> Unit
                        // A \uXXXX cut in half is not decodable yet; skip the whole escape rather
                        // than leaking four hex digits into the bubble for a frame.
                        'u' -> i += 4
                        null -> Unit
                        else -> out.append(text[i + 1])
                    }
                    i += 2
                }
                c == '"' -> break@loop
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        val body = out.toString().trim()
        // A model handed a schema will eventually answer WITH the schema, nesting a second
        // envelope inside `say`. Half-written, that puts a brace on screen — the exact thing this
        // path exists to prevent — so unwrap one level, the same way cleanReply does at the end.
        return if (body.startsWith("{")) partialSay(body) else body
    }

    /**
     * The envelope is JSON. The REPLY inside it is prose, and prose is the only thing allowed to
     * reach a chat bubble.
     *
     * A small model handed a schema will, sooner or later, answer WITH the schema: echo the
     * template verbatim, nest a second JSON object inside `say`, or wrap the whole reply in a code
     * fence. Any of those rendered as-is is the most broken-looking thing this app could show
     * someone, so the parse is defensive at three levels — decode, then salvage the field by hand,
     * then strip the syntax and keep whatever prose is left.
     */
    internal fun parseTurn(raw: String): AssistantTurn {
        val text = raw.replace(FENCE, "").trim()
        // Take the envelope wherever it sits, and repair it if the generation stopped early.
        // Requiring the reply to BEGIN with a brace is the bug people actually saw: a model that
        // writes "Sure, here you go:" before its JSON — and they all do, eventually — failed that
        // test, and the entire envelope, braces and keys and all, was printed into the bubble as
        // if it were the reply. No envelope anywhere means the model simply talked, which is a
        // valid turn with no search in it.
        // Balanced braces are not an envelope. "Use the { and } keys to bracket it" scans as a
        // complete object, so the decoder was handed a fragment with no fields in it, the salvage
        // found nothing, and a perfectly good sentence came out as an empty bubble. To be treated
        // as our object it has to carry one of our keys.
        val json = extractJsonObject(text)
            ?.takeIf { ENVELOPE_HINT.containsMatchIn(it) }
            ?.let(::escapeControlChars)
            ?: return AssistantTurn(say = cleanReply(text))
        val decoded = runCatching { AppJson.decodeFromString(AssistantTurn.serializer(), json) }.getOrNull()
        // A strict decode fails for reasons that have nothing to do with the fields we act on: one
        // wrong type ("places":["1"]), a key we have never heard of, a literal newline inside the
        // prose, a generation cut off mid-object. Every one of those used to keep the sentence and
        // silently drop the search and the cited places — so the model answered with restaurants
        // and the app showed none, which is exactly what "it isn't handling the JSON" looks like.
        val turn = decoded ?: salvage(json)
        val say = cleanReply(turn.say).ifBlank { cleanReply(strippedProse(json)) }
        return turn.copy(
            say = say,
            search = turn.search.trim().take(120),
            // An area with nothing to look for is not actionable, and a search phrase is cheap to
            // infer from the reply the model already wrote. Dropping the area instead would send
            // the search back to wherever the person is standing, which is the exact bug.
            area = turn.area.trim().take(80),
            places = turn.places.filter { it > 0 }.distinct().take(6),
        )
    }

    /** Reads the fields we act on straight out of an object the strict decode refused. */
    private fun salvage(json: String) = AssistantTurn(
        say = SAY_FIELD.find(json)?.groupValues?.get(1)?.let(::unescape)
        // No closing quote: the generation stopped inside the sentence. What follows the opening
        // quote is still the sentence, and half a reply beats an error message.
            ?: SAY_OPEN.find(json)?.let { unescape(json.substring(it.range.last + 1)) }.orEmpty(),
        search = SEARCH_FIELD.find(json)?.groupValues?.get(1)?.let(::unescape).orEmpty(),
        area = AREA_FIELD.find(json)?.groupValues?.get(1)?.let(::unescape).orEmpty(),
        places = PLACES_FIELD.find(json)?.groupValues?.get(1)
            ?.let { list -> NUMBER.findAll(list).mapNotNull { it.value.toIntOrNull() }.toList() }
            .orEmpty(),
    )

    private val FENCE = Regex("```(?:json|JSON)?")

    /** `"say" : "..."` picked out by hand, for when the strict decode refused the whole object. */
    private val SAY_FIELD = Regex("\"say\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    /** Just the opening of that field, for reading a reply that has not finished arriving. */
    private val SAY_OPEN = Regex("\"say\"\\s*:\\s*\"")

    /** The other two fields, for the same hand salvage. `places` is read as digits, not as JSON,
     *  because the shape the decode choked on is usually the shape of that very array. */
    private val SEARCH_FIELD = Regex("\"search\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val AREA_FIELD = Regex("\"area\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val PLACES_FIELD = Regex("\"places\"\\s*:\\s*\\[([^\\]]*)\\]")

    /** Enough of our envelope to tell a truncated object from a sentence with a brace in it. */
    private val ENVELOPE_HINT = Regex("\"(?:say|search|area|places)\"\\s*:")
    private val NUMBER = Regex("\\d+")

    private fun unescape(v: String) = v
        .replace("\\n", "\n").replace("\\r", "").replace("\\t", " ")
        .replace("\\\"", "\"").replace("\\\\", "\\")

    /**
     * Everything that must never survive to the screen: code fences, a reply that is itself another
     * JSON object, and the stray braces a truncated generation leaves behind.
     */
    private fun cleanReply(raw: String): String {
        var text = raw.replace(FENCE, "").trim()
        // `say` containing its own JSON — unwrap one level rather than printing braces at someone.
        if (text.startsWith("{") && text.contains("\"say\"")) {
            text = SAY_FIELD.find(text)?.groupValues?.get(1)?.let(::unescape)?.trim() ?: text
        }
        if (text.startsWith("{") || text.startsWith("[")) text = strippedProse(text)
        return text.trim().trim('"').replace(Regex("[ \\t]+"), " ").trim()
    }

    /**
     * Last resort: throw away JSON syntax and keep the sentences. A model that ignored the format
     * entirely usually still wrote something useful, and showing that beats showing an error.
     */
    private fun strippedProse(raw: String): String {
        val withoutKeys = raw.replace(Regex("\"(?:say|search|area|places|profile|cuisines|diets|vibes|price_comfort)\"\\s*:"), " ")
        val prose = withoutKeys.replace(Regex("[{}\\[\\]]"), " ").replace("\\n", " ")
        // Needs to actually read as a sentence — a bare "null, 2" is worse than the caller's fallback.
        return if (prose.count { it.isLetter() } >= 12) prose else ""
    }

    /** How many of the on-screen places the model is shown. Prompt size is latency. */
    private const val GROUND_LIMIT = 12

    /** One place, flattened to the facts worth reasoning over. No ids, no coordinates, no URLs. */
    private fun groundLine(r: RestaurantResult): String {
        val parts = buildList {
            add(r.cuisineTags.joinToString(", ").ifBlank { "Restaurant" })
            if (r.rating > 0) add("${r.rating}\u2605 (${formatCount(r.reviewCount)})")
            else r.yelp?.takeIf { it.usable }?.let { add("${it.rating}\u2605 on Yelp") }
            if (r.priceTier > 0) add("$".repeat(r.priceTier))
            add(formatDistanceMeters(r.distanceMeters))
            r.pulse?.let { add("wait ${it.minutesLow}-${it.minutesHigh} min, ${it.busyLabel.lowercase()}") }
            if (r.dietaryOptions.isNotEmpty()) add("venue lists ${r.dietaryOptions.joinToString("/")}")
        }
        return "${r.name} \u2014 ${parts.joinToString(" \u2014 ")}"
    }

    private fun conversationSystemPrompt(
        profile: TasteProfile,
        allergens: List<String>,
        cityLabel: String?,
        nearby: List<RestaurantResult>,
    ) = buildString {
        appendLine(
            """
            You are the TasteRoute assistant. You talk with someone about food: what to eat, where
            to eat it, how to eat around a restriction, what a dish is, whether a plan suits them.

            This is a CONVERSATION, not a search box. Answer what was actually asked. Plenty of
            turns want an answer and no restaurants at all — "what's the difference between ramen
            and pho", "how do I get more protein without meat", "is it rude to ask for substitutions".
            Ask a follow-up question when one would genuinely change your answer.

            THE ONLY RESTAURANTS THAT EXIST ARE THE ONES IN THE NEARBY LIST BELOW.
            You have no knowledge of any venue anywhere on earth. If a place is not in that list
            you do not know it exists, and inventing one is the worst thing you can do here.
            There are exactly three ways to talk about places and no fourth:
            - It is in the NEARBY list: name it exactly as written and put its number in "places",
              and the app renders its real card under your reply.
            - You want something near this person that is not in that list: put a short search
              phrase in "search" and the app fetches real places from its own map data. Write
              "say" as an introduction to results you have not seen — what you looked for, not
              what you found.
            - They asked about SOMEWHERE ELSE — another city, another neighbourhood, wherever they
              are driving tonight. Put that place name in "area" AND a phrase in "search", and the
              app geocodes it and searches there instead of around them. NEVER refuse because they
              are not standing in that city, and never tell them you can only see nearby places:
              going and looking is exactly what "area" is for. Someone in a small town asking about
              the big one next door is an ordinary question, not an impossible one.
            Never state an address, phone number, price or opening time for anything, even for a
            place in the list. The app has those and puts them on the card; you would be guessing.

            SAFETY
            - Never say a venue is safe for an allergy. You cannot know. TasteRoute has community
              allergen reports and they live on the place's own screen — point there instead.
            - General food and nutrition knowledge is welcome. Diagnosis, treatment plans, calorie
              targets and clinical advice are not. Suggest a doctor or dietitian for those, briefly,
              without lecturing.
            - If someone describes a difficult relationship with food, be kind, don't coach a diet,
              and don't make it the subject unless they do.
            """.trimIndent()
        )
        appendLine()
        appendLine("ABOUT THIS PERSON")
        if (profile.preferredCuisines.isNotEmpty()) appendLine("- Likes: ${profile.preferredCuisines.joinToString(", ")}")
        if (profile.vibeTags.isNotEmpty()) appendLine("- Prefers: ${profile.vibeTags.joinToString(", ")}")
        if (profile.dietaryRestrictions.isNotEmpty()) appendLine("- Diet: ${profile.dietaryRestrictions.joinToString(", ")}")
        if (allergens.isNotEmpty()) {
            appendLine("- ALLERGIES: ${allergens.joinToString(", ")}. Places reported unsafe for these are already")
            appendLine("  hidden from every list. Say so once if it is relevant; do not repeat it every turn.")
        }
        appendLine("- Budget: ${"$".repeat(profile.priceComfort.coerceIn(1, 4))}")
        cityLabel?.let {
            appendLine("- Around: $it")
            appendLine("  (Where they are standing. NOT a limit on what they may ask about — use \"area\".)")
        }
        if (nearby.isNotEmpty()) {
            appendLine()
            appendLine("NEARBY — real places the app is showing this person right now, best match first:")
            nearby.take(GROUND_LIMIT).forEachIndexed { i, r -> appendLine("${i + 1}. ${groundLine(r)}") }
        }
        appendLine()
        appendLine(
            """
            Reply with ONE JSON object and nothing else. No markdown, no code fences, no commentary.
            {"say":"","search":"","area":"","places":[],"profile":null}

            A good reply looks exactly like this, with nothing before it and nothing after it:
            {"say":"Number 2 is the one — proper charcoal yakitori, and it's four minutes' walk from you.","search":"","area":"","places":[2],"profile":null}

            And when they ask about somewhere they are not, exactly like this:
            {"say":"Vegas it is — let me pull up sushi over on the Strip rather than anything out here.","search":"sushi","area":"Las Vegas Strip","places":[],"profile":null}

            - say: your reply, as PLAIN PROSE. Second person, warm, specific, 2-4 sentences.
              Never put JSON inside it. No braces, no quotes wrapped around the whole thing, no
              markdown, no bullet lists, no headings, no code fences. If you find yourself typing
              a { inside "say", you have misunderstood: the JSON is only the envelope, and "say"
              is the sentence a person reads. Do not repeat the question back.
            - search: a short phrase to search for, ONLY on turns that should show places that
              are not already in the NEARBY list (e.g. "quiet vegetarian dinner"). Empty string on
              every other turn, and empty when the NEARBY list already answers the question.
            - area: the name of a place to search IN, and only when they asked about somewhere they
              are not — "Las Vegas", "downtown Reno", "the Strip". A name, never coordinates; the
              app geocodes it. Empty string on every other turn. Whenever you set "area" you must
              set "search" too: "area" says where, "search" says what.
            - places: the numbers from the NEARBY list you actually talked about, e.g. [2,5].
              Empty on every turn that named none. Never an id, never a name — only the number.
            - profile: null almost always. Set it only when the person states a LASTING preference
              or restriction ("I've gone vegetarian", "I hate spicy food"), never for one meal.
              Shape: {"cuisines":[],"diets":[],"vibes":[],"price_comfort":2}
            """.trimIndent()
        )
    }

    suspend fun parseTaste(
        text: String,
        cuisines: List<String>,
        diets: List<String>,
        vibes: List<String>,
    ): ParsedTaste {
        val system = """
            You convert a person's free-text description of their food taste into TasteRoute's
            fixed categories. Use ONLY these exact strings, copied verbatim:

            cuisines: ${cuisines.joinToString(", ")}
            diets: ${diets.joinToString(", ")}
            vibes: ${vibes.joinToString(", ")}

            price_comfort is 1-4 (1 = cheap eats, 4 = splurge). Infer it; default 2.
            Include a category only if the text actually supports it. Omit anything you're unsure of.
            Put a diet in "diets" only if it is a requirement, not a preference.

            Reply with ONE JSON object and nothing else. No markdown, no code fences, no commentary.
            {"cuisines":[],"diets":[],"vibes":[],"price_comfort":2}
        """.trimIndent()
        val json = chat(system = system, user = text.trim().take(1200), maxTokens = 300)
        return AppJson.decodeFromString(ParsedTaste.serializer(), json)
    }

    /** One tiny round trip for the settings screen. Reports which model answered. */
    suspend fun ping(): String {
        val json = chat(
            system = "Reply with ONE JSON object and nothing else: {\"ok\":true}",
            user = "ping",
            maxTokens = 24,
        )
        return if (json.contains("\"ok\"")) activeModel else "Unexpected reply: ${json.take(60)}"
    }

    /**
     * The bookkeeping wrapper: circuit breaker, [lastFailure], and demoting a model that keeps
     * timing out. [rawChat] does the talking and is used bare by [probeModels].
     */
    private suspend fun chat(
        system: String,
        user: String,
        maxTokens: Int,
        model: String = activeModel,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    ): String = chat(listOf(Msg("system", system), Msg("user", user)), maxTokens, model, connectTimeoutMs)

    private suspend fun chat(
        messages: List<Msg>,
        maxTokens: Int,
        model: String = activeModel,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = RANK_TIMEOUT_MS,
        temperature: Double = 0.2,
        backoffKey: String = Backoff.RANKER,
        requireJson: Boolean = true,
    ): String {
        if (!isConfigured) throw NimException("NIM_API_KEY missing from local.properties")
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
    }

    /**
     * [chat] with the reply arriving in pieces. Same bookkeeping — breaker, [lastFailure], model
     * demotion — because a stream that dies is exactly as much of a failure as a call that does.
     */
    private suspend fun chatStream(
        messages: List<Msg>,
        maxTokens: Int,
        model: String = activeModel,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = RANK_TIMEOUT_MS,
        temperature: Double = 0.2,
        backoffKey: String = Backoff.CHAT,
        onPartial: (String) -> Unit,
    ): String {
        if (!isConfigured) throw NimException("NIM_API_KEY missing from local.properties")
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
    }

    /**
     * Reads the SSE body and hands the caller everything received so far after each delta.
     *
     * A malformed chunk is skipped rather than thrown: one unreadable frame in the middle of a
     * hundred good ones must not lose the reply that is otherwise arriving perfectly.
     */
    private suspend fun rawChatStream(
        messages: List<Msg>,
        maxTokens: Int,
        model: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        temperature: Double,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val body = AppJson.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = messages,
                maxTokens = maxTokens,
                temperature = temperature,
                stream = true,
            ),
        )
        val text = StringBuilder()
        httpPostStream(
            url = "${BuildConfig.NIM_BASE_URL}/chat/completions",
            body = body,
            contentType = "application/json",
            headers = mapOf("Authorization" to "Bearer ${BuildConfig.NIM_API_KEY}"),
            readTimeoutMs = readTimeoutMs,
            connectTimeoutMs = connectTimeoutMs,
        ) { line ->
            if (line.startsWith("data:")) {
                val payload = line.removePrefix("data:").trim()
                if (payload.isNotEmpty() && payload != "[DONE]") {
                    val delta = runCatching {
                        AppJson.decodeFromString(StreamChunk.serializer(), payload)
                            .choices.firstOrNull()?.delta?.content.orEmpty()
                    }.getOrDefault("")
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        onPartial(text.toString())
                    }
                }
            }
        }
        text.toString().trim()
    }

    /** Talks to NIM and returns the JSON object from the reply. No state, no backoff, no logging. */
    private suspend fun rawChat(
        system: String,
        user: String,
        maxTokens: Int,
        model: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String = rawChat(listOf(Msg("system", system), Msg("user", user)), maxTokens, model, connectTimeoutMs, readTimeoutMs)

    private suspend fun rawChat(
        messages: List<Msg>,
        maxTokens: Int,
        model: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        temperature: Double = 0.2,
        requireJson: Boolean = true,
    ): String {
        val body = AppJson.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model = model, messages = messages, maxTokens = maxTokens, temperature = temperature),
        )
        val raw = withContext(Dispatchers.IO) {
            httpPost(
                url = "${BuildConfig.NIM_BASE_URL}/chat/completions",
                body = body,
                contentType = "application/json",
                headers = mapOf("Authorization" to "Bearer ${BuildConfig.NIM_API_KEY}"),
                accept = "application/json",
                readTimeoutMs = readTimeoutMs,
                connectTimeoutMs = connectTimeoutMs,
            )
        }
        val content = runCatching {
            AppJson.decodeFromString(ChatResponse.serializer(), raw).choices.firstOrNull()?.message?.content.orEmpty()
        }.getOrElse { throw NimException("Reply wasn't the shape we expect: ${raw.take(160)}") }

        if (content.isBlank()) throw NimException("Model returned an empty reply")
        val extracted = extractJsonObject(content)?.let(::escapeControlChars)
        // For the ranker, a reply with no JSON in it is a failure. For a conversation it is just
        // an answer, so hand the prose back and let the caller decide what to do with it.
        return extracted
            ?: if (requireJson) throw NimException("Model returned no JSON: ${content.take(160)}") else content.trim()
    }

    /** HTTP codes mean specific, fixable things here; say which rather than echoing a stack message. */
    private fun describe(
        error: Throwable,
        code: Int?,
        elapsedMs: Long,
        // Passed in rather than read from [activeModel]: by the time this runs the failed id has
        // usually already been replaced, so reading the active one named the wrong model in every
        // message the settings screen showed.
        model: String = activeModel,
    ): String = when {
        code == 401 || code == 403 -> "NVIDIA rejected the key (HTTP $code) — check NIM_API_KEY in local.properties"
        code == 404 -> "No such model (HTTP 404): $model"
        code == 429 -> "Rate limited by NVIDIA (HTTP 429)"
        code != null && code >= 500 -> "NVIDIA returned HTTP $code"
        // Naming which budget ran out is the difference between "raise the timeout" and "the phone
        // can't reach NVIDIA at all" — they look identical in a stack trace.
        error is java.net.SocketTimeoutException && elapsedMs < RANK_CONNECT_MS + 500 ->
            "Couldn't connect to NVIDIA within ${elapsedMs}ms — network, not the key"
        error is java.net.SocketTimeoutException ->
            "$model didn't finish within ${elapsedMs}ms"
        error is java.net.UnknownHostException -> "Can't resolve ${BuildConfig.NIM_BASE_URL} — no DNS"
        else -> error.message?.take(180) ?: error::class.simpleName ?: "Unknown failure"
    }

    private fun rankSystemPrompt(limit: Int) = """
        You rank restaurants for TasteRoute. The user message contains a numbered list of REAL
        places near the user, returned by a places API.

        HARD RULES
        - Choose only from the numbered list. Never invent a place, a name, or a location.
        - Refer to entries by their number. Pick at most $limit, best fit first. Fewer is fine.
        - Skip anything that clashes with a stated dietary need.

        Reply with ONE JSON object and nothing else. No markdown, no code fences, no commentary.
        {"picks":[{"i":1,"score":0}]}

        - i: the entry's number from the list.
        - score: 0-100, how well it fits this person's taste profile and request.

        Write nothing else. No explanations, no prose, no trailing text. Every token you generate is
        a second the diner spends watching a list they can already see.
    """.trimIndent()

    private fun rankUserPrompt(
        candidates: List<Candidate>,
        profile: TasteProfile,
        query: String,
        cityLabel: String?,
    ) = buildString {
        appendLine("Request: ${query.ifBlank { "anything great near me right now" }}")
        cityLabel?.let { appendLine("Area: $it") }
        if (profile.preferredCuisines.isNotEmpty()) appendLine("Loves: ${profile.preferredCuisines.joinToString(", ")}")
        if (profile.dietaryRestrictions.isNotEmpty()) appendLine("Must satisfy: ${profile.dietaryRestrictions.joinToString(", ")}")
        if (profile.vibeTags.isNotEmpty()) appendLine("Preferred vibes: ${profile.vibeTags.joinToString(", ")}")
        appendLine("Budget comfort: ${"$".repeat(profile.priceComfort.coerceIn(1, 4))}")
        val dismissed = profile.pastInteractions.filter { it.action == "dismissed" }.flatMap { it.cuisineTags }.distinct()
        if (dismissed.isNotEmpty()) appendLine("Avoid: ${dismissed.joinToString(", ")}")
        appendLine()
        appendLine("Nearby places:")
        candidates.forEachIndexed { i, c ->
            val r = c.record
            val parts = buildList {
                add(r.cuisineTags.joinToString(", ").ifBlank { "Restaurant" })
                if (r.rating > 0) add("${r.rating}★ (${formatCount(r.reviewCount)})")
                if (r.priceTier > 0) add("$".repeat(r.priceTier))
                add(formatDistanceMeters(c.distanceMeters))
                if (r.dietaryOptions.isNotEmpty()) add("diet: ${r.dietaryOptions.joinToString("/")}")
            }
            appendLine("${i + 1}. ${r.name} — ${parts.joinToString(" — ")}")
        }
    }

    @Serializable
    private data class Pick(
        @SerialName("i") val index: Int = 0,
        val score: Int = 0,
        val why: String = "",
    )

    @Serializable
    private data class Picks(val picks: List<Pick> = emptyList())

    /**
     * Both fields carry defaults on purpose. NIM returns `content: null` when a generation stops
     * for certain reasons, and a non-nullable field WITHOUT a default is not covered by
     * `coerceInputValues` — so the whole response failed to decode with a serialization error that
     * said nothing about the actual cause.
     */
    @Serializable
    private data class Msg(val role: String = "assistant", val content: String = "")

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Msg>,
        val temperature: Double = 0.2,
        @SerialName("top_p") val topP: Double = 0.9,
        @SerialName("max_tokens") val maxTokens: Int = 900,
        val stream: Boolean = false,
    )

    /** One SSE frame. Every field defaults, because a keepalive frame carries almost nothing. */
    @Serializable
    private data class StreamDelta(val content: String = "")

    @Serializable
    private data class StreamChoice(val delta: StreamDelta = StreamDelta())

    @Serializable
    private data class StreamChunk(val choices: List<StreamChoice> = emptyList())

    /** Only the ids are read; the catalogue carries pricing and modality fields we do not use. */
    @Serializable
    private data class ModelList(val data: List<Entry> = emptyList()) {
        @Serializable
        data class Entry(val id: String = "")
    }

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList()) {
        @Serializable
        data class Choice(val message: Msg = Msg("assistant", ""))
    }

    /**
     * Models wrap JSON in prose or fences, so take the outermost balanced object. If it never
     * closes, the reply hit the token ceiling mid-write — cut back to the last complete pick and
     * close what is still open. Eleven usable picks beat discarding all twelve.
     */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val open = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> open.addLast(c)
                c == '}' || c == ']' -> {
                    open.removeLastOrNull()
                    if (open.isEmpty()) return text.substring(start, i + 1)
                }
            }
        }
        return repairTruncated(text, start)
    }

    /**
     * Escapes control characters that are still sitting raw inside a JSON string.
     *
     * A model writing prose into a JSON field will eventually put a literal newline in it, and that
     * is invalid JSON however lenient the parser is set to be. Ranking never tripped over this
     * because its output is numbers; a conversation trips over it constantly.
     */
    private fun escapeControlChars(json: String): String = buildString(json.length + 16) {
        var inString = false
        var escaped = false
        for (c in json) {
            when {
                escaped -> { append(c); escaped = false }
                c == '\\' && inString -> { append(c); escaped = true }
                c == '"' -> { append(c); inString = !inString }
                !inString -> append(c)
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(' ')
                else -> append(c)
            }
        }
    }

    private fun repairTruncated(text: String, start: Int): String? {
        val lastComplete = text.lastIndexOf('}')
        // A generation cut off before it ever wrote a closing brace still has whole fields in it.
        // Returning null here sent the raw text to cleanReply, which keeps `say` and throws
        // `search` and `area` away — so the model said "let me look in Vegas", the app searched
        // nowhere, and nothing on screen explained why. Only attempted when the fragment carries
        // one of our keys: a sentence that merely happens to contain a brace is still prose.
        val trimmed = when {
            lastComplete > start -> text.substring(start, lastComplete + 1)
            ENVELOPE_HINT.containsMatchIn(text.substring(start)) -> text.substring(start)
            else -> return null
        }

        val open = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in trimmed) {
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> open.addLast(c)
                c == '}' || c == ']' -> open.removeLastOrNull()
            }
        }
        if (open.isEmpty() && !inString) return trimmed
        return buildString {
            append(trimmed)
            // Close the string FIRST. A brace appended while still inside one is not a brace, it
            // is one more character of the sentence, and the object stays unparseable.
            if (inString) append('"')
            while (open.isNotEmpty()) append(if (open.removeLast() == '[') ']' else '}')
        }
    }
}
