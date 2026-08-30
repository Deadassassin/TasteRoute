package space.gexemy.tasteroute.data.model

import space.gexemy.tasteroute.data.repository.MallFinder

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
