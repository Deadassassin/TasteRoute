package space.gexemy.tasteroute.data.model

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

val ALL_CUISINES = listOf("Thai", "Japanese", "Mexican", "Italian", "Korean", "Indian", "Mediterranean", "American", "French", "Vegan")
val ALL_DIETARY = listOf("Vegan", "Vegetarian", "Gluten-free", "Halal")
val ALL_VIBES = listOf("Cozy", "Casual", "Date night", "Lively", "Quiet", "Trendy", "Family-friendly", "Quick bite")
val ALL_ALLERGENS = listOf("Peanuts", "Tree nuts", "Dairy", "Eggs", "Gluten", "Wheat", "Soy", "Fish", "Shellfish", "Sesame", "Mustard", "Sulphites")

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
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v?.lowercase(Locale.US) } ?: FREE }
}

@Serializable
data class Coordinates(val lat: Double, val lng: Double)

enum class SearchMode { NEARBY, ON_THE_WAY, ANYWHERE }

@Serializable
data class ChatMessage(
    @Transient val fromUser: Boolean = false,
    val text: String = "",
    @Transient val results: List<RestaurantResult> = emptyList(),
    @Transient val searching: Boolean = false,
    val me: Boolean = false,
)

@Serializable
data class PlacePhoto(val id: Long = 0, val url: String, val caption: String = "", @SerialName("user_id") val userId: Long? = null)

@Serializable
data class Review(val rating: Int, val body: String = "", val name: String = "Diner", @SerialName("user_id") val userId: Long? = null, @SerialName("visited_on") val visitedOn: String? = null, @SerialName("updated_at") val updatedAt: String? = null, val source: String = "tasteroute", @SerialName("source_url") val sourceUrl: String? = null) { val imported: Boolean get() = source != "tasteroute" }

@Serializable
data class ReviewSource(val source: String, val label: String, val url: String? = null, val count: Int = 0)

@Serializable
data class ReviewSummary(val rating: Double = 0.0, val count: Int = 0, val own: Int = 0)

@Serializable
data class YelpInfo(val rating: Double = 0.0, @SerialName("review_count") val reviewCount: Int = 0, val price: String = "", val url: String = "", @SerialName("image_url") val imageUrl: String? = null, val categories: List<String> = emptyList(), val reviews: List<YelpReview> = emptyList()) { val usable: Boolean get() = rating > 0 && url.isNotBlank() }

@Serializable
data class YelpReview(val rating: Int = 0, val text: String = "", val author: String = "", val url: String = "", @SerialName("created_at") val createdAt: String? = null)

@Serializable
data class SourcePhoto(val url: String, val caption: String = "", val credit: String = "", @SerialName("credit_url") val creditUrl: String? = null, val width: Int? = null, val height: Int? = null)

@Serializable
data class SourceReview(val rating: Int = 0, val title: String = "", val body: String = "", val author: String = "", @SerialName("author_url") val authorUrl: String? = null, val url: String = "", val lang: String? = null, @SerialName("visited_on") val visitedOn: String? = null, @SerialName("created_at") val createdAt: String? = null)

typealias FactGroup = Map<String, Boolean?>

@Serializable
data class MenuDish(val name: String, val description: String? = null, val price: String? = null, val diet: List<String> = emptyList())

@Serializable
data class MenuSection(val name: String = "", val items: List<MenuDish> = emptyList())

@Serializable
data class PlaceFacts(
    val phone: String? = null, val website: String? = null, val menu: String? = null,
    @SerialName("menu_items") val menuItems: List<String> = emptyList(),
    @SerialName("menu_sections") val menuSections: List<MenuSection> = emptyList(),
    @SerialName("menu_source") val menuSource: String? = null,
    val email: String? = null, val address: String? = null, @SerialName("maps_url") val mapsUrl: String? = null,
    val summary: String? = null, val status: String? = null, @SerialName("hours_text") val hoursText: List<String> = emptyList(),
    val openNow: Boolean? = null, @SerialName("price_range") val priceRange: String? = null,
    val cuisines: List<String> = emptyList(), val ranking: String? = null,
    @SerialName("rating_image") val ratingImage: String? = null, val awards: List<String> = emptyList(),
    val service: FactGroup = emptyMap(), val amenities: FactGroup = emptyMap(), val diet: FactGroup = emptyMap(),
    val meals: FactGroup = emptyMap(), val drinks: FactGroup = emptyMap(), val payment: FactGroup = emptyMap(),
    val socials: Map<String, String> = emptyMap(), @SerialName("self_rating") val selfRating: Double? = null,
) {
    val hasAnything: Boolean get() = phone != null || website != null || menu != null || summary != null || menuItems.isNotEmpty() || menuSections.isNotEmpty() || hoursLines.isNotEmpty() || cuisines.isNotEmpty() || socials.isNotEmpty() || listOf(service, amenities, diet, meals, drinks, payment).any { g -> g.values.any { it != null } }
    val hoursLines: List<String> get() = hoursText.filterNot { Regex("00:00\\s*[-\\u2013]\\s*00:00").containsMatchIn(it) }
    fun fillFrom(other: PlaceFacts): PlaceFacts = copy(phone = phone ?: other.phone, website = website ?: other.website, menu = menu ?: other.menu, menuItems = menuItems.ifEmpty { other.menuItems }, menuSections = menuSections.ifEmpty { other.menuSections }, menuSource = menuSource ?: other.menuSource, email = email ?: other.email, address = address ?: other.address, mapsUrl = mapsUrl ?: other.mapsUrl, summary = summary ?: other.summary, status = status ?: other.status, hoursText = hoursLines.ifEmpty { other.hoursLines }, openNow = openNow ?: other.openNow, priceRange = priceRange ?: other.priceRange, cuisines = cuisines.ifEmpty { other.cuisines }, ranking = ranking ?: other.ranking, awards = awards.ifEmpty { other.awards }, service = other.service + service, amenities = other.amenities + amenities, diet = other.diet + diet, meals = other.meals + meals, drinks = other.drinks + drinks, payment = other.payment + payment, socials = other.socials + socials)
}

