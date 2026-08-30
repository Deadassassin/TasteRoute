package space.gexemy.tasteroute.data.repository

import java.util.Locale
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.gexemy.tasteroute.data.Perf
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.network.*
import space.gexemy.tasteroute.data.state.AppState

enum class PlaceSource(val label: String) {
    GEXEMY("Map data © OpenStreetMap contributors"),
    DIRECT("Map data © OpenStreetMap contributors"),
}

enum class ResultStage { WARM, FIRST, LOCAL, ENRICHED, RANKED }

data class RecommendationOutcome(
    val results: List<RestaurantResult> = emptyList(),
    val source: PlaceSource? = null,
    val stage: ResultStage = ResultStage.LOCAL,
    val rankedLocally: Boolean = false,
    val pending: Boolean = false,
    val error: String? = null,
    val note: String? = null,
    val routeGeometry: List<Coordinates> = emptyList(),
) {
    val settling: Boolean get() = pending || stage < ResultStage.LOCAL
}

object Recommender {

    private const val WARM_RADIUS_M = 1_200
    private const val WARM_TTL_MS = 45 * 60 * 1000L
    private const val WARM_GRACE_MS = 350L
    private const val QUICK_RADIUS_M = 900
    private const val QUICK_LIMIT = 4
    private const val THIN_RESULTS = 5
    private const val WIDE_RADIUS_M = 25_000

