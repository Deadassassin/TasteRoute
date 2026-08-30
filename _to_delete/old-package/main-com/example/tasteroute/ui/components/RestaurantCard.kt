package com.example.tasteroute.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tasteroute.data.AllergenSignal
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.CrowdPulse
import com.example.tasteroute.data.Entitlements
import com.example.tasteroute.data.Perf
import com.example.tasteroute.data.RecommendationEngine
import com.example.tasteroute.data.RestaurantResult
import com.example.tasteroute.data.Tier
import com.example.tasteroute.data.YelpInfo
import com.example.tasteroute.data.formatCount
import com.example.tasteroute.data.formatDistanceMeters
import com.example.tasteroute.ui.theme.Genie
import com.example.tasteroute.ui.theme.LocalBrandTones

/** The one renderer for a [RestaurantResult] — feed rows, chat cards and the map sheet reuse these. */
@Composable
fun RestaurantCard(
    result: RestaurantResult,
    userTier: Tier,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    showReasoning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The genie has to know which card it came out of, so the card records where it is and hands
    // that to the transition on tap. Cheaper than a shared-element framework and exact.
    val view = LocalView.current
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clickable {
                Genie.anchorTo(bounds, view.width, view.height)
                onClick()
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Perf.cardElevationDp.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row {
                PhotoThumb(result, Modifier.size(88.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            result.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
                            Icon(
                                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remove from saved" else "Save",
                                tint = if (isFavorite) LocalBrandTones.current.favorite else LocalBrandTones.current.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    RatingRow(result)
                    result.address?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MatchPill(result.aiMatchScore)
                        PulseBadge(result.pulse, userTier)
                    }
                }
            }
            AllergenRow(result.allergens, AppState.allergens)
            if (showReasoning && result.reasoningParts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                result.reasoningParts.forEach { part ->
                    Row(Modifier.padding(start = 2.dp, top = 2.dp)) {
                        Text("•  ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            part,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Placeholder rows so the first paint has structure instead of an empty screen. */
@Composable
fun PlaceCardSkeleton(modifier: Modifier = Modifier) {
    // One brush for the whole card so every bar sweeps in step — four independent shimmers read as
    // flicker, one synchronised sweep reads as a single thing loading.
    val bar = rememberShimmerBrush()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.padding(14.dp)) {
            Box(Modifier.size(88.dp).clip(MaterialTheme.shapes.medium).background(bar))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.fillMaxWidth(0.6f).height(15.dp).clip(CircleShape).background(bar))
                Box(Modifier.fillMaxWidth(0.85f).height(11.dp).clip(CircleShape).background(bar))
                Box(Modifier.fillMaxWidth(0.4f).height(11.dp).clip(CircleShape).background(bar))
            }
        }
    }
}

/**
 * Stars on a card come from our own reviews when we have any, and from Yelp when we don't — most
 * OSM places carry no rating at all, and an unrated card is the reason a good restaurant gets
 * scrolled past. Yelp's number is always labelled as Yelp's; the two are never averaged together.
 */
@Composable
fun RatingRow(result: RestaurantResult) {
    val yelp: YelpInfo? = result.yelp?.takeIf { it.usable && result.rating <= 0 }
    val meta = remember(result) {
        buildList {
            when {
                result.rating > 0 -> {
                    add(result.rating.toString())
                    if (result.reviewCount > 0) add("(${formatCount(result.reviewCount)})")
                }
                yelp != null -> {
                    add("${yelp.rating} on Yelp")
                    if (yelp.reviewCount > 0) add("(${formatCount(yelp.reviewCount)})")
                }
            }
            if (result.detourMeters > 0) add(RecommendationEngine.detourLabel(result.detourMeters))
            else add(formatDistanceMeters(result.distanceMeters))
            val price = if (result.priceTier > 0) "$".repeat(result.priceTier) else yelp?.price.orEmpty()
            if (price.isNotBlank()) add(price)
            result.cuisineTags.firstOrNull()?.let { add(it) }
        }.joinToString(" · ")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (result.rating > 0 || yelp != null) {
            Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(3.dp))
        }
        Text(
            meta,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MatchPill(score: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            "$score% match",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Real crowd data, not an estimate. Pro sees the number; everyone else sees that a live reading
 * exists and how well backed it is — a teaser that is still honest about the underlying data.
 */
@Composable
fun PulseBadge(pulse: CrowdPulse?, userTier: Tier, modifier: Modifier = Modifier) {
    if (pulse == null) return
    val tones = LocalBrandTones.current
    val unlocked = Entitlements.canSeeLivePulse(userTier)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (unlocked) tones.live else tones.muted))
            Spacer(Modifier.width(5.dp))
            if (unlocked) {
                Text(
                    "${pulse.minutesLow}–${pulse.minutesHigh} min · ${pulse.busyLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tones.live,
                )
            } else {
                Icon(Icons.Filled.Lock, null, Modifier.size(11.dp), tint = tones.muted)
                Spacer(Modifier.width(4.dp))
                Text(
                    "Live wait · ${pulse.reports} report${if (pulse.reports == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tones.muted,
                )
            }
        }
    }
}

/**
 * Only renders for allergens this person actually selected — an allergen row for everyone would be
 * noise, and the ones that matter would be lost in it.
 */
@Composable
fun AllergenRow(signals: List<AllergenSignal>, watching: List<String>, modifier: Modifier = Modifier) {
    if (watching.isEmpty() || signals.isEmpty()) return
    val relevant = signals.filter { s -> watching.any { it.equals(s.allergen, true) } && s.reports > 0 }
    if (relevant.isEmpty()) return
    val tones = LocalBrandTones.current

    Row(
        modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        relevant.take(3).forEach { signal ->
            val safe = signal.accommodates == true && signal.confidence != "contested"
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    if (safe) "${signal.allergen} ok · ${signal.safe}" else "${signal.allergen} contested",
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (safe) tones.allergenSafe else tones.allergenContested,
                )
            }
        }
    }
}

@Composable
fun PhotoThumb(result: RestaurantResult, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (result.imageUrl != null) {
            AsyncImage(
                model = result.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = if (Perf.richMotion) FilterQuality.Medium else FilterQuality.Low,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(
                result.name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
fun UpsellCard(lockedCount: Int, onUpgrade: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onUpgrade),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "$lockedCount more matches nearby",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Unlock the full list and a wider radius with Plus",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                )
            }
        }
    }
}
