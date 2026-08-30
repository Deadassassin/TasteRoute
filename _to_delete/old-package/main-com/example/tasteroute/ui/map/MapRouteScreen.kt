package com.example.tasteroute.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.Coordinates
import com.example.tasteroute.data.GexemyClient
import com.example.tasteroute.data.Instructions
import com.example.tasteroute.data.LocationRepository
import com.example.tasteroute.data.NavRoute
import com.example.tasteroute.data.Perf
import com.example.tasteroute.data.RestaurantResult
import com.example.tasteroute.data.driveMinutes
import com.example.tasteroute.data.formatDistanceMeters
import com.example.tasteroute.data.formatDuration
import com.example.tasteroute.data.formatNavDistance
import com.example.tasteroute.data.toNavRoute
import com.example.tasteroute.data.walkMinutes
import com.example.tasteroute.ui.components.BackChip
import com.example.tasteroute.ui.components.LocalRequestLocation
import com.example.tasteroute.ui.components.MatchPill
import com.example.tasteroute.ui.components.PulseBadge
import com.example.tasteroute.ui.components.RatingRow
import com.example.tasteroute.ui.components.WarningNote
import com.example.tasteroute.ui.theme.LocalBrandTones
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Route preview: the whole drive at a glance, the turn list if you want it, and one button into
 * guidance. It no longer hands off to an external maps app — [NavigationScreen] does the driving,
 * so the route fetched here is the one navigation starts from and nothing is fetched twice.
 */
@Composable
fun MapRouteScreen(onBack: () -> Unit, onNavigate: () -> Unit) {
    val context = LocalContext.current
    val requestLocation = LocalRequestLocation.current
    val tier = AppState.tier
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

    Box(Modifier.fillMaxSize()) {
        OsmMap(origin, target, path, hasPermission, Modifier.fillMaxSize())

        BackChip(onBack, Modifier.align(Alignment.TopStart))

        Surface(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ) {
            Text(
                ATTRIBUTION,
                Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = if (Perf.richMotion) 10.dp else 0.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(target.name, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(2.dp))
                        RatingRow(target)
                    }
                    MatchPill(target.aiMatchScore)
                }
                target.address?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                PulseBadge(target.pulse, tier)
                Spacer(Modifier.height(10.dp))

                val built = route
                Text(
                    when {
                        built != null ->
                            "${formatDistanceMeters(built.distanceMeters)} by road · ${formatDuration(built.durationSeconds)} drive"
                        loading -> "Working out the drive…"
                        else ->
                            "${formatDistanceMeters(target.distanceMeters)} · ~${driveMinutes(target.distanceMeters)} min drive" +
                                " · ${walkMinutes(target.distanceMeters)} min walk"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (built != null) {
                    TextButton(onClick = { showSteps = !showSteps }, modifier = Modifier.padding(top = 2.dp)) {
                        Text("${built.steps.size} steps")
                        Icon(
                            if (showSteps) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            null,
                            Modifier.size(18.dp),
                        )
                    }
                    AnimatedVisibility(showSteps) {
                        Column(
                            Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
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

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onNavigate,
                    enabled = !loading && route != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
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
                    Text("Start navigation", style = MaterialTheme.typography.labelLarge)
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
    showMyLocation: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val routeColor = LocalBrandTones.current.route.toArgb()

    val mapView = remember {
        MapView(context).apply {
            setTileSource(BASEMAP)
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
            val here = GeoPoint(origin.lat, origin.lng)
            val there = GeoPoint(target.coordinates.lat, target.coordinates.lng)
            val points = path.map { GeoPoint(it.lat, it.lng) }.ifEmpty { listOf(here, there) }

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
