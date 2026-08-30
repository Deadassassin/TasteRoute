package com.example.tasteroute.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.Entitlements
import com.example.tasteroute.data.LocationRepository
import com.example.tasteroute.data.Perf
import com.example.tasteroute.data.RecommendationRequest
import com.example.tasteroute.data.Recommender
import com.example.tasteroute.data.RestaurantResult
import com.example.tasteroute.data.ResultStage
import com.example.tasteroute.data.ResultSource
import com.example.tasteroute.data.SearchMode
import com.example.tasteroute.data.TasteAi
import com.example.tasteroute.ui.components.LocationBanner
import com.example.tasteroute.ui.components.OriginLabel
import com.example.tasteroute.ui.components.PlaceCardSkeleton
import com.example.tasteroute.ui.components.RestaurantCard
import com.example.tasteroute.ui.components.staggeredEntry
import com.example.tasteroute.ui.components.UpsellCard
import com.example.tasteroute.ui.components.WarningNote
import com.example.tasteroute.ui.onboarding.TasteSummaryRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val chipDefs = listOf(
    "nearby" to "Nearby",
    "top_rated" to "Top Rated",
    "vegan" to "Vegan",
    "budget" to "Budget-friendly",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPlace: () -> Unit,
    onUpgrade: () -> Unit,
    onRetune: () -> Unit,
    onTableSync: () -> Unit,
    onEditAllergens: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tier = AppState.tier
    val profile = AppState.profile
    val filters = AppState.activeFilters.toList()
    val allergens = AppState.allergens.toList()
    val mode = AppState.searchMode
    val destination = AppState.destination
    // Distances are measured from whatever you are browsing, not from your body — a place 400m
    // from the hotel you are searching is 400m away, however far the hotel is from you.
    val origin = if (mode == SearchMode.ANYWHERE) AppState.searchArea else AppState.origin

    // Seeded, not empty. lastResults survives a tab switch and warmResults survives a restart, so
    // the very first frame of Discover has food in it instead of a skeleton or — worse, for the
    // ~120ms before the debounce fires — the "no matches" empty state.
    var results by remember { mutableStateOf(AppState.lastResults.ifEmpty { AppState.warmResults }) }
    var stage by remember {
        mutableStateOf(if (AppState.lastResults.isEmpty()) ResultStage.WARM else ResultStage.LOCAL)
    }
    var loading by remember { mutableStateOf(true) }
    var personalizing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var attribution by remember { mutableStateOf<String?>(null) }
    var emptyMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, filters, allergens, profile, tier, origin, mode, destination) {
        if (origin == null || (mode == SearchMode.ON_THE_WAY && destination == null)) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        delay(if (query.isBlank()) 120 else 500) // debounce typing before hitting the network
        loading = true
        Recommender.stream(
            RecommendationRequest(
                origin = origin,
                profile = profile,
                query = query,
                source = ResultSource.FILTER_SEARCH,
                tier = tier,
                appliedFilters = filters,
                allergens = allergens,
                mode = mode,
                destination = destination,
            ),
            cityLabel = AppState.cityLabel,
        ).collect { outcome ->
            // A failed refresh must not wipe a list that is still perfectly good. Overpass is a
            // free community service and rate-limits hard; blanking the screen every time it says
            // no is exactly how this turns into "it stopped returning anything".
            val keepPrevious = outcome.results.isEmpty() && outcome.error != null && results.isNotEmpty()
            if (!keepPrevious) {
                results = outcome.results
                AppState.lastResults = outcome.results
            }
            AppState.routeGeometry = outcome.routeGeometry
            if (outcome.source != null) attribution = outcome.source.label
            stage = outcome.stage
            personalizing = outcome.pending
            // A warm list is still a loading state — it just happens to be a loading state made of
            // real restaurants. Everything else treats it as "nothing has arrived yet".
            if (outcome.stage > ResultStage.WARM) loading = false
            notice = when {
                keepPrevious -> "Couldn't refresh just now — showing your last results. ${outcome.error}"
                outcome.rankedLocally && !outcome.pending -> outcome.error
                else -> null
            }
            emptyMessage = outcome.error.takeIf { outcome.results.isEmpty() && !keepPrevious }
            loading = false
        }
        loading = false
        personalizing = false
    }

    val visible = remember(results, tier) { results.filter { tier.atLeast(it.tierRequired) } }
    val lockedCount = results.size - visible.size

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Discover",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (mode == SearchMode.ANYWHERE && AppState.searchAreaLabel.isNotBlank()) {
                    Text(
                        AppState.searchAreaLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    OriginLabel()
                }
                IconButton(onClick = onTableSync) {
                    Icon(Icons.Filled.Group, "Table Sync", tint = MaterialTheme.colorScheme.primary)
                }
            }
            TasteSummaryRow(TasteAi.summarize(profile), onRetune)
        }

        LocationBanner()

        SearchModeRow(
            mode = mode,
            tier = tier,
            onMode = { next ->
                if (next == SearchMode.ON_THE_WAY && !Entitlements.canSearchCorridor(tier)) onUpgrade()
                else AppState.searchMode = next
            },
        )

        when (mode) {
            SearchMode.ON_THE_WAY -> PlaceField(
                label = AppState.destinationLabel,
                placeholder = "Where are you heading?",
                onLabel = { AppState.destinationLabel = it },
                onResolve = {
                    scope.launch {
                        val hit = LocationRepository.geocode(context, AppState.destinationLabel)
                        if (hit == null) {
                            AppState.destination = null
                            emptyMessage = "Couldn't find that address."
                        } else {
                            AppState.destination = hit.first
                            AppState.destinationLabel = hit.second
                        }
                    }
                },
            )

            SearchMode.ANYWHERE -> PlaceField(
                label = AppState.searchAreaLabel,
                placeholder = "City, neighbourhood or address",
                onLabel = { AppState.searchAreaLabel = it },
                onResolve = {
                    scope.launch {
                        val hit = LocationRepository.geocode(context, AppState.searchAreaLabel)
                        if (hit == null) {
                            AppState.searchArea = null
                            emptyMessage = "Couldn't find that place."
                        } else {
                            AppState.searchArea = hit.first
                            AppState.searchAreaLabel = hit.second
                        }
                    }
                },
            )

            SearchMode.NEARBY -> Unit
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Sushi, cozy date spot, quick lunch…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chipDefs, key = { it.first }) { (id, label) ->
                FilterChip(
                    selected = id in AppState.activeFilters,
                    onClick = { if (!AppState.activeFilters.remove(id)) AppState.activeFilters.add(id) },
                    label = { Text(label) },
                    shape = CircleShape,
                )
            }
        }

        if (allergens.isNotEmpty()) {
            AllergenNotice(allergens, onEditAllergens)
        }

        // One line for every kind of "not finished yet". A spinner over a list that already has
        // real cards in it has to say WHICH part is still moving, or it reads as the whole screen
        // being broken rather than as the last 10% arriving.
        val progressLabel = when {
            visible.isEmpty() -> null
            stage == ResultStage.WARM -> "Showing what was here last time — checking for changes…"
            personalizing -> "Sorting these for your taste…"
            else -> null
        }
        progressLabel?.let { label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        notice?.takeIf { results.isNotEmpty() }?.let {
            WarningNote(
                if (it.startsWith("Couldn't refresh")) it
                else "Ranked these nearby places locally — the AI ranker is unavailable ($it)"
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            if (visible.isEmpty() && (loading || origin == null)) {
                items(Perf.skeletonCount, key = { "skeleton-$it" }, contentType = { "skeleton" }) {
                    PlaceCardSkeleton(Modifier.animateItem())
                }
            }
            // Keyed items + animateItem is what turns the skeleton→results swap into a cross-fade
            // and makes the AI re-rank visibly reorder the list instead of snapping to a new one.
            itemsIndexed(visible, key = { _, r -> r.id }, contentType = { _, _ -> "place" }) { index, r ->
                RestaurantCard(
                    modifier = Modifier.animateItem().staggeredEntry(index),
                    result = r,
                    userTier = tier,
                    isFavorite = r.id in AppState.favorites,
                    onToggleFavorite = { AppState.toggleFavorite(r.id) },
                    onClick = {
                        AppState.selectedRestaurant = r
                        onOpenPlace()
                    },
                )
            }
            if (lockedCount > 0) {
                item("upsell", contentType = "upsell") { UpsellCard(lockedCount, onUpgrade) }
            }
            attribution?.takeIf { results.isNotEmpty() }?.let { credit ->
                item("attribution", contentType = "credit") {
                    Text(
                        credit,
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (results.isEmpty() && !loading && origin != null) {
                item("empty", contentType = "empty") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            emptyMessage ?: when {
                                mode == SearchMode.ON_THE_WAY && destination == null ->
                                    "Add where you're heading and I'll find stops along the way."
                                mode == SearchMode.ANYWHERE && AppState.searchArea == null ->
                                    "Name a city or address and I'll rank its places against your taste."
                                else -> "No matches in range — try fewer filters."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchModeRow(mode: SearchMode, tier: com.example.tasteroute.data.Tier, onMode: (SearchMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = mode == SearchMode.NEARBY,
            onClick = { onMode(SearchMode.NEARBY) },
            shape = SegmentedButtonDefaults.itemShape(0, 3),
        ) { Text("Near me") }
        SegmentedButton(
            selected = mode == SearchMode.ON_THE_WAY,
            onClick = { onMode(SearchMode.ON_THE_WAY) },
            shape = SegmentedButtonDefaults.itemShape(1, 3),
        ) {
            Text(if (Entitlements.canSearchCorridor(tier)) "On my way" else "On my way · Plus")
        }
        SegmentedButton(
            selected = mode == SearchMode.ANYWHERE,
            onClick = { onMode(SearchMode.ANYWHERE) },
            shape = SegmentedButtonDefaults.itemShape(2, 3),
        ) { Text("Anywhere") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceField(label: String, placeholder: String, onLabel: (String) -> Unit, onResolve: () -> Unit) {
    OutlinedTextField(
        value = label,
        onValueChange = onLabel,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onResolve() }),
        trailingIcon = {
            TextButton(onClick = onResolve) { Text("Set") }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun AllergenNotice(allergens: List<String>, onEdit: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Hiding places reported unsafe for ${allergens.joinToString(", ")}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onEdit) { Text("Edit") }
        }
    }
}
