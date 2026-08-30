package space.gexemy.tasteroute.data.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.gexemy.tasteroute.data.network.GexemyClient

/**
 * Turn-by-turn navigation, entirely in-app.
 *
 * The old route screen drew a line and handed off to whatever maps app was installed, which meant
 * leaving TasteRoute at exactly the moment it had done its job. This is the state machine that
 * replaces that handoff: a route from OSRM, a stream of fixes, and one [NavProgress] per fix.
 */

@Serializable
data class Maneuver(
    val type: String = "turn",
    val modifier: String = "",
    @SerialName("bearing_after") val bearingAfter: Int = 0,
    val exit: Int = 0,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
) {
    val at: Coordinates get() = Coordinates(lat, lng)
}

/** [startIndex] / [endIndex] index into [NavRoute.geometry] — the server folds step geometry into
 *  one polyline so a snapped position maps straight back to the step it belongs to. */
@Serializable
data class RouteStep(
    @SerialName("distance_meters") val distanceMeters: Int = 0,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val name: String = "",
    val ref: String = "",
    val maneuver: Maneuver = Maneuver(),
    @SerialName("start_index") val startIndex: Int = 0,
    @SerialName("end_index") val endIndex: Int = 0,
) {
    /** What the sign says, preferring the road name and falling back to its shield number. */
    val road: String get() = name.ifBlank { ref }
}

data class NavRoute(
    val destination: Coordinates,
    val destinationName: String,
    val geometry: List<Coordinates>,
    val steps: List<RouteStep>,
    val distanceMeters: Int,
    val durationSeconds: Int,
) {
    /** Metres from the start to each vertex, so "how far is left" is a subtraction, not a loop. */
    val cumulative: DoubleArray = DoubleArray(geometry.size).also { out ->
        for (i in 1 until geometry.size) {
            out[i] = out[i - 1] + Geo.metres(geometry[i - 1], geometry[i])
        }
    }

    val totalMetres: Double get() = cumulative.lastOrNull() ?: 0.0

    val usable: Boolean get() = geometry.size >= 2 && steps.isNotEmpty()
}

/**
 * OSRM's step model trips everyone up once: a step's `maneuver` sits at its *start*, and its
 * distance is the road you drive *after* it. So the instruction a driver needs while on step i is
 * step i+1's maneuver, and the road name to say is step i+1's name — the road you end up on.
 *
 * [travelling] is the step under the wheels (its length is what the announcement schedule is scaled
 * to); [maneuver] is the one being approached and is what the banner and the voice describe.
 */
data class NavProgress(
    val stepIndex: Int,
    val travelling: RouteStep,
    val maneuver: RouteStep,
    val next: RouteStep?,
    val distanceToManeuver: Int,
    val distanceRemaining: Int,
    val secondsRemaining: Int,
    val snapped: Coordinates,
    val vertexIndex: Int,
    val courseDegrees: Float,
    val offRouteMetres: Int,
    val offRoute: Boolean,
    val arrived: Boolean,
)

object Geo {
    private const val R = 6_371_000.0

