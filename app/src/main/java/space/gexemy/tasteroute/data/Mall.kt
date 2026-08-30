package space.gexemy.tasteroute.data

import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * "Am I inside a mall?" — answered by geometry, not by proximity.
 *
 * A radius query cannot answer this. Standing in the middle of a large mall the nearest tagged
 * node may be two hundred metres away at an entrance; standing in the car park of a small one it
 * is ten metres away and you are still outside. So the footprint is fetched and the fix is tested
 * against it, and only a point that falls INSIDE a ring may set [Mall.inside].
 *
 * The obvious way to write this is Overpass's `is_in` + `pivot`, and then `map_to_area` for the
 * directory. Both were written that way first and both were thrown away: they resolve against
 * Overpass's derived AREA database, which is generated from a tag ruleset that this code has no
 * way to check and no control over. If `shop=mall` is not in that ruleset on a given mirror — and
 * there are four mirrors, each free to differ — the feature returns nothing, everywhere, silently,
 * and looks exactly like "no malls near you". Pulling `out geom` and doing the containment test
 * here depends on nothing but the raw map, costs one query either way, and hands back the polygon,
 * which is then also what bounds the directory search.
 */
object MallFinder {

    /**
     * `around:` on a way matches when ANY of its nodes is in range, so this is half the width of
     * the largest mall that can be detected from its own centre. 800m covers everything short of
     * an airport.
     */
    private const val SEARCH_RADIUS_M = 800

    /** How far a mall with no usable footprint may be and still be worth mentioning. */
    private const val NEAR_RADIUS_M = 220

    /** Food POIs to list. Deliberately wider than a restaurant search — a food court is the point. */
    private const val FOOD = "^(restaurant|cafe|fast_food|food_court|ice_cream|bar|pub|bakery)${'$'}"

    private const val DIRECTORY_LIMIT = 200

    /** One lookup per latched search origin. `searchOrigin` already only moves every 450m / 5min. */
    private var cacheKey: String? = null
    private var cached: Mall? = null

    private fun key(c: Coordinates) = String.format(Locale.US, "%.4f,%.4f", c.lat, c.lng)

    /**
     * Null means "no mall here", and also means "could not tell" — the caller renders nothing
     * either way, so the two are not worth distinguishing at this layer. A thrown exception would
     * be, which is why nothing here throws: this runs on the way to a screen that must paint.
     */
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

        // Containment beats proximity outright, however close the runner-up is: being inside a
        // building is a different claim from being near one, and the whole point of the type is
        // that the UI can tell them apart.
        val found = candidates.filter { it.inside }.minByOrNull { it.distanceMeters }
            ?: candidates.filter { it.distanceMeters <= NEAR_RADIUS_M }.minByOrNull { it.distanceMeters }

