package space.gexemy.tasteroute.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.network.GexemyClient
import space.gexemy.tasteroute.data.network.Backoff

/**
 * Everything about a place that isn't the place itself: live waits, allergen signal, cover photos
 * and star ratings. All first-party, all fetched in one batched second pass.
 */
object CrowdRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val pulses = mutableMapOf<String, CrowdPulse>()
    private val allergens = mutableMapOf<String, List<AllergenSignal>>()
    private val covers = mutableMapOf<String, String>()
    private val ratings = mutableMapOf<String, ReviewSummary>()
    private val yelps = mutableMapOf<String, YelpInfo>()
    private val sources = mutableMapOf<String, List<ExternalSource>>()

    private var pulseFetchedAt = 0L
    private const val PULSE_TTL_MS = 120_000L

    fun snapshotPulse(id: String): CrowdPulse? = synchronized(pulses) { pulses[id] }
    fun snapshotAllergens(id: String): List<AllergenSignal> = synchronized(allergens) { allergens[id] ?: emptyList() }
    fun snapshotYelp(id: String): YelpInfo? = synchronized(yelps) { yelps[id] }
    fun snapshotSources(id: String): List<ExternalSource> = synchronized(sources) { sources[id] ?: emptyList() }

    fun enrich(results: List<RestaurantResult>): List<RestaurantResult> {
        if (results.isEmpty()) return results
        val yelpById = synchronized(yelps) { yelps.toMap() }
        val sourcesById = synchronized(sources) { sources.toMap() }
        return synchronized(pulses) {
            synchronized(allergens) {
                synchronized(covers) {
                    synchronized(ratings) {
                        results.map { r ->
                            val rating = ratings[r.id]
                            r.copy(
                                pulse = pulses[r.id] ?: r.pulse,
                                allergens = allergens[r.id] ?: r.allergens,
                                imageUrl = covers[r.id] ?: r.imageUrl ?: yelpById[r.id]?.imageUrl
                                    ?: sourcesById[r.id]?.firstNotNullOfOrNull { s -> s.photos.firstOrNull()?.url },
                                rating = rating?.rating ?: r.rating,
                                reviewCount = rating?.count ?: r.reviewCount,
                                yelp = yelpById[r.id] ?: r.yelp,
                                sources = sourcesById[r.id] ?: r.sources,
                                menuHighlights = r.menuHighlights.ifEmpty {
                                    sourcesById[r.id]?.firstNotNullOfOrNull { s -> s.facts.menuItems.ifEmpty { null } } ?: emptyList()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun refresh(ids: List<String>, onBatch: suspend () -> Unit = {}): Boolean {
        if (!GexemyClient.reachable(Backoff.CROWD) || ids.isEmpty()) return false
        val wanted = ids.take(60)
        val stale = System.currentTimeMillis() - pulseFetchedAt > PULSE_TTL_MS
        val missingAllergens = synchronized(allergens) { wanted.filterNot { allergens.containsKey(it) } }
        val missingMedia = synchronized(covers) { wanted.filterNot { covers.containsKey(it) } }
        val missingRatings = synchronized(ratings) { wanted.filterNot { ratings.containsKey(it) } }
        val missingPulse = synchronized(pulses) { wanted.filterNot { pulses.containsKey(it) } }
        val pulseIds = if (stale) wanted else missingPulse
        if (pulseIds.isEmpty() && missingAllergens.isEmpty() && missingMedia.isEmpty() && missingRatings.isEmpty()) {
            return false
        }

        return lock.withLock {
            coroutineScope {
                buildList<Deferred<Boolean>> {
                    if (pulseIds.isNotEmpty()) add(async { fetchPulse(pulseIds, onBatch) })
                    if (missingAllergens.isNotEmpty()) add(async { fetchAllergens(missingAllergens, onBatch) })
                    if (missingMedia.isNotEmpty()) add(async { fetchCovers(missingMedia, onBatch) })
                    if (missingRatings.isNotEmpty()) add(async { fetchRatings(missingRatings, onBatch) })
                }.awaitAll().any { it }
            }
        }
    }

    suspend fun refreshStaged(results: List<RestaurantResult>, onBatch: suspend () -> Unit) {
        if (results.isEmpty()) return
        coroutineScope {
            launch { runCatching { refresh(results.map { it.id }, onBatch) } }
            launch { runCatching { if (refreshYelp(results)) onBatch() } }
            launch { runCatching { if (refreshSources(results)) onBatch() } }
        }
    }

    private suspend fun fetchPulse(ids: List<String>, onBatch: suspend () -> Unit): Boolean =
        runCatching { GexemyClient.pulseFor(ids) }
            .onFailure { Backoff.record(Backoff.CROWD, it) }
            .fold(
                onSuccess = { fresh ->
                    synchronized(pulses) { ids.forEach { pulses.remove(it) }; pulses.putAll(fresh) }
                    pulseFetchedAt = System.currentTimeMillis()
                    onBatch()
                    true
                },
                onFailure = { false },
            )

    private suspend fun fetchAllergens(ids: List<String>, onBatch: suspend () -> Unit): Boolean =
        runCatching { GexemyClient.allergensFor(ids) }
            .onFailure { Backoff.record(Backoff.CROWD, it) }
            .fold(
                onSuccess = { fresh ->
                    synchronized(allergens) { ids.forEach { allergens[it] = fresh[it] ?: emptyList() } }
                    onBatch()
                    true
                },
                onFailure = { false },
            )

    private suspend fun fetchCovers(ids: List<String>, onBatch: suspend () -> Unit): Boolean =
        runCatching { GexemyClient.coversFor(ids) }
            .onFailure { Backoff.record(Backoff.CROWD, it) }
            .fold(
                onSuccess = { fresh ->
                    synchronized(covers) { ids.forEach { id -> fresh[id]?.let { covers[id] = it } } }
                    onBatch()
                    true
                },
                onFailure = { false },
            )

    private suspend fun fetchRatings(ids: List<String>, onBatch: suspend () -> Unit): Boolean =
        runCatching { GexemyClient.ratingsFor(ids) }
            .onFailure { Backoff.record(Backoff.CROWD, it) }
            .fold(
                onSuccess = { fresh ->
                    synchronized(ratings) { ids.forEach { id -> fresh[id]?.let { ratings[id] = it } } }
                    onBatch()
                    true
                },
                onFailure = { false },
            )

    suspend fun checkIn(placeId: String, waitMinutes: Int, busy: Int, seated: Boolean, partySize: Int): CrowdPulse? {
        val pulse = GexemyClient.checkIn(placeId, waitMinutes, busy, seated, partySize)
        if (pulse != null) synchronized(pulses) { pulses[placeId] = pulse }
        return pulse
    }

    suspend fun report(placeId: String, allergen: String, stance: String, note: String): List<AllergenSignal> {
        val signals = GexemyClient.reportAllergen(placeId, allergen, stance, note)
        synchronized(allergens) { allergens[placeId] = signals }
        return signals
    }

    fun invalidatePlace(placeId: String) {
        synchronized(covers) { covers.remove(placeId) }
        synchronized(ratings) { ratings.remove(placeId) }
    }

    fun noteRating(placeId: String, summary: ReviewSummary) {
        synchronized(ratings) { ratings[placeId] = summary }
    }

    suspend fun refreshYelp(results: List<RestaurantResult>): Boolean {
        if (!GexemyClient.reachable(Backoff.YELP) || results.isEmpty()) return false
        val wanted = results.take(30).filterNot { r -> synchronized(yelps) { yelps.containsKey(r.id) } }
        if (wanted.isEmpty()) return false
        return runCatching { GexemyClient.yelpFor(wanted) }
            .onFailure { Backoff.record(Backoff.YELP, it) }
            .map { fresh ->
                synchronized(yelps) { wanted.forEach { p -> fresh[p.id]?.let { yelps[p.id] = it } } }
                fresh.isNotEmpty()
            }
            .getOrDefault(false)
    }

    suspend fun refreshSources(results: List<RestaurantResult>): Boolean {
        if (!GexemyClient.reachable(Backoff.SOURCES) || results.isEmpty()) return false
        val wanted = results.take(30).filterNot { r -> synchronized(sources) { sources.containsKey(r.id) } }
        if (wanted.isEmpty()) return false
        return runCatching { GexemyClient.sourcesFor(wanted) }
            .onFailure { Backoff.record(Backoff.SOURCES, it) }
            .map { fresh ->
                synchronized(sources) { wanted.forEach { p -> sources[p.id] = fresh[p.id] ?: emptyList() } }
                fresh.values.any { it.isNotEmpty() }
            }
            .getOrDefault(false)
    }

    fun noteSources(placeId: String, found: List<ExternalSource>) { synchronized(sources) { sources[placeId] = found } }
    fun noteYelp(placeId: String, info: YelpInfo) { synchronized(yelps) { yelps[placeId] = info } }
    fun prefetch(results: List<RestaurantResult>) { if (results.isEmpty()) return; scope.launch { runCatching { refreshStaged(results) {} } } }

    fun clear() {
        synchronized(pulses) { pulses.clear() }
        synchronized(allergens) { allergens.clear() }
        synchronized(covers) { covers.clear() }
        synchronized(ratings) { ratings.clear() }
        synchronized(yelps) { yelps.clear() }
        synchronized(sources) { sources.clear() }
        pulseFetchedAt = 0L
    }
}
