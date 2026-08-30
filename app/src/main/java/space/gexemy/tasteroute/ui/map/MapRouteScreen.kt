package space.gexemy.tasteroute.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.Coordinates
import space.gexemy.tasteroute.data.CrowdRepository
import space.gexemy.tasteroute.data.Entitlements
import space.gexemy.tasteroute.data.GexemyClient
import space.gexemy.tasteroute.data.Instructions
import space.gexemy.tasteroute.data.LocationRepository
import space.gexemy.tasteroute.data.NavRoute
import space.gexemy.tasteroute.data.Perf
import space.gexemy.tasteroute.data.RecommendationEngine
import space.gexemy.tasteroute.data.RecommendationRequest
import space.gexemy.tasteroute.data.RestaurantResult
import space.gexemy.tasteroute.data.SearchMode
import space.gexemy.tasteroute.data.Tier
import space.gexemy.tasteroute.data.driveMinutes
import space.gexemy.tasteroute.data.formatDistanceMeters
import space.gexemy.tasteroute.data.formatDuration
import space.gexemy.tasteroute.data.formatNavDistance
import space.gexemy.tasteroute.data.toNavRoute
import space.gexemy.tasteroute.data.walkMinutes
import space.gexemy.tasteroute.ui.components.BackChip
import space.gexemy.tasteroute.ui.components.LocalRequestLocation
import space.gexemy.tasteroute.ui.components.MatchPill
import space.gexemy.tasteroute.ui.components.PulseBadge
import space.gexemy.tasteroute.ui.components.RatingRow
import space.gexemy.tasteroute.ui.components.RestaurantCard
import space.gexemy.tasteroute.ui.components.WarningNote
import space.gexemy.tasteroute.ui.theme.LocalBrandTones
import space.gexemy.tasteroute.ui.theme.LocalIsDark
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** How tall the map is as a corner popup, and how tall it grows to when tapped. */
private val MAP_POPUP_HEIGHT = 176.dp
private val MAP_OPEN_HEIGHT = 460.dp

/**
 * The route screen, rebuilt around what people actually do with it: glance at the shape of the
 * drive, then decide whether to stop for something on the way.
 *
 * The map used to be the whole screen, which made it the answer to a question nobody asked — you
 * already know where you are going, you tapped it. It is now a popup in the top-right corner that
 * expands when you want it, and the space it gave back is the on-the-way list: real places in the
 * corridor between here and there, fetched the moment this screen opens.
 */
