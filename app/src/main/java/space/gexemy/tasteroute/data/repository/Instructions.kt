package space.gexemy.tasteroute.data.repository

import java.util.Locale
import space.gexemy.tasteroute.data.model.*

/**
 * OSRM returns maneuvers, not sentences
 */
object Instructions {

    private val ORDINALS = listOf("", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
    private val COMPASS = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    private fun compass(bearing: Int) = COMPASS[(((bearing % 360) + 360) % 360 + 22) / 45 % 8]
    private fun turnWord(modifier: String, sharpPrefix: Boolean = true) = when (modifier) {
        "left" -> "left"; "right" -> "right"; "slight left" -> "slightly left"; "slight right" -> "slightly right"
        "sharp left" -> if (sharpPrefix) "sharp left" else "left"
        "sharp right" -> if (sharpPrefix) "sharp right" else "right"
        "uturn" -> "around"; else -> "straight"
    }
    private fun onto(road: String) = if (road.isBlank()) "" else " onto $road"
    private fun on(road: String) = if (road.isBlank()) "" else " on $road"

    fun banner(step: RouteStep): String {
        val m = step.maneuver; val road = step.road
        return when (m.type) {
            "depart" -> "Head ${compass(m.bearingAfter)}${on(road)}"
            "arrive" -> when (m.modifier) { "left" -> "Arrive on the left"; "right" -> "Arrive on the right"; else -> "Arrive at your destination" }
            "new name" -> "Continue${onto(road)}"
            "continue" -> if (m.modifier == "uturn") "Make a U-turn${onto(road)}" else "Continue${onto(road)}"
            "merge" -> "Merge ${turnWord(m.modifier)}${onto(road)}"
            "on ramp" -> "Take the ramp${onto(road)}"
            "off ramp" -> "Take the exit${onto(road)}"
            "fork" -> "Keep ${turnWord(m.modifier, sharpPrefix = false)}${onto(road)}"
            "end of road" -> "Turn ${turnWord(m.modifier)}${onto(road)}"
            "roundabout", "rotary" -> roundabout(m, road)
            "roundabout turn" -> "At the roundabout, turn ${turnWord(m.modifier)}${onto(road)}"
            "exit roundabout", "exit rotary" -> "Exit the roundabout${onto(road)}"
            "notification" -> "Continue${onto(road)}"
            else -> if (m.modifier == "uturn") "Make a U-turn${onto(road)}" else "Turn ${turnWord(m.modifier)}${onto(road)}"
        }
    }

    private fun roundabout(m: Maneuver, road: String): String {
        val exit = ORDINALS.getOrNull(m.exit).orEmpty()
        return if (exit.isBlank()) "At the roundabout, continue${onto(road)}" else "At the roundabout, take the $exit exit${onto(road)}"
    }

    fun spoken(step: RouteStep, metres: Int, units: Units): String {
        val body = speakable(banner(step))
        if (step.maneuver.type == "depart") return body
        val lead = spokenDistance(metres, units)
        return if (lead.isBlank()) body else "$lead, ${body.replaceFirstChar { it.lowercase(Locale.US) }}"
    }

    fun spokenWithNext(step: RouteStep, next: RouteStep?, metres: Int, units: Units): String {
        val first = spoken(step, metres, units)
        if (next == null || step.distanceMeters > CHAIN_METRES) return first
        return "$first, then ${speakable(banner(next)).replaceFirstChar { it.lowercase(Locale.US) }}"
    }

    fun spokenDistance(metres: Int, units: Units): String {
        if (metres <= 0) return ""
        if (units.metric()) {
            return when {
                metres < 40 -> ""; metres < 300 -> "In ${(metres / 50) * 50} meters"
                metres < 1_000 -> "In ${(metres / 100) * 100} meters"; metres < 1_500 -> "In 1 kilometer"
                else -> "In ${String.format(Locale.US, "%.1f", metres / 1000.0)} kilometers"
            }
        }
        val feet = metres * 3.28084; val miles = metres / 1609.344
        return when {
            feet < 130 -> ""; feet < 1_200 -> "In ${(feet / 100).toInt() * 100} feet"
            miles < 0.35 -> "In a quarter mile"; miles < 0.6 -> "In half a mile"; miles < 0.85 -> "In three quarters of a mile"
            miles < 1.3 -> "In 1 mile"; else -> "In ${String.format(Locale.US, "%.1f", miles)} miles"
        }
    }

    private val ABBREVIATIONS = listOf(Regex("\\bSt\\b") to "Street", Regex("\\bRd\\b") to "Road", Regex("\\bAve\\b") to "Avenue", Regex("\\bBlvd\\b") to "Boulevard", Regex("\\bDr\\b") to "Drive", Regex("\\bLn\\b") to "Lane", Regex("\\bCt\\b") to "Court", Regex("\\bPl\\b") to "Place", Regex("\\bPkwy\\b") to "Parkway", Regex("\\bHwy\\b") to "Highway", Regex("\\bSq\\b") to "Square", Regex("\\bTer\\b") to "Terrace", Regex("\\bCir\\b") to "Circle", Regex("\\bExpy\\b") to "Expressway", Regex("\\bFwy\\b") to "Freeway", Regex("\\bMt\\b") to "Mount", Regex("\\bFt\\b") to "Fort", Regex("\\bJct\\b") to "Junction")
    private val DIRECTIONS = listOf(Regex("(?<=\\s|^)N(?=\\s)") to "North", Regex("(?<=\\s|^)S(?=\\s)") to "South", Regex("(?<=\\s|^)E(?=\\s)") to "East", Regex("(?<=\\s|^)W(?=\\s)") to "West", Regex("(?<=\\s|^)NE(?=\\s)") to "Northeast", Regex("(?<=\\s|^)NW(?=\\s)") to "Northwest", Regex("(?<=\\s|^)SE(?=\\s)") to "Southeast", Regex("(?<=\\s|^)SW(?=\\s)") to "Southwest")

    fun speakable(text: String): String {
        var out = text
        out = Regex("\\bI-(\\d+)").replace(out) { "Interstate ${it.groupValues[1]}" }
        out = Regex("\\bUS-(\\d+)").replace(out) { "U S ${it.groupValues[1]}" }
        out = Regex("\\b([A-Z]{2})-(\\d+)").replace(out) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        DIRECTIONS.forEach { (pattern, word) -> out = pattern.replace(out, word) }
        ABBREVIATIONS.forEach { (pattern, word) -> out = pattern.replace(out, word) }
        return out.replace(Regex("\\s{2,}"), " ").trim()
    }
    private const val CHAIN_METRES = 220
}

class Announcer(private val units: Units) {
    private val bands = if (units.metric()) METRIC_BANDS else IMPERIAL_BANDS
    private val spoken = mutableSetOf<Int>()
    private var step = -1; private var departed = false; private var announcedArrival = false
    fun next(progress: NavProgress): String? {
        if (progress.stepIndex != step) { step = progress.stepIndex; spoken.clear() }
        if (progress.arrived) { if (announcedArrival) return null; announcedArrival = true; return "You have arrived." }
        if (!departed) { departed = true; if (progress.travelling.maneuver.type == "depart") return Instructions.spoken(progress.travelling, 0, units) }
        val distance = progress.distanceToManeuver; val length = progress.travelling.distanceMeters
        for (band in bands) {
            if (band in spoken || distance > band) continue
            if (band != bands.last() && length <= band * 1.25) { spoken += band; continue }
            bands.filter { it >= distance }.forEach { spoken += it }
            return Instructions.spokenWithNext(progress.maneuver, progress.next, if (band == bands.last()) 0 else distance, units)
        }
        return null
    }
    fun reset() { spoken.clear(); step = -1; departed = false; announcedArrival = false }
    private companion object { val METRIC_BANDS = listOf(2_000, 1_000, 400, 150, 40); val IMPERIAL_BANDS = listOf(3_200, 1_600, 400, 150, 45) }
}
