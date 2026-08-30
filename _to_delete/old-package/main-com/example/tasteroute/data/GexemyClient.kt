package com.example.tasteroute.data

import com.example.tasteroute.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client for the Gexemy accounts + features API. Everything the app knows about other people's
 * data — live waits, allergen reports, group sessions — comes through here; nothing else in the
 * app talks to a third-party place provider.
 *
 * A 401 on an authenticated call refreshes once and retries; a second 401 signs the device out
 * rather than looping.
 */
object GexemyClient {

    private val base = BuildConfig.GEXEMY_BASE_URL.trimEnd('/')

    /** Configured in the build. Says nothing about whether the host is actually answering. */
    val isConfigured: Boolean get() = base.isNotBlank()

    /** Configured AND not currently in a failure backoff. Callers on the hot path check this one. */
    fun reachable(key: String = Backoff.PLACES) = isConfigured && !Backoff.blocked(key)

    /** The configured host, for the settings screen. Callers must not build URLs from this. */
    val baseUrl: String get() = base

    /**
     * [capabilities] is what separates "the server is behind" from "the server is broken" — the two
     * failures look identical from the app otherwise, and only one of them is fixed by redeploying.
     * An empty list means a build old enough to predate the list itself, which is itself the answer.
     */
    @Serializable
    data class Health(
        val ok: Boolean = false,
        val service: String = "",
        val version: String = "",
        val db: Boolean = true,
        val capabilities: List<String> = emptyList(),
        /**
         * Which content connectors have keys on THIS box. Distinct from [capabilities]: that says
         * the build knows how to talk to Google, this says whether it actually can.
         */
        val sources: List<String> = emptyList(),
    ) {
        fun supports(feature: String) = feature in capabilities
        val reportsCapabilities: Boolean get() = capabilities.isNotEmpty()
    }

    /** Liveness probe for the settings screen. Short timeout, no auth, deliberately not cached. */
    suspend fun health(): Health =
        send("GET", "/v1/health", null, Health.serializer(), authed = false, timeoutMs = 6_000)

    // Auth -------------------------------------------------------------------------------------

    @Serializable
    data class AuthResult(val access: String, val refresh: String, val user: Account? = null)

    @Serializable
    data class Account(
        val id: Long = 0,
        val email: String = "",
        @SerialName("display_name") val displayName: String = "",
        val tier: String = "free",
        val taste: TasteProfile? = null,
        val allergens: List<String> = emptyList(),
        @SerialName("taste_text") val tasteText: String = "",
        val favorites: List<Favorite> = emptyList(),
    )

    @Serializable
    data class Favorite(@SerialName("place_id") val placeId: String, val name: String = "")

    suspend fun register(email: String, password: String, displayName: String): AuthResult =
        post("/v1/auth/register", buildJsonObject {
            put("email", email); put("password", password)
            put("display_name", displayName); put("device", USER_AGENT)
        }, AuthResult.serializer(), authed = false)

    suspend fun login(email: String, password: String): AuthResult =
        post("/v1/auth/login", buildJsonObject {
            put("email", email); put("password", password); put("device", USER_AGENT)
        }, AuthResult.serializer(), authed = false)

    suspend fun refresh(token: String): AuthResult =
        post("/v1/auth/refresh", buildJsonObject { put("refresh", token); put("device", USER_AGENT) },
            AuthResult.serializer(), authed = false)

    suspend fun logout(token: String) {
        runCatching { post("/v1/auth/logout", buildJsonObject { put("refresh", token) }, null, authed = false) }
    }

    suspend fun me(): Account = send("GET", "/v1/me", null, Account.serializer(), authed = true)

