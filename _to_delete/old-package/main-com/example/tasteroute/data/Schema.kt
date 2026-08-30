package com.example.tasteroute.data

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Single source of truth for every recommendation result in the app.
 * Filter search, corridor search, AI conversational search and map queries all emit
 * [RestaurantResult]; list rows, map pins and chat cards are just different renderers of it.
 */

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
enum class Tier {
    @SerialName("free") FREE,
    @SerialName("plus") PLUS,
    @SerialName("pro") PRO;

    fun atLeast(other: Tier) = ordinal >= other.ordinal

    val wire: String get() = name.lowercase(Locale.US)

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v?.lowercase(Locale.US) } ?: FREE
    }
}

@Serializable
data class Coordinates(val lat: Double, val lng: Double)

/**
 * How a result list was gathered. NEARBY and ANYWHERE both search a radius — the difference is
 * only whose coordinates the radius is centred on, so distances stay honest either way.
 * ON_THE_WAY results additionally carry detour data.
 */
enum class SearchMode { NEARBY, ON_THE_WAY, ANYWHERE }

@Serializable
data class PlacePhoto(
    val id: Long = 0,
    val url: String,
    val caption: String = "",
    @SerialName("user_id") val userId: Long? = null,
)

@Serializable
data class Review(
    val rating: Int,
    val body: String = "",
    val name: String = "Diner",
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("visited_on") val visitedOn: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** "tasteroute" for our own; anything else is imported and must be credited on screen. */
    val source: String = "tasteroute",
    @SerialName("source_url") val sourceUrl: String? = null,
) {
    val imported: Boolean get() = source != "tasteroute"
}

/** Where an imported review came from. Data-driven so a new connector needs no app release. */
@Serializable
data class ReviewSource(
    val source: String,
    val label: String,
    val url: String? = null,
    val count: Int = 0,
)

/**
 * Aggregate over every review on a place. [own] is how many are first-party — surfaced because a
 * 4.5 from four of our diners and a 4.5 from four imported rows are not the same claim.
 */
@Serializable
data class ReviewSummary(val rating: Double = 0.0, val count: Int = 0, val own: Int = 0)

/**
 * Yelp's rating and review excerpts for a place, fetched server-side and kept in their own field.
 *
 * Never merged into [RestaurantResult.rating]: that average is a claim about our own diners plus
 * open data, and Yelp's terms are explicit that its content may not be used to build a derivative
 * rating. [url] is not optional either — every surface that shows a Yelp number has to link back
 * to the listing it came from, which is the price of using it.
 */
@Serializable
data class YelpInfo(
    val rating: Double = 0.0,
    @SerialName("review_count") val reviewCount: Int = 0,
    val price: String = "",
    val url: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    val categories: List<String> = emptyList(),
    val reviews: List<YelpReview> = emptyList(),
) {
    val usable: Boolean get() = rating > 0 && url.isNotBlank()
}

