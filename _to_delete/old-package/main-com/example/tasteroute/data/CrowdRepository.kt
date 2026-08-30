package com.example.tasteroute.data

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

/**
 * Everything about a place that isn't the place itself: live waits, allergen signal, cover photos
 * and star ratings. All first-party, all fetched in one batched second pass.
 *
 * Deliberately not part of the place fetch: the feed paints from the local scorer in well under a
 * second, and blocking that on four lookups would trade the one thing the app is fast at for a
 * badge. Results are enriched in place once the data lands.
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

    /** Waits go stale in minutes. Photos and ratings do not, so they are fetched once per id. */
    private var pulseFetchedAt = 0L
    private const val PULSE_TTL_MS = 120_000L

    fun snapshotPulse(id: String): CrowdPulse? = synchronized(pulses) { pulses[id] }

    fun snapshotAllergens(id: String): List<AllergenSignal> = synchronized(allergens) { allergens[id] ?: emptyList() }

    fun snapshotYelp(id: String): YelpInfo? = synchronized(yelps) { yelps[id] }

    fun snapshotSources(id: String): List<ExternalSource> = synchronized(sources) { sources[id] ?: emptyList() }

    /** Enrich a result list with everything already cached. Cheap, synchronous, no network. */
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
                                // OSM never carries a photo for most places, so a diner's upload
                                // leads whenever one exists.
                                imageUrl = covers[r.id] ?: r.imageUrl,
                                rating = rating?.rating ?: r.rating,
                                reviewCount = rating?.count ?: r.reviewCount,
                                // Yelp's numbers stay in their own field. They are Yelp's claim,
                                // rendered as Yelp's and linked back to it — never folded into the
                                // average above, which is a claim about our own diners.
                                yelp = yelpById[r.id] ?: r.yelp,
                                // Same rule as Yelp, generalised: every platform keeps its own
                                // number under its own name. None of them touch `rating`.
                                sources = sourcesById[r.id] ?: r.sources,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Fetch pulse, allergen, cover-photo and rating data for the visible ids.
     * Silent on failure: a missing badge is not worth an error banner over real results.
     *
     * The four lookups run CONCURRENTLY and each calls [onBatch] the moment it lands. They used to
     * run one after another, which meant cover photos — the part you actually look at — waited on
     * three unrelated round trips before anything could be drawn. Now each costs its own latency
     * and nobody else's, and the card fills in visibly instead of all at once at the end.
     */
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

    /**
     * [refresh] plus the Yelp pass, telling the caller after EACH batch instead of once at the end.
     * Five round trips land in unpredictable order; publishing each as it arrives is what makes the
     * feed fill in rather than sitting still until the slowest of them returns.
     */
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
                    synchronized(pulses) {
                        // Places with no reports must drop out, or a stale wait lingers forever.
                        ids.forEach { pulses.remove(it) }
                        pulses.putAll(fresh)
                    }
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
                    // Cache the misses too, so an unreported place isn't re-fetched every scroll.
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

    /** After an upload or a review, the cached cover and rating for that place are wrong. */
    fun invalidatePlace(placeId: String) {
        synchronized(covers) { covers.remove(placeId) }
        synchronized(ratings) { ratings.remove(placeId) }
    }

    fun noteRating(placeId: String, summary: ReviewSummary) {
        synchronized(ratings) { ratings[placeId] = summary }
    }

    /**
     * Yelp matches on a name and a fix, not on our id, so this takes results rather than ids. It is
     * also the one upstream here with a licensed daily call ceiling, so it runs for the page the
     * user is actually looking at and never speculatively.
     */
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

    /**
     * The multi-source pass. Like Yelp it matches on name and coordinates rather than our id, so it
     * takes results — and like Yelp it is capped, because the expensive upstreams behind it bill
     * per call. Detail-only connectors contribute cache hits here and never spend anything.
     */
    suspend fun refreshSources(results: List<RestaurantResult>): Boolean {
        if (!GexemyClient.reachable(Backoff.SOURCES) || results.isEmpty()) return false
        val wanted = results.take(30).filterNot { r -> synchronized(sources) { sources.containsKey(r.id) } }
        if (wanted.isEmpty()) return false
        return runCatching { GexemyClient.sourcesFor(wanted) }
            .onFailure { Backoff.record(Backoff.SOURCES, it) }
            .map { fresh ->
                // Cache the empty answers too. A place no platform has heard of must not be
                // re-queried on every scroll for the rest of the session.
                synchronized(sources) { wanted.forEach { p -> sources[p.id] = fresh[p.id] ?: emptyList() } }
                fresh.values.any { it.isNotEmpty() }
            }
            .getOrDefault(false)
    }

    /** After a detail fetch, which is the only place the expensive connectors actually run. */
    fun noteSources(placeId: String, found: List<ExternalSource>) {
        synchronized(sources) { sources[placeId] = found }
    }

    fun noteYelp(placeId: String, info: YelpInfo) {
        synchronized(yelps) { yelps[placeId] = info }
    }

    fun prefetch(results: List<RestaurantResult>) {
        if (results.isEmpty()) return
        scope.launch { runCatching { refreshStaged(results) {} } }
    }

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
