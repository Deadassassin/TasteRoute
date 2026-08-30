package com.example.tasteroute.data

import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Free, key-less place search from OpenStreetMap. Used when the Gexemy API is unreachable.
 * No ratings, review counts or price tiers exist in OSM — those stay 0 ("unknown").
 * A minority of entries do carry a photo tag, which is why [photo] exists.
 */
object OverpassClient {

    /**
     * Mirrors, tried in order. This list is the app's entire safety net: when our own API is
     * unreachable, everything the user sees comes through here, so two mirrors was one bad
     * afternoon at overpass-api.de away from an empty screen.
     */
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.osm.ch/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    private const val MAX_ELEMENTS = 90

    suspend fun nearby(origin: Coordinates, radiusMeters: Int): List<RestaurantRecord> {
        val lat = String.format(Locale.US, "%.6f", origin.lat)
        val lng = String.format(Locale.US, "%.6f", origin.lng)
        val ql = """
            [out:json][timeout:25];
            nwr["amenity"~"^(restaurant|cafe|fast_food)${'$'}"]["name"](around:$radiusMeters,$lat,$lng);
            out center $MAX_ELEMENTS;
        """.trimIndent()
        val body = "data=" + URLEncoder.encode(ql, "UTF-8")

        val raw = withContext(Dispatchers.IO) {
            var last: Exception? = null
            for (endpoint in ENDPOINTS) {
                try {
                    // Deliberately no Accept: application/json — overpass-api.de answers that with 406.
                    return@withContext httpPost(
                        url = endpoint,
                        body = body,
                        contentType = "application/x-www-form-urlencoded",
                        readTimeoutMs = 14_000,
                    )
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: HttpException(0, "No Overpass endpoint reachable")
        }
        return AppJson.decodeFromString(Response.serializer(), raw).elements.mapNotNull { it.toRecord() }
    }

    @Serializable
    private data class Response(val elements: List<Element> = emptyList())

    @Serializable
    private data class Element(
        val type: String = "node",
        val id: Long = 0,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: Center? = null,
        val tags: Map<String, String> = emptyMap(),
    ) {
        fun toRecord(): RestaurantRecord? {
            val name = tags["name"]?.trim().orEmpty().ifBlank { return null }
            val y = lat ?: center?.lat ?: return null
            val x = lon ?: center?.lon ?: return null
            return RestaurantRecord(
                id = "osm-$type-$id",
                name = name,
                coordinates = Coordinates(y, x),
                cuisineTags = cuisines(),
                vibeTags = vibes(),
                dietaryOptions = diets(),
                address = address(),
                openingHours = tags["opening_hours"],
                imageUrl = photo(),
                webUrl = tags["website"]?.takeIf { it.startsWith("http") }
                    ?: "https://www.openstreetmap.org/$type/$id",
            )
        }

        /**
         * OSM sometimes carries a photo: a direct `image` URL, or a Commons filename that
         * Special:FilePath resolves to one. Coverage is thin, but this is the only source of
         * restaurant photos when the Gexemy API is unreachable — without it the fallback path
         * shows no images at all, which is exactly how it looked before.
         */
        private fun photo(): String? {
            tags["image"]?.takeIf { it.startsWith("http") }?.let { return it }
            val commons = tags["wikimedia_commons"]?.removePrefix("File:")?.trim().orEmpty()
            if (commons.isBlank() || commons == tags["wikimedia_commons"]) return null
            return "https://commons.wikimedia.org/wiki/Special:FilePath/" +
                URLEncoder.encode(commons, "UTF-8").replace("+", "%20") + "?width=800"
        }

        private fun cuisines(): List<String> {
            val fromTag = tags["cuisine"].orEmpty()
                .split(";", ",")
                .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::pretty) }
            val fallback = when (tags["amenity"]) {
                "cafe" -> "Café"
                "fast_food" -> "Fast food"
                else -> "Restaurant"
            }
            return (fromTag.ifEmpty { listOf(fallback) }).distinct().take(4)
        }

        private fun vibes() = buildList {
            when (tags["amenity"]) {
                "fast_food" -> add("Quick bite")
                "cafe" -> add("Cozy")
            }
            if (tags["outdoor_seating"] == "yes") add("Casual")
            if (tags["takeaway"] == "only") add("Quick bite")
        }.distinct()

        private fun diets() = buildList {
            if (tags["diet:vegan"] in YES) add("Vegan")
            if (tags["diet:vegetarian"] in YES) add("Vegetarian")
            if (tags["diet:gluten_free"] in YES) add("Gluten-free")
            if (tags["diet:halal"] in YES) add("Halal")
        }

        private fun address(): String? {
            val street = listOfNotNull(tags["addr:housenumber"], tags["addr:street"]).joinToString(" ")
            return listOfNotNull(street.ifBlank { null }, tags["addr:city"]).joinToString(", ").ifBlank { null }
        }
    }

    @Serializable
    private data class Center(val lat: Double, val lon: Double)

    private val YES = setOf("yes", "only")

    private fun pretty(raw: String) = raw.replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}
