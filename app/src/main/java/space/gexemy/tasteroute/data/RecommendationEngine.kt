package space.gexemy.tasteroute.data

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class AiOverlay(val score: Int, val reasoning: String)

/**
 * Local scorer over real candidates. Runs on its own when the model is unreachable, and supplies
 * distance, tier gating and ordering when the model does rank.
 *
 * Rating, review count and price are optional (0 = unknown) and score neutrally when absent
 * rather than being invented. Wait times are no longer estimated here at all — they come from
 * real check-ins via [CrowdRepository], because a plausible-looking made-up wait is worse than
 * an empty badge.
 */
object RecommendationEngine {

    fun recommend(
        request: RecommendationRequest,
        catalog: List<RestaurantRecord>,
        aiOverlay: Map<String, AiOverlay> = emptyMap(),
        /**
         * Ids in the model's order, applied as the last sort. Its picks lead; everything it never
         * looked at keeps its own honest score and stays on the list underneath rather than being
         * deleted for not having been mentioned.
         */
        priority: List<String> = emptyList(),
    ): RecommendationResponse {
        val (origin, profile, query, source, tier) = request
        val filters = request.appliedFilters
        val tokens = tokenize(query)
        val onTheWay = request.mode == SearchMode.ON_THE_WAY

        val scored = catalog
            .asSequence()
            .filter { matchesFilters(it, filters) }
            .map { r -> scoreCandidate(r, distanceMeters(origin, r.coordinates), profile, tokens, query, onTheWay) }
            .map { r ->
                val overlay = aiOverlay[r.id] ?: return@map r
                r.copy(
                    aiMatchScore = overlay.score,
                    // The ranker returns indices and scores only. Reasons stay local, where they are
                    // built from the listed facts and cannot drift — and where they cost no latency.
                    aiMatchReasoning = overlay.reasoning.ifBlank { r.aiMatchReasoning },
                )
            }
            .toList()

        val ranked = scored.sortedByDescending { it.aiMatchScore }.toMutableList()
        when {
            onTheWay -> ranked.sortBy { it.detourMeters }
            "nearby" in filters -> ranked.sortBy { it.distanceMeters }
            // Stable, so the tail the model never scored keeps the order the local scorer gave it.
            priority.isNotEmpty() -> {
                val order = priority.withIndex().associate { (i, id) -> id to i }
                ranked.sortBy { order[it.id] ?: Int.MAX_VALUE }
            }
        }

        val limit = if (source == ResultSource.AI_CHAT) 3 else Entitlements.maxResults(Tier.PRO)
        val freeCap = Entitlements.maxResults(Tier.FREE)
        val results = ranked.take(limit).mapIndexed { i, r ->
            r.copy(tierRequired = if (i < freeCap) Tier.FREE else Tier.PLUS)
        }
        return RecommendationResponse(source, query, results, System.currentTimeMillis())
    }

    /**
     * A confirmed-unsafe report for something the user is allergic to removes the place outright.
     * This is the only hard filter in the app: dietary *preferences* demote, allergens exclude.
     */
    fun allergenSafe(result: RestaurantResult, allergens: List<String>): Boolean =
        allergens.none { result.allergenFor(it)?.accommodates == false }

