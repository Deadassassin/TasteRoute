package com.example.tasteroute.ui.group

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.GexemyClient
import com.example.tasteroute.data.RecommendationRequest
import com.example.tasteroute.data.Recommender
import com.example.tasteroute.data.ResultSource
import com.example.tasteroute.data.RestaurantResult
import com.example.tasteroute.data.Session
import com.example.tasteroute.ui.components.BackChip
import com.example.tasteroute.ui.components.RestaurantCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Table Sync: everyone joins with a six-character code, the server merges the group's taste
 * profiles, and the picks are ranked for the whole table instead of whoever is holding the phone.
 *
 * The merge is deliberately not an intersection — allergens and diets union, price takes the
 * minimum, cuisines survive with a third of the table behind them. Strict intersection returns
 * nothing past three people, which is exactly when a group needs this.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TableSyncScreen(onBack: () -> Unit, onOpenPlace: () -> Unit, onSignIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<GexemyClient.GroupSnapshot?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picks by remember { mutableStateOf<List<RestaurantResult>>(emptyList()) }

    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = it.message ?: "Something went wrong." }
            busy = false
        }
    }

    // Members join over time, so the merged profile keeps changing. Poll while the screen is up —
    // a socket for a session that lives twenty minutes is not worth the complexity.
    LaunchedEffect(snapshot?.code) {
        val code = snapshot?.code ?: return@LaunchedEffect
        while (true) {
            delay(6_000)
            runCatching { GexemyClient.group(code) }.onSuccess { snapshot = it }
        }
    }

    // Re-rank whenever the table changes.
    LaunchedEffect(snapshot?.members?.size, snapshot?.merged) {
        val merged = snapshot?.merged ?: return@LaunchedEffect
        val origin = AppState.origin ?: return@LaunchedEffect
        val outcome = Recommender.recommend(
            RecommendationRequest(
                origin = origin,
                profile = merged.toProfile(),
                query = "",
                source = ResultSource.FILTER_SEARCH,
                tier = AppState.tier,
                allergens = merged.allergens,
            ),
            cityLabel = AppState.cityLabel,
        )
        picks = outcome.results.take(8)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(66.dp))
            Text("Table Sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

            if (!Session.signedIn) {
                Text(
                    "Sign in to start or join a table — the merge needs your saved taste profile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onSignIn, Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.large) {
                    Text("Sign in")
                }
            } else if (snapshot == null) {
                Text(
                    "Everyone's tastes, one shortlist. Start a table and share the code, or join one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        perform {
                            snapshot = GexemyClient.createGroup(
                                title = AppState.cityLabel.orEmpty(),
                                name = Session.account?.displayName.orEmpty(),
                                origin = AppState.origin,
                            )
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.large,
                ) { Text("Start a table") }

                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase().filter(Char::isLetterOrDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Join with a code") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                OutlinedButton(
                    onClick = {
                        perform {
                            snapshot = GexemyClient.joinGroup(codeInput, Session.account?.displayName.orEmpty())
                        }
                    },
                    enabled = codeInput.length == 6 && !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Join") }
            } else {
                val group = snapshot!!
                CodeCard(group)
                MergeSummary(group.merged)

                if (picks.isNotEmpty()) {
                    Text("Works for the table", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    picks.forEach { place ->
                        val vote = group.votes.firstOrNull { it.placeId == place.id }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RestaurantCard(
                                result = place,
                                userTier = AppState.tier,
                                isFavorite = place.id in AppState.favorites,
                                onToggleFavorite = { AppState.toggleFavorite(place.id) },
                                onClick = {
                                    AppState.selectedRestaurant = place
                                    onOpenPlace()
                                },
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    perform { snapshot = GexemyClient.voteGroup(group.code, place.id, place.name, 1) }
                                }) { Text("👍 ${vote?.yes ?: 0}") }
                                TextButton(onClick = {
                                    perform { snapshot = GexemyClient.voteGroup(group.code, place.id, place.name, -1) }
                                }) { Text("👎 ${vote?.no ?: 0}") }
                            }
                        }
                    }
                } else if (AppState.origin == null) {
                    Text(
                        "Turn on location to rank places for the table.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(onClick = {
                    val code = group.code
                    snapshot = null
                    picks = emptyList()
                    scope.launch { GexemyClient.leaveGroup(code) }
                }) { Text("Leave this table") }
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(28.dp))
        }

        BackChip(onBack, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun CodeCard(group: GexemyClient.GroupSnapshot) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Share this code",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                group.code,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                group.members.joinToString(", ") { it.name.ifBlank { "Guest" } },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MergeSummary(merged: GexemyClient.MergedTaste) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What the table agrees on", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (merged.preferredCuisines + merged.vibeTags).forEach { tag ->
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        tag,
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val constraints = merged.allergens + merged.dietaryRestrictions
        if (constraints.isNotEmpty()) {
            Text(
                "Avoiding for everyone: ${constraints.joinToString(", ")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Budget capped at ${"$".repeat(merged.priceComfort.coerceIn(1, 4))} — the lowest in the group." +
                if (merged.consensus.droppedCuisines.isEmpty()) ""
                else " Dropped: ${merged.consensus.droppedCuisines.joinToString(", ")}.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
