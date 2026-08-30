package space.gexemy.tasteroute.data.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.builtins.ListSerializer
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.local.Prefs
import space.gexemy.tasteroute.data.repository.Recommender

private const val FAVORITE_LIMIT = 100

object UserState {
    var tier by mutableStateOf(Tier.FREE)
    var profile by mutableStateOf(
        TasteProfile(
            preferredCuisines = listOf("Thai", "Japanese", "Mexican"),
            priceComfort = 2,
            vibeTags = listOf("Cozy", "Casual"),
        )
    )
    val allergens = mutableStateListOf<String>()
    val favorites = mutableStateListOf<String>()
    val favoritePlaces = mutableStateListOf<RestaurantResult>()
    val knownNames = mutableStateMapOf<String, String>()
    var aiQueriesUsedToday by mutableStateOf(0)
    var selectedRestaurant by mutableStateOf<RestaurantResult?>(null)
    var lastResults by mutableStateOf<List<RestaurantResult>>(emptyList())
    var tasteText by mutableStateOf("")

    fun toggleFavorite(id: String) {
        if (favorites.remove(id)) {
            favoritePlaces.removeAll { it.id == id }
        } else {
            favorites.add(id)
        }
        persistFavorites()
    }

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

    private fun RestaurantResult.slimForWarm() = copy(
        photos = emptyList(), allergens = emptyList(), sources = emptyList(), facts = null, yelp = null,
    )

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
        Prefs.put(Prefs.ONBOARDED, PreferenceState.onboarded)
    }

    fun rememberNames(results: List<RestaurantResult>) {
        results.forEach { knownNames[it.id] = it.name }
    }
}