        cacheKey = k
        cached = found
        return found
    }

    /**
     * Everything edible inside the mall's own footprint.
     *
     * Overpass is asked for the bounding BOX, which is cheap and indexed; the polygon then filters
     * what came back. A bbox alone would sweep in the retail park across the road for any mall
     * that is not a rectangle, which for a mall is most of them.
     */
    suspend fun directory(mall: Mall): List<MallStop> {
        if (Backoff.blocked(Backoff.OSM)) return emptyList()
        val b = mall.bounds
        val box = if (b != null) {
            String.format(Locale.US, "(%.6f,%.6f,%.6f,%.6f)", b.south, b.west, b.north, b.east)
        } else {
            String.format(
                Locale.US,
                "(around:%d,%.6f,%.6f)",
                NEAR_RADIUS_M,
                mall.center.lat,
                mall.center.lng,
            )
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

    /**
     * Ray casting, in raw degrees.
     *
     * Deliberately NOT projected. Over a few hundred metres the longitude squeeze is a constant
     * factor, and scaling both the polygon and the test point by the same constant cannot change
     * which side of an edge the point falls on. Projecting would be arithmetic that buys nothing
     * and one more place to get a cos(lat) the wrong way round.
     */
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

    @Serializable
    data class Pt(val lat: Double = 0.0, val lon: Double = 0.0)

    @Serializable
    private data class Response(val elements: List<Element> = emptyList())

    @Serializable
    private data class Bounds(
        val minlat: Double = 0.0,
        val minlon: Double = 0.0,
        val maxlat: Double = 0.0,
        val maxlon: Double = 0.0,
    )

    @Serializable
    private data class Member(
        val type: String = "",
        val role: String = "",
        val geometry: List<Pt> = emptyList(),
    )

    @Serializable
    private data class Center(val lat: Double, val lon: Double)

    @Serializable
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
        /**
         * The closed rings that make up this thing's outline.
         *
         * A way is one ring. A multipolygon relation is its outer ways — inner ways are holes
         * (a courtyard), and ignoring them can only ever say "inside" where the truth is "in the
         * courtyard of", which is close enough to be useful and never claims the wrong building.
         */
        private fun rings(): List<List<Pt>> = when (type) {
            "way" -> if (geometry.size >= 3) listOf(geometry) else emptyList()
            "relation" -> members
                .filter { it.role == "outer" || it.role.isEmpty() }
                .map { it.geometry }
                .filter { it.size >= 3 }
            else -> emptyList()
        }

        fun toMall(origin: Coordinates): Mall? {
            val name = tags["name"]?.trim().orEmpty().ifBlank {
                if (tags["amenity"] == "food_court") "Food court" else return null
            }
            val shape = rings()
            val inside = shape.any { inRing(it, origin.lat, origin.lng) }
            val box = bounds?.let { MallBounds(it.minlat, it.minlon, it.maxlat, it.maxlon) }
                ?: shape.flatten().takeIf { it.isNotEmpty() }?.let { pts ->
                    MallBounds(
                        pts.minOf { it.lat }, pts.minOf { it.lon },
                        pts.maxOf { it.lat }, pts.maxOf { it.lon },
                    )
                }
            val middle = box?.let { Coordinates((it.south + it.north) / 2, (it.west + it.east) / 2) }
                ?: point()
                ?: return null
            return Mall(
                osmId = id,
                osmType = type,
                name = name,
                inside = inside,
                center = middle,
                bounds = box,
                rings = shape,
                website = tags["website"] ?: tags["contact:website"] ?: tags["url"],
                // Inside is inside. Otherwise the honest number is how far to the nearest bit of
                // the building, not to a centroid that may sit in the middle of a car park.
                distanceMeters = if (inside) {
                    0
                } else {
                    shape.flatten().minOfOrNull { RecommendationEngine.distanceMeters(origin, Coordinates(it.lat, it.lon)) }
                        ?: RecommendationEngine.distanceMeters(origin, middle)
                },
            )
        }

        fun toStop(mall: Mall): MallStop? {
            val at = point() ?: return null
            val name = tags["name"]?.trim().orEmpty().ifBlank { return null }
            // A bbox is not a building. Anything the polygon rejects is a neighbour that happened
            // to fall in the rectangle -- unless the mall has no polygon at all, in which case the
            // query was a radius and there is nothing to filter against.
            if (mall.rings.isNotEmpty() && mall.rings.none { inRing(it, at.lat, at.lng) }) return null
            return MallStop(
                id = "osm-$type-$id",
                name = name,
                coordinates = at,
                cuisine = cuisine(),
                level = levelTag(),
                distanceMeters = RecommendationEngine.distanceMeters(mall.center, at),
                openingHours = tags["opening_hours"],
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

        /**
         * OSM's indoor vocabulary, in the order a mapper is most likely to have used. `level` is
         * the simple-indoor-tagging key and wins; `addr:floor` is what address-first mappers reach
         * for; `layer` is a rendering hint about what crosses over what and is the last resort.
         * A repeated value ("0;1", a unit spanning two floors) keeps its first level.
         */
        private fun levelTag(): String? = listOf("level", "addr:floor", "layer")
            .firstNotNullOfOrNull { tags[it] }
            ?.split(";", ",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun pretty(raw: String) = raw.replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

/** The mall's bounding box, used to ask Overpass a cheap indexed question. */
data class MallBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * A mall the app believes the person is in, or beside.
 *
 * [inside] is the whole point of the type and is never inferred from [distanceMeters]: it is true
 * only when the fix falls within one of [rings].
 */
data class Mall(
    val osmId: Long,
    val osmType: String,
    val name: String,
    val inside: Boolean,
    val center: Coordinates,
    val bounds: MallBounds?,
    val rings: List<List<MallFinder.Pt>>,
    val website: String?,
    val distanceMeters: Int,
)

/** One place in the mall directory. */
data class MallStop(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val cuisine: String,
    /** Raw OSM level string, or null when nobody has mapped which floor this is on. */
    val level: String?,
    val distanceMeters: Int,
    val openingHours: String?,
) {
    /** Numeric levels sort naturally; anything unmapped sinks below every mapped floor. */
    val levelOrder: Double get() = level?.toDoubleOrNull() ?: Double.MAX_VALUE

    /**
     * What the floor heading says. A number gets "Level n" because that is what mall signage says;
     * anything else is printed as the mapper wrote it rather than being guessed at, and an absent
     * level says so out loud instead of quietly defaulting to the ground floor.
     */
    val levelLabel: String
        get() {
            val raw = level ?: return "Floor not mapped"
            val n = raw.toDoubleOrNull() ?: return raw
            return "Level " + if (n == n.toInt().toDouble()) n.toInt().toString() else raw
        }
}
