package space.gexemy.tasteroute.data.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.builtins.ListSerializer
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.local.Prefs
import space.gexemy.tasteroute.data.network.NimClient

private const val WARM_LIMIT = 12

/**
 * App-wide observable state facade. Delegates to specific state holders.
 */
object AppState {
    // Delegates to LocationState
    val origin get() = LocationState.origin
    val searchOrigin get() = LocationState.searchOrigin
    var cityLabel by LocationState::cityLabel
    var mall by LocationState::mall
    var locationStatus by LocationState::locationStatus
    fun noteFix(fix: Coordinates) = LocationState.noteFix(fix)

    // Delegates to ChatState
    var assistantBusy by ChatState::assistantBusy
    val chatMessages = ChatState.chatMessages
    fun addChat(message: ChatMessage) = ChatState.addChat(message)
    fun replaceChat(index: Int, message: ChatMessage) = ChatState.replaceChat(index, message)
    fun clearChat() = ChatState.clearChat()

    // Delegates to UserState
    var tier by UserState::tier
    var profile by UserState::profile
    val allergens = UserState.allergens
    val favorites = UserState.favorites
    val favoritePlaces = UserState.favoritePlaces
    val knownNames = UserState.knownNames
    var aiQueriesUsedToday by UserState::aiQueriesUsedToday
    var selectedRestaurant by UserState::selectedRestaurant
    var lastResults by UserState::lastResults
    var tasteText by UserState::tasteText
    fun toggleFavorite(id: String) = UserState.toggleFavorite(id)
    fun toggleFavorite(place: RestaurantResult) = UserState.toggleFavorite(place)
    fun toggleAllergen(name: String) = UserState.toggleAllergen(name)
    fun applyProfile(next: TasteProfile, text: String? = null) = UserState.applyProfile(next, text)
    fun persistProfile() = UserState.persistProfile()
    fun rememberNames(results: List<RestaurantResult>) = UserState.rememberNames(results)

    // Delegates to PreferenceState
    var themeMode by PreferenceState::themeMode
    var fontChoice by PreferenceState::fontChoice
    var fontScale by PreferenceState::fontScale
    var welcomed by PreferenceState::welcomed
    var onboarded by PreferenceState::onboarded
    var preciseLocation by PreferenceState::preciseLocation
    var saveHistory by PreferenceState::saveHistory
    var navVoice by PreferenceState::navVoice
    var voiceName by PreferenceState::voiceName
    var voiceSpeed by PreferenceState::voiceSpeed
    var units by PreferenceState::units
    fun setTheme(mode: ThemeMode) = PreferenceState.setTheme(mode)
    fun setFont(choice: FontChoice) = PreferenceState.setFont(choice)
    fun updateFontScale(scale: Float) = PreferenceState.updateFontScale(scale)
    fun setWelcomed() = PreferenceState.setWelcomed()
    fun updateNavVoice(on: Boolean) = PreferenceState.updateNavVoice(on)
    fun updateVoiceName(name: String) = PreferenceState.updateVoiceName(name)
    fun updateVoiceSpeed(rate: Float) = PreferenceState.updateVoiceSpeed(rate)
    fun updateUnits(value: Units) = PreferenceState.updateUnits(value)

    // Delegates to SearchState
    var searchMode by SearchState::searchMode
    var searchArea by SearchState::searchArea
    var searchAreaLabel by SearchState::searchAreaLabel
    var destination by SearchState::destination
    var destinationLabel by SearchState::destinationLabel
    var routeGeometry by SearchState::routeGeometry
    var navRoute by SearchState::navRoute

    // Warm results state (kept here for now or could move to UserState)
    var warmResults by mutableStateOf<List<RestaurantResult>>(emptyList())
        private set
    var warmOrigin by mutableStateOf<Coordinates?>(null)
        private set
    var warmSavedAt = 0L
        private set

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

    private fun RestaurantResult.slimForWarm() = copy(
        photos = emptyList(), allergens = emptyList(), sources = emptyList(), facts = null, yelp = null,
    )

    fun forgetWarm() {
        warmResults = emptyList()
        warmOrigin = null
        warmSavedAt = 0L
        Prefs.remove(Prefs.WARM_RESULTS, Prefs.WARM_ORIGIN, Prefs.WARM_AT)
    }

    fun updateSaveHistory(on: Boolean) {
        saveHistory = on
        Prefs.put(Prefs.HISTORY, on)
        if (!on) {
            forgetWarm()
            Prefs.remove(Prefs.CHAT_LOG)
        }
    }

    fun restoreCritical() {
        themeMode = runCatching { ThemeMode.valueOf(Prefs.getString(Prefs.THEME, ThemeMode.SYSTEM.name)) }.getOrDefault(ThemeMode.SYSTEM)
        fontChoice = runCatching { FontChoice.valueOf(Prefs.getString(Prefs.FONT, FontChoice.SYSTEM.name)) }.getOrDefault(FontChoice.SYSTEM)
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
        units = runCatching { Units.valueOf(Prefs.getString(Prefs.UNITS, Units.AUTO.name)) }.getOrDefault(Units.AUTO)
        allergens.addAll(Prefs.getStringSet(Prefs.ALLERGENS))
        favorites.addAll(Prefs.getStringSet(Prefs.FAVORITES))
        Session.restoreToken()
    }

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
