package space.gexemy.tasteroute.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import space.gexemy.tasteroute.data.AllergenSignal
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.CrowdPulse
import space.gexemy.tasteroute.data.DishPicks
import space.gexemy.tasteroute.data.Entitlements
import space.gexemy.tasteroute.data.Perf
import space.gexemy.tasteroute.data.RecommendationEngine
import space.gexemy.tasteroute.data.RestaurantResult
import space.gexemy.tasteroute.data.SearchMode
import space.gexemy.tasteroute.data.Tier
import space.gexemy.tasteroute.data.YelpInfo
import space.gexemy.tasteroute.data.formatCount
import space.gexemy.tasteroute.data.formatDistanceMeters
import space.gexemy.tasteroute.ui.theme.LocalBrandTones

/** The one renderer for a [RestaurantResult] — feed rows, chat cards and the map sheet reuse these. */
@OptIn(ExperimentalLayoutApi::class)
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
    // The food is the reason anyone taps. Showing it only after the tap made the feed a list of
    // names, so the photo leads whenever one exists and the compact row is now the fallback.
    val hero = result.imageUrl
    val haptics = LocalHapticFeedback.current
    val dish = remember(result.id, result.menuHighlights, AppState.profile, AppState.allergens.size) {
        DishPicks.suggest(result)
    }

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Perf.cardElevationDp.dp),
    ) {
        if (hero != null) {
            Box(Modifier.fillMaxWidth().height(if (Perf.richMotion) 168.dp else 140.dp)) {
                AsyncImage(
                    model = hero,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = if (Perf.richMotion) FilterQuality.Medium else FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (Perf.richMotion) 168.dp else 140.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                // A bare icon on top of a photo is invisible over half the photos it lands on.
                Surface(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                ) {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleFavorite()
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove from saved" else "Save",
                            tint = if (isFavorite) LocalBrandTones.current.favorite else LocalBrandTones.current.muted,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }

        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hero == null) {
                    PhotoThumb(result, Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        result.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                }
                if (hero == null) {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleFavorite()
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove from saved" else "Save",
                            tint = if (isFavorite) LocalBrandTones.current.favorite else LocalBrandTones.current.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // FlowRow, not Row: badges have wildly different widths and a long wait label used to
            // stretch the row it was in. Wrapping is what the layout should do when it runs out of
            // width — growing the control is not.
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MatchPill(result.aiMatchScore)
                dish?.let { DishPill(it) }
                PulseBadge(result.pulse, userTier)
            }

            AllergenRow(result.allergens, AppState.allergens)
            if (showReasoning && result.reasoningParts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
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

/**
 * The dish, not the venue. "Popular" is a real harvested menu item; "Your pick" is a suggestion
 * built from this kitchen's cuisine and this person's taste, and the two must never share a label —
 * one is a fact about the restaurant, the other is a fact about the diner.
 */
@Composable
fun DishPill(pick: DishPicks.Pick, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pick.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                pick.dish,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        // Matches the real card's silhouette, photo included. A skeleton shaped like a different
        // card is why the swap to results reads as the layout jumping.
        Box(Modifier.fillMaxWidth().height(if (Perf.richMotion) 168.dp else 140.dp).background(bar))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(15.dp).clip(CircleShape).background(bar))
            Box(Modifier.fillMaxWidth(0.8f).height(11.dp).clip(CircleShape).background(bar))
            Box(Modifier.fillMaxWidth(0.35f).height(11.dp).clip(CircleShape).background(bar))
        }
    }
}

/**
 * How far away this place is RIGHT NOW, rather than when the list was built.
 *
 * Only for nearby browsing. In Anywhere mode the distance is measured from the city you are looking
 * at rather than from your body, and in corridor mode the detour is the number that matters — in
 * both, recomputing against the device fix would replace a true number with a meaningless one.
 */
internal fun liveDistanceMeters(result: RestaurantResult): Int {
    if (AppState.searchMode != SearchMode.NEARBY) return result.distanceMeters
    val here = AppState.origin ?: return result.distanceMeters
    val exact = RecommendationEngine.distanceMeters(here, result.coordinates)
    // Snap to 10m increments. Reduces recompositions of all cards on screen every time the
    // location fix wobbles by a metre. Above 1km, the label only shows 100m steps anyway.
    return if (exact < 1000) (exact / 10) * 10 else (exact / 100) * 100
}

/**
 * Stars on a card come from our own reviews when we have any, and from Yelp when we don't — most
 * OSM places carry no rating at all, and an unrated card is the reason a good restaurant gets
 * scrolled past. Yelp's number is always labelled as Yelp's; the two are never averaged together.
 */
@Composable
fun RatingRow(result: RestaurantResult) {
    val yelp: YelpInfo? = result.yelp?.takeIf { it.usable && result.rating <= 0 }
    // Read through a derived state. The fix updates every fifteen seconds, and reading it
    // directly would recompose every card on screen even if the distance label hadn't moved.
    // Snapping to 10m in liveDistanceMeters means we only recompose when it matters.
    val distance by remember(result.id) {
        derivedStateOf { liveDistanceMeters(result) }
    }
    val meta = remember(result, distance) {
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
            else add(formatDistanceMeters(distance))
            val price = if (result.priceTier > 0) "$".repeat(result.priceTier) else yelp?.price.orEmpty()
            if (price.isNotBlank()) add(price)
            result.cuisineTags.firstOrNull()?.let { add(it) }
        }.joinToString(" · ")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (result.rating > 0 || yelp != null) {
            Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(4.dp))
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

/**
 * A bare number is a judgement nobody asked for. "50% match" reads as a verdict against the
 * restaurant when what it mostly means is that the app had four blank columns to average over —
 * reported as "I see a 50% match and I'm like, ehh, do I really want it". The word leads now and
 * the number follows in a quieter size: the score still orders the list and is still shown, it
 * just stops being the loudest thing on a card about a place somebody might love.
 */
@Composable
fun MatchPill(score: Int) {
    val label = when {
        score >= 85 -> "Great match"
        score >= 70 -> "Strong match"
        score >= 55 -> "Good match"
        else -> "Worth a look"
    }
    val strong = score >= 55
    val container =
        if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val ink =
        if (strong) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = container, contentColor = ink) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(
                "$score%",
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
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
            Spacer(Modifier.width(4.dp))
            if (unlocked) {
                Text(
                    "${pulse.minutesLow}–${pulse.minutesHigh} min · ${pulse.busyLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tones.live,
                    maxLines = 1,
                )
            } else {
                Icon(Icons.Filled.Lock, null, Modifier.size(11.dp), tint = tones.muted)
                Spacer(Modifier.width(4.dp))
                Text(
                    "Live wait · ${pulse.reports} report${if (pulse.reports == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tones.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Only renders for allergens this person actually selected — an allergen row for everyone would be
 * noise, and the ones that matter would be lost in it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllergenRow(signals: List<AllergenSignal>, watching: List<String>, modifier: Modifier = Modifier) {
    if (watching.isEmpty() || signals.isEmpty()) return
    val relevant = signals.filter { s -> watching.any { it.equals(s.allergen, true) } && s.reports > 0 }
    if (relevant.isEmpty()) return
    val tones = LocalBrandTones.current

    FlowRow(
        modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        relevant.take(3).forEach { signal ->
            val safe = signal.accommodates == true && signal.confidence != "contested"
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    if (safe) "${signal.allergen} ok · ${signal.safe}" else "${signal.allergen} contested",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (safe) tones.allergenSafe else tones.allergenContested,
                    maxLines = 1,
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
