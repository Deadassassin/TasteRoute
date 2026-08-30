package com.example.tasteroute.data

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where places come from. Yelp was dropped: its terms require its brand on every result and a link
 * back to its listing, which is an advert for a competitor sitting on our own results page. The
 * catalog is now our own service over OpenStreetMap, with a direct Overpass call as the fallback
 * when our API is unreachable — and everything Yelp had that OSM lacks (waits, allergen truth,
 * group fit) is now first-party data we own.
 */
enum class PlaceSource(val label: String) {
    GEXEMY("Map data © OpenStreetMap contributors"),
    DIRECT("Map data © OpenStreetMap contributors"),
}

/**
 * How finished a result list is. The screen renders every one of these — the point is that it
 * never renders nothing. Ordered, so a late enrichment can never downgrade a ranked list.
 */
enum class ResultStage {
    /** Last session's list for this area, painted before the first packet leaves the phone. */
    WARM,

    /** Real places from the provider, ordered by the local scorer. */
    LOCAL,

    /** Photos, ratings, waits and allergen signal, merged in as each batch lands. */
    ENRICHED,

    /** The model's ordering. */
    RANKED,
}

data class RecommendationOutcome(
    val results: List<RestaurantResult> = emptyList(),
    val source: PlaceSource? = null,
    val stage: ResultStage = ResultStage.LOCAL,
    /** True when the local scorer ranked these — either as the fast first pass or because AI failed. */
    val rankedLocally: Boolean = false,
    /** True while an AI re-rank of these same results is still in flight. */
    val pending: Boolean = false,
    val error: String? = null,
    /** Corridor searches carry the driving path so the map can draw the real route. */
    val routeGeometry: List<Coordinates> = emptyList(),
) {
    /** Anything still on its way. Drives a thin progress line, never a skeleton over real cards. */
    val settling: Boolean get() = pending || stage == ResultStage.WARM
}

/**
 * Single entry point for every screen.
 *
 * [stream] is deliberately chatty. It emits the previous session's list before it touches the
 * network, then real places as soon as the provider answers, then again after each batch of crowd
 * data lands, then once more when the model finishes re-ranking. Waiting for any one of those
 * before showing anything was what made Discover feel like it never loaded — the fix is not to
 * make the slow call faster but to stop it being the first thing anyone waits on.
 */
object Recommender {

    /** How far from the saved fix a warm list is still describing the same neighbourhood. */
    private const val WARM_RADIUS_M = 1_200

    /** Older than this and "places near you" is a claim we can no longer make about a cached list. */
    private const val WARM_TTL_MS = 6 * 60 * 60 * 1000L

