package space.gexemy.tasteroute.data.repository

import java.net.URLEncoder
import java.util.Locale
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.network.*

/**
 * "Am I inside a mall?" — answered by geometry, not by proximity.
 */
object MallFinder {

    private const val SEARCH_RADIUS_M = 800
    private const val NEAR_RADIUS_M = 220
    private const val FOOD = "^(restaurant|cafe|fast_food|food_court|ice_cream|bar|pub|bakery)${'$'}"
    private const val DIRECTORY_LIMIT = 200

    private var cacheKey: String? = null
    private var cached: Mall? = null

    private fun key(c: Coordinates) = String.format(Locale.US, "%.4f,%.4f", c.lat, c.lng)

    suspend fun detect(origin: Coordinates): Mall? {
        val k = key(origin)
        if (k == cacheKey) return cached
        if (Backoff.blocked(Backoff.OSM)) return null

        val lat = String.format(Locale.US, "%.6f", origin.lat)
        val lng = String.format(Locale.US, "%.6f", origin.lng)
        val ql = """
            [out:json][timeout:20];
            (
              nwr["shop"="mall"](around:$SEARCH_RADIUS_M,$lat,$lng);
              nwr["amenity"="food_court"](around:$NEAR_RADIUS_M,$lat,$lng);
            );
            out geom 12;
        """.trimIndent()

        val candidates = runCatching { decode(ql).elements.mapNotNull { it.toMall(origin) } }
            .onFailure { Backoff.record(Backoff.OSM, it) }
            .getOrNull()
            .orEmpty()

        val found = candidates.filter { it.inside }.minByOrNull { it.distanceMeters }
            ?: candidates.filter { it.distanceMeters <= NEAR_RADIUS_M }.minByOrNull { it.distanceMeters }

        cacheKey = k
        cached = found
        return found
    }

    suspend fun directory(mall: Mall): List<MallStop> {
        if (Backoff.blocked(Backoff.OSM)) return emptyList()
        val b = mall.bounds
        val box = if (b != null) {
            String.format(Locale.US, "(%.6f,%.6f,%.6f,%.6f)", b.south, b.west, b.north, b.east)
        } else {
            String.format(Locale.US, "(around:%d,%.6f,%.6f)", NEAR_RADIUS_M, mall.center.lat, mall.center.lng)
        }
        val ql = """
            [out:json][timeout:25];
            nwr["amenity"~"$FOOD"]["name"]$box;
            out tags center $DIRECTORY_LIMIT;
        """.trimIndent()

        return runCatching { decode(ql) }
            .onFailure { Backoff.record(Backoff.OSM, it) }
            .getOrNull()
            ?.elements
            ?.mapNotNull { it.toStop(mall) }
            ?.distinctBy { it.id }
            ?.sortedWith(compareBy({ it.levelOrder }, { it.name }))
            .orEmpty()
    }

    private suspend fun decode(ql: String): Response {
        val raw = OverpassClient.post("data=" + URLEncoder.encode(ql, "UTF-8"))
        return AppJson.decodeFromString(Response.serializer(), raw)
    }

    internal fun inRing(ring: List<Pt>, lat: Double, lng: Double): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val yi = ring[i].lat
            val xi = ring[i].lon
            val yj = ring[j].lat
            val xj = ring[j].lon
            if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    @kotlinx.serialization.Serializable
    data class Pt(val lat: Double = 0.0, val lon: Double = 0.0)

    @kotlinx.serialization.Serializable
    private data class Response(val elements: List<Element> = emptyList())

    @kotlinx.serialization.Serializable
    private data class Bounds(val minlat: Double = 0.0, val minlon: Double = 0.0, val maxlat: Double = 0.0, val maxlon: Double = 0.0)

    @kotlinx.serialization.Serializable
    private data class Member(val type: String = "", val role: String = "", val geometry: List<Pt> = emptyList())

    @kotlinx.serialization.Serializable
    private data class Center(val lat: Double, val lon: Double)

    @kotlinx.serialization.Serializable
    private data class Element(
        val type: String = "node",
        val id: Long = 0,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: Center? = null,
        val bounds: Bounds? = null,
        val geometry: List<Pt> = emptyList(),
        val members: List<Member> = emptyList(),
        val tags: Map<String, String> = emptyMap(),
    ) {
        private fun rings(): List<List<Pt>> = when (type) {
            "way" -> if (geometry.size >= 3) listOf(geometry) else emptyList()
            "relation" -> members.filter { it.role == "outer" || it.role.isEmpty() }.map { it.geometry }.filter { it.size >= 3 }
            else -> emptyList()
        }

        fun toMall(origin: Coordinates): Mall? {
            val name = tags["name"]?.trim().orEmpty().ifBlank { if (tags["amenity"] == "food_court") "Food court" else return null }
            val shape = rings()
            val inside = shape.any { inRing(it, origin.lat, origin.lng) }
            val box = bounds?.let { MallBounds(it.minlat, it.minlon, it.maxlat, it.maxlon) }
                ?: shape.flatten().takeIf { it.isNotEmpty() }?.let { pts ->
                    MallBounds(pts.minOf { it.lat }, pts.minOf { it.lon }, pts.maxOf { it.lat }, pts.maxOf { it.lon })
                }
            val middle = box?.let { Coordinates((it.south + it.north) / 2, (it.west + it.east) / 2) } ?: point() ?: return null
            return Mall(
                osmId = id, osmType = type, name = name, inside = inside, center = middle, bounds = box, rings = shape,
                website = tags["website"] ?: tags["contact:website"] ?: tags["url"],
                distanceMeters = if (inside) 0 else {
                    shape.flatten().minOfOrNull { RecommendationEngine.distanceMeters(origin, Coordinates(it.lat, it.lon)) }
                        ?: RecommendationEngine.distanceMeters(origin, middle)
                },
            )
        }

        fun toStop(mall: Mall): MallStop? {
            val at = point() ?: return null
            val name = tags["name"]?.trim().orEmpty().ifBlank { return null }
            if (mall.rings.isNotEmpty() && mall.rings.none { inRing(it, at.lat, at.lng) }) return null
            return MallStop(
                id = "osm-$type-$id", name = name, coordinates = at, cuisine = cuisine(), level = levelTag(),
                distanceMeters = RecommendationEngine.distanceMeters(mall.center, at), openingHours = tags["opening_hours"],
            )
        }

        private fun point(): Coordinates? {
            val y = lat ?: center?.lat ?: return null
            val x = lon ?: center?.lon ?: return null
            return Coordinates(y, x)
        }

        private fun cuisine(): String {
            val tag = tags["cuisine"].orEmpty().split(";", ",").firstOrNull()?.trim().orEmpty()
            if (tag.isNotEmpty()) return pretty(tag)
            return when (tags["amenity"]) {
                "cafe" -> "Café"
                "fast_food" -> "Fast food"
                "food_court" -> "Food court"
                "ice_cream" -> "Ice cream"
                "bakery" -> "Bakery"
                "bar" -> "Bar"
                "pub" -> "Pub"
                else -> "Restaurant"
            }
        }

        private fun levelTag(): String? = listOf("level", "addr:floor", "layer").firstNotNullOfOrNull { tags[it] }?.split(";", ",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun pretty(raw: String) = raw.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}
