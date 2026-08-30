package com.example.tasteroute.data

import android.util.Log
import com.example.tasteroute.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class NimException(message: String) : Exception(message)

data class Candidate(val record: RestaurantRecord, val distanceMeters: Int)

data class RankedPick(val id: String, val score: Int, val reasoning: String)

/** One model's answer to a ping: how long it took, or why it didn't answer. */
data class ModelProbe(val model: String, val millis: Long, val error: String? = null) {
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

    /** A bad key or a retired model name is configuration, not weather. Stop asking for a while. */
    private const val CONFIG_BACKOFF_MS = 30 * 60_000L
    private val CONFIG_CODES = setOf(400, 401, 403, 404, 422)

    /** Filter Logcat on this tag to read the real cause without opening a settings screen. */
    private const val TAG = "TasteRouteRanker"

    /** Ordered fastest-first. Set NIM_MODELS in local.properties to change it. */
    val candidates: List<String> =
        BuildConfig.NIM_MODELS.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** NIM_MODEL, when set, pins the model and skips probing entirely. */
    private val pinned: String? = BuildConfig.NIM_MODEL.trim().ifBlank { null }

    private var chosenModel: String? = null
    private val failed = mutableSetOf<String>()

    /** The pin, the probed winner, or the first candidate until a probe lands. */
    val activeModel: String
        get() = pinned ?: chosenModel ?: candidates.firstOrNull { it !in failed }
            ?: candidates.firstOrNull() ?: "meta/llama-3.1-8b-instruct"

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
        val saved = Prefs.getString(Prefs.NIM_MODEL_CHOICE)
        if (saved.isNotBlank() && saved in candidates) chosenModel = saved
    }

    /** Runs one probe round if nothing has been chosen yet. Safe to call on every app start. */
    suspend fun ensureModelChosen() {
        if (!isConfigured || pinned != null || chosenModel != null) return
        runCatching { probeModels() }
    }

    /**
     * Races every candidate with an 12-token ping and keeps the fastest that answers.
     * Deliberately does NOT go through [chat]: a slow candidate we are about to discard must not
     * trip the ranker's circuit breaker or overwrite [lastFailure].
     */
    suspend fun probeModels(): List<ModelProbe> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()

        val results = candidates.map { candidate ->
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
                        ModelProbe(candidate, elapsed, describe(error, (error as? HttpException)?.code, elapsed))
                    },
                )
            }
        }.awaitAll().sortedWith(compareBy({ !it.ok }, { it.millis }))

        lastProbe = results
        results.firstOrNull { it.ok }?.let { winner ->
            chosenModel = winner.model
            failed.clear()
            Prefs.put(Prefs.NIM_MODEL_CHOICE, winner.model)
            Backoff.clear(Backoff.RANKER)
            lastFailure = null
            Log.i(TAG, "Fastest model: ${winner.model} at ${winner.millis}ms")
        }
        results.forEach { Log.i(TAG, "probe ${it.model}: ${if (it.ok) "${it.millis}ms" else it.error}") }
        results
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
    ): AssistantTurn {
        val messages = buildList {
            add(Msg("system", conversationSystemPrompt(profile, allergens, cityLabel)))
            history.takeLast(CHAT_HISTORY_TURNS).forEach {
                add(Msg(if (it.fromUser) "user" else "assistant", it.text.take(600)))
            }
        }
        val json = chat(
            messages = messages,
            maxTokens = CHAT_TOKENS,
            connectTimeoutMs = RANK_CONNECT_MS,
            readTimeoutMs = CHAT_TIMEOUT_MS,
            // Ranking wants the same answer every time. A conversation that answers identically
            // twice reads as a phone tree.
            temperature = 0.6,
            backoffKey = Backoff.CHAT,
        )
        return AppJson.decodeFromString(AssistantTurn.serializer(), json)
    }

    private fun conversationSystemPrompt(
        profile: TasteProfile,
        allergens: List<String>,
        cityLabel: String?,
    ) = buildString {
        appendLine(
            """
            You are the TasteRoute assistant. You talk with someone about food: what to eat, where
            to eat it, how to eat around a restriction, what a dish is, whether a plan suits them.

            This is a CONVERSATION, not a search box. Answer what was actually asked. Plenty of
            turns want an answer and no restaurants at all — "what's the difference between ramen
            and pho", "how do I get more protein without meat", "is it rude to ask for substitutions".
            Ask a follow-up question when one would genuinely change your answer.

            YOU DO NOT KNOW ANY RESTAURANTS.
            Never name a venue, address, phone number, price or opening time. You have no such
            knowledge and anything you produce will be invented. When a turn should show places,
            put a short search phrase in "search" and the app fetches real ones from its own map
            data and shows them under your reply. Then write "say" as an introduction to results
            you have not seen yet — say what you looked for, not what you found.

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
        cityLabel?.let { appendLine("- Around: $it") }
        appendLine()
        appendLine(
            """
            Reply with ONE JSON object and nothing else. No markdown, no code fences, no commentary.
            {"say":"","search":"","profile":null}

            - say: your reply. Second person, warm, specific, 2-4 sentences. Plain prose — no
              markdown, no bullet lists, no headings. Do not repeat the question back.
            - search: a short phrase to search for, ONLY on turns that should show places
              (e.g. "quiet vegetarian dinner"). Empty string on every other turn.
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
    ): String {
        if (!isConfigured) throw NimException("NIM_API_KEY missing from local.properties")
        val startedAt = System.currentTimeMillis()
        try {
            val json = rawChat(messages, maxTokens, model, connectTimeoutMs, readTimeoutMs, temperature)
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

        return content.takeIf { it.isNotBlank() }?.let(::extractJsonObject)?.let(::escapeControlChars)
            ?: throw NimException(
                if (content.isBlank()) "Model returned an empty reply"
                else "Model returned no JSON: ${content.take(160)}"
            )
    }

    /** HTTP codes mean specific, fixable things here; say which rather than echoing a stack message. */
    private fun describe(error: Throwable, code: Int?, elapsedMs: Long): String = when {
        code == 401 || code == 403 -> "NVIDIA rejected the key (HTTP $code) — check NIM_API_KEY in local.properties"
        code == 404 -> "No such model (HTTP 404): $activeModel"
        code == 429 -> "Rate limited by NVIDIA (HTTP 429)"
        code != null && code >= 500 -> "NVIDIA returned HTTP $code"
        // Naming which budget ran out is the difference between "raise the timeout" and "the phone
        // can't reach NVIDIA at all" — they look identical in a stack trace.
        error is java.net.SocketTimeoutException && elapsedMs < RANK_CONNECT_MS + 500 ->
            "Couldn't connect to NVIDIA within ${elapsedMs}ms — network, not the key"
        error is java.net.SocketTimeoutException ->
            "$activeModel didn't finish within ${elapsedMs}ms"
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
        if (lastComplete <= start) return null
        val trimmed = text.substring(start, lastComplete + 1)

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
        if (open.isEmpty()) return trimmed
        return buildString {
            append(trimmed)
            while (open.isNotEmpty()) append(if (open.removeLast() == '[') ']' else '}')
        }
    }
}
