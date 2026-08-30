package com.example.tasteroute.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tasteroute.data.AllergenSignal
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.CrowdPulse
import com.example.tasteroute.data.CrowdRepository
import com.example.tasteroute.data.Entitlements
import com.example.tasteroute.data.GexemyClient
import com.example.tasteroute.data.Perf
import com.example.tasteroute.data.PhotoUpload
import com.example.tasteroute.data.RestaurantResult
import com.example.tasteroute.data.Session
import com.example.tasteroute.data.ExternalSource
import com.example.tasteroute.data.FactGroup
import com.example.tasteroute.data.FactLabels
import com.example.tasteroute.data.PlaceFacts
import com.example.tasteroute.data.YelpInfo
import com.example.tasteroute.data.driveMinutes
import com.example.tasteroute.data.formatCount
import com.example.tasteroute.data.formatDistanceMeters
import com.example.tasteroute.data.walkMinutes
import com.example.tasteroute.ui.components.MatchPill
import com.example.tasteroute.ui.components.PulseBadge
import com.example.tasteroute.ui.theme.LocalBrandTones
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceDetailScreen(
    onBack: () -> Unit,
    onRoute: () -> Unit,
    onCheckIn: () -> Unit,
    onSignIn: () -> Unit,
    onWriteReview: () -> Unit,
) {
    val place = AppState.selectedRestaurant
    if (place == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val context = LocalContext.current
    val tier = AppState.tier
    val favorite = place.id in AppState.favorites
    val tones = LocalBrandTones.current

    var pulse by remember(place.id) { mutableStateOf(place.pulse ?: CrowdRepository.snapshotPulse(place.id)) }
    var signals by remember(place.id) { mutableStateOf(place.allergens.ifEmpty { CrowdRepository.snapshotAllergens(place.id) }) }
    var byHour by remember(place.id) { mutableStateOf<List<GexemyClient.HourBucket>>(emptyList()) }
    var photos by remember(place.id) { mutableStateOf(place.photos.ifEmpty { listOfNotNull(place.imageUrl) }) }
    var reviews by remember(place.id) { mutableStateOf(GexemyClient.ReviewPage()) }
    var yelp by remember(place.id) { mutableStateOf(place.yelp ?: CrowdRepository.snapshotYelp(place.id)) }
    var sources by remember(place.id) { mutableStateOf(place.sources.ifEmpty { CrowdRepository.snapshotSources(place.id) }) }
    var facts by remember(place.id) { mutableStateOf(place.facts ?: PlaceFacts()) }
    var uploading by remember(place.id) { mutableStateOf(false) }
    var notice by remember(place.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The system photo picker needs no storage permission and hands back a single item.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        notice = null
        scope.launch {
            runCatching {
                val prepared = PhotoUpload.prepare(context, uri)
                GexemyClient.uploadPhoto(place.id, prepared.bytes, prepared.mime, "")
            }.onSuccess { url ->
                photos = listOf(url) + photos.filterNot { it == url }
                CrowdRepository.invalidatePlace(place.id)
            }.onFailure { notice = it.message ?: "Couldn't upload that photo." }
            uploading = false
        }
    }

    LaunchedEffect(place.id) {
        if (!GexemyClient.isConfigured) return@LaunchedEffect
        runCatching { GexemyClient.pulseDetail(place.id) }.onSuccess {
            pulse = it.pulse ?: pulse
            byHour = it.byHour
        }
        runCatching { CrowdRepository.refresh(listOf(place.id)) }
        signals = CrowdRepository.snapshotAllergens(place.id).ifEmpty { signals }
        runCatching { GexemyClient.photosFor(place.id) }.onSuccess { list ->
            if (list.isNotEmpty()) photos = list.map { it.url }
        }
        runCatching { GexemyClient.reviewsFor(place.id, place.coordinates) }.onSuccess { reviews = it }
        runCatching { GexemyClient.yelpFor(place.id, place.coordinates, place.name) }.onSuccess { info ->
            if (info != null) {
                yelp = info
                CrowdRepository.noteYelp(place.id, info)
            }
        }
        // The only place the expensive connectors are allowed to spend a call. Tripadvisor bills
        // four per place and Google's reviews sit in its priciest tier, so this route exists so
        // that opening a place pays for depth and scrolling a feed never does.
        runCatching {
            GexemyClient.sourcesFor(place.id, place.coordinates, place.name, place.webUrl)
        }.onSuccess { (found, merged) ->
            if (found.isNotEmpty()) {
                sources = found
                CrowdRepository.noteSources(place.id, found)
            }
            facts = merged
            // A photo from the venue's own site is usually the best one there is, and OSM carries
            // none for most places — so it leads only when we have nothing of our own.
            if (photos.isEmpty()) {
                photos = found.flatMap { source -> source.photos.map { it.url } }.distinct().take(8)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PlaceGallery(
                photos = photos,
                fallbackInitial = place.name.take(1).uppercase(),
                uploading = uploading,
                onAddPhoto = {
                    if (Session.signedIn) {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        onSignIn()
                    }
                },
            )

            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Facts(place)
                    }
                    MatchPill(place.aiMatchScore)
                }

                if (place.cuisineTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        place.cuisineTags.forEach { tag ->
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Text(
                                    tag,
                                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }

                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }

                LivePulseSection(pulse, byHour, tier, onCheckIn, onSignIn)

                ReviewSection(reviews, onWriteReview, onSignIn)

                DetailsSection(facts) { openUrl(context, it) }

                SourcesSection(sources) { openUrl(context, it) }

                // Only when the server predates the multi-source route: the unified endpoint
                // already returns Yelp, and rendering both would print it twice.
                if (sources.isEmpty()) YelpSection(yelp) { openUrl(context, it) }

                AllergenSection(signals, place.dietaryOptions)

                if (place.reasoningParts.isNotEmpty()) {
                    Section("Why this fits you") {
                        place.reasoningParts.forEach { part ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Text("•  ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    part,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                Section("Getting there") {
                    Text(
                        "${formatDistanceMeters(place.distanceMeters)} away · " +
                            "~${driveMinutes(place.distanceMeters)} min drive · ${walkMinutes(place.distanceMeters)} min walk",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    place.address?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                place.openingHours?.let {
                    Section("Hours") {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                place.webUrl?.let { url ->
                    AssistChip(onClick = { openUrl(context, url) }, label = { Text("Website") })
                }

                Spacer(Modifier.height(88.dp))
            }
        }

        Surface(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = if (Perf.richMotion) 12.dp else 0.dp,
        ) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { AppState.toggleFavorite(place.id) },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (favorite) "Remove from saved" else "Save",
                        Modifier.size(20.dp),
                        tint = if (favorite) tones.favorite else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onRoute,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Route", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * The replacement for the invented wait estimate. When nobody has reported, it says so and asks —
 * that ask is the entire supply side of the feature, so it is a first-class button, not a footnote.
 */
@Composable
private fun LivePulseSection(
    pulse: CrowdPulse?,
    byHour: List<GexemyClient.HourBucket>,
    tier: com.example.tasteroute.data.Tier,
    onCheckIn: () -> Unit,
    onSignIn: () -> Unit,
) {
    val tones = LocalBrandTones.current
    Section("Right now") {
        if (pulse == null) {
            Text(
                "No live reports in the last couple of hours.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (Entitlements.canSeeLivePulse(tier)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${pulse.minutesLow}–${pulse.minutesHigh} min wait",
                    style = MaterialTheme.typography.titleMedium,
                    color = tones.live,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${pulse.busyLabel} · ${pulse.reports} report${if (pulse.reports == 1) "" else "s"} · ${pulse.confidence} confidence",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (byHour.size >= 3) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Usually quietest around ${byHour.minBy { it.avgWait }.hour}:00, busiest around ${byHour.maxBy { it.avgWait }.hour}:00",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            PulseBadge(pulse, tier)
            Spacer(Modifier.height(6.dp))
            Text(
                "${pulse.reports} people reported a wait here recently. Pro shows the minutes.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = if (Session.signedIn) onCheckIn else onSignIn) {
            Text(if (Session.signedIn) "Report the wait" else "Sign in to report")
        }
    }
}

@Composable
private fun AllergenSection(signals: List<AllergenSignal>, declared: List<String>) {
    if (signals.isEmpty() && declared.isEmpty()) return
    val tones = LocalBrandTones.current
    Section("Allergens") {
        if (declared.isNotEmpty()) {
            Text(
                "The venue lists: ${declared.joinToString(", ")} (OpenStreetMap)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }
        signals.take(6).forEach { s ->
            val contested = s.confidence == "contested"
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (contested) tones.allergenContested else tones.allergenSafe,
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    buildString {
                        append(s.allergen)
                        append(" — ")
                        append(
                            when {
                                contested -> "conflicting reports (${s.unsafe} unsafe, ${s.safe} safe)"
                                s.accommodates == true -> "accommodated, ${s.safe} confirmation${if (s.safe == 1) "" else "s"}"
                                else -> "nobody has confirmed yet"
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Community reports are what diners experienced; a venue listing is what the venue claims. " +
                "Neither is a guarantee — always confirm at the table.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * First-party reviews. They also supply the stars on the card — with Yelp gone, this is the only
 * place a rating can come from, so the empty state has to actively recruit the first one.
 */
@Composable
private fun ReviewSection(page: GexemyClient.ReviewPage, onWrite: () -> Unit, onSignIn: () -> Unit) {
    Section("Reviews") {
        if (page.summary.count == 0) {
            Text(
                "No reviews yet — yours would be the first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(6.dp))
                Text(
                    String.format(java.util.Locale.US, "%.1f", page.summary.rating),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    buildString {
                        append("${page.summary.count} review${if (page.summary.count == 1) "" else "s"}")
                        // An average built mostly from imported rows is a weaker claim than one
                        // built from our own diners, and the number should say which it is.
                        val imported = page.summary.count - page.summary.own
                        if (imported > 0) append(" · $imported from open sources")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            page.reviews.take(5).forEach { review ->
                Column(Modifier.padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "★".repeat(review.rating.coerceIn(1, 5)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            review.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (review.imported) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    "open data",
                                    Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (review.body.isNotBlank()) {
                        Text(
                            review.body,
                            Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        if (page.sources.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Imported reviews from " + page.sources.joinToString(", ") { "${it.label} (${it.count})" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = if (Session.signedIn) onWrite else onSignIn) {
            Text(
                when {
                    !Session.signedIn -> "Sign in to review"
                    page.mine != null -> "Edit your review"
                    else -> "Write a review"
                },
            )
        }
    }
}

/**
 * Yelp's rating and up to three review excerpts, in their own section.
 *
 * Not folded into the section above on purpose. Yelp's terms require its content to be identified
 * as Yelp's and to link back to the listing, and every excerpt links to the full review — which is
 * also the honest presentation: these are other people's reviews on someone else's platform, not
 * ours. Our own reviews still lead, because those are the ones we can stand behind.
 */
@Composable
private fun YelpSection(info: YelpInfo?, onOpen: (String) -> Unit) {
    val yelp = info?.takeIf { it.usable } ?: return
    Section("On Yelp") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(6.dp))
            Text(
                String.format(java.util.Locale.US, "%.1f", yelp.rating),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                buildList {
                    add("${formatCount(yelp.reviewCount)} Yelp review${if (yelp.reviewCount == 1) "" else "s"}")
                    if (yelp.price.isNotBlank()) add(yelp.price)
                    yelp.categories.firstOrNull()?.let { add(it) }
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        yelp.reviews.forEach { review ->
            Column(
                Modifier
                    .padding(top = 10.dp)
                    .clickable { onOpen(review.url) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "★".repeat(review.rating.coerceIn(1, 5)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        review.author,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (review.text.isNotBlank()) {
                    Text(
                        review.text,
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onOpen(yelp.url) }) { Text("Read on Yelp") }
        Spacer(Modifier.height(4.dp))
        Text(
            "Rating and excerpts from Yelp. Excerpts are shortened — tap one to read it in full.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Everything structured we know, merged across every source that knew something.
 *
 * Only answered facts appear. A venue nobody has said anything about shows a short section rather
 * than a wall of greyed-out "no" — absent and false are different claims, and rendering the first
 * as the second is how a detail screen ends up confidently wrong about a ramp or a patio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsSection(facts: PlaceFacts, onOpen: (String) -> Unit) {
    if (!facts.hasAnything) return
    Section("Details") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            facts.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (facts.hoursText.isNotEmpty()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Opening hours", style = MaterialTheme.typography.labelLarge)
                        facts.openNow?.let { open ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (open) "Open now" else "Closed now",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    facts.hoursText.take(7).forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val links = buildList {
                facts.phone?.takeIf { it.isNotBlank() }?.let { add("Call" to "tel:${it.filter { c -> c.isDigit() || c == '+' }}") }
                facts.menu?.takeIf { it.isNotBlank() }?.let { add("Menu" to it) }
                facts.website?.takeIf { it.isNotBlank() }?.let { add("Website" to it) }
                facts.socials["instagram"]?.let { add("Instagram" to it) }
                facts.socials["facebook"]?.let { add("Facebook" to it) }
            }
            if (links.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    links.forEach { (label, url) ->
                        AssistChip(onClick = { onOpen(url) }, label = { Text(label) })
                    }
                }
            }

            FactRow("Service", facts.service)
            FactRow("Good to know", facts.amenities)
            FactRow("Serves", facts.meals + facts.drinks)
            FactRow("Diet options the venue lists", facts.diet)
            FactRow("Payment", facts.payment)

            facts.ranking?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactRow(title: String, group: FactGroup) {
    val known = FactLabels.known(group)
    if (known.isEmpty()) return
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            known.forEach { (label, yes) ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (yes) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        if (yes) label else "No $label".lowercase().replaceFirstChar { it.uppercase() },
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (yes) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * Every outside platform's rating, each under its own name and linked to its own listing.
 *
 * Deliberately never averaged together and never folded into the stars at the top of the screen.
 * Those are our own diners; these are four other companies' users, counted four different ways, and
 * every one of the licences involved forbids deriving a new rating from their content. Showing them
 * side by side is also just more honest than one invented number.
 */
@Composable
private fun SourcesSection(sources: List<ExternalSource>, onOpen: (String) -> Unit) {
    val rated = sources.filter { it.hasContent }
    if (rated.isEmpty()) return
    Section("Elsewhere on the web") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rated.forEach { source ->
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (source.ratesIt) {
                                Icon(Icons.Filled.Star, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    String.format(java.util.Locale.US, "%.1f", source.rating),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                buildList {
                                    add("on ${source.label}")
                                    if (source.reviewCount > 0) add("${formatCount(source.reviewCount)} reviews")
                                    if (source.price.isNotBlank()) add(source.price)
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        source.reviews.take(2).forEach { review ->
                            Column(Modifier.padding(top = 2.dp)) {
                                if (review.title.isNotBlank()) {
                                    Text(review.title, style = MaterialTheme.typography.labelLarge)
                                }
                                Text(
                                    review.body.take(220) + if (review.body.length > 220) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // Author attribution and a link to the original are display
                                // conditions on every licensed source here, not a nicety.
                                Text(
                                    "— ${review.author.ifBlank { source.label }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (review.url.isNotBlank()) {
                                    OutlinedButton(onClick = { onOpen(review.url) }) { Text("Read in full") }
                                }
                            }
                        }

                        if (source.url.isNotBlank()) {
                            OutlinedButton(onClick = { onOpen(source.url) }) { Text("Open on ${source.label}") }
                        }
                    }
                }
            }
            Text(
                "Ratings and excerpts belong to the sites named above and are shown with a link back " +
                    "to each original. They are never merged into TasteRoute's own rating.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Facts(place: RestaurantResult) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (place.rating > 0) {
            Icon(Icons.Filled.Star, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            buildList {
                if (place.rating > 0) add("${place.rating}")
                if (place.reviewCount > 0) add("${formatCount(place.reviewCount)} reviews")
                if (place.priceTier > 0) add("$".repeat(place.priceTier))
                add(formatDistanceMeters(place.distanceMeters))
            }.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
