package space.gexemy.tasteroute.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer

val ALL_CUISINES = listOf("Thai", "Japanese", "Mexican", "Italian", "Korean", "Indian", "Mediterranean", "American", "French", "Vegan")
val ALL_DIETARY = listOf("Vegan", "Vegetarian", "Gluten-free", "Halal")
val ALL_VIBES = listOf("Cozy", "Casual", "Date night", "Lively", "Quiet", "Trendy", "Family-friendly", "Quick bite")

/** Mirrors the server's list exactly; a value the server rejects can never reach a report call. */
val ALL_ALLERGENS = listOf(
    "Peanuts", "Tree nuts", "Dairy", "Eggs", "Gluten", "Wheat", "Soy", "Fish", "Shellfish", "Sesame", "Mustard", "Sulphites",
)

/** How many results are worth carrying across a restart. Beyond a screenful nobody scrolls. */
private const val WARM_LIMIT = 12

/** Saved-place snapshots kept on disk. Slim records are tiny; past this nobody is curating. */
private const val FAVORITE_LIMIT = 100

/**
 * How far the live fix has to drift from the point a search was run before running another one is
 * worth it. Roughly a long block: far enough that walking around inside a restaurant, or a fused
 * provider wobbling between wifi and GPS, changes nothing.
 */
private const val RESEARCH_DRIFT_M = 450

/** …and how long a latched search origin stands even if you have not moved at all. */
private const val RESEARCH_MAX_AGE_MS = 5 * 60_000L

/** Conversation kept across restarts. Long enough to pick up a thread, short enough to load fast. */
private const val CHAT_LIMIT = 40

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Font override. The first four are system faces — always present, no download, no APK weight.
 * The rest are Google Fonts pulled through the platform's downloadable-font provider; [label] is
 * the family name the provider expects, so it doubles as the menu text.
 */
enum class FontChoice(val label: String, val note: String) {
    SYSTEM("System default", "Whatever your phone uses"),
    SYSTEM_SERIF("System serif", "No download"),
    SYSTEM_MONO("System mono", "No download"),
    SYSTEM_CURSIVE("System cursive", "No download"),
    INTER("Inter", "Clean UI sans"),
    ROBOTO("Roboto", "Android classic"),
    OPEN_SANS("Open Sans", "Friendly and wide"),
    NUNITO("Nunito", "Rounded and soft"),
    POPPINS("Poppins", "Geometric"),
    MANROPE("Manrope", "Modern sans"),
    DM_SANS("DM Sans", "Compact sans"),
    WORK_SANS("Work Sans", "Sturdy"),
    RUBIK("Rubik", "Slightly rounded"),
    SPACE_GROTESK("Space Grotesk", "Techy"),
    LORA("Lora", "Readable serif"),
    MERRIWEATHER("Merriweather", "Bookish serif"),
    PLAYFAIR_DISPLAY("Playfair Display", "High-contrast serif"),
    SOURCE_CODE_PRO("Source Code Pro", "Monospace"),
    ATKINSON_HYPERLEGIBLE("Atkinson Hyperlegible", "Built for low vision"),
    ;

    val downloadable: Boolean get() = ordinal >= INTER.ordinal
}

/**
 * One line of the assistant conversation. The prose persists; the attached places deliberately do
 * not — a restaurant list from three days ago restored into a chat would be presented as current
 * when its waits, hours and ratings have all moved on.
 */
@Serializable
data class ChatMessage(
    @Transient val fromUser: Boolean = false,
    val text: String = "",
    @Transient val results: List<RestaurantResult> = emptyList(),
    /** The reply is written; the place lookup it asked for is still running. */
    @Transient val searching: Boolean = false,
    /** "me" survives serialization; [fromUser] is its transient mirror for Compose. */
    val me: Boolean = false,
)

/**
 * What a result keeps when it is written to disk for the next cold start.
 *
 * Sources, facts, galleries and allergen reports are all re-fetched within a second of the app
 * opening — and they are also almost all of the bytes. Storing them meant the warm list took
 * longer to parse at startup than the network took to replace it, which is the opposite of the
 * point of having one.
 */
