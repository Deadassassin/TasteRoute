package space.gexemy.tasteroute.ui.profile

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.RestaurantResult
import space.gexemy.tasteroute.data.GexemyClient
import space.gexemy.tasteroute.data.Session
import space.gexemy.tasteroute.data.Social
import space.gexemy.tasteroute.data.HttpException
import space.gexemy.tasteroute.data.TasteAi
import space.gexemy.tasteroute.ui.theme.LocalBrandTones
import kotlinx.coroutines.launch

/**
 * The Profile tab is a profile now.
 *
 * It used to be the settings screen, which meant the one place in the app that should say who you
 * are was a list of switches — and the app had nowhere to put a person at all. Settings did not
 * disappear: every one of them lives behind the overflow button in the corner, which is where a
 * person looks for them and nowhere near where they look for themselves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    onSettings: () -> Unit,
    onAccount: () -> Unit,
    onRetune: () -> Unit,
    onFriends: () -> Unit,
    onOpenFriend: (Long) -> Unit,
    onOpenPlace: () -> Unit,
) {
    val account = Session.account
    val signedIn = Session.signedIn && account != null

    LaunchedEffect(signedIn) { if (signedIn) Social.refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Profile",
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.MoreVert, "Settings and preferences")
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            item("identity") {
                IdentityCard(account, signedIn, onAccount)
            }

            item("taste") {
                Card(
                    Modifier.fillMaxWidth().clickable(onClick = onRetune),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Your taste", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Retune",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val bits = remember(AppState.profile) { TasteAi.summarizeList(AppState.profile) }
                        if (bits.isEmpty()) {
                            Text(
                                "Nothing set yet — tell me what you like and every list in the app changes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) { bits.forEach { TasteChip(it) } }
                        }
                    }
                }
            }

            item("savedhead") {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved places", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (AppState.favorites.isNotEmpty()) {
                        Text(
                            "${AppState.favorites.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (AppState.favorites.isEmpty()) {
                item("savedempty") {
                    Text(
                        "Nothing saved yet — the heart on any place keeps it here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(AppState.favoritePlaces, key = { "saved-${it.id}" }) { place ->
                    SavedPlaceRow(place, onOpenPlace)
                }
                // Hearts synced from another device, or set before snapshots existed: still saved,
                // named when the name is known, and they upgrade the next time they are hearted.
                val bare = AppState.favorites.filter { id -> AppState.favoritePlaces.none { it.id == id } }
                items(bare, key = { "saved-bare-$it" }) { id -> BareSavedRow(id) }
            }

            if (signedIn && Social.supported) {
                item("friends") { FriendsCard(onFriends, onOpenFriend) }
                item("feedhead") {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Where friends have been", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (Social.feed.isEmpty()) {
                    item("feedempty") {
                        Text(
                            if (Social.friends.isEmpty()) {
                                "Add a friend and anything they choose to share shows up here."
                            } else {
                                "Nothing shared yet. Visits are private unless someone marks one to share, " +
                                    "so this fills up slowly and on purpose."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(Social.feed, key = { "feed-${it.id}" }) { visit -> VisitRow(visit, visit.by?.label) }
                }
            }

            item("minehead") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Where you've been", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (Social.visits.isNotEmpty()) {
                        Text(
                            "${Social.visits.count { it.shared }} of ${Social.visits.size} shared",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (Social.visits.isEmpty()) {
                item("mineempty") {
                    Text(
                        if (signedIn) {
                            "Open a place and tap \"I ate here\". Each one is private unless you say otherwise — " +
                                "TasteRoute never logs where you go on its own."
                        } else {
                            "Sign in and your visits, reviews and photos follow you between devices."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(Social.visits, key = { "mine-${it.id}" }) { visit -> VisitRow(visit, null) }
            }

            Social.error?.let { message ->
                item("err") {
                    Text(message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(account: GexemyClient.Account?, signedIn: Boolean, onAccount: () -> Unit) {
    var editing by remember(account?.handle) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        val initial = account?.displayName?.trim()?.take(1)?.uppercase().orEmpty()
                        if (initial.isBlank()) {
                            Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        } else {
                            Text(
                                initial,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        account?.displayName?.takeIf { it.isNotBlank() } ?: "Not signed in",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        account?.handle?.let { "@$it" } ?: if (signedIn) "No handle yet" else "Sign in to sync and add friends",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            account?.bio?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            if (signedIn && account != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat(account.stats.visits, "visits")
                    Stat(account.stats.reviews, "reviews")
                    Stat(account.stats.photos, "photos")
                    Stat(account.stats.friends, "friends")
                }
                Spacer(Modifier.height(12.dp))
                if (editing) {
                    HandleEditor(account) { editing = false }
                } else {
                    OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (account.handle == null) "Pick a handle" else "Edit profile", maxLines = 1)
                    }
                }
            } else {
                Button(onClick = onAccount, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in or create an account", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FriendsCard(onFriends: () -> Unit, onOpenFriend: (Long) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Friends", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onFriends) {
                    Text(
                        if (Social.requestCount > 0) "${Social.requestCount} waiting" else "Manage",
                        maxLines = 1,
                        color = if (Social.requestCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (Social.friends.isEmpty()) {
                Text(
                    "Nobody yet. Friends are added by handle — no contact upload, no phone number.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(Social.friends, key = { it.linkId }) { link ->
                        Column(
                            Modifier.width(72.dp).clickable { onOpenFriend(link.user.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        link.user.label.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                link.user.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One logged visit. [who] is null for your own, where the name would just be your own name. */