    private fun scoreCandidate(
        r: RestaurantRecord,
        distance: Int,
        profile: TasteProfile,
        tokens: List<String>,
        query: String,
        onTheWay: Boolean,
    ): RestaurantResult {
        val matchedCuisines = r.cuisineTags.filter { c -> profile.preferredCuisines.any { it.equals(c, true) } }
        val cuisine = if (profile.preferredCuisines.isEmpty()) 0.5 else min(1.0, matchedCuisines.size * 0.55 + 0.1)

        val matchedVibes = r.vibeTags.filter { v -> profile.vibeTags.any { it.equals(v, true) } }
        val vibe = if (profile.vibeTags.isEmpty()) 0.5 else min(1.0, matchedVibes.size * 0.5 + 0.15)

        val haystack = (r.cuisineTags + r.vibeTags + r.dietaryOptions + r.name.split(" ")).map { it.lowercase() }
        val hits = tokens.count { t -> haystack.any { it.contains(t) } }
        val relevance = if (tokens.isEmpty()) 0.5 else hits.toDouble() / tokens.size

        // Diet tags are sparse in open data, so an unmet restriction demotes rather than excludes —
        // a hard filter would empty the list in most cities. Allergens are handled separately.
        val metDiets = profile.dietaryRestrictions.count { d -> r.dietaryOptions.any { it.equals(d, true) } }
        val diet = when {
            profile.dietaryRestrictions.isEmpty() -> 0.5
            else -> 0.25 + 0.75 * (metDiets.toDouble() / profile.dietaryRestrictions.size)
        }

        // On a corridor search, "close to me" is the wrong axis — a place 8km away but 40m off the
        // route is a better stop than one 500m away that costs a U-turn.
        val proximity = if (onTheWay) {
            1.0 - (r.detourMeters / 2_000.0).coerceIn(0.0, 1.0)
        } else {
            1.0 - (distance / 10_000.0).coerceIn(0.0, 1.0)
        }
        val price = if (r.priceTier == 0) 0.5 else 1.0 - min(1.0, abs(r.priceTier - profile.priceComfort) * 0.4)
        val quality = if (r.rating <= 0.0) 0.5 else ((r.rating - 3.5) / 1.5).coerceIn(0.0, 1.0)

        val past = profile.pastInteractions.sumOf { p ->
            val overlap = p.cuisineTags.any { c -> r.cuisineTags.any { it.equals(c, true) } }
            when {
                !overlap -> 0.0
                p.action == "dismissed" -> -0.05
                else -> 0.04
            }
        }.coerceIn(-0.1, 0.12)

        /*
         * A FACTOR NOBODY ANSWERED IS NOT A BAD SCORE, IT IS NO SCORE.
         *
         * The old average fed a neutral 0.5 into every unknown — no rating in OSM, no price tier,
         * no query typed, no vibes set — and seven of those pinned almost every card to somewhere
         * near 50%. Reported as "I see a 50% match and I'm like, ehh, do I really want it", which
         * is exactly right: 50% reads as "probably not" for a place that might be perfect and the
         * app simply had nothing to say about four of its columns.
         *
         * So unknowns drop out and the weights renormalise over what is actually known. Proximity
         * is the only one always present, which is why it can carry the average alone.
         */
        val factors = buildList {
            if (profile.preferredCuisines.isNotEmpty()) add(0.26 to cuisine)
            if (tokens.isNotEmpty()) add(0.22 to relevance)
            add(0.18 to proximity)
            if (r.rating > 0.0) add(0.14 to quality)
            if (profile.dietaryRestrictions.isNotEmpty()) add(0.10 to diet)
            if (profile.vibeTags.isNotEmpty()) add(0.08 to vibe)
            if (r.priceTier > 0) add(0.08 to price)
        }
        val weight = factors.sumOf { it.first }
        val base = if (weight <= 0.0) 0.6 else factors.sumOf { it.first * it.second } / weight
        // Then eased, not inflated. The curve is monotonic so it cannot reorder anything — it only
        // stops a genuinely good fit from landing in the band people scroll past.
        val raw = (base + past).coerceIn(0.0, 1.0).pow(0.62)

        val reasons = buildList {
            if (matchedCuisines.isNotEmpty()) add("Matches your ${matchedCuisines.take(2).joinToString(" + ")} taste")
            if (tokens.isNotEmpty() && hits > 0) add("Fits \"$query\"")
            if (onTheWay) add(detourLabel(r.detourMeters))
            if (r.rating >= 4.3) add("${r.rating}★ from ${formatCount(r.reviewCount)} reviews")
            if (!onTheWay) {
                if (distance <= 1_200) add("${walkMinutes(distance)} min walk") else add("${formatDistanceMeters(distance)} away")
            }
            if (metDiets > 0) add("${r.dietaryOptions.first()} options listed")
            if (matchedVibes.isNotEmpty()) add("${matchedVibes.first()} vibe")
        }.take(3)

        return RestaurantResult(
            id = r.id,
            name = r.name,
            coordinates = r.coordinates,
            rating = r.rating,
            reviewCount = r.reviewCount,
            priceTier = r.priceTier,
            cuisineTags = r.cuisineTags,
            distanceMeters = distance,
            aiMatchScore = (raw * 100).roundToInt(),
            aiMatchReasoning = reasons.joinToString(" · "),
            imageUrl = r.imageUrl,
            address = r.address,
            openingHours = r.openingHours,
            webUrl = r.webUrl,
            dietaryOptions = r.dietaryOptions,
            detourMeters = r.detourMeters,
            alongRouteFraction = r.alongRouteFraction,
        )
    }

    fun detourLabel(detourMeters: Int): String = when {
        detourMeters <= 150 -> "Right on your route"
        detourMeters <= 600 -> "${formatDistanceMeters(detourMeters)} detour"
        else -> "${driveMinutes(detourMeters)} min detour"
    }

    private fun tokenize(query: String) =
        query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }

    private fun matchesFilters(r: RestaurantRecord, filters: List<String>) =
        ("vegan" !in filters || r.dietaryOptions.any { it.equals("Vegan", true) }) &&
            ("budget" !in filters || r.priceTier == 0 || r.priceTier <= 2)

    fun distanceMeters(a: Coordinates, b: Coordinates): Int {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val s = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return (2 * radius * asin(sqrt(s))).roundToInt()
    }
}