private fun RestaurantResult.slimForWarm() = copy(
    photos = emptyList(),
    allergens = emptyList(),
    sources = emptyList(),
    facts = null,
    yelp = null,
)

/** App-wide observable state. Anything worth surviving process death is mirrored into [Prefs]. */
object AppState {
    /**
     * The live device fix, updated continuously while the app is in front of the person. Null until
     * they grant location — nothing is recommended before then. Read this for anything that should
     * follow them, like the distance on a card; read [searchOrigin] for anything that triggers work.
     */
    var origin by mutableStateOf<Coordinates?>(null)
        private set

    /**
     * The fix a SEARCH is keyed on, which is not the same thing as where you are.
     *
     * The fused provider publishes every few metres. Keying Discover on that meant every fix
     * cancelled the in-flight stream — including an AI re-rank seconds from landing — and started
     * the clock again, forever, which is why walking down a street with the app open looked like it
     * never finished loading. This one latches: it follows [origin] only once the live fix has
     * genuinely moved [RESEARCH_DRIFT_M] away or [RESEARCH_MAX_AGE_MS] has passed. Distances update
     * continuously; searches do not.
     */
    var searchOrigin by mutableStateOf<Coordinates?>(null)
        private set
    private var searchOriginAt = 0L

    var cityLabel by mutableStateOf<String?>(null)

    /**
     * The mall the live fix falls inside, or null. Never persisted: it is a claim about where the
     * person is standing right now, and a restored one would be wrong the moment they drive home.
     */
    var mall by mutableStateOf<Mall?>(null)

    /**
     * The assistant has a reply in flight. Lives here rather than in the chat screen because the
     * button that shows it is in the navigation bar, which outlives every tab — the point of it is
     * that you can walk away from the conversation and still see it working.
     */
    var assistantBusy by mutableStateOf(false)

    /**
     * The single door every location fix comes through. Returns true when this fix moved the search
     * origin, which is the only kind of movement anything expensive should react to.
     */
    fun noteFix(fix: Coordinates): Boolean {
        origin = fix
        val anchor = searchOrigin
        val now = System.currentTimeMillis()
        val moved = anchor == null ||
            RecommendationEngine.distanceMeters(anchor, fix) >= RESEARCH_DRIFT_M ||
            now - searchOriginAt >= RESEARCH_MAX_AGE_MS
        if (moved) {
            searchOrigin = fix
            searchOriginAt = now
        }
        return moved
    }
    var locationStatus by mutableStateOf(LocationStatus.UNKNOWN)

    var tier by mutableStateOf(Tier.FREE)
    var profile by mutableStateOf(
        TasteProfile(
            preferredCuisines = listOf("Thai", "Japanese", "Mexican"),
            priceComfort = 2,
            vibeTags = listOf("Cozy", "Casual"),
        )
    )

    /** Hard constraints. Separate from profile.dietaryRestrictions, which only demote. */
    val allergens = mutableStateListOf<String>()
    val favorites = mutableStateListOf<String>()

    /**
     * Slim snapshots of what the hearts point at, newest first — what the Profile list renders.
     * [favorites] stays the id truth (it is what syncs); a heart from before snapshots existed,
     * or synced in from another device, is still a heart — it just lists by name only until it
     * is hearted again with the full record in hand.
     */
    val favoritePlaces = mutableStateListOf<RestaurantResult>()

    /** id -> name so saved places render without re-fetching the result they came from. */
    val knownNames = mutableStateMapOf<String, String>()
    val activeFilters = mutableStateListOf<String>()
    val chatMessages = mutableStateListOf<ChatMessage>()
    var aiQueriesUsedToday by mutableStateOf(0)
    var selectedRestaurant by mutableStateOf<RestaurantResult?>(null)
    var lastResults by mutableStateOf<List<RestaurantResult>>(emptyList())