    private val cache = object : LinkedHashMap<String, RecommendationOutcome>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecommendationOutcome>) = size > 24
    }

    private val places = object : LinkedHashMap<String, Triple<PlaceSource, List<RestaurantRecord>, List<Coordinates>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<PlaceSource, List<RestaurantRecord>, List<Coordinates>>>) = size > 8
    }

    fun stream(request: RecommendationRequest, cityLabel: String? = null): Flow<RecommendationOutcome> = channelFlow {
        val key = cacheKey(request)
        synchronized(cache) { cache[key] }?.let { hit ->
            suspend fun replay() = send(hit.copy(results = present(hit.results, request), stage = ResultStage.RANKED))
            replay()
            CrowdRepository.refreshStaged(hit.results) { replay() }
            return@channelFlow
        }

        val radius = searchRadius(request)
        val guard = Mutex()
        var painted = false
        var settled = false

        val quick: Deferred<List<RestaurantRecord>>? = if (request.mode == SearchMode.ON_THE_WAY) null else async {
            runCatching { quickFetch(request) }.getOrDefault(emptyList())
        }
        val full = async { runCatching { fetchCached(request, radius) } }

        val fastJob = quick?.let { deferred ->
            launch {
                val records = deferred.await().ifEmpty { return@launch }
                val scored = RecommendationEngine.recommend(
                    request,
                    records.sortedBy { RecommendationEngine.distanceMeters(request.origin, it.coordinates) }.take(QUICK_LIMIT),
                ).results.ifEmpty { return@launch }
                guard.withLock {
                    if (settled) return@withLock
                    painted = true
                    AppState.rememberNames(scored)
                    send(RecommendationOutcome(results = present(scored, request), stage = ResultStage.FIRST, rankedLocally = true, pending = true))
                }
                CrowdRepository.refresh(scored.map { it.id }) {
                    guard.withLock {
                        if (settled) return@withLock
                        send(RecommendationOutcome(results = present(scored, request), stage = ResultStage.FIRST, rankedLocally = true, pending = true))
                    }
                }
            }
        }

        val warmJob = launch {
            delay(WARM_GRACE_MS)
            guard.withLock {
                if (painted || settled) return@withLock
                warmStart(request)?.let { painted = true; send(it) }
            }
        }

        val fetched = full.await().getOrElse { e ->
            fastJob?.join(); warmJob.cancel()
            send(RecommendationOutcome(error = e.message ?: "Couldn't reach the places service"))
            return@channelFlow
        }
        warmJob.cancel()

        var (source, nearby, geometry) = fetched
        var note: String? = null

        if (request.mode != SearchMode.ON_THE_WAY && nearby.size < THIN_RESULTS && radius < WIDE_RADIUS_M) {
            val wider = (radius * 4).coerceAtMost(WIDE_RADIUS_M)
            runCatching { fetchCached(request, wider) }.getOrNull()?.takeIf { it.second.size > nearby.size }?.let {
                source = it.first; nearby = it.second; geometry = it.third
                note = "Not much within ${formatDistanceMeters(radius)} — widened to ${formatDistanceMeters(wider)}."
            }
        }

        if (nearby.isEmpty()) {
            fastJob?.join()
            send(RecommendationOutcome(source = source, routeGeometry = geometry, error = if (request.mode == SearchMode.ON_THE_WAY) "Nothing along that route — widen the corridor or pick a different destination." else "Nothing found within ${formatDistanceMeters((radius * 4).coerceAtMost(WIDE_RADIUS_M))} of ${cityLabel ?: "your location"} (${round(request.origin)})."))
            return@channelFlow
        }

        val candidates = nearby.map { Candidate(it, RecommendationEngine.distanceMeters(request.origin, it.coordinates)) }
            .sortedBy { if (request.mode == SearchMode.ON_THE_WAY) it.record.detourMeters else it.distanceMeters }
            .take(Perf.candidateBudget)

        var base = RecommendationOutcome(results = RecommendationEngine.recommend(request, candidates.map { it.record }).results, source = source, stage = ResultStage.LOCAL, rankedLocally = true, pending = NimClient.isReady, note = note, routeGeometry = geometry)
        AppState.rememberNames(base.results)
        AppState.rememberWarm(base.results, request)
        guard.withLock { settled = true; send(base.copy(results = present(base.results, request))) }
        fastJob?.cancel()

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
            val chosen = ordered.mapTo(mutableSetOf()) { it.id }
            val rest = candidates.map { it.record }.filterNot { it.id in chosen }
            val overlay = picks.associate { it.id to AiOverlay(it.score, it.reasoning) }
            RecommendationOutcome(results = RecommendationEngine.recommend(request, ordered + rest, overlay, priority = ordered.map { it.id }).results, source = source, stage = ResultStage.RANKED, note = note, routeGeometry = geometry)
        } catch (e: Exception) {
            guard.withLock { base }.copy(pending = false, error = NimClient.lastFailure ?: e.message ?: "Ranking service unavailable")
        }
        AppState.rememberNames(ranked.results)
        guard.withLock { base = ranked; send(ranked.copy(results = present(ranked.results, request))) }
        if (ranked.error == null) AppState.rememberWarm(ranked.results, request)
        enriching.join()
        if (ranked.error == null) store(key, ranked)
    }

    suspend fun recommend(request: RecommendationRequest, cityLabel: String? = null): RecommendationOutcome = stream(request, cityLabel).last()
    suspend fun prefetch(origin: Coordinates, tier: Tier) { runCatching { fetchCached(RecommendationRequest(origin, AppState.profile, tier = tier), Entitlements.radiusMeters(tier)) } }
    fun invalidate() { synchronized(cache) { cache.clear() }; synchronized(places) { places.clear() } }
    private fun searchRadius(request: RecommendationRequest) = if (request.mode == SearchMode.ANYWHERE) Entitlements.areaRadiusMeters(request.tier) else Entitlements.radiusMeters(request.tier)

    private suspend fun quickFetch(request: RecommendationRequest): List<RestaurantRecord> {
        if (GexemyClient.reachable()) {
            val viaApi = runCatching { GexemyClient.nearby(request.origin, QUICK_RADIUS_M, quick = true) }.getOrNull()
            if (!viaApi.isNullOrEmpty()) return viaApi
        }
        return runCatching { OverpassClient.nearby(request.origin, QUICK_RADIUS_M, limit = 20, timeoutSeconds = 8, readTimeoutMs = 7_000) }.getOrDefault(emptyList())
    }

    private fun warmStart(request: RecommendationRequest): RecommendationOutcome? {
        if (request.mode == SearchMode.ON_THE_WAY || request.query.isNotBlank() || request.appliedFilters.isNotEmpty()) return null
        val warm = AppState.warmResults
        val savedAt = AppState.warmOrigin ?: return null
        if (warm.isEmpty() || System.currentTimeMillis() - AppState.warmSavedAt > WARM_TTL_MS || RecommendationEngine.distanceMeters(request.origin, savedAt) > WARM_RADIUS_M) return null
        return RecommendationOutcome(results = present(warm, request), stage = ResultStage.WARM, rankedLocally = true, pending = true)
    }

    private fun store(key: String, outcome: RecommendationOutcome) { synchronized(cache) { cache[key] = outcome } }

    private suspend fun fetchCached(request: RecommendationRequest, radius: Int): Triple<PlaceSource, List<RestaurantRecord>, List<Coordinates>> {
        val origin = request.origin
        val destination = request.destination
        val areaKey = if (request.mode == SearchMode.ON_THE_WAY && destination != null) "way|${round(origin)}|${round(destination)}|${Entitlements.corridorMeters(request.tier)}" else "near|${round(origin)}|$radius"
        synchronized(places) { places[areaKey] }?.let { return it }
        val fetched = fetch(request, radius)
        if (fetched.second.isNotEmpty()) synchronized(places) { places[areaKey] = fetched }
        return fetched
    }

    private suspend fun fetch(request: RecommendationRequest, radius: Int): Triple<PlaceSource, List<RestaurantRecord>, List<Coordinates>> {
        val destination = request.destination
        if (request.mode == SearchMode.ON_THE_WAY && destination != null) {
            val corridor = GexemyClient.corridor(request.origin, destination, Entitlements.corridorMeters(request.tier))
            return Triple(PlaceSource.GEXEMY, corridor.places, corridor.geometry)
        }
        var apiError: Exception? = null
        if (GexemyClient.reachable()) {
            try { return Triple(PlaceSource.GEXEMY, GexemyClient.nearby(request.origin, radius), emptyList()) }
            catch (e: Exception) { Backoff.record(Backoff.PLACES, e); apiError = e }
        }
        return try { Triple(PlaceSource.DIRECT, OverpassClient.nearby(request.origin, radius), emptyList()) }
        catch (e: Exception) { throw Exception("Couldn't reach TasteRoute (${describe(apiError)}) or OpenStreetMap (${describe(e)})") }
    }

    private fun describe(error: Exception?): String = when ((error as? HttpException)?.code) {
        null -> error?.message?.take(90) ?: "not configured"
        404, 501 -> "GEXEMY_BASE_URL isn't serving this API"
        else -> error?.message?.take(90) ?: "unavailable"
    }

    private fun present(results: List<RestaurantResult>, request: RecommendationRequest): List<RestaurantResult> {
        val enriched = CrowdRepository.enrich(results)
        return if (request.allergens.isEmpty()) enriched else enriched.filter { RecommendationEngine.allergenSafe(it, request.allergens) }
    }

    private fun round(c: Coordinates) = String.format(Locale.US, "%.3f,%.3f", c.lat, c.lng)
    private fun cacheKey(r: RecommendationRequest) = listOf(round(r.origin), r.mode.name, r.destination?.let { round(it) }.orEmpty(), r.query.trim().lowercase(Locale.US), r.source.name, r.tier.name, r.appliedFilters.sorted().joinToString(","), r.allergens.sorted().joinToString(","), r.profile.preferredCuisines.sorted().joinToString(","), r.profile.dietaryRestrictions.sorted().joinToString(","), r.profile.vibeTags.sorted().joinToString(","), r.profile.priceComfort.toString()).joinToString("|")
}