    private val cache = object : LinkedHashMap<String, RecommendationOutcome>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecommendationOutcome>) = size > 24
    }

    /** Provider calls are rate-limited: cache the place list so typing only re-ranks. */
    private val places = object : LinkedHashMap<String, Triple<PlaceSource, List<RestaurantRecord>, List<Coordinates>>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Triple<PlaceSource, List<RestaurantRecord>, List<Coordinates>>>,
        ) = size > 8
    }

    fun stream(request: RecommendationRequest, cityLabel: String? = null): Flow<RecommendationOutcome> = channelFlow {
        val key = cacheKey(request)
        synchronized(cache) { cache[key] }?.let { hit ->
            suspend fun replay() = send(hit.copy(results = present(hit.results, request), stage = ResultStage.RANKED))
            replay()
            // A cached ordering is still worth re-enriching: waits go stale in minutes, and a photo
            // uploaded since this list was built should appear without a cold search to trigger it.
            CrowdRepository.refreshStaged(hit.results) { replay() }
            return@channelFlow
        }

        // Something real on screen before the radio wakes up. Stale by definition, which is why it
        // ships as its own stage rather than pretending to be a fresh result.
        warmStart(request)?.let { send(it) }

        val radius = Entitlements.radiusMeters(request.tier)
        val fetched = try {
            fetchCached(request, radius)
        } catch (e: Exception) {
            send(RecommendationOutcome(error = e.message ?: "Couldn't reach the places service"))
            return@channelFlow
        }
        val (source, nearby, geometry) = fetched
        if (nearby.isEmpty()) {
            send(
                RecommendationOutcome(
                    source = source,
                    routeGeometry = geometry,
                    error = if (request.mode == SearchMode.ON_THE_WAY) {
                        "Nothing along that route — widen the corridor or pick a different destination."
                    } else {
                        // Name the point it actually searched. An empty area and a bad location fix
                        // produce the identical screen otherwise, and they have opposite fixes.
                        "Nothing found within ${formatDistanceMeters(radius)} of " +
                            "${cityLabel ?: "your location"} (${round(request.origin)})."
                    },
                )
            )
            return@channelFlow
        }

        val candidates = nearby
            .map { Candidate(it, RecommendationEngine.distanceMeters(request.origin, it.coordinates)) }
            .sortedBy { if (request.mode == SearchMode.ON_THE_WAY) it.record.detourMeters else it.distanceMeters }
            .take(Perf.candidateBudget)

        var base = RecommendationOutcome(
            results = RecommendationEngine.recommend(request, candidates.map { it.record }).results,
            source = source,
            stage = ResultStage.LOCAL,
            rankedLocally = true,
            pending = NimClient.isReady,
            routeGeometry = geometry,
        )
        AppState.rememberNames(base.results)
        AppState.rememberWarm(base.results, request)
        send(base.copy(results = present(base.results, request)))

        // One lock over `base` because enrichment and ranking finish in either order, and the
        // loser must not publish a list that undoes the winner's ordering.
        val guard = Mutex()

        val enriching = launch {
            CrowdRepository.refreshStaged(base.results) {
                guard.withLock {
                    if (base.stage < ResultStage.ENRICHED) base = base.copy(stage = ResultStage.ENRICHED)
                    send(base.copy(results = present(base.results, request)))
                }
            }
        }

        if (!NimClient.isReady) {
            enriching.join()
            guard.withLock { store(key, base.copy(pending = false)) }
            return@channelFlow
        }

        val limit = if (request.source == ResultSource.AI_CHAT) 3 else 12
        val ranked = try {
            val picks = NimClient.rank(candidates, request.profile, request.query, cityLabel, limit)
            if (picks.isEmpty()) throw NimException("Model returned no picks")
            val byId = candidates.associateBy { it.record.id }
            val ordered = picks.mapNotNull { byId[it.id]?.record }
            val overlay = picks.associate { it.id to AiOverlay(it.score, it.reasoning) }
            RecommendationOutcome(
                results = RecommendationEngine.recommend(request, ordered, overlay).results,
                source = source,
                stage = ResultStage.RANKED,
                routeGeometry = geometry,
            )
        } catch (e: Exception) {
            // NimClient.lastFailure is the human-readable version ("NVIDIA rejected the key
            // (HTTP 401) — check NIM_API_KEY"); e.message is the raw one. Show the useful one.
            guard.withLock { base }.copy(
                pending = false,
                error = NimClient.lastFailure ?: e.message ?: "Ranking service unavailable",
            )
        }
        AppState.rememberNames(ranked.results)
        guard.withLock {
            base = ranked
            send(ranked.copy(results = present(ranked.results, request)))
        }
        if (ranked.error == null) AppState.rememberWarm(ranked.results, request)
        // channelFlow cancels its children the moment this block returns, so enrichment that is
        // still in flight has to be waited for or the last batch is silently dropped.
        enriching.join()
        // Never cache a failed re-rank. The cache is consulted before the backoff is, so a stored
        // error replays the same banner on every later search for the same query and the ranker is
        // never retried — which is most of why "the AI ranker is unavailable" looked permanent.
        if (ranked.error == null) store(key, ranked)
    }

    /** Final answer only — for callers like the assistant that render one message. */
    suspend fun recommend(request: RecommendationRequest, cityLabel: String? = null): RecommendationOutcome =
        stream(request, cityLabel).last()

    /** Warms the place cache while onboarding is on screen so Discover paints immediately after. */
    suspend fun prefetch(origin: Coordinates, tier: Tier) {
        runCatching {
            fetchCached(RecommendationRequest(origin, AppState.profile, tier = tier), Entitlements.radiusMeters(tier))
        }
    }

    fun invalidate() {
        synchronized(cache) { cache.clear() }
        synchronized(places) { places.clear() }
    }

    /**
     * The list this device saw last, if it was for roughly this spot and recently enough to still
     * be true. Only for a plain unfiltered nearby search: a warm list cannot honestly answer a
     * typed query or a filter it was never built under, and showing one that does not match the
     * controls on screen is worse than a skeleton.
     */
    private fun warmStart(request: RecommendationRequest): RecommendationOutcome? {
        if (request.mode == SearchMode.ON_THE_WAY) return null
        if (request.query.isNotBlank() || request.appliedFilters.isNotEmpty()) return null
        val warm = AppState.warmResults
        val savedAt = AppState.warmOrigin ?: return null
        if (warm.isEmpty()) return null
        if (System.currentTimeMillis() - AppState.warmSavedAt > WARM_TTL_MS) return null
        if (RecommendationEngine.distanceMeters(request.origin, savedAt) > WARM_RADIUS_M) return null
        return RecommendationOutcome(
            results = present(warm, request),
            stage = ResultStage.WARM,
            rankedLocally = true,
            pending = true,
        )
    }

    private fun store(key: String, outcome: RecommendationOutcome) {
        synchronized(cache) { cache[key] = outcome }
    }

    private suspend fun fetchCached(
        request: RecommendationRequest,
        radius: Int,
    ): Triple<PlaceSource, List<RestaurantRecord>, List<Coordinates>> {
        val origin = request.origin
        val destination = request.destination
        val areaKey = if (request.mode == SearchMode.ON_THE_WAY && destination != null) {
            "way|${round(origin)}|${round(destination)}|${Entitlements.corridorMeters(request.tier)}"
        } else {
            "near|${round(origin)}|$radius"
        }

        synchronized(places) { places[areaKey] }?.let { return it }
        val fetched = fetch(request, radius)
        // NEVER cache an empty list. Overpass answers 200 with zero elements when it is shedding
        // load, and caching that pins a whole neighbourhood to "nothing found" for the rest of the
        // session — which from the outside is indistinguishable from the app having stopped working,
        // because it survives every retry and only a process restart clears it.
        if (fetched.second.isNotEmpty()) synchronized(places) { places[areaKey] = fetched }
        return fetched
    }

    private suspend fun fetch(
        request: RecommendationRequest,
        radius: Int,
    ): Triple<PlaceSource, List<RestaurantRecord>, List<Coordinates>> {
        val destination = request.destination
        if (request.mode == SearchMode.ON_THE_WAY && destination != null) {
            val corridor = GexemyClient.corridor(request.origin, destination, Entitlements.corridorMeters(request.tier))
            return Triple(PlaceSource.GEXEMY, corridor.places, corridor.geometry)
        }

        var apiError: Exception? = null
        if (GexemyClient.reachable()) {
            try {
                return Triple(PlaceSource.GEXEMY, GexemyClient.nearby(request.origin, radius), emptyList())
            } catch (e: Exception) {
                // Skip our own API for a few minutes rather than paying its timeout every search.
                Backoff.record(Backoff.PLACES, e)
                apiError = e
            }
        }
        return try {
            Triple(PlaceSource.DIRECT, OverpassClient.nearby(request.origin, radius), emptyList())
        } catch (e: Exception) {
            throw Exception("Couldn't reach TasteRoute (${describe(apiError)}) or OpenStreetMap (${describe(e)})")
        }
    }

    /**
     * A 404 from our own API is a wrong base URL, not an outage, and saying so is the difference
     * between a user who edits one line of local.properties and one who thinks the app is broken.
     */
    private fun describe(error: Exception?): String = when ((error as? HttpException)?.code) {
        null -> error?.message?.take(90) ?: "not configured"
        404, 501 -> "GEXEMY_BASE_URL isn't serving this API"
        else -> error?.message?.take(90) ?: "unavailable"
    }

    /**
     * Everything between the scorer and the screen: merge whatever crowd data has landed, then
     * drop anything a user's allergen is confirmed unsafe for. Applied at every emit so a late
     * allergen report removes a card the next time the list is read, not only on a cold search.
     */
    private fun present(results: List<RestaurantResult>, request: RecommendationRequest): List<RestaurantResult> {
        val enriched = CrowdRepository.enrich(results)
        if (request.allergens.isEmpty()) return enriched
        return enriched.filter { RecommendationEngine.allergenSafe(it, request.allergens) }
    }

    private fun round(c: Coordinates) = String.format(Locale.US, "%.3f,%.3f", c.lat, c.lng)

    private fun cacheKey(r: RecommendationRequest) = listOf(
        round(r.origin),
        r.mode.name,
        r.destination?.let { round(it) }.orEmpty(),
        r.query.trim().lowercase(Locale.US),
        r.source.name,
        r.tier.name,
        r.appliedFilters.sorted().joinToString(","),
        r.allergens.sorted().joinToString(","),
        r.profile.preferredCuisines.sorted().joinToString(","),
        r.profile.dietaryRestrictions.sorted().joinToString(","),
        r.profile.vibeTags.sorted().joinToString(","),
        r.profile.priceComfort.toString(),
    ).joinToString("|")
}