    /**
     * The last plain nearby list this device saw, kept on disk so a cold start paints food
     * instead of a skeleton. Stale on purpose and labelled as such — the fresh list replaces it
     * within a second, but that second is the whole first impression of the app.
     */
    var warmResults by mutableStateOf<List<RestaurantResult>>(emptyList())
        private set
    var warmOrigin by mutableStateOf<Coordinates?>(null)
        private set
    var warmSavedAt = 0L
        private set
    var onboarded by mutableStateOf(false)
    var tasteText by mutableStateOf("")

    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    var fontChoice by mutableStateOf(FontChoice.SYSTEM)
    var fontScale by mutableStateOf(1f)
    /** First-run greeting has been answered — signed in, or explicitly skipped. */
    var welcomed by mutableStateOf(false)
    var preciseLocation by mutableStateOf(true)
    var saveHistory by mutableStateOf(true)

    // Navigation. Voice defaults on: a satnav you have to look at is a map.
    var navVoice by mutableStateOf(true)
    var voiceName by mutableStateOf("")
    var voiceSpeed by mutableStateOf(1f)
    var units by mutableStateOf(Units.AUTO)

    /** Built by the route preview and handed to guidance, so starting a drive fetches nothing. */
    var navRoute by mutableStateOf<NavRoute?>(null)

    // On-my-way search
    var searchMode by mutableStateOf(SearchMode.NEARBY)

    /** Set when browsing somewhere you aren't — trip planning. Distances are measured from here. */
    var searchArea by mutableStateOf<Coordinates?>(null)
    var searchAreaLabel by mutableStateOf("")
    var destination by mutableStateOf<Coordinates?>(null)
    var destinationLabel by mutableStateOf("")
    var routeGeometry by mutableStateOf<List<Coordinates>>(emptyList())

    fun toggleFavorite(id: String) {
        if (favorites.remove(id)) {
            favoritePlaces.removeAll { it.id == id }
        } else {
            favorites.add(id)
        }
        persistFavorites()
    }

    /** The heart. With the full record in hand, saving also keeps a snapshot Profile can list. */
    fun toggleFavorite(place: RestaurantResult) {
        if (place.id in favorites) {
            toggleFavorite(place.id)
            return
        }
        favorites.add(place.id)
        knownNames[place.id] = place.name
        favoritePlaces.removeAll { it.id == place.id }
        favoritePlaces.add(0, place.slimForWarm())
        while (favoritePlaces.size > FAVORITE_LIMIT) favoritePlaces.removeAt(favoritePlaces.lastIndex)
        persistFavorites()
    }

    private fun persistFavorites() {
        Prefs.put(Prefs.FAVORITES, favorites.toSet())
        Prefs.put(
            Prefs.FAVORITE_PLACES,
            AppJson.encodeToString(ListSerializer(RestaurantResult.serializer()), favoritePlaces.toList()),
        )
        Session.pushFavorites()
    }

    fun toggleAllergen(name: String) {
        if (!allergens.remove(name)) allergens.add(name)
        Prefs.put(Prefs.ALLERGENS, allergens.toSet())
        Recommender.invalidate()
        Session.pushProfile()
    }

    /**
     * Persist a list as the next cold start's first paint. Only unfiltered nearby searches qualify:
     * a warm list has to match the controls on screen, and one built under a query or a filter the
     * user is no longer applying is a lie that survives a restart.
     */
    fun rememberWarm(results: List<RestaurantResult>, request: RecommendationRequest) {
        if (!saveHistory || results.isEmpty()) return
        if (request.mode == SearchMode.ON_THE_WAY) return
        if (request.query.isNotBlank() || request.appliedFilters.isNotEmpty()) return
        val keep = results.take(WARM_LIMIT).map { it.slimForWarm() }
        warmResults = keep
        warmOrigin = request.origin
        warmSavedAt = System.currentTimeMillis()
        Prefs.put(Prefs.WARM_RESULTS, AppJson.encodeToString(ListSerializer(RestaurantResult.serializer()), keep))
        Prefs.put(Prefs.WARM_ORIGIN, "${request.origin.lat},${request.origin.lng}")
        Prefs.put(Prefs.WARM_AT, warmSavedAt.toString())
    }