@Serializable
data class ExternalSource(val source: String, val label: String, val url: String = "", val home: String = "", @SerialName("logo_required") val logoRequired: Boolean = false, val rating: Double = 0.0, @SerialName("review_count") val reviewCount: Int = 0, val price: String = "", @SerialName("image_url") val imageUrl: String? = null, val categories: List<String> = emptyList(), val facts: PlaceFacts = PlaceFacts(), val photos: List<SourcePhoto> = emptyList(), val reviews: List<SourceReview> = emptyList()) { val ratesIt: Boolean get() = rating > 0 && url.isNotBlank(); val hasContent: Boolean get() = ratesIt || reviews.isNotEmpty() || photos.isNotEmpty() }

@Serializable
data class Attribution(val source: String, val label: String, val url: String = "", val note: String = "")

@Serializable
data class CrowdPulse(@SerialName("minutes_low") val minutesLow: Int, @SerialName("minutes_high") val minutesHigh: Int, val busy: Int = 3, val confidence: String = "low", val reports: Int = 0, @SerialName("updated_at") val updatedAt: String? = null) { val busyLabel: String get() = when (busy) { 1 -> "Quiet"; 2 -> "Steady"; 3 -> "Busy"; 4 -> "Packed"; else -> "Slammed" } }

@Serializable
data class AllergenSignal(val allergen: String, val accommodates: Boolean? = null, val confidence: String = "unknown", val safe: Int = 0, val unsafe: Int = 0, val unsure: Int = 0, val reports: Int = 0, val notes: List<String> = emptyList())

@Serializable
data class RestaurantResult(val id: String, val name: String, val coordinates: Coordinates, val rating: Double = 0.0, @SerialName("review_count") val reviewCount: Int = 0, @SerialName("price_tier") val priceTier: Int = 0, @SerialName("cuisine_tags") val cuisineTags: List<String>, @SerialName("distance_meters") val distanceMeters: Int, @SerialName("ai_match_score") val aiMatchScore: Int, @SerialName("ai_match_reasoning") val aiMatchReasoning: String, @SerialName("tier_required") val tierRequired: Tier = Tier.FREE, @SerialName("image_url") val imageUrl: String? = null, val photos: List<String> = emptyList(), @SerialName("menu_highlights") val menuHighlights: List<String> = emptyList(), val address: String? = null, @SerialName("opening_hours") val openingHours: String? = null, @SerialName("web_url") val webUrl: String? = null, val pulse: CrowdPulse? = null, val allergens: List<AllergenSignal> = emptyList(), @SerialName("dietary_options") val dietaryOptions: List<String> = emptyList(), @SerialName("detour_meters") val detourMeters: Int = 0, @SerialName("along_route_fraction") val alongRouteFraction: Double = 0.0, val yelp: YelpInfo? = null, val sources: List<ExternalSource> = emptyList(), val facts: PlaceFacts? = null) {
    val reasoningParts: List<String> get() = aiMatchReasoning.split(" · ").filter { it.isNotBlank() }
    fun allergenFor(name: String) = allergens.firstOrNull { it.allergen.equals(name, ignoreCase = true) }
    val ratedSources: List<ExternalSource> get() = sources.filter { it.ratesIt }
    val photoSources: List<ExternalSource> get() = sources.filter { it.photos.isNotEmpty() }
    val venueSite: String? get() = webUrl?.takeIf { it.isNotBlank() }?.takeUnless { it.contains("openstreetmap.org", ignoreCase = true) }
}

