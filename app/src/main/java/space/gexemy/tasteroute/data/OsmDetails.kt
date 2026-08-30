package space.gexemy.tasteroute.data

import java.util.Locale

/** Everything OpenStreetMap holds about one place, in the shapes the detail screen already renders. */
data class OsmDetail(val facts: PlaceFacts, val photo: String?)

/**
 * The detail screen's own source of hours, a phone number and the venue's website.
 *
 * The search response carries a trimmed record — name, coordinates, cuisine — because a list does
 * not need the rest and the payload is paid for on every card. Everything else about a place was
 * therefore left to the server's multi-source harvest, which is the right place for it and also
 * the thing most likely to be unreachable, undeployed, or to have found nothing for a small venue.
 * When it comes back empty the screen used to have nothing at all to show.
 *
 * So: when somebody actually opens a place, ask the map what else it knows. One request, no key,
 * no dependency on our own API being up, and it answers for the majority of venues because
 * `opening_hours`, `phone` and `website` are among the most commonly mapped tags there are.
 * It only ever FILLS HOLES — a harvested fact from the venue's own site outranks a map tag
 * somebody typed in 2019, and [PlaceFacts.fillFrom] is what keeps that order.
 */
object OsmDetails {

    /**
     * Successes only. Caching a failure is how a transient Overpass 429 turns into a place that
     * has no details for the rest of the session, and the user cannot tell that from data we
     * genuinely do not have.
     */
    private val cache = HashMap<String, OsmDetail>()

    suspend fun forPlace(placeId: String): OsmDetail? {
        synchronized(cache) { cache[placeId] }?.let { return it }
        if (Backoff.blocked(Backoff.OSM)) return null
        val tags = try {
            OverpassClient.tagsFor(placeId)
        } catch (e: Exception) {
            Backoff.record(Backoff.OSM, e)
            return null
        } ?: return null
        val detail = OsmDetail(factsFrom(tags), OverpassClient.photoFromTags(tags))
        synchronized(cache) { cache[placeId] = detail }
        return detail
    }

    private fun factsFrom(tags: Map<String, String>): PlaceFacts {
        val raw = tags["opening_hours"]
        val schedule = Hours.parse(raw)
        return PlaceFacts(
            phone = first(tags, "phone", "contact:phone", "contact:mobile"),
            website = url(first(tags, "website", "contact:website", "url")),
            menu = url(first(tags, "website:menu", "contact:menu", "menu")),
            email = first(tags, "email", "contact:email"),
            address = address(tags),
            // The parsed week when the tag is one we fully understand, the tag itself when it is
            // not. Printing the raw syntax is ugly; inventing a week from a rule we half-read is
            // worse, and the difference matters at 9pm.
            hoursText = schedule?.lines ?: listOfNotNull(raw?.takeIf { it.isNotBlank() }),
            openNow = schedule?.openNow,
            socials = socials(tags),
            service = group(
                "dine_in" to tags["dine_in"],
                "takeout" to tags["takeaway"],
                "delivery" to tags["delivery"],
                "reservable" to tags["reservation"],
            ),
            amenities = group(
                "outdoor_seating" to tags["outdoor_seating"],
                "wheelchair" to tags["wheelchair"],
                "restroom" to tags["toilets"],
                "wifi" to wifi(tags),
                "dogs" to tags["dog"],
                "live_music" to tags["live_music"],
                "highchairs" to tags["highchair"],
                "good_for_children" to tags["kids_area"],
            ),
            diet = group(
                "vegan" to tags["diet:vegan"],
                "vegetarian" to tags["diet:vegetarian"],
                "gluten_free" to tags["diet:gluten_free"],
                "halal" to tags["diet:halal"],
                "kosher" to tags["diet:kosher"],
            ),
            drinks = group(
                "beer" to tags["drink:beer"],
                "wine" to tags["drink:wine"],
                "coffee" to tags["drink:coffee"],
                "cocktails" to tags["drink:cocktails"],
            ),
            payment = group(
                "cards" to tags["payment:cards"],
                "debit" to tags["payment:debit_cards"],
                "nfc" to tags["payment:contactless"],
                "cash_only" to cashOnly(tags),
            ),
        )
    }

    private val YES = setOf("yes", "only", "designated", "required", "recommended")
    private val NO = setOf("no", "none")

    /**
     * Absent is not false, and neither is "limited". Flattening either into a no is how a detail
     * screen ends up confidently wrong about a ramp — which is worse than saying nothing, because
     * somebody plans around it.
     */
    private fun tri(value: String?): Boolean? = when (value?.lowercase(Locale.US).orEmpty()) {
        in YES -> true
        in NO -> false
        else -> null
    }

    private fun group(vararg pairs: Pair<String, String?>): FactGroup =
        pairs.mapNotNull { (key, value) -> tri(value)?.let { key to it } }.toMap()

    private fun first(tags: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { tags[it]?.trim()?.takeIf(String::isNotEmpty) }

    private fun url(value: String?): String? = value?.let {
        if (it.startsWith("http://") || it.startsWith("https://")) it
        else if (it.contains('.') && !it.contains(' ')) "https://$it" else null
    }

    private fun address(tags: Map<String, String>): String? = listOfNotNull(
        tags["addr:housenumber"], tags["addr:street"], tags["addr:city"],
    ).joinToString(" ").trim().takeIf { it.isNotEmpty() }

    private fun socials(tags: Map<String, String>): Map<String, String> = buildMap {
        url(first(tags, "contact:instagram", "instagram"))?.let { put("instagram", it) }
        url(first(tags, "contact:facebook", "facebook"))?.let { put("facebook", it) }
    }

    private fun wifi(tags: Map<String, String>): String? = when (tags["internet_access"]?.lowercase(Locale.US)) {
        "wlan", "yes", "wifi", "terminal" -> "yes"
        "no" -> "no"
        else -> null
    }

    private fun cashOnly(tags: Map<String, String>): String? = when {
        tags["payment:cash"] == "only" -> "yes"
        tri(tags["payment:cards"]) == false -> "yes"
        else -> null
    }
}