    fun forgetWarm() {
        warmResults = emptyList()
        warmOrigin = null
        warmSavedAt = 0L
        Prefs.remove(Prefs.WARM_RESULTS, Prefs.WARM_ORIGIN, Prefs.WARM_AT)
    }

    /**
     * Turning history off has to DELETE what was already kept, not just stop adding to it.
     * A switch that leaves the last dozen restaurants you looked at sitting in SharedPreferences
     * is not a privacy setting, it is a privacy setting's user interface.
     */
    fun updateSaveHistory(on: Boolean) {
        saveHistory = on
        Prefs.put(Prefs.HISTORY, on)
        if (!on) {
            forgetWarm()
            Prefs.remove(Prefs.CHAT_LOG)
        }
    }

    fun addChat(message: ChatMessage) {
        chatMessages.add(message.copy(me = message.fromUser))
        persistChat()
    }

    fun replaceChat(index: Int, message: ChatMessage) {
        if (index !in chatMessages.indices) return
        chatMessages[index] = message.copy(me = message.fromUser)
        persistChat()
    }

    fun clearChat() {
        chatMessages.clear()
        Prefs.remove(Prefs.CHAT_LOG)
    }

    private fun persistChat() {
        if (!saveHistory) return
        val keep = chatMessages.takeLast(CHAT_LIMIT)
        Prefs.put(Prefs.CHAT_LOG, AppJson.encodeToString(ListSerializer(ChatMessage.serializer()), keep))
    }