@Serializable
data class RestaurantRecord(val id: String, val name: String, val coordinates: Coordinates, val rating: Double = 0.0, @SerialName("review_count") val reviewCount: Int = 0, @SerialName("price_tier") val priceTier: Int = 0, @SerialName("cuisine_tags") val cuisineTags: List<String> = emptyList(), @SerialName("vibe_tags") val vibeTags: List<String> = emptyList(), @SerialName("dietary_options") val dietaryOptions: List<String> = emptyList(), val address: String? = null, @SerialName("opening_hours") val openingHours: String? = null, @SerialName("image_url") val imageUrl: String? = null, @SerialName("web_url") val webUrl: String? = null, @SerialName("detour_meters") val detourMeters: Int = 0, @SerialName("along_route_fraction") val alongRouteFraction: Double = 0.0)

@Serializable
data class PastInteraction(@SerialName("restaurant_id") val restaurantId: String, val action: String, @SerialName("cuisine_tags") val cuisineTags: List<String> = emptyList())

@Serializable
data class TasteProfile(@SerialName("preferred_cuisines") val preferredCuisines: List<String> = emptyList(), @SerialName("dietary_restrictions") val dietaryRestrictions: List<String> = emptyList(), @SerialName("price_comfort") val priceComfort: Int = 2, @SerialName("vibe_tags") val vibeTags: List<String> = emptyList(), @SerialName("past_interactions") val pastInteractions: List<PastInteraction> = emptyList())

@Serializable
enum class ResultSource { @SerialName("filter_search") FILTER_SEARCH, @SerialName("ai_chat") AI_CHAT, @SerialName("map_query") MAP_QUERY }

@Serializable
data class RecommendationRequest(val origin: Coordinates, val profile: TasteProfile, val query: String = "", val source: ResultSource = ResultSource.FILTER_SEARCH, val tier: Tier = Tier.FREE, @SerialName("applied_filters") val appliedFilters: List<String> = emptyList(), val allergens: List<String> = emptyList(), val mode: SearchMode = SearchMode.NEARBY, val destination: Coordinates? = null)

@Serializable
data class RecommendationResponse(val source: ResultSource, val query: String, val results: List<RestaurantResult>, @SerialName("generated_at") val generatedAt: Long)

object Entitlements {
    fun maxResults(tier: Tier) = if (tier == Tier.FREE) 8 else 20
    fun radiusMeters(tier: Tier) = if (tier == Tier.FREE) 3_000 else 10_000
    fun areaRadiusMeters(tier: Tier) = if (tier == Tier.FREE) 12_000 else 25_000
    fun aiQueriesPerDay(tier: Tier) = if (tier == Tier.FREE) 5 else Int.MAX_VALUE
    fun canSeeLivePulse(tier: Tier) = tier.atLeast(Tier.PRO)
    fun canSearchCorridor(tier: Tier) = tier.atLeast(Tier.PLUS)
    fun corridorMeters(tier: Tier) = if (tier == Tier.PRO) 1_500 else 800
}

object Plans { const val PLUS_MONTHLY = "$2.99"; const val PRO_MONTHLY = "$5.99" }

fun formatCount(n: Int): String { if (n < 1000) return n.toString(); val v = n / 1000.0; return if (v >= 10) "${v.toInt()}k" else String.format(Locale.US, "%.1fk", v) }
fun formatDistanceMeters(m: Int): String = if (m < 1000) "$m m" else String.format(Locale.US, "%.1f km", m / 1000.0)
fun walkMinutes(m: Int) = maxOf(1, m / 80)
fun driveMinutes(m: Int) = maxOf(1, m / 500)

object FactLabels {
    private val labels = mapOf("dine_in" to "Dine-in", "takeout" to "Takeaway", "delivery" to "Delivery", "curbside" to "Kerbside pickup", "reservable" to "Takes reservations", "outdoor_seating" to "Outdoor seating", "live_music" to "Live music", "restroom" to "Toilets", "good_for_groups" to "Good for groups", "good_for_children" to "Good for kids", "kids_menu" to "Kids' menu", "sports" to "Shows sport", "dogs" to "Dog friendly", "wheelchair" to "Step-free entrance", "wheelchair_seating" to "Accessible seating", "wheelchair_parking" to "Accessible parking", "wheelchair_restroom" to "Accessible toilet", "free_parking" to "Free parking", "paid_parking" to "Paid parking", "street_parking" to "Street parking", "valet" to "Valet", "wifi" to "Wi-Fi", "television" to "TV", "highchairs" to "Highchairs", "parking" to "Parking", "vegetarian" to "Vegetarian options", "vegan" to "Vegan options", "halal" to "Halal", "kosher" to "Kosher", "gluten_free" to "Gluten-free options", "breakfast" to "Breakfast", "brunch" to "Brunch", "lunch" to "Lunch", "dinner" to "Dinner", "dessert" to "Dessert", "late_night" to "Late night", "beer" to "Beer", "wine" to "Wine", "cocktails" to "Cocktails", "coffee" to "Coffee", "cards" to "Cards", "debit" to "Debit cards", "cash_only" to "Cash only", "nfc" to "Contactless")
    fun label(key: String): String = labels[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    fun known(group: FactGroup): List<Pair<String, Boolean>> = group.mapNotNull { (k, v) -> v?.let { label(k) to it } }.sortedBy { it.first }
}
