package com.example.tasteroute.ui.map

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.tasteroute.R
import com.example.tasteroute.data.Announcer
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.Coordinates
import com.example.tasteroute.data.GexemyClient
import com.example.tasteroute.data.Instructions
import com.example.tasteroute.data.NavLocation
import com.example.tasteroute.data.NavProgress
import com.example.tasteroute.data.NavRoute
import com.example.tasteroute.data.NavigationEngine
import com.example.tasteroute.data.Perf
import com.example.tasteroute.data.RouteStep
import com.example.tasteroute.data.Voice
import com.example.tasteroute.data.arrivalClock
import com.example.tasteroute.data.formatDuration
import com.example.tasteroute.data.formatNavDistance
import com.example.tasteroute.data.toNavRoute
import com.example.tasteroute.ui.theme.LocalBrandTones
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Full turn-by-turn guidance, inside the app.
 *
 * The map is oriented to the driver's course rather than north, because a turn instruction is only
 * readable when "right" on screen is "right" out of the windscreen. Everything that changes every
 * second — the snapped puck, the camera, the maneuver card — is driven from one [NavProgress] so
 * the map and the words can never disagree.
 */
@Composable
fun NavigationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val target = AppState.selectedRestaurant

    var route by remember { mutableStateOf(AppState.navRoute) }
    var progress by remember { mutableStateOf<NavProgress?>(null) }
    var following by remember { mutableStateOf(true) }
    var rerouting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var engine by remember { mutableStateOf(route?.let { NavigationEngine(it) }) }
    val announcer = remember(route) { Announcer(AppState.units) }

    // A screen that goes dark mid-turn is worse than no navigation at all.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        Voice.attach(context)
        onDispose {
            view.keepScreenOn = false
            Voice.release()
        }
    }

    if (target == null) {
        NavFailure("Nothing selected to navigate to", onBack)
        return
    }

    // Entered without a prepared route (deep link, or a stale one): fetch before guiding.
    LaunchedEffect(target.id) {
        if (route != null) return@LaunchedEffect
        runCatching { GexemyClient.route(AppState.origin ?: target.coordinates, target.coordinates, steps = true) }
            .onSuccess { result ->
                val built = result.toNavRoute(target.coordinates, target.name)
                if (built == null) {
                    failure = "The routing service returned a path but no turn directions. " +
                        "That server build predates guidance — redeploy the API."
                }
                else {
                    route = built
                    engine = NavigationEngine(built)
                    AppState.navRoute = built
                }
            }
            .onFailure { failure = it.message ?: "Couldn't build a route." }
    }

    LaunchedEffect(route) {
        val active = engine ?: return@LaunchedEffect
        NavLocation.fixes(context).collect { fix ->
            val update = active.update(fix.at, fix.bearing, fix.speedMps)
            progress = update
            announcer.next(update)?.let { Voice.say(it) }

            if (update.offRoute && !rerouting) {
                rerouting = true
                val rebuilt = runCatching { GexemyClient.route(fix.at, target.coordinates, steps = true) }
                    .getOrNull()?.toNavRoute(target.coordinates, target.name)
                // Clear the spinner before swapping the route: assigning `route` cancels this
                // collector, so anything after it would never run.
                rerouting = false
                if (rebuilt != null) {
                    Voice.say("Rerouting.")
                    engine = NavigationEngine(rebuilt)
                    AppState.navRoute = rebuilt
                    route = rebuilt
                }
            }
        }
    }

    val current = route
    if (current == null) {
        NavLoading(failure, onBack)
        return
    }

    Box(Modifier.fillMaxSize()) {
        NavMap(
            route = current,
            progress = progress,
            following = following,
            onUserPan = { following = false },
            modifier = Modifier.fillMaxSize(),
        )

        ManeuverCard(
            progress = progress,
            destination = current.destinationName,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundControl(
                icon = if (AppState.navVoice) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                description = if (AppState.navVoice) "Mute guidance" else "Unmute guidance",
                onClick = {
                    AppState.updateNavVoice(!AppState.navVoice)
                    if (!AppState.navVoice) Voice.stop()
                },
            )
            AnimatedVisibility(!following) {
                RoundControl(Icons.Filled.MyLocation, "Recenter") { following = true }
            }
        }

        AnimatedVisibility(
            visible = rerouting,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Rerouting…", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        TripBar(progress, onBack, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ManeuverCard(progress: NavProgress?, destination: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().padding(12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = if (Perf.richMotion) 10.dp else 0.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    maneuverIcon(progress?.maneuver),
                    contentDescription = null,
                    Modifier.size(40.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        progress?.let { formatNavDistance(it.distanceToManeuver, AppState.units) } ?: "Starting…",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        progress?.maneuver?.let { Instructions.banner(it) } ?: "Heading to $destination",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            progress?.next?.let { next ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(maneuverIcon(next), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "then ${Instructions.banner(next)}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TripBar(progress: NavProgress?, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (Perf.richMotion) 12.dp else 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (progress?.arrived == true) {
                    Text("You've arrived", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        progress?.let { arrivalClock(it.secondsRemaining) } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        progress?.let {
                            "${formatDuration(it.secondsRemaining)} · ${formatNavDistance(it.distanceRemaining, AppState.units)}"
                        } ?: "Waiting for a fix",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onEnd,
                modifier = Modifier.height(48.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (progress?.arrived == true) "Done" else "End")
            }
        }
    }
}

@Composable
private fun RoundControl(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shadowElevation = 4.dp) {
        IconButton(onClick = onClick) {
            Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun NavLoading(failure: String?, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (failure == null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Building your route…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    failure,
                    Modifier.padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

@Composable
private fun NavFailure(message: String, onBack: () -> Unit) = NavLoading(message, onBack)

/**
 * Course-up map with a snapped puck. Overlays are created once and mutated per fix — rebuilding
 * them every second allocates a polyline of a few thousand points into every frame budget.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun NavMap(
    route: NavRoute,
    progress: NavProgress?,
    following: Boolean,
    onUserPan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tones = LocalBrandTones.current
    val aheadColor = tones.route.toArgb()
    val behindColor = tones.muted.copy(alpha = 0.75f).toArgb()

    val mapView = remember {
        MapView(context).apply {
            setTileSource(BASEMAP)
            setMultiTouchControls(true)
            isTilesScaledToDpi = Perf.richMotion
            setUseDataConnection(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        }
    }

    val ahead = remember { Polyline() }
    val behind = remember { Polyline() }
    val puck = remember { Marker(mapView) }
    val destination = remember { Marker(mapView) }

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

    // Any touch on the map is the driver taking over; the camera stops chasing until they recenter.
    DisposableEffect(mapView) {
        mapView.setOnTouchListener { _, _ ->
            onUserPan()
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    LaunchedEffect(route) {
        val points = route.geometry.map { GeoPoint(it.lat, it.lng) }
        ahead.setPoints(points)
        ahead.outlinePaint.color = aheadColor
        ahead.outlinePaint.strokeWidth = 14f
        behind.outlinePaint.color = behindColor
        behind.outlinePaint.strokeWidth = 14f
        destination.position = GeoPoint(route.destination.lat, route.destination.lng)
        destination.title = route.destinationName
        destination.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        puck.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        puck.isFlat = true
        puck.setInfoWindow(null)
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_nav_puck)?.let {
            androidx.core.graphics.drawable.DrawableCompat.setTint(it, aheadColor)
            puck.icon = it
        }

        mapView.overlays.clear()
        mapView.overlays.add(behind)
        mapView.overlays.add(ahead)
        mapView.overlays.add(destination)
        mapView.overlays.add(puck)
        mapView.controller.setZoom(NAV_ZOOM)
        mapView.invalidate()
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            val fix = progress ?: return@AndroidView
            val here = GeoPoint(fix.snapped.lat, fix.snapped.lng)
            puck.position = here
            // The puck points up when the map rotates with the course, and rotates when it doesn't.
            puck.rotation = if (following) 0f else fix.courseDegrees

            val split = splitAt(route.geometry, fix.vertexIndex)
            // Only a short trail is drawn behind: the full travelled polyline grows without bound
            // and is redrawn every second.
            behind.setPoints(split.first.takeLast(TRAIL_VERTICES).map { GeoPoint(it.lat, it.lng) } + here)
            ahead.setPoints(listOf(here) + split.second.map { GeoPoint(it.lat, it.lng) })

            if (following) {
                map.mapOrientation = -fix.courseDegrees
                map.controller.animateTo(here, NAV_ZOOM, CAMERA_MS)
            }
            map.invalidate()
        },
    )
}

/** Vertices strictly behind and strictly ahead of the driver, for the two-tone route line. */
private fun splitAt(geometry: List<Coordinates>, index: Int): Pair<List<Coordinates>, List<Coordinates>> {
    val cut = index.coerceIn(0, geometry.lastIndex)
    return geometry.take(cut + 1) to geometry.drop(cut + 1)
}

private fun maneuverIcon(step: RouteStep?): ImageVector {
    val maneuver = step?.maneuver ?: return Icons.Filled.Navigation
    val left = maneuver.modifier.contains("left")
    return when (maneuver.type) {
        "depart" -> Icons.Filled.Navigation
        "arrive" -> Icons.Filled.Flag
        "merge" -> Icons.Filled.Merge
        "fork", "on ramp", "off ramp" -> if (left) Icons.Filled.ForkLeft else Icons.Filled.ForkRight
        "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" ->
            if (left) Icons.Filled.RoundaboutLeft else Icons.Filled.RoundaboutRight
        else -> when (maneuver.modifier) {
            "left" -> Icons.Filled.TurnLeft
            "right" -> Icons.Filled.TurnRight
            "slight left" -> Icons.Filled.TurnSlightLeft
            "slight right" -> Icons.Filled.TurnSlightRight
            "sharp left" -> Icons.Filled.TurnSharpLeft
            "sharp right" -> Icons.Filled.TurnSharpRight
            "uturn" -> Icons.Filled.UTurnLeft
            else -> Icons.Filled.Straight
        }
    }
}

private const val NAV_ZOOM = 17.5
private const val CAMERA_MS = 900L
private const val TRAIL_VERTICES = 150