    fun rememberNames(results: List<RestaurantResult>) {
        results.forEach { knownNames[it.id] = it.name }
    }

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        Prefs.put(Prefs.THEME, mode.name)
    }

    fun setFont(choice: FontChoice) {
        fontChoice = choice
        Prefs.put(Prefs.FONT, choice.name)
    }

    fun updateFontScale(scale: Float) {
        fontScale = scale.coerceIn(0.85f, 1.35f)
        Prefs.put(Prefs.FONT_SCALE, (fontScale * 100).toInt())
    }

    fun setWelcomed() {
        welcomed = true
        Prefs.put(Prefs.WELCOMED, true)
    }

    fun updateNavVoice(on: Boolean) {
        navVoice = on
        Prefs.put(Prefs.NAV_VOICE, on)
    }

    fun updateVoiceName(name: String) {
        voiceName = name
        Prefs.put(Prefs.VOICE_NAME, name)
        Voice.applyVoice(name)
    }

    fun updateVoiceSpeed(rate: Float) {
        voiceSpeed = rate.coerceIn(0.7f, 1.4f)
        Prefs.put(Prefs.VOICE_SPEED, (voiceSpeed * 100).toInt())
        Voice.setSpeed(voiceSpeed)
    }

    fun updateUnits(value: Units) {
        units = value
        Prefs.put(Prefs.UNITS, value.name)
    }

    fun applyProfile(next: TasteProfile, text: String? = null) {
        profile = next
        text?.let { tasteText = it }
        persistProfile()
        Recommender.invalidate()
        Session.pushProfile()
    }

    fun persistProfile() {
        Prefs.put(Prefs.PROFILE, AppJson.encodeToString(TasteProfile.serializer(), profile))
        Prefs.put(Prefs.TASTE_TEXT, tasteText)
        Prefs.put(Prefs.TIER, tier.wire)
        Prefs.put(Prefs.ONBOARDED, onboarded)
    }

    /**
     * The scalars the first composition reads, and nothing else.
     *
     * Every line here is a SharedPreferences hit on the main thread before the first frame can be
     * drawn, so anything that costs a JSON decode belongs in [restoreRest] instead.
     */
    fun restoreCritical() {
        themeMode = runCatching { ThemeMode.valueOf(Prefs.getString(Prefs.THEME, ThemeMode.SYSTEM.name)) }
            .getOrDefault(ThemeMode.SYSTEM)
        fontChoice = runCatching { FontChoice.valueOf(Prefs.getString(Prefs.FONT, FontChoice.SYSTEM.name)) }
            .getOrDefault(FontChoice.SYSTEM)
        fontScale = Prefs.getInt(Prefs.FONT_SCALE, 100) / 100f
        welcomed = Prefs.getBoolean(Prefs.WELCOMED, false)
        onboarded = Prefs.getBoolean(Prefs.ONBOARDED, false)
        tasteText = Prefs.getString(Prefs.TASTE_TEXT)
        tier = Tier.fromWire(Prefs.getString(Prefs.TIER, Tier.FREE.wire))
        preciseLocation = Prefs.getBoolean(Prefs.PRECISE, true)
        saveHistory = Prefs.getBoolean(Prefs.HISTORY, true)
        navVoice = Prefs.getBoolean(Prefs.NAV_VOICE, true)
        voiceName = Prefs.getString(Prefs.VOICE_NAME)
        voiceSpeed = Prefs.getInt(Prefs.VOICE_SPEED, 100) / 100f
        units = runCatching { Units.valueOf(Prefs.getString(Prefs.UNITS, Units.AUTO.name)) }
            .getOrDefault(Units.AUTO)
        allergens.addAll(Prefs.getStringSet(Prefs.ALLERGENS))
        favorites.addAll(Prefs.getStringSet(Prefs.FAVORITES))
        // One string, no JSON, and the first composition asks for it: every screen that gates on
        // Session.signedIn would otherwise paint its signed-out branch before restoreRest lands.
        Session.restoreToken()
    }

    /**
     * Everything with a JSON document behind it, run off the main thread once the UI is already up.
     *
     * None of it is needed to draw the first screen: the profile only matters when a search runs,
     * and both the warm list and the chat log land long before anyone has finished reading a
     * header. Deserializing them in Application.onCreate was most of the cold-start wait.
     */
    fun restoreRest() {
        Prefs.getString(Prefs.PROFILE).takeIf { it.isNotBlank() }?.let { json ->
            runCatching { AppJson.decodeFromString(TasteProfile.serializer(), json) }.onSuccess { profile = it }
        }
        restoreFavoritePlaces()
        restoreWarm()
        restoreChat()
        Session.restore()
        NimClient.restore()
    }

    private fun restoreChat() {
        if (!saveHistory) return
        val json = Prefs.getString(Prefs.CHAT_LOG).ifBlank { return }
        runCatching { AppJson.decodeFromString(ListSerializer(ChatMessage.serializer()), json) }
            .onSuccess { saved -> chatMessages.addAll(saved.map { it.copy(fromUser = it.me) }) }
    }

    private fun restoreFavoritePlaces() {
        val json = Prefs.getString(Prefs.FAVORITE_PLACES).ifBlank { return }
        runCatching { AppJson.decodeFromString(ListSerializer(RestaurantResult.serializer()), json) }
            .onSuccess { saved ->
                favoritePlaces.addAll(saved)
                // pushFavorites sends id -> name; without reseeding this map, the first push
                // after a cold start would blank every name the server holds.
                saved.forEach { knownNames[it.id] = it.name }
            }
    }

    private fun restoreWarm() {
        if (!saveHistory) return
        val json = Prefs.getString(Prefs.WARM_RESULTS).ifBlank { return }
        val at = Prefs.getString(Prefs.WARM_ORIGIN).split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (at.size != 2) return
        runCatching { AppJson.decodeFromString(ListSerializer(RestaurantResult.serializer()), json) }
            .onSuccess {
                warmResults = it
                warmOrigin = Coordinates(at[0], at[1])
                warmSavedAt = Prefs.getString(Prefs.WARM_AT).toLongOrNull() ?: 0L
            }
    }
}
