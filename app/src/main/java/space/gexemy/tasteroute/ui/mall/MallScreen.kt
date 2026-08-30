package space.gexemy.tasteroute.ui.mall

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.Mall
import space.gexemy.tasteroute.data.MallFinder
import space.gexemy.tasteroute.data.MallStop
import space.gexemy.tasteroute.data.RestaurantResult
import space.gexemy.tasteroute.data.formatDistanceMeters
import space.gexemy.tasteroute.ui.components.rememberShimmerBrush
import space.gexemy.tasteroute.ui.components.staggeredEntry

/**
 * The mall you are standing in, as a directory by floor.
 *
 * This is deliberately NOT the Discover feed filtered to a building. Discover ranks on taste,
 * distance and rating, all of which are close to meaningless across a hundred metres of the same
 * roof — everything is four minutes away and the ranking is noise. Inside a mall the only question
 * that survives is "what is on which floor", so that is the only ordering here.
 *
 * The floor headings come from OSM's `level` tag and nothing else. Where nobody has mapped a
 * level, the group says so rather than defaulting to the ground floor, for the same reason the
 * menu screen will not invent a dish: a confident wrong answer about a building somebody is
 * currently standing in is worse than an honest gap.
 */
@Composable
fun MallScreen(onBack: () -> Unit, onOpenPlace: () -> Unit) {
    val mall = AppState.mall
    val context = LocalContext.current
    var stops by remember(mall?.osmId) { mutableStateOf<List<MallStop>>(emptyList()) }
    var loading by remember(mall?.osmId) { mutableStateOf(true) }

    LaunchedEffect(mall?.osmId) {
        val here = mall
        if (here == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        stops = MallFinder.directory(here)
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    mall?.name ?: "No mall here",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                )
                Text(
                    subtitle(mall, stops.size, loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val site = mall?.website
        if (site != null) {
            AssistChip(
                onClick = { openUrl(context, site) },
                label = { Text("Mall's own floor plan", maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.Map, null, Modifier.size(18.dp)) },
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (loading) {
                item {
                    // One brush across every placeholder row, same rule as the card skeleton:
                    // four independent sweeps read as flicker, one shared sweep reads as a
                    // single directory being fetched.
                    val bar = rememberShimmerBrush()
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(5) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.fillMaxWidth(0.5f).height(15.dp).clip(CircleShape).background(bar))
                                Box(Modifier.fillMaxWidth(0.3f).height(11.dp).clip(CircleShape).background(bar))
                            }
                        }
                    }
                }
            } else if (mall == null) {
                item { EmptyNote("Nothing to show — this screen needs a mall around you.") }
            } else if (stops.isEmpty()) {
                item {
                    EmptyNote(
                        "OpenStreetMap has " + mall.name + " on the map, but nobody has mapped the " +
                            "units inside it yet. That is a gap in the map rather than an empty " +
                            "mall — Discover will still find whatever is tagged around you.",
                    )
                }
            } else {
                // groupBy preserves encounter order, and the list arrived sorted by levelOrder,
                // so the headings come out bottom floor upward without a second sort.
                stops.groupBy { it.levelLabel }.forEach { (label, onThisFloor) ->
                    item(key = "h-" + label) { FloorHeading(label, onThisFloor.size) }
                    itemsIndexed(onThisFloor, key = { _, s -> s.id }) { index, stop ->
                        StopRow(stop, index) {
                            AppState.selectedRestaurant = stop.asResult()
                            onOpenPlace()
                        }
                    }
                }
            }
        }
    }
}

private fun subtitle(mall: Mall?, count: Int, loading: Boolean): String {
    if (mall == null) return "Open this from Discover when you're in one"
    val where = if (mall.inside) "You're inside" else formatDistanceMeters(mall.distanceMeters) + " away"
    if (loading) return where + " · reading the directory"
    return where + " · " + count + " place" + (if (count == 1) "" else "s") + " mapped"
}

@Composable
private fun FloorHeading(label: String, count: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StopRow(stop: MallStop, index: Int, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().staggeredEntry(index).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Storefront,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stop.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    stop.cuisine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                formatDistanceMeters(stop.distanceMeters),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        Modifier.padding(vertical = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The minimum a detail screen needs to go and fetch the rest.
 *
 * Scores are left at zero on purpose: zero means "unknown" everywhere else in the app and scores
 * neutrally, and a directory row has never been through the ranker. Inventing a match percentage
 * here would put a number on the detail screen that nothing computed.
 */
private fun MallStop.asResult() = RestaurantResult(
    id = id,
    name = name,
    coordinates = coordinates,
    cuisineTags = listOf(cuisine),
    distanceMeters = distanceMeters,
    aiMatchScore = 0,
    aiMatchReasoning = "",
    openingHours = openingHours,
)

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