@Composable
fun MapRouteScreen(
    onBack: () -> Unit,
    onNavigate: () -> Unit,
    onOpenPlace: () -> Unit = {},
) {
    val context = LocalContext.current
    val requestLocation = LocalRequestLocation.current
    val tier = AppState.tier
    // The whole on-the-way feature, not a trimmed version of it. A free tier that gets two stops
    // and a card selling the rest is an advert wearing a feature's clothes; either the corridor is
    // yours or the screen does not mention it.
    val corridorAllowed = Entitlements.canSearchCorridor(tier)
    val origin = AppState.origin
    val target = AppState.selectedRestaurant ?: AppState.lastResults.firstOrNull()

    if (origin == null || target == null) {
        EmptyState(
            message = if (origin == null) "Turn on location to see your route" else "Pick a spot to route there",
            actionLabel = if (origin == null) "Allow location" else null,
            onAction = if (origin == null) requestLocation else null,
            onBack = onBack,
        )
        return
    }

    val hasPermission = remember(AppState.locationStatus) { LocationRepository.hasPermission(context) }

    var route by remember(target.id) { mutableStateOf<NavRoute?>(null) }
    var drawnPath by remember(target.id) { mutableStateOf<List<Coordinates>>(emptyList()) }
    var noSteps by remember(target.id) { mutableStateOf(false) }
    var loading by remember(target.id) { mutableStateOf(true) }
    var showSteps by remember(target.id) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    var stops by remember(target.id) { mutableStateOf<List<RestaurantResult>>(emptyList()) }
    var stopsLoading by remember(target.id) { mutableStateOf(true) }

    // Straight-line polylines were a lie about the drive. Real geometry comes from the server;
    // if it can't answer we fall back to the straight line rather than showing nothing.
    val path = drawnPath.ifEmpty { listOf(origin, target.coordinates) }

    LaunchedEffect(target.id, origin) {
        loading = true
        if (GexemyClient.isConfigured) {
            runCatching { GexemyClient.route(origin, target.coordinates, steps = true) }
                .onSuccess { result ->
                    route = result.toNavRoute(target.coordinates, target.name)
                    drawnPath = result.geometry
                    // A path with no maneuvers means the server ignored `steps: true` — an older
                    // build. Drawing the line and letting Start fail would be the worst of both.
                    noSteps = route == null && result.geometry.size >= 2
                }
        }
        AppState.navRoute = route
        loading = false
    }

    // Nobody opens a route screen to be told the distance they already saw on the card. What they
    // do not know is what is on the way, so this runs unprompted rather than behind a button.
    LaunchedEffect(target.id, origin, tier) {
        if (!corridorAllowed) {
            stops = emptyList()
            stopsLoading = false
            return@LaunchedEffect
        }
        stopsLoading = true
        val found = runCatching {
            val corridor = GexemyClient.corridor(origin, target.coordinates, Entitlements.corridorMeters(tier))
            RecommendationEngine.recommend(
                RecommendationRequest(
                    origin = origin,
                    profile = AppState.profile,
                    tier = tier,
                    mode = SearchMode.ON_THE_WAY,
                    destination = target.coordinates,
                    allergens = AppState.allergens.toList(),
                ),
                corridor.places,
            ).results
        }.getOrDefault(emptyList())
        stops = found.filter { it.id != target.id }
        stopsLoading = false
        CrowdRepository.prefetch(stops)
    }

    val visibleStops = remember(stops, corridorAllowed) { if (corridorAllowed) stops.take(8) else emptyList() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackChip(onBack)
            Text(
                "On the way to ${target.name}",
                Modifier.weight(1f).padding(end = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        MapRow(
            origin = origin,
            target = target,
            path = path,
            stops = visibleStops,
            showMyLocation = hasPermission,
            expanded = expanded,
            onToggleExpanded = { expanded = !expanded },
            route = route,
            loading = loading,
            tier = tier,
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            Button(
                onClick = onNavigate,
                enabled = !loading && route != null,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Filled.Navigation, null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Start navigation", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }

            route?.let { built ->
                TextButton(onClick = { showSteps = !showSteps }) {
                    Text("${built.steps.size} steps", maxLines = 1)
                    Icon(
                        if (showSteps) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        null,
                        Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(showSteps) {
                    Column(
                        Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        built.steps.dropLast(1).forEachIndexed { index, step ->
                            val maneuver = built.steps.getOrNull(index + 1) ?: return@forEachIndexed
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    formatNavDistance(step.distanceMeters, AppState.units),
                                    Modifier.width(72.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    Instructions.banner(maneuver),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            if (noSteps) {
                Spacer(Modifier.height(8.dp))
                WarningNote(
                    "The routing service returned a path but no turn directions — that server " +
                        "build predates guidance. Redeploy the API and this becomes turn-by-turn.",
                )
            }
        }

        if (!corridorAllowed) {
            Spacer(Modifier.weight(1f))
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("head") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "On my way",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    if (stopsLoading) {
                        CircularProgressIndicator(
                            Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            items(visibleStops, key = { it.id }) { stop ->
                RestaurantCard(
                    result = stop,
                    userTier = tier,
                    isFavorite = stop.id in AppState.favorites,
                    onToggleFavorite = { AppState.toggleFavorite(stop) },
                    onClick = {
                        AppState.selectedRestaurant = stop
                        onOpenPlace()
                    },
                )
            }
            if (!stopsLoading && stops.isEmpty()) {
                item("none") {
                    Text(
                        "Nothing worth pulling over for on this one.",
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** The drive summary on the left, the map popup on the right, both in one fixed-height band. */
@Composable
private fun MapRow(
    origin: Coordinates,
    target: RestaurantResult,
    path: List<Coordinates>,
    stops: List<RestaurantResult>,
    showMyLocation: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    route: NavRoute?,
    loading: Boolean,
    tier: Tier,
) {
    val height by animateDpAsState(
        if (expanded) MAP_OPEN_HEIGHT else MAP_POPUP_HEIGHT,
        tween(280),
        label = "mapHeight",
    )
    val widthFraction by animateFloatAsState(if (expanded) 1f else 0.54f, tween(280), label = "mapWidth")

    Box(Modifier.fillMaxWidth().height(height).padding(horizontal = 16.dp)) {
        if (!expanded) {
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth(0.44f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    target.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RatingRow(target)
                Text(
                    when {
                        route != null ->
                            "${formatDistanceMeters(route.distanceMeters)} · ${formatDuration(route.durationSeconds)}"
                        loading -> "Working out the drive…"
                        else ->
                            "${formatDistanceMeters(target.distanceMeters)} · ~${driveMinutes(target.distanceMeters)} min drive" +
                                " · ${walkMinutes(target.distanceMeters)} min walk"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MatchPill(target.aiMatchScore)
                PulseBadge(target.pulse, tier)
            }
        }

        Surface(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(widthFraction)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onToggleExpanded),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = if (Perf.richMotion) 6.dp else 0.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                OsmMap(origin, target, path, stops, showMyLocation, Modifier.fillMaxSize())
                Surface(
                    Modifier.align(Alignment.BottomStart).padding(8.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                ) {
                    Text(
                        ATTRIBUTION,
                        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Surface(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                ) {
                    Text(
                        if (expanded) "Shrink" else "Expand",
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun OsmMap(
    origin: Coordinates,
    target: RestaurantResult,
    path: List<Coordinates>,
    stops: List<RestaurantResult>,
    showMyLocation: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val routeColor = LocalBrandTones.current.route.toArgb()
    // The map is the one surface in the app that used to ignore the theme entirely.
    val dark = LocalIsDark.current
    val backdrop = MaterialTheme.colorScheme.surfaceVariant.toArgb()

    val mapView = remember {
        ensureOsmdroid(context)
        MapView(context).apply {
            setTileSource(basemapFor(dark))
            setMultiTouchControls(true)
            // Scaling tiles to dpi doubles the bitmaps a low-end GPU has to push for no real gain.
            isTilesScaledToDpi = Perf.richMotion
            setUseDataConnection(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            // Raster tiles are baked images, so following the theme means swapping the source, not
            // tinting the view. Guarded by name because setTileSource drops the whole tile cache.
            val wanted = basemapFor(dark)
            if (map.tileProvider.tileSource.name() != wanted.name()) map.setTileSource(wanted)
            map.setBackgroundColor(backdrop)

            val here = GeoPoint(origin.lat, origin.lng)
            val there = GeoPoint(target.coordinates.lat, target.coordinates.lng)
            val points = path.map { GeoPoint(it.lat, it.lng) }.ifEmpty { listOf(here, there) }

            map.overlays.filterIsInstance<MyLocationNewOverlay>().forEach { it.disableMyLocation() }
            map.overlays.clear()
            map.overlays.add(
                Polyline(map).apply {
                    setPoints(points)
                    outlinePaint.color = routeColor
                    outlinePaint.strokeWidth = 10f
                }
            )
            map.overlays.add(
                Marker(map).apply {
                    position = there
                    title = target.name
                    snippet = target.address
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
            )
            // The stops are the point of this screen, so they are on the map too — a list of
            // detours with no idea where they sit on the route is half an answer.
            stops.take(8).forEach { stop ->
                map.overlays.add(
                    Marker(map).apply {
                        position = GeoPoint(stop.coordinates.lat, stop.coordinates.lng)
                        title = stop.name
                        alpha = 0.85f
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                )
            }
            if (showMyLocation) {
                map.overlays.add(
                    MyLocationNewOverlay(GpsMyLocationProvider(map.context), map).apply { enableMyLocation() }
                )
            } else {
                map.overlays.add(
                    Marker(map).apply {
                        position = here
                        title = "You"
                        alpha = 0.7f
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                )
            }

            map.post {
                if (target.distanceMeters < 300) {
                    map.controller.setZoom(17.0)
                    map.controller.setCenter(there)
                } else {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.7f), false)
                }
            }
            map.invalidate()
        },
    )
}

@Composable
private fun EmptyState(message: String, actionLabel: String?, onAction: (() -> Unit)?, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        BackChip(onBack, Modifier.align(Alignment.TopStart))
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
