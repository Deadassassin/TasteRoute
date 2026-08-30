package space.gexemy.tasteroute.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.LocationRepository
import space.gexemy.tasteroute.data.Mall
import space.gexemy.tasteroute.data.Perf
import space.gexemy.tasteroute.data.RecommendationRequest
import space.gexemy.tasteroute.data.Recommender
import space.gexemy.tasteroute.data.ResultStage
import space.gexemy.tasteroute.data.ResultSource
import space.gexemy.tasteroute.data.SearchMode
import space.gexemy.tasteroute.data.TasteAi
import space.gexemy.tasteroute.ui.components.LocationBanner
import space.gexemy.tasteroute.ui.components.OriginLabel
import space.gexemy.tasteroute.ui.components.PlaceCardSkeleton
import space.gexemy.tasteroute.ui.components.RestaurantCard
import space.gexemy.tasteroute.ui.components.staggeredEntry
import space.gexemy.tasteroute.ui.components.UpsellCard
import space.gexemy.tasteroute.ui.components.WarningNote
import space.gexemy.tasteroute.ui.onboarding.TasteSummaryRow
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "You're in X" — offered, never imposed.
 *
 * The feed underneath is untouched: a mall does not change what is good, it changes what question
 * you are asking, and answering the unasked one by silently filtering Discover to one building is
 * the same mistake as silently widening a radius. One tap opens the directory; ignoring it costs
 * nothing. [Mall.inside] is what gates the confident wording — a mall mapped as a bare node can
 * only ever be "nearby", however close the node happens to be.
 */
@Composable
private fun MallBanner(mall: Mall, onOpen: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Storefront, null, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (mall.inside) "You're in " + mall.name else mall.name + " is right here",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "See what's inside, by floor",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
        }
    }
}

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
    onMall: () -> Unit,
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
    // searchOrigin, not origin. The fix now updates continuously so distances can follow you, but
    // a search keyed on that would be cancelled and restarted every few metres — including an AI
    // re-rank seconds from landing. searchOrigin latches and only moves once you have actually gone
    // somewhere; see AppState.noteFix. The rounding on top is belt and braces at the same precision
    // the Recommender's own cache key uses, below which the request is byte-for-byte identical.
    val origin = if (mode == SearchMode.ANYWHERE) AppState.searchArea else AppState.searchOrigin
    val originKey = origin?.let { String.format(Locale.US, "%.3f,%.3f", it.lat, it.lng) }

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
    // Not an error. "I had to look further than usual" is information, and rendering it in the
    // warning colour next to a working list would read as something having gone wrong.
    var areaNote by remember { mutableStateOf<String?>(null) }
    // A pull re-runs the search for real. The tick is what the effect below keys on, because every
    // other key is an input to the search and none of them changed — the person is asking the same
    // question again and expecting a newer answer.
    var refreshTick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(query, filters, allergens, profile, tier, originKey, mode, destination, refreshTick) {
        if (origin == null) {
            results = emptyList()
            loading = false
            refreshing = false
            return@LaunchedEffect
        }
        // Only typing needs debouncing. The old 120ms applied to the very first search too, and
        // a delay before the first request of a cold start is latency with no keystroke to absorb.
        // Not on a pull. The debounce absorbs keystrokes; there is no keystroke behind a pull,
        // and 400ms of a spinner sitting still is the part people read as the app being stuck.
        if (query.isNotBlank() && !refreshing) delay(400)
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
            areaNote = outcome.note
            personalizing = outcome.pending
            // A warm list is still a loading state — it just happens to be a loading state made of
            // real restaurants. Everything else treats it as "nothing has arrived yet".
            if (outcome.stage > ResultStage.WARM) {
                loading = false
                // The real list has landed. Holding the indicator through the AI re-rank would keep
                // it spinning for twenty seconds over results that are already on screen.
                refreshing = false
            }
            notice = when {
                keepPrevious -> "Couldn't refresh just now. These are your last results."
                outcome.rankedLocally && !outcome.pending -> outcome.error
                else -> null
            }
            emptyMessage = outcome.error.takeIf { outcome.results.isEmpty() && !keepPrevious }
            loading = false
        }
        loading = false
        personalizing = false
        refreshing = false
    }

    val visible by remember(results, tier) {
        derivedStateOf { results.filter { tier.atLeast(it.tierRequired) } }
    }
    val lockedCount by remember(results, visible) {
        derivedStateOf { results.size - visible.size }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Discover",
                    style = MaterialTheme.typography.headlineSmall,
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

        // Only when the map actually contains you. Nothing about the ranking changes inside a
        // mall — it is still the same radius search — so this is a doorway to a different
        // ORDERING (by floor), not a filter on this one.
        AppState.mall?.let { MallBanner(it, onMall) }

        SearchModeRow(mode = mode, onMode = { AppState.searchMode = it })

        when (mode) {
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

            // Searching a corridor is not a way of browsing, it is something you do once you
            // have picked somewhere to go — so it lives on the route screen now. See MapRouteScreen.
            SearchMode.NEARBY, SearchMode.ON_THE_WAY -> Unit
        }

        SearchBar(
            value = query,
            onValueChange = { query = it }
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
            stage == ResultStage.FIRST -> "Here are the closest — still searching the rest of the area…"
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

        areaNote?.takeIf { results.isNotEmpty() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        notice?.takeIf { results.isNotEmpty() }?.let {
            // The reason is a fact about our infrastructure, and the person cannot act on it.
            // What they can act on is what it means for the list in front of them: it is sorted,
            // just not personalised. The cause is in the log and on the diagnostics screen.
            WarningNote(
                if (it.startsWith("Couldn't refresh")) it
                else "Sorted these on your phone. Personalised ranking is unavailable right now."
            )
        }

        // Pull down to ask again. Both of Recommender's caches have to go or this is a no-op:
        // `places` holds the provider's answer for this area and `cache` holds the ranked outcome,
        // and either one alone will happily hand back exactly what is already on screen.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                Recommender.invalidate()
                refreshing = true
                refreshTick++
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
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
                        onToggleFavorite = { AppState.toggleFavorite(r) },
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
}

@Composable
private fun SearchBar(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Sushi, cozy date spot, quick lunch…") },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/**
 * Two segments, not three. "On my way" was a search mode here, which put a corridor search in
 * front of someone who had not yet said where they were going — and, on the free tier, a locked
 * control that could do nothing but sell. It now lives where the question actually comes up: the
 * route screen, once a destination exists, and only for the tiers that have it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchModeRow(mode: SearchMode, onMode: (SearchMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        // icon = {} on both. The default icon slot draws a checkmark on whichever segment is
        // selected, so selecting one widens it and shoves the other sideways — the row visibly
        // reflows every time you touch it.
        SegmentedButton(
            selected = mode == SearchMode.NEARBY,
            onClick = { onMode(SearchMode.NEARBY) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = {},
        ) { SegmentLabel("Near me") }
        SegmentedButton(
            selected = mode == SearchMode.ANYWHERE,
            onClick = { onMode(SearchMode.ANYWHERE) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = {},
        ) { SegmentLabel("Anywhere") }
    }
}

/** One line, ellipsised, never wrapped. A segment that grows to fit its text is not a segment. */
@Composable
private fun SegmentLabel(text: String) {
    Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
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
