package com.example.tasteroute.data

import com.example.tasteroute.BuildConfig
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Yelp Fusion place search. Unlike OSM this carries ratings, review counts, price tier and photos,
 * so it is the primary source; [OverpassClient] stays as the no-key fallback.
 *
 * Yelp's brand terms require attribution and a link back to the business page — see webUrl.
 */
object YelpClient {

    private const val ENDPOINT = "https://api.yelp.com/v3/businesses/search"
    private const val MAX_RADIUS = 40_000 // Yelp's hard cap
    private const val LIMIT = 50

    val isConfigured: Boolean get() = BuildConfig.YELP_API_KEY.isNotBlank()

    suspend fun nearby(
        origin: Coordinates,
        radiusMeters: Int,
        query: String,
        sortBy: String,
        maxPriceTier: Int?,
    ): List<RestaurantRecord> {
        if (!isConfigured) throw HttpException(0, "YELP_API_KEY missing from local.properties")
        val params = buildList {
            add("latitude" to String.format(Locale.US, "%.6f", origin.lat))
            add("longitude" to String.format(Locale.US, "%.6f", origin.lng))
            add("radius" to radiusMeters.coerceIn(100, MAX_RADIUS).toString())
            add("categories" to "restaurants,food")
            add("limit" to LIMIT.toString())
            add("sort_by" to sortBy)
            if (query.isNotBlank()) add("term" to query.trim())
            if (maxPriceTier != null) add("price" to (1..maxPriceTier).joinToString(","))
        }.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

        val raw = withContext(Dispatchers.IO) {
            httpGet(
                url = "$ENDPOINT?$params",
                headers = mapOf("Authorization" to "Bearer ${BuildConfig.YELP_API_KEY}"),
                accept = "application/json",
            )
        }
        return AppJson.decodeFromString(Response.serializer(), raw)
            .businesses
            .filterNot { it.isClosed }
            .mapNotNull { it.toRecord() }
    }

    @Serializable
    private data class Response(val businesses: List<Business> = emptyList())

    @Serializable
    private data class Business(
        val id: String = "",
        val name: String = "",
        val url: String? = null,
        @SerialName("image_url") val imageUrl: String? = null,
        @SerialName("is_closed") val isClosed: Boolean = false,
        @SerialName("review_count") val reviewCount: Int = 0,
        val rating: Double = 0.0,
        val price: String? = null,
        val categories: List<Category> = emptyList(),
        val coordinates: LatLng? = null,
        val location: Location? = null,
    ) {
        fun toRecord(): RestaurantRecord? {
            val lat = coordinates?.latitude ?: return null
            val lng = coordinates.longitude ?: return null
            if (name.isBlank() || id.isBlank()) return null
            return RestaurantRecord(
                id = "yelp-$id",
                name = name,
                coordinates = Coordinates(lat, lng),
                rating = rating,
                reviewCount = reviewCount,
                priceTier = price?.count { it == '$' } ?: 0,
                cuisineTags = categories.mapNotNull { it.title?.takeIf(String::isNotBlank) }.take(4),
                dietaryOptions = diets(),
                address = location?.displayAddress?.joinToString(", ")?.ifBlank { null },
                imageUrl = imageUrl,
                webUrl = url,
            )
        }

        private fun diets() = buildList {
            val aliases = categories.mapNotNull { it.alias }
            if ("vegan" in aliases) add("Vegan")
            if ("vegetarian" in aliases) add("Vegetarian")
            if ("gluten_free" in aliases) add("Gluten-free")
            if ("halal" in aliases) add("Halal")
        }
    }

    @Serializable
    private data class Category(val alias: String? = null, val title: String? = null)

    @Serializable
    private data class LatLng(val latitude: Double? = null, val longitude: Double? = null)

    @Serializable
    private data class Location(@SerialName("display_address") val displayAddress: List<String> = emptyList())
}