/** Yelp returns excerpts, not full reviews; [url] opens the whole thing on Yelp. */
@Serializable
data class YelpReview(
    val rating: Int = 0,
    val text: String = "",
    val author: String = "",
    val url: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * A photo from an outside source. [credit] is not decoration — most upstreams licence their images
 * on the condition that the photographer is named, so a photo whose credit was lost is a photo we
 * are not allowed to show.
 */
@Serializable
data class SourcePhoto(
    val url: String,
    val caption: String = "",
    val credit: String = "",
    @SerialName("credit_url") val creditUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** An excerpt from someone else's platform. [url] opens the full review where it was written. */
@Serializable
data class SourceReview(
    val rating: Int = 0,
    val title: String = "",
    val body: String = "",
    val author: String = "",
    @SerialName("author_url") val authorUrl: String? = null,
    val url: String = "",
    val lang: String? = null,
    @SerialName("visited_on") val visitedOn: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * A tri-state group of venue attributes: true, false, or absent.
 *
 * Absent is NOT false and the distinction has to survive all the way to the screen. "We know they
 * have no outdoor seating" and "nobody has ever said" look identical once you collapse them, and
 * collapsing them is how a detail screen ends up confidently wrong about a patio, a ramp, or
 * whether a kitchen does gluten-free.
 */
typealias FactGroup = Map<String, Boolean?>

/**
 * Everything structured about a place that isn't a rating, merged across every source that knew
 * something. Each field is whatever the most trusted source that had an answer said — see the
 * server's `mergeFacts`, where the order of trust is defined and argued.
 */
@Serializable
data class PlaceFacts(
    val phone: String? = null,
    val website: String? = null,
    val menu: String? = null,
    val email: String? = null,
    val address: String? = null,
    @SerialName("maps_url") val mapsUrl: String? = null,
    val summary: String? = null,
    val status: String? = null,
    @SerialName("hours_text") val hoursText: List<String> = emptyList(),
    @SerialName("open_now") val openNow: Boolean? = null,
    @SerialName("price_range") val priceRange: String? = null,
    val cuisines: List<String> = emptyList(),
    val ranking: String? = null,
    /** Tripadvisor's bubble graphic. Their display terms require it in place of our own stars. */
    @SerialName("rating_image") val ratingImage: String? = null,
    val awards: List<String> = emptyList(),
    val service: FactGroup = emptyMap(),
    val amenities: FactGroup = emptyMap(),
    val diet: FactGroup = emptyMap(),
    val meals: FactGroup = emptyMap(),
    val drinks: FactGroup = emptyMap(),
    val payment: FactGroup = emptyMap(),
    val socials: Map<String, String> = emptyMap(),
    /**
     * A rating a venue published about itself on its own website. Kept because throwing away a
     * known fact is worse than storing it, and NEVER rendered as a rating — a restaurant claiming
     * 4.9 stars about itself is marketing, not an aggregate.
     */
    @SerialName("self_rating") val selfRating: Double? = null,
) {
    val hasAnything: Boolean
        get() = phone != null || website != null || menu != null || summary != null ||
            hoursText.isNotEmpty() || cuisines.isNotEmpty() || socials.isNotEmpty() ||
            listOf(service, amenities, diet, meals, drinks, payment).any { g -> g.values.any { it != null } }
}

/**
 * One outside platform's view of a place: Tripadvisor, Google, Yelp, or the venue's own website.
 *
 * Never merged into [RestaurantResult.rating]. That average is a claim about our own diners plus
 * open data, and every licensed upstream forbids deriving a rating from its content — so each one
 * keeps its own number, under its own name, linked back to its own listing. [logoRequired] is the
 * platform's display terms encoded as data rather than as a rule someone has to remember.
 */
@Serializable
data class ExternalSource(
    val source: String,
    val label: String,
    val url: String = "",
    val home: String = "",
    @SerialName("logo_required") val logoRequired: Boolean = false,
    val rating: Double = 0.0,
    @SerialName("review_count") val reviewCount: Int = 0,
    val price: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    val categories: List<String> = emptyList(),
    val facts: PlaceFacts = PlaceFacts(),
    val photos: List<SourcePhoto> = emptyList(),
    val reviews: List<SourceReview> = emptyList(),
) {
    /** Has a number worth showing AND somewhere to send the tap. Both, or it must not be rendered. */
    val ratesIt: Boolean get() = rating > 0 && url.isNotBlank()

    val hasContent: Boolean get() = ratesIt || reviews.isNotEmpty() || photos.isNotEmpty()
}

/** Credit line for the About screen, driven by the server so a new connector needs no release. */
@Serializable
data class Attribution(
    val source: String,
    val label: String,
    val url: String = "",
    val note: String = "",
)

/**
 * Live crowd report aggregated from real check-ins, replacing the seeded estimate the app used
 * to show. [reports] and [confidence] ship to the UI on purpose: an unbacked number presented
 * with the same weight as a well-backed one is worse than no number.
 */
@Serializable
data class CrowdPulse(
    @SerialName("minutes_low") val minutesLow: Int,
    @SerialName("minutes_high") val minutesHigh: Int,
    val busy: Int = 3, // 1..5
    val confidence: String = "low", // "low" | "medium" | "high"
    val reports: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val busyLabel: String
        get() = when (busy) {
            1 -> "Quiet"
            2 -> "Steady"
            3 -> "Busy"
            4 -> "Packed"
            else -> "Slammed"
        }
}

/**
 * Community allergen signal. [accommodates] is deliberately nullable: null means nobody has
 * reported, which is a different answer from "no" and has to stay visible as one.
 */
@Serializable
data class AllergenSignal(
    val allergen: String,
    val accommodates: Boolean? = null,
    val confidence: String = "unknown", // "unknown" | "low" | "high" | "contested"
    val safe: Int = 0,
    val unsafe: Int = 0,
    val unsure: Int = 0,
    val reports: Int = 0,
    val notes: List<String> = emptyList(),
)

/** rating 0.0 / reviewCount 0 / priceTier 0 all mean "unknown" and score neutrally. */
@Serializable
data class RestaurantResult(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val rating: Double = 0.0,
    @SerialName("review_count") val reviewCount: Int = 0,
    @SerialName("price_tier") val priceTier: Int = 0, // 1..4, 0 = unknown
    @SerialName("cuisine_tags") val cuisineTags: List<String>,
    @SerialName("distance_meters") val distanceMeters: Int,
    @SerialName("ai_match_score") val aiMatchScore: Int, // 0..100
    @SerialName("ai_match_reasoning") val aiMatchReasoning: String, // parts joined with " · "
    @SerialName("tier_required") val tierRequired: Tier = Tier.FREE,
    @SerialName("image_url") val imageUrl: String? = null,
    /** Full gallery, newest first. [imageUrl] is whatever should lead. */
    val photos: List<String> = emptyList(),
    val address: String? = null,
    @SerialName("opening_hours") val openingHours: String? = null,
    @SerialName("web_url") val webUrl: String? = null,
    /** Live crowd data, merged in after the list paints so it never delays first render. */
    val pulse: CrowdPulse? = null,
    val allergens: List<AllergenSignal> = emptyList(),
    /**
     * Diets the venue itself lists in open map data. A different class of claim from a community
     * allergen report — "they say they do vegan" is not "someone with a peanut allergy ate safely" —
     * so the UI shows the two separately and never merges them.
     */
    @SerialName("dietary_options") val dietaryOptions: List<String> = emptyList(),
    /** Corridor search only: extra metres of driving this stop costs, and progress along the route. */
    @SerialName("detour_meters") val detourMeters: Int = 0,
    @SerialName("along_route_fraction") val alongRouteFraction: Double = 0.0,
    /** Merged in by [CrowdRepository] after first paint; null means unmatched or Yelp is off. */
    val yelp: YelpInfo? = null,
    /**
     * Every outside platform that had something to say, each keeping its own rating and its own
     * link. Empty until the batched second pass lands — never blocks first paint.
     */
    val sources: List<ExternalSource> = emptyList(),
    /** The union of what every source knows, structured. Populated on the detail screen. */
    val facts: PlaceFacts? = null,
) {
    val reasoningParts: List<String> get() = aiMatchReasoning.split(" · ").filter { it.isNotBlank() }

    fun allergenFor(name: String) = allergens.firstOrNull { it.allergen.equals(name, ignoreCase = true) }

    /** Sources with a rating worth showing, best-known platform first. */
    val ratedSources: List<ExternalSource> get() = sources.filter { it.ratesIt }

    /**
     * A venue's own site is not a review platform, so it never sits in a row of ratings — but it is
     * usually the best photograph of the food, which is why it is fetched at all.
     */
    val photoSources: List<ExternalSource> get() = sources.filter { it.photos.isNotEmpty() }
}

/** A candidate place before scoring. */
@Serializable
data class RestaurantRecord(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val rating: Double = 0.0,
    @SerialName("review_count") val reviewCount: Int = 0,
    @SerialName("price_tier") val priceTier: Int = 0,
    @SerialName("cuisine_tags") val cuisineTags: List<String> = emptyList(),
    @SerialName("vibe_tags") val vibeTags: List<String> = emptyList(),
    @SerialName("dietary_options") val dietaryOptions: List<String> = emptyList(),
    val address: String? = null,
    @SerialName("opening_hours") val openingHours: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("web_url") val webUrl: String? = null,
    @SerialName("detour_meters") val detourMeters: Int = 0,
    @SerialName("along_route_fraction") val alongRouteFraction: Double = 0.0,
)

@Serializable
data class PastInteraction(
    @SerialName("restaurant_id") val restaurantId: String,
    val action: String, // "visited" | "favorited" | "dismissed"
    @SerialName("cuisine_tags") val cuisineTags: List<String> = emptyList(),
)

@Serializable
data class TasteProfile(
    @SerialName("preferred_cuisines") val preferredCuisines: List<String> = emptyList(),
    @SerialName("dietary_restrictions") val dietaryRestrictions: List<String> = emptyList(),
    @SerialName("price_comfort") val priceComfort: Int = 2, // 1..4
    @SerialName("vibe_tags") val vibeTags: List<String> = emptyList(),
    @SerialName("past_interactions") val pastInteractions: List<PastInteraction> = emptyList(),
)

@Serializable
enum class ResultSource {
    @SerialName("filter_search") FILTER_SEARCH,
    @SerialName("ai_chat") AI_CHAT,
    @SerialName("map_query") MAP_QUERY,
}

@Serializable
data class RecommendationRequest(
    val origin: Coordinates,
    val profile: TasteProfile,
    val query: String = "",
    val source: ResultSource = ResultSource.FILTER_SEARCH,
    val tier: Tier = Tier.FREE,
    @SerialName("applied_filters") val appliedFilters: List<String> = emptyList(),
    /** Hard constraints, unlike [TasteProfile.dietaryRestrictions] which only demote. */
    val allergens: List<String> = emptyList(),
    val mode: SearchMode = SearchMode.NEARBY,
    /** Required when [mode] is ON_THE_WAY. */
    val destination: Coordinates? = null,
)

@Serializable
data class RecommendationResponse(
    val source: ResultSource,
    val query: String,
    val results: List<RestaurantResult>,
    @SerialName("generated_at") val generatedAt: Long,
)

/**
 * Every tier gate in the app reads from here.
 *
 * Allergen filtering is free on purpose — a safety filter behind a paywall is indefensible, and
 * the reports it depends on only exist if everyone can use it. Table Sync is free for the same
 * structural reason: it is worth nothing unless the people you invite can join.
 */
object Entitlements {
    fun maxResults(tier: Tier) = if (tier == Tier.FREE) 8 else 20
    fun radiusMeters(tier: Tier) = if (tier == Tier.FREE) 3_000 else 10_000
    fun aiQueriesPerDay(tier: Tier) = if (tier == Tier.FREE) 5 else Int.MAX_VALUE
    fun canSeeLivePulse(tier: Tier) = tier.atLeast(Tier.PRO)
    fun canSearchCorridor(tier: Tier) = tier.atLeast(Tier.PLUS)
    fun corridorMeters(tier: Tier) = if (tier == Tier.PRO) 1_500 else 800
}

// Shared presentation helpers so engine reasoning strings and UI labels agree.
fun formatCount(n: Int): String {
    if (n < 1000) return n.toString()
    val v = n / 1000.0
    return if (v >= 10) "${v.toInt()}k" else String.format(Locale.US, "%.1fk", v)
}

fun formatDistanceMeters(m: Int): String =
    if (m < 1000) "$m m" else String.format(Locale.US, "%.1f km", m / 1000.0)

fun walkMinutes(m: Int) = maxOf(1, m / 80)
fun driveMinutes(m: Int) = maxOf(1, m / 500)

/**
 * Turns a fact key into something a person reads.
 *
 * The vocabulary is fixed on the server so every platform's flags arrive under the same names, and
 * the map is exhaustive over what the connectors actually emit. Anything unrecognised falls back to
 * a de-underscored version of the key rather than being dropped — a new server capability should
 * show up in the app as a slightly plain label, not as a missing feature.
 */
object FactLabels {
    private val labels = mapOf(
        // service
        "dine_in" to "Dine-in", "takeout" to "Takeaway", "delivery" to "Delivery",
        "curbside" to "Kerbside pickup", "reservable" to "Takes reservations",
        // amenities
        "outdoor_seating" to "Outdoor seating", "live_music" to "Live music", "restroom" to "Toilets",
        "good_for_groups" to "Good for groups", "good_for_children" to "Good for kids",
        "kids_menu" to "Kids' menu", "sports" to "Shows sport", "dogs" to "Dog friendly",
        "wheelchair" to "Step-free entrance", "wheelchair_seating" to "Accessible seating",
        "wheelchair_parking" to "Accessible parking", "wheelchair_restroom" to "Accessible toilet",
        "free_parking" to "Free parking", "paid_parking" to "Paid parking",
        "street_parking" to "Street parking", "valet" to "Valet", "wifi" to "Wi-Fi",
        "television" to "TV", "highchairs" to "Highchairs", "parking" to "Parking",
        // diet
        "vegetarian" to "Vegetarian options", "vegan" to "Vegan options",
        "halal" to "Halal", "kosher" to "Kosher", "gluten_free" to "Gluten-free options",
        // meals
        "breakfast" to "Breakfast", "brunch" to "Brunch", "lunch" to "Lunch",
        "dinner" to "Dinner", "dessert" to "Dessert", "late_night" to "Late night",
        // drinks
        "beer" to "Beer", "wine" to "Wine", "cocktails" to "Cocktails", "coffee" to "Coffee",
        // payment
        "cards" to "Cards", "debit" to "Debit cards", "cash_only" to "Cash only", "nfc" to "Contactless",
    )

    fun label(key: String): String = labels[key]
        ?: key.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }

    /** Only the keys somebody actually answered. Unknowns are omitted, never shown as "no". */
    fun known(group: FactGroup): List<Pair<String, Boolean>> =
        group.mapNotNull { (k, v) -> v?.let { label(k) to it } }.sortedBy { it.first }
}
