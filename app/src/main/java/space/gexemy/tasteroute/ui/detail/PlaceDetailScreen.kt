package space.gexemy.tasteroute.ui.detail

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
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.AllergenSignal
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.Backoff
import space.gexemy.tasteroute.data.CrowdPulse
import space.gexemy.tasteroute.data.CrowdRepository
import space.gexemy.tasteroute.data.Entitlements
import space.gexemy.tasteroute.data.GexemyClient
import space.gexemy.tasteroute.data.Perf
import space.gexemy.tasteroute.data.PhotoUpload
import space.gexemy.tasteroute.data.RestaurantResult
import space.gexemy.tasteroute.data.Session
import space.gexemy.tasteroute.data.Social
import space.gexemy.tasteroute.data.ExternalSource
import space.gexemy.tasteroute.data.FactGroup
import space.gexemy.tasteroute.data.FactLabels
import space.gexemy.tasteroute.data.Hours
import space.gexemy.tasteroute.data.OsmDetails
import space.gexemy.tasteroute.data.PlaceFacts
import space.gexemy.tasteroute.data.YelpInfo
import space.gexemy.tasteroute.data.driveMinutes
import space.gexemy.tasteroute.data.formatCount
import space.gexemy.tasteroute.data.formatDistanceMeters
import space.gexemy.tasteroute.data.walkMinutes
import space.gexemy.tasteroute.ui.components.MatchPill
import space.gexemy.tasteroute.ui.components.liveDistanceMeters
import space.gexemy.tasteroute.ui.components.PulseBadge
import space.gexemy.tasteroute.ui.theme.LocalBrandTones
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceDetailScreen(
    onBack: () -> Unit,
    onRoute: () -> Unit,
    onMenu: () -> Unit,
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
    val haptics = LocalHapticFeedback.current
    // Same rule as the card: in NEARBY the distance follows you; in Anywhere or corridor mode
    // the searched number is the true one, and recomputing it against your body would be wrong.
    val liveMeters = liveDistanceMeters(place)

    var pulse by remember(place.id) { mutableStateOf(place.pulse ?: CrowdRepository.snapshotPulse(place.id)) }
    var signals by remember(place.id) { mutableStateOf(place.allergens.ifEmpty { CrowdRepository.snapshotAllergens(place.id) }) }
    var byHour by remember(place.id) { mutableStateOf<List<GexemyClient.HourBucket>>(emptyList()) }
    var photos by remember(place.id) { mutableStateOf(place.photos.ifEmpty { listOfNotNull(place.imageUrl) }) }
    var reviews by remember(place.id) { mutableStateOf(GexemyClient.ReviewPage()) }
    var yelp by remember(place.id) { mutableStateOf(place.yelp ?: CrowdRepository.snapshotYelp(place.id)) }
    var sources by remember(place.id) { mutableStateOf(place.sources.ifEmpty { CrowdRepository.snapshotSources(place.id) }) }
    var facts by remember(place.id) { mutableStateOf(place.facts ?: PlaceFacts()) }
    // Kept apart from [facts] rather than merged on arrival, because these two land in either
    // order and a merge that depends on which won is a merge that is wrong half the time.
    var osmFacts by remember(place.id) { mutableStateOf(PlaceFacts()) }
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

    // NOTHING HERE WAITS ON ANYTHING ELSE. These six fetches are independent and used to run in a
    // line — pulse, crowd, photos, reviews, Yelp, and only then sources, the one that carries the
    // hours, the phone number and most of the photographs. Each of the five ahead of it can spend
    // a ten-second read timeout before failing, so a place could sit there with no details at all
    // while the request that would have filled them in had not been sent yet. It was never missing
    // data; it was data nobody had asked for.
    LaunchedEffect(place.id) {
        // Runs whether or not our own API is configured or reachable: OSM answers for most venues,
        // needs no key, and is the difference between a detail screen and a name with a map pin.
        val fromMap = async { OsmDetails.forPlace(place.id) }

        val fetches: List<Job> = if (!GexemyClient.isConfigured) emptyList() else listOf(
            launch {
                runCatching { GexemyClient.pulseDetail(place.id) }.onSuccess {
                    pulse = it.pulse ?: pulse
                    byHour = it.byHour
                }
            },
            launch {
                runCatching { CrowdRepository.refresh(listOf(place.id)) }
                signals = CrowdRepository.snapshotAllergens(place.id).ifEmpty { signals }
            },
            launch {
                runCatching { GexemyClient.photosFor(place.id) }.onSuccess { list ->
                    // Ours lead, they do not replace: the card's own photo is a real photograph of
                    // this place and throwing it away left galleries with one image in them.
                    if (list.isNotEmpty()) photos = (list.map { it.url } + photos).distinct().take(12)
                }
            },
            launch {
                runCatching { GexemyClient.reviewsFor(place.id, place.coordinates) }.onSuccess { reviews = it }
            },
            launch {
                runCatching { GexemyClient.yelpFor(place.id, place.coordinates, place.name) }.onSuccess { info ->
                    if (info != null) {
                        yelp = info
                        CrowdRepository.noteYelp(place.id, info)
                    }
                }
            },
            // The only place the expensive connectors are allowed to spend a call. Tripadvisor bills
            // four per place and Google's reviews sit in its priciest tier, so this route exists so
            // that opening a place pays for depth and scrolling a feed never does.
            launch {
                runCatching {
                    GexemyClient.sourcesFor(place.id, place.coordinates, place.name, place.venueSite)
                }.onSuccess { (found, merged) ->
                    if (found.isNotEmpty()) {
                        sources = found
                        CrowdRepository.noteSources(place.id, found)
                    }
                    // Merged rather than assigned: this and the second wave below land in
                    // either order, and each must only add what the other did not.
                    facts = merged.fillFrom(facts)
                    // A photo from the venue's own site is usually the best one there is, and OSM
                    // carries none for most places — so these go after whatever we already have
                    // rather than only appearing when we have nothing.
                    val harvested = found.flatMap { source -> source.photos.map { it.url } }
                    if (harvested.isNotEmpty()) photos = (photos + harvested).distinct().take(12)
                }
            },
        )

        runCatching { fromMap.await() }.getOrNull()?.let { detail ->
            osmFacts = detail.facts
            detail.photo?.let { photos = (photos + it).distinct().take(12) }

            // SECOND WAVE. The map very often knows the venue's website when the trimmed search
            // record did not, and the server harvest is the only method that can produce a menu —
            // so a discovered site (or a mapped menu page) is fed back to the harvester instead
            // of being merely displayed. The server treats a query that knows more as superseding
            // its cached answer to a worse one, so this costs one extra harvest per newly learned
            // fact and cannot loop: once the row records this site and hint, the same call is a
            // cache hit.
            val discovered = detail.facts.website?.split(";")?.first()?.trim()
                ?.takeIf { it.isNotEmpty() && !it.contains("openstreetmap.org", ignoreCase = true) }
            val menuHint = detail.facts.menu?.trim()?.takeIf { it.isNotEmpty() }
            val site = place.venueSite ?: discovered
            if (site != null && (place.venueSite == null || menuHint != null) &&
                GexemyClient.reachable(Backoff.SOURCES)
            ) {
                launch {
                    runCatching {
                        GexemyClient.sourcesFor(place.id, place.coordinates, place.name, site, menuHint)
                    }.onSuccess { (found, merged) ->
                        if (found.isNotEmpty()) {
                            sources = found
                            CrowdRepository.noteSources(place.id, found)
                        }
                        facts = merged.fillFrom(facts)
                        val harvested = found.flatMap { source -> source.photos.map { it.url } }
                        if (harvested.isNotEmpty()) photos = (photos + harvested).distinct().take(12)
                    }
                }
            }
        }
        fetches.joinAll()
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

            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
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
                                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }

                MenuCard(facts.fillFrom(osmFacts), place.venueSite, onMenu)

                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }

                LivePulseSection(pulse, byHour, tier, onCheckIn, onSignIn)
                VisitSection(place, onSignIn)

                ReviewSection(reviews, onWriteReview, onSignIn)

                DetailsSection(facts.fillFrom(osmFacts)) { openUrl(context, it) }

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
                        "${formatDistanceMeters(liveMeters)} away · " +
                            "~${driveMinutes(liveMeters)} min drive · ${walkMinutes(liveMeters)} min walk",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    place.address?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HoursSection(place.openingHours, facts.fillFrom(osmFacts))

                // venueSite, not webUrl: a chip labelled "Website" that opens an OpenStreetMap
                // node page is not this restaurant's website. No site, no chip.
                place.venueSite?.let { url ->
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
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        AppState.toggleFavorite(place)
                    },
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
                FilledTonalButton(
                    onClick = { sharePlace(context, place) },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Share, "Share this place", Modifier.size(20.dp))
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
 * The way into the menu, and deliberately the loudest control above the fold.
 *
 * The dish line on a card falls back to [space.gexemy.tasteroute.data.DishPicks] when nothing was
 * harvested, and however carefully that is labelled "Your pick", people read a dish printed on a
 * restaurant as a dish that restaurant serves. The fix is the real menu, one tap away. The
 * subtitle says which of the two you are about to get BEFORE the tap, because for a lot of venues
 * the honest answer is still "they only publish a PDF" — and a button that opens a shrug is worse
 * than one that admits what it has.
 */
