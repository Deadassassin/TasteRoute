package space.gexemy.tasteroute.ui.profile

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.GexemyClient
import space.gexemy.tasteroute.ui.components.BackChip
import space.gexemy.tasteroute.ui.theme.LocalBrandTones

/**
 * One friend: how alike your taste is, what that is actually made of, and the visits they chose
 * to share.
 *
 * The overlap number is the point, and it is built to be readable rather than flattering: a
 * dimension neither of you answered drops out of the average instead of counting as disagreement,
 * so two people who both left "vibes" blank are not told they are a 40% match over it.
 * `null` means there is genuinely nothing to compare yet, and the screen says so rather than
 * printing a confident 0%.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FriendProfileScreen(userId: Long, onBack: () -> Unit) {
    var detail by remember(userId) { mutableStateOf<GexemyClient.FriendDetail?>(null) }
    var failure by remember(userId) { mutableStateOf<String?>(null) }

    LaunchedEffect(userId) {
        runCatching { GexemyClient.friend(userId) }
            .onSuccess { detail = it }
            .onFailure { failure = it.message?.take(140) ?: "Couldn't load that profile." }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackChip(onBack)
            Text(
                detail?.user?.label ?: "Friend",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val loaded = detail
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                failure?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } ?: CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            return
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Text(
                                loaded.user.label.take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(loaded.user.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        loaded.user.handle?.let {
                            Text(
                                "@$it",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            loaded.user.bio.takeIf { it.isNotBlank() }?.let { bio ->
                item("bio") {
                    Text(bio, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item("overlap") { OverlapCard(loaded.overlap) }

            item("visitshead") {
                Text("Where they've been", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            if (loaded.visits.isEmpty()) {
                item("novisits") {
                    Text(
                        "Nothing shared. Visits are private by default and each one is shared on purpose, " +
                            "so an empty list here is the system working.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(loaded.visits, key = { "v-${it.id}" }) { visit -> VisitRow(visit, null) }
        }
    }
}

@Composable
private fun OverlapCard(overlap: GexemyClient.TasteOverlap?) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (overlap == null) {
                Text("Taste match", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Not enough set on either side to compare yet. Fill in a few cuisines and vibes " +
                        "and this turns into something worth reading.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${overlap.score}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(verdict(overlap.score), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "taste match",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val shared = overlap.shared.cuisines + overlap.shared.vibes + overlap.shared.diets
            if (shared.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Both of you", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Chips(shared, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            }

            val theirs = overlap.onlyTheirs.cuisines + overlap.onlyTheirs.vibes
            if (theirs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("They're into, you haven't said", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Chips(theirs, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "The useful half of a comparison — this is the part that turns into somewhere new.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalBrandTones.current.muted,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    items: List<String>,
    container: androidx.compose.ui.graphics.Color,
    ink: androidx.compose.ui.graphics.Color,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.take(12).forEach { item ->
            Surface(shape = CircleShape, color = container) {
                Text(
                    item.replaceFirstChar { it.uppercase() },
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = ink,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun verdict(score: Int) = when {
    score >= 85 -> "You two eat the same"
    score >= 70 -> "Very close"
    score >= 50 -> "Plenty of common ground"
    score >= 30 -> "Some overlap"
    else -> "Different tastes"
}