    suspend fun putProfile(profile: TasteProfile, allergens: List<String>, tasteText: String, tier: Tier) {
        send("PUT", "/v1/me/profile", buildJsonObject {
            put("taste", AppJson.encodeToJsonElement(TasteProfile.serializer(), profile))
            put("allergens", buildJsonArray { allergens.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("taste_text", tasteText)
            put("tier", tier.wire)
        }, null, authed = true)
    }

    suspend fun putFavorites(favorites: Map<String, String>) {
        send("PUT", "/v1/me/favorites", buildJsonObject {
            put("favorites", buildJsonArray {
                favorites.forEach { (id, name) ->
                    add(buildJsonObject { put("place_id", id); put("name", name) })
                }
            })
        }, null, authed = true)
    }

    // Crowd pulse ------------------------------------------------------------------------------

    @Serializable
    private data class PulseBatch(val pulse: Map<String, CrowdPulse> = emptyMap())

    @Serializable
    data class PulseDetail(
        val pulse: CrowdPulse? = null,
        @SerialName("by_hour") val byHour: List<HourBucket> = emptyList(),
    )

    @Serializable
    data class HourBucket(val hour: Int, @SerialName("avg_wait") val avgWait: Int, val reports: Int)

    @Serializable
    private data class CheckInResult(val ok: Boolean = false, val pulse: CrowdPulse? = null)

    suspend fun pulseFor(placeIds: List<String>): Map<String, CrowdPulse> {
        if (placeIds.isEmpty()) return emptyMap()
        return post("/v1/pulse/batch", buildJsonObject {
            put("place_ids", buildJsonArray { placeIds.take(60).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }, PulseBatch.serializer(), authed = false).pulse
    }

    suspend fun pulseDetail(placeId: String): PulseDetail =
        send("GET", "/v1/places/${enc(placeId)}/pulse", null, PulseDetail.serializer(), authed = false)

    suspend fun checkIn(placeId: String, waitMinutes: Int, busy: Int, seated: Boolean, partySize: Int): CrowdPulse? =
        post("/v1/checkins", buildJsonObject {
            put("place_id", placeId); put("wait_minutes", waitMinutes); put("busy", busy)
            put("seated", seated); put("party_size", partySize)
        }, CheckInResult.serializer(), authed = true, optionalAuth = true).pulse

    // Allergens --------------------------------------------------------------------------------

    @Serializable
    private data class AllergenBatch(val places: Map<String, List<AllergenSignal>> = emptyMap())

    @Serializable
    private data class AllergenDetail(val allergens: List<AllergenSignal> = emptyList())

    suspend fun allergensFor(placeIds: List<String>): Map<String, List<AllergenSignal>> {
        if (placeIds.isEmpty()) return emptyMap()
        return post("/v1/allergens/batch", buildJsonObject {
            put("place_ids", buildJsonArray { placeIds.take(60).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }, AllergenBatch.serializer(), authed = false).places
    }

    suspend fun reportAllergen(placeId: String, allergen: String, stance: String, note: String): List<AllergenSignal> =
        post("/v1/places/${enc(placeId)}/allergens", buildJsonObject {
            put("allergen", allergen); put("stance", stance); put("note", note)
        }, AllergenDetail.serializer(), authed = true).allergens

    // Photos and reviews ------------------------------------------------------------------------

    @Serializable
    private data class PhotoList(val photos: List<PlacePhoto> = emptyList())

    @Serializable
    private data class CoverBatch(val covers: Map<String, String> = emptyMap())

    @Serializable
    private data class UploadResult(val id: Long = 0, val url: String = "")

    @Serializable
    data class ReviewPage(
        val summary: ReviewSummary = ReviewSummary(),
        val sources: List<ReviewSource> = emptyList(),
        val mine: Review? = null,
        val reviews: List<Review> = emptyList(),
    )

    @Serializable
    private data class ReviewBatch(val places: Map<String, ReviewSummary> = emptyMap())

    /** The API returns app-relative paths so it never has to know its own public hostname. */
    fun absolute(path: String) = if (path.startsWith("http")) path else base + path

    suspend fun photosFor(placeId: String): List<PlacePhoto> =
        send("GET", "/v1/places/${enc(placeId)}/photos", null, PhotoList.serializer(), authed = false)
            .photos.map { it.copy(url = absolute(it.url)) }

    /** One cover per card for the feed, rather than a gallery per card. */
    suspend fun coversFor(placeIds: List<String>): Map<String, String> {
        if (placeIds.isEmpty()) return emptyMap()
        return post("/v1/photos/batch", buildJsonObject {
            put("place_ids", buildJsonArray { placeIds.take(60).forEach { add(JsonPrimitive(it)) } })
        }, CoverBatch.serializer(), authed = false).covers.mapValues { absolute(it.value) }
    }

    suspend fun uploadPhoto(placeId: String, bytes: ByteArray, mime: String, caption: String): String =
        withContext(Dispatchers.IO) {
            val token = Session.accessToken ?: Session.refreshAccess() ?: throw HttpException(401, "Sign in to add a photo")
            val raw = httpSendBytes(
                method = "POST",
                url = "$base/v1/places/${enc(placeId)}/photos",
                body = bytes,
                contentType = mime,
                headers = mapOf("Authorization" to "Bearer $token", "X-Caption" to caption.take(180)),
            )
            absolute(AppJson.decodeFromString(UploadResult.serializer(), raw).url)
        }

    /**
     * Coordinates are optional but worth sending: the server uses them to pull whatever an open
     * review dataset has for this spot, once per place per week. Without them you get first-party
     * reviews only.
     */
    suspend fun reviewsFor(placeId: String, at: Coordinates? = null): ReviewPage {
        val where = at?.let { "?lat=${it.lat}&lng=${it.lng}" }.orEmpty()
        return send(
            "GET", "/v1/places/${enc(placeId)}/reviews$where", null,
            ReviewPage.serializer(), authed = true, optionalAuth = true,
        )
    }

    suspend fun writeReview(placeId: String, rating: Int, body: String): ReviewPage =
        post("/v1/places/${enc(placeId)}/reviews", buildJsonObject {
            put("rating", rating); put("body", body)
        }, ReviewPage.serializer(), authed = true)

    suspend fun ratingsFor(placeIds: List<String>): Map<String, ReviewSummary> {
        if (placeIds.isEmpty()) return emptyMap()
        return post("/v1/reviews/batch", buildJsonObject {
            put("place_ids", buildJsonArray { placeIds.take(60).forEach { add(JsonPrimitive(it)) } })
        }, ReviewBatch.serializer(), authed = false).places
    }

    // Yelp -------------------------------------------------------------------------------------

    @Serializable
    private data class YelpDetail(val yelp: YelpInfo? = null, val enabled: Boolean = false)

    @Serializable
    private data class YelpBatch(val places: Map<String, YelpInfo> = emptyMap())

    /**
     * The key lives on the server, so the app never ships it and every device shares one cache.
     * Coordinates and name are required: Yelp is matched by geography and title, not by our id.
     */
    suspend fun yelpFor(placeId: String, at: Coordinates, name: String): YelpInfo? =
        send(
            "GET",
            "/v1/places/${enc(placeId)}/yelp?lat=${at.lat}&lng=${at.lng}&name=${enc(name)}",
            null, YelpDetail.serializer(), authed = false,
        ).yelp

    suspend fun yelpFor(places: List<RestaurantResult>): Map<String, YelpInfo> {
        if (places.isEmpty()) return emptyMap()
        return post("/v1/yelp/batch", buildJsonObject {
            put("places", buildJsonArray {
                places.take(60).forEach { place ->
                    add(buildJsonObject {
                        put("id", place.id)
                        put("lat", place.coordinates.lat)
                        put("lng", place.coordinates.lng)
                        put("name", place.name)
                    })
                }
            })
        }, YelpBatch.serializer(), authed = false, timeoutMs = PLACES_TIMEOUT_MS).places
    }

    // Content sources --------------------------------------------------------------------------

    @Serializable
    private data class SourcesDetail(
        val sources: List<ExternalSource> = emptyList(),
        val facts: PlaceFacts = PlaceFacts(),
        val active: List<String> = emptyList(),
    )

    @Serializable
    private data class SourcesEntry(
        val sources: List<ExternalSource> = emptyList(),
        val facts: PlaceFacts = PlaceFacts(),
    )

    @Serializable
    private data class SourcesBatch(
        val places: Map<String, SourcesEntry> = emptyMap(),
        val active: List<String> = emptyList(),
    )

    @Serializable
    private data class AttributionList(val attributions: List<Attribution> = emptyList())

    /**
     * Everything every configured platform has about one place, plus the union of their structured
     * facts. Coordinates, name and website all travel up because this API keeps no place table —
     * outside platforms are matched by geography and title, never by our id.
     *
     * The expensive connectors only spend a call on this route, never on the feed one below.
     */
    suspend fun sourcesFor(
        placeId: String,
        at: Coordinates,
        name: String,
        website: String? = null,
    ): Pair<List<ExternalSource>, PlaceFacts> {
        val query = buildString {
            append("/v1/places/${enc(placeId)}/sources")
            append("?lat=${at.lat}&lng=${at.lng}&name=${enc(name)}")
            if (!website.isNullOrBlank()) append("&website=${enc(website)}")
        }
        val detail = send("GET", query, null, SourcesDetail.serializer(), authed = false)
        return detail.sources to detail.facts
    }

    /** One pass for a screen of cards. Serves cache hits; detail-only sources contribute nothing. */
    suspend fun sourcesFor(places: List<RestaurantResult>): Map<String, List<ExternalSource>> {
        if (places.isEmpty()) return emptyMap()
        return post("/v1/sources/batch", buildJsonObject {
            put("places", buildJsonArray {
                places.take(60).forEach { place ->
                    add(buildJsonObject {
                        put("id", place.id)
                        put("lat", place.coordinates.lat)
                        put("lng", place.coordinates.lng)
                        put("name", place.name)
                        place.webUrl?.takeIf { it.isNotBlank() }?.let { put("website", it) }
                    })
                }
            })
        }, SourcesBatch.serializer(), authed = false, timeoutMs = PLACES_TIMEOUT_MS)
            .places.mapValues { it.value.sources }
    }

    /** Who to credit, straight from the server, so a new connector needs no app release. */
    suspend fun attributions(): List<Attribution> =
        send("GET", "/v1/attributions", null, AttributionList.serializer(), authed = false).attributions

    // Table Sync -------------------------------------------------------------------------------

    @Serializable
    data class GroupSnapshot(
        val code: String,
        val title: String = "",
        @SerialName("host_id") val hostId: Long? = null,
        val origin: Coordinates? = null,
        val closed: Boolean = false,
        val members: List<GroupMember> = emptyList(),
        val merged: MergedTaste = MergedTaste(),
        val votes: List<GroupVote> = emptyList(),
    )

    @Serializable
    data class GroupMember(@SerialName("user_id") val userId: Long, val name: String = "")

    @Serializable
    data class GroupVote(
        @SerialName("place_id") val placeId: String,
        val name: String = "",
        val yes: Int = 0,
        val no: Int = 0,
    )

    @Serializable
    data class MergedTaste(
        @SerialName("preferred_cuisines") val preferredCuisines: List<String> = emptyList(),
        @SerialName("dietary_restrictions") val dietaryRestrictions: List<String> = emptyList(),
        @SerialName("vibe_tags") val vibeTags: List<String> = emptyList(),
        @SerialName("price_comfort") val priceComfort: Int = 2,
        val allergens: List<String> = emptyList(),
        val consensus: Consensus = Consensus(),
    ) {
        fun toProfile() = TasteProfile(
            preferredCuisines = preferredCuisines,
            dietaryRestrictions = dietaryRestrictions,
            priceComfort = priceComfort,
            vibeTags = vibeTags,
        )
    }

    @Serializable
    data class Consensus(
        val members: Int = 0,
        val threshold: Int = 1,
        @SerialName("dropped_cuisines") val droppedCuisines: List<String> = emptyList(),
    )

    suspend fun createGroup(title: String, name: String, origin: Coordinates?): GroupSnapshot =
        post("/v1/groups", buildJsonObject {
            put("title", title); put("name", name)
            origin?.let { put("origin", buildJsonObject { put("lat", it.lat); put("lng", it.lng) }) }
        }, GroupSnapshot.serializer(), authed = true)

    suspend fun joinGroup(code: String, name: String): GroupSnapshot =
        post("/v1/groups/${enc(code)}/join", buildJsonObject { put("name", name) },
            GroupSnapshot.serializer(), authed = true)

    suspend fun group(code: String): GroupSnapshot =
        send("GET", "/v1/groups/${enc(code)}", null, GroupSnapshot.serializer(), authed = true)

    suspend fun voteGroup(code: String, placeId: String, name: String, vote: Int): GroupSnapshot =
        post("/v1/groups/${enc(code)}/vote", buildJsonObject {
            put("place_id", placeId); put("name", name); put("vote", vote)
        }, GroupSnapshot.serializer(), authed = true)

    suspend fun leaveGroup(code: String) {
        runCatching { post("/v1/groups/${enc(code)}/leave", buildJsonObject { }, null, authed = true) }
    }

    // Places, routing, corridor ------------------------------------------------------------------

    @Serializable
    private data class PlacesResult(val places: List<RestaurantRecord> = emptyList())

    @Serializable
    data class RouteResult(
        @SerialName("distance_meters") val distanceMeters: Int = 0,
        @SerialName("duration_seconds") val durationSeconds: Int = 0,
        val geometry: List<Coordinates> = emptyList(),
        /** Populated only when the caller asked for steps; corridor search doesn't and shouldn't. */
        val steps: List<RouteStep> = emptyList(),
    )

    @Serializable
    data class CorridorResult(
        @SerialName("route_distance_meters") val routeDistanceMeters: Int = 0,
        @SerialName("route_duration_seconds") val routeDurationSeconds: Int = 0,
        val geometry: List<Coordinates> = emptyList(),
        val places: List<RestaurantRecord> = emptyList(),
    )

    suspend fun nearby(origin: Coordinates, radiusMeters: Int): List<RestaurantRecord> =
        post("/v1/places/nearby", buildJsonObject {
            put("lat", origin.lat); put("lng", origin.lng); put("radius_m", radiusMeters)
        }, PlacesResult.serializer(), authed = false, timeoutMs = PLACES_TIMEOUT_MS).places

    suspend fun route(
        from: Coordinates,
        to: Coordinates,
        mode: String = "driving",
        steps: Boolean = false,
    ): RouteResult =
        post("/v1/route", buildJsonObject {
            put("from", buildJsonObject { put("lat", from.lat); put("lng", from.lng) })
            put("to", buildJsonObject { put("lat", to.lat); put("lng", to.lng) })
            put("mode", mode)
            put("steps", steps)
        }, RouteResult.serializer(), authed = false, timeoutMs = PLACES_TIMEOUT_MS)

    suspend fun corridor(from: Coordinates, to: Coordinates, corridorMeters: Int): CorridorResult =
        post("/v1/corridor", buildJsonObject {
            put("from", buildJsonObject { put("lat", from.lat); put("lng", from.lng) })
            put("to", buildJsonObject { put("lat", to.lat); put("lng", to.lng) })
            put("corridor_m", corridorMeters)
        }, CorridorResult.serializer(), authed = false, timeoutMs = PLACES_TIMEOUT_MS)

    private const val PLACES_TIMEOUT_MS = 15_000

    // Transport --------------------------------------------------------------------------------

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    private suspend fun <T> post(
        path: String,
        body: JsonObject,
        serializer: KSerializer<T>,
        authed: Boolean,
        optionalAuth: Boolean = false,
        timeoutMs: Int = READ_TIMEOUT_MS,
    ): T = send("POST", path, body, serializer, authed, optionalAuth, timeoutMs)

    private suspend fun post(path: String, body: JsonObject, serializer: Nothing?, authed: Boolean) {
        raw("POST", path, body, authed, optionalAuth = false)
    }

    private suspend fun <T> send(
        method: String,
        path: String,
        body: JsonObject?,
        serializer: KSerializer<T>,
        authed: Boolean,
        optionalAuth: Boolean = false,
        timeoutMs: Int = READ_TIMEOUT_MS,
    ): T = AppJson.decodeFromString(serializer, raw(method, path, body, authed, optionalAuth, timeoutMs))

    private suspend fun send(method: String, path: String, body: JsonObject?, serializer: Nothing?, authed: Boolean) {
        raw(method, path, body, authed, optionalAuth = false)
    }

    private suspend fun raw(
        method: String,
        path: String,
        body: JsonObject?,
        authed: Boolean,
        optionalAuth: Boolean,
        timeoutMs: Int = READ_TIMEOUT_MS,
    ): String = withContext(Dispatchers.IO) {
        if (!isConfigured) throw HttpException(0, "GEXEMY_BASE_URL missing from local.properties")

        fun call(token: String?): String = httpSend(
            method = method,
            url = base + path,
            body = body?.toString(),
            contentType = if (body != null) "application/json" else null,
            headers = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap(),
            accept = "application/json",
            readTimeoutMs = timeoutMs,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
        )

        var token = if (authed) Session.accessToken else null
        if (authed && !optionalAuth && token == null) throw HttpException(401, "Sign in to use this")

        try {
            call(token)
        } catch (e: HttpException) {
            // One refresh, then give up — a retry loop on a revoked token is how you get a
            // client that hammers the server while showing the user nothing.
            if (e.code != 401 || !authed) throw e
            token = Session.refreshAccess()
            if (token == null && !optionalAuth) throw e
            call(token)
        }
    }
}