@Composable
fun VisitRow(visit: GexemyClient.Visit, who: String?) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    visit.name.ifBlank { visit.placeId },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = buildList {
                    who?.let { add(it) }
                    visit.rating?.let { add("Rated $it") }
                    visit.note?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (who == null && !visit.shared) {
                Icon(
                    Icons.Filled.Lock,
                    "Private",
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Claiming a handle is the only way to be findable, and clearing it is the only way to stop being
 * findable. Both are one field, right where the profile is, rather than buried in an account
 * screen — being searchable by strangers-with-your-handle is a decision, not a setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandleEditor(account: GexemyClient.Account, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var handle by remember { mutableStateOf(account.handle.orEmpty()) }
    var bio by remember { mutableStateOf(account.bio) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = handle,
            onValueChange = { handle = it.trimStart('@'); problem = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Handle", maxLines = 1) },
            placeholder = { Text("letters, numbers, underscore", maxLines = 1) },
            singleLine = true,
            shape = CircleShape,
        )
        OutlinedTextField(
            value = bio,
            onValueChange = { bio = it.take(280) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("About you", maxLines = 1) },
            maxLines = 3,
        )
        problem?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val wanted = handle.trim().ifBlank { null }
                        runCatching {
                            GexemyClient.setHandle(wanted, bio)
                            Session.pull()
                        }.onFailure { e ->
                            problem = when ((e as? HttpException)?.code) {
                                409 -> "@${handle.trim()} is taken."
                                400 -> "3-20 characters, letters, numbers and underscore only."
                                else -> e.message?.take(120) ?: "Couldn't save that."
                            }
                        }.onSuccess { onDone() }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Saving…" else "Save", maxLines = 1) }
            TextButton(onClick = onDone) { Text("Cancel", maxLines = 1) }
        }
    }
}

@Composable
private fun SavedPlaceRow(place: RestaurantResult, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                AppState.selectedRestaurant = place
                onOpen()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(place.cuisineTags.firstOrNull(), place.address).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { AppState.toggleFavorite(place) }) {
            Icon(Icons.Filled.Favorite, "Remove from saved", tint = LocalBrandTones.current.favorite)
        }
    }
}

/** A heart with no snapshot behind it — synced from another device or saved before 0.1.16. */
@Composable
private fun BareSavedRow(id: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            AppState.knownNames[id] ?: id,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = { AppState.toggleFavorite(id) }) {
            Icon(Icons.Filled.Favorite, "Remove from saved", tint = LocalBrandTones.current.favorite)
        }
    }
}

@Composable
private fun TasteChip(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