@Composable
private fun MenuCard(facts: PlaceFacts, fallbackUrl: String?, onOpen: () -> Unit) {
    val dishes = facts.menuSections.sumOf { it.items.size }.takeIf { it > 0 } ?: facts.menuItems.size
    val link = facts.menu ?: facts.menuSource ?: facts.website ?: fallbackUrl
    if (dishes == 0 && link == null) return
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.RestaurantMenu, null, Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Menu", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (dishes > 0) {
                        "$dishes dish${if (dishes == 1) "" else "es"} the venue publishes itself"
                    } else {
                        "No readable menu published — open theirs instead"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
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
    tier: space.gexemy.tasteroute.data.Tier,
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Usually quietest around ${byHour.minBy { it.avgWait }.hour}:00, busiest around ${byHour.maxBy { it.avgWait }.hour}:00",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            PulseBadge(pulse, tier)
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(8.dp))
        }
        signals.take(6).forEach { s ->
            val contested = s.confidence == "contested"
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Spacer(Modifier.width(8.dp))
                Text(
                    String.format(java.util.Locale.US, "%.1f", page.summary.rating),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(8.dp))
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
                Column(Modifier.padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StarRow(review.rating)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            review.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (review.imported) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    "open data",
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
            Spacer(Modifier.width(8.dp))
            Text(
                String.format(java.util.Locale.US, "%.1f", yelp.rating),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(8.dp))
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
                    .padding(top = 12.dp)
                    .clickable { onOpen(review.url) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarRow(review.rating)
                    Spacer(Modifier.width(8.dp))
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
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            facts.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (facts.menuItems.isNotEmpty()) {
                Column {
                    Text("On the menu", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        facts.menuItems.take(12).forEach { dish ->
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(
                                    dish,
                                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Text(
                        "Published by the venue on its own site.",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The server stopped emitting `00:00-00:00` in 1.4.0, but its harvest cache is 14 days
            // deep, so rows written before that are still out there claiming every venue opens and
            // closes at midnight. Filtering here clears them the moment the app updates.
            val hours = facts.hoursLines
            if (hours.isNotEmpty()) {
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
                    hours.take(7).forEach {
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (source.ratesIt) {
                                Icon(Icons.Filled.Star, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    String.format(java.util.Locale.US, "%.1f", source.rating),
                                    style = MaterialTheme.typography.titleMedium,
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
                add(formatDistanceMeters(liveDistanceMeters(place)))
            }.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The map's own `opening_hours`, for the wait between opening a place and the harvested schedule
 * arriving — or forever, for the many venues no source has anything to say about.
 *
 * The tag is a machine syntax and was being printed verbatim at people. [Hours] turns it into day
 * lines and into the only part anyone reads, which is whether the place is open right now; when it
 * uses a corner of the specification we do not parse, the original string is shown unchanged,
 * because a cryptic truth beats a confident wrong answer about somewhere being closed.
 */
@Composable
private fun HoursSection(raw: String?, facts: PlaceFacts) {
    // The harvested schedule is better and Details already renders it. Two hour lists on one
    // screen, disagreeing, is worse than either of them alone.
    if (facts.hoursLines.isNotEmpty()) return
    val text = raw?.takeIf { it.isNotBlank() } ?: return
    val schedule = remember(text) { Hours.parse(text) }
    Section("Hours") {
        if (schedule == null) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Section
        }
        Column {
            Text(
                if (schedule.openNow) "Open now" else "Closed now",
                style = MaterialTheme.typography.labelLarge,
                color = if (schedule.openNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            schedule.lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Logging a visit is an action somebody takes, never something the app does for them.
 *
 * There is no background location history here and there never will be — this button is the only
 * way a place ends up in a visit log, and the second button is the only way one is ever visible to
 * anybody else. Two explicit buttons rather than a switch with a default, because the difference
 * between them is the entire privacy question and it should be read, not toggled past.
 */
@Composable
private fun VisitSection(place: RestaurantResult, onSignIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var note by remember(place.id) { mutableStateOf<String?>(null) }
    Section("Been here?") {
        Column {
            Text(
                "TasteRoute never logs where you go on its own. A visit exists because you added it, " +
                    "and it stays private unless you choose the second button.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (!Session.signedIn) onSignIn()
                    else scope.launch { note = Social.logVisit(place, share = false) ?: "Saved, just for you." }
                }) { Text("I ate here", maxLines = 1) }
                OutlinedButton(onClick = {
                    if (!Session.signedIn) onSignIn()
                    else scope.launch { note = Social.logVisit(place, share = true) ?: "Saved and shared with friends." }
                }) { Text("Share with friends", maxLines = 1) }
            }
            note?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * A review's rating, drawn with the same star the rest of the app uses.
 *
 * It was `"★".repeat(n)` — the typographic star, set in the body face. It inherited whatever
 * weight and metrics the user's chosen font happens to give U+2605 (which for a downloadable font
 * is often nothing, so it fell back to a different face mid-line), it sat on the text baseline
 * instead of centred, and it was a different shape from the `Icons.Filled.Star` on every card in
 * the feed. Two kinds of star in one app is the sort of thing nobody names but everybody sees.
 */
@Composable
private fun StarRow(rating: Int) {
    val filled = rating.coerceIn(1, 5)
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(filled) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * Name, address, and the venue's own site when it has one. The OSM permalink otherwise — sending
 * somebody a link to look the place up is exactly the case webUrl exists for.
 */
private fun sharePlace(context: Context, place: RestaurantResult) {
    val text = buildString {
        append(place.name)
        place.address?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
        append('\n')
        append(place.venueSite ?: place.webUrl)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