    fun metres(a: Coordinates, b: Coordinates): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * R * atan2(kotlin.math.sqrt(s), kotlin.math.sqrt(1 - s))
    }

    fun bearing(a: Coordinates, b: Coordinates): Float {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /**
     * Projection of [p] onto segment a→b in a local flat frame. Returns the fraction along the
     * segment and the perpendicular distance in metres — accurate well past the scale of one OSRM
     * segment, and far cheaper than doing this on the sphere for every vertex of every fix.
     */
    fun project(p: Coordinates, a: Coordinates, b: Coordinates): Pair<Double, Double> {
        val kx = cos(Math.toRadians((a.lat + b.lat) / 2)) * 111_320.0
        val ky = 110_574.0
        val ax = a.lng * kx; val ay = a.lat * ky
        val bx = b.lng * kx; val by = b.lat * ky
        val px = p.lng * kx; val py = p.lat * ky
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        return t to hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    fun interpolate(a: Coordinates, b: Coordinates, t: Double) =
        Coordinates(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)

    /** Shortest signed turn from [from] to [to], -180..180. */
    fun turn(from: Float, to: Float): Float {
        var d = (to - from + 540f) % 360f - 180f
        if (d == -180f) d = 180f
        return d
    }
}

/**
 * Stateful because snapping has to be: matching a fix against the whole polyline every time makes
 * a route that doubles back on itself jump between the two passes. Search starts at the last
 * matched vertex and only looks a bounded distance ahead, with a short look-back for GPS jitter.
 */
class NavigationEngine(val route: NavRoute) {

    private var vertex = 0
    private var stepIndex = 0
    private var consecutiveOff = 0
    private var lastCourse = 0f

    /** Below this a fix has drifted, not moved — GPS bearing is noise when you're standing still. */
    private val minSpeedForCourse = 1.2f

    fun update(fix: Coordinates, deviceBearing: Float?, speedMps: Float): NavProgress {
        val geometry = route.geometry
        val lookBack = max(0, vertex - 8)
        val lookAhead = min(geometry.size - 2, vertex + 250)

        var bestSegment = lookBack
        var bestT = 0.0
        var bestOff = Double.MAX_VALUE
        for (i in lookBack..lookAhead) {
            val (t, off) = Geo.project(fix, geometry[i], geometry[i + 1])
            if (off < bestOff) {
                bestOff = off
                bestSegment = i
                bestT = t
            }
        }

        // Snapping backwards past a junction reads as "you missed the turn" and re-announces it.
        if (bestSegment >= vertex || bestOff < 25.0) vertex = bestSegment

        val snapped = Geo.interpolate(geometry[bestSegment], geometry[bestSegment + 1], bestT)
        val travelled = route.cumulative[bestSegment] +
            Geo.metres(geometry[bestSegment], geometry[bestSegment + 1]) * bestT
        val remaining = max(0.0, route.totalMetres - travelled)

        // The active step is the first one that hasn't been driven past. Never rewinds.
        while (stepIndex < route.steps.lastIndex && bestSegment >= route.steps[stepIndex].endIndex) {
            stepIndex += 1
        }
        val step = route.steps[stepIndex]
        val maneuver = route.steps.getOrNull(stepIndex + 1) ?: route.steps.last()
        val next = route.steps.getOrNull(stepIndex + 2)
        val toManeuver = max(0.0, route.cumulative[min(step.endIndex, route.cumulative.lastIndex)] - travelled)

        val course = when {
            deviceBearing != null && speedMps >= minSpeedForCourse -> deviceBearing
            bestSegment + 1 < geometry.size -> Geo.bearing(geometry[bestSegment], geometry[bestSegment + 1])
            else -> lastCourse
        }
        lastCourse = course

        // One bad fix off a bridge or in an urban canyon should not trigger a reroute.
        consecutiveOff = if (bestOff > OFF_ROUTE_METRES) consecutiveOff + 1 else 0

        val secondsLeft = route.steps.drop(stepIndex + 1).sumOf { it.durationSeconds } +
            (step.durationSeconds * (if (step.distanceMeters > 0) toManeuver / step.distanceMeters else 0.0))

        return NavProgress(
            stepIndex = stepIndex,
            travelling = step,
            maneuver = maneuver,
            next = next,
            distanceToManeuver = toManeuver.roundToInt(),
            distanceRemaining = remaining.roundToInt(),
            secondsRemaining = secondsLeft.roundToInt(),
            snapped = snapped,
            vertexIndex = bestSegment,
            courseDegrees = course,
            offRouteMetres = bestOff.roundToInt(),
            offRoute = consecutiveOff >= OFF_ROUTE_FIXES,
            arrived = remaining <= ARRIVE_METRES,
        )
    }

    companion object {
        const val OFF_ROUTE_METRES = 45.0
        const val OFF_ROUTE_FIXES = 3
        const val ARRIVE_METRES = 25.0
    }
}

enum class Units { AUTO, METRIC, IMPERIAL }

/** Resolved once per call site; AUTO follows the device locale rather than a hardcoded guess. */
fun Units.metric(): Boolean = when (this) {
    Units.METRIC -> true
    Units.IMPERIAL -> false
    Units.AUTO -> java.util.Locale.getDefault().country.uppercase(java.util.Locale.US) !in setOf("US", "LR", "MM", "GB")
}

/** Compact, for a banner: "450 ft", "0.4 mi", "1.2 km". */
fun formatNavDistance(metres: Int, units: Units): String {
    val loc = java.util.Locale.US
    if (units.metric()) {
        return when {
            metres < 1_000 -> "${((metres + 5) / 10) * 10} m"
            metres < 10_000 -> String.format(loc, "%.1f km", metres / 1000.0)
            else -> "${(metres / 1000.0).roundToInt()} km"
        }
    }
    val feet = metres * 3.28084
    return when {
        feet < 1_000 -> "${(feet / 50).roundToInt() * 50} ft"
        else -> {
            val miles = metres / 1609.344
            if (miles < 10) String.format(loc, "%.1f mi", miles) else "${miles.roundToInt()} mi"
        }
    }
}

fun formatDuration(seconds: Int): String {
    val total = max(1, seconds / 60)
    return if (total < 60) "$total min" else "${total / 60} hr ${total % 60} min"
}

/** Clock time of arrival, which is the number people actually plan around. */
fun arrivalClock(seconds: Int): String {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.SECOND, max(0, seconds))
    return java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(cal.time)
}

/** Server payload to the shape the engine drives from. Null when the route has no usable steps. */
fun GexemyClient.RouteResult.toNavRoute(destination: Coordinates, destinationName: String): NavRoute? {
    val route = NavRoute(
        destination = destination,
        destinationName = destinationName,
        geometry = geometry,
        steps = steps,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
    )
    return route.takeIf { it.usable }
}
