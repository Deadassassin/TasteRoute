package space.gexemy.tasteroute.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.CrowdRepository
import space.gexemy.tasteroute.data.DishPicks
import space.gexemy.tasteroute.data.ExternalSource
import space.gexemy.tasteroute.data.GexemyClient
import space.gexemy.tasteroute.data.MenuDish
import space.gexemy.tasteroute.data.MenuSection
import space.gexemy.tasteroute.data.PlaceFacts
import space.gexemy.tasteroute.ui.components.BackChip

/**
 * The venue's own menu, in full, on its own screen.
 *
 * It exists because the dish line on a card could not carry one: with no harvested menu the card
 * falls back to [DishPicks], which suggests a dish from the cuisine and says it is a suggestion —
 * accurate, but it is a claim about the diner and people read it as a claim about the kitchen.
 * The fix for "your pick doesn't match what they actually serve" is the real menu, so this screen
 * shows nothing but: dishes the restaurant itself published, under the sections it published them
 * in, with its own prices. Same rule as everywhere else — no heuristics over page text, and when
 * there is nothing structured to show it says so rather than filling the space.
 */
@Composable
fun MenuScreen(onBack: () -> Unit) {
    val place = AppState.selectedRestaurant
    if (place == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val context = LocalContext.current

    // Seeded from what the detail screen already pulled, so a menu that has been fetched once this
    // session is on screen before the refresh below has left the phone. CrowdRepository holds each
    // source's own facts; the venue's website is the only connector that harvests dishes, so
    // first-non-empty across the sources IS the merge.
    var facts by remember(place.id) { mutableStateOf(seedFacts(place.id)) }
    var loading by remember(place.id) { mutableStateOf(true) }

    LaunchedEffect(place.id) {
        loading = true
        if (GexemyClient.isConfigured) {
            runCatching {
                GexemyClient.sourcesFor(place.id, place.coordinates, place.name, place.venueSite)
            }.onSuccess { (found, merged) ->
                if (found.isNotEmpty()) CrowdRepository.noteSources(place.id, found)
                facts = merged.fillFrom(facts)
            }
        }
        loading = false
    }

    // A server older than menu_sections still sends the flat highlight list; render it as one
    // untitled section rather than showing nothing because the shape changed.
    val sections = remember(facts) {
        facts.menuSections.filter { it.items.isNotEmpty() }.ifEmpty {
            if (facts.menuItems.isEmpty()) emptyList()
            else listOf(MenuSection(items = facts.menuItems.map { MenuDish(it) }))
        }
    }
    val dishes = sections.sumOf { it.items.size }
    val link = facts.menu ?: facts.menuSource ?: facts.website ?: place.venueSite

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackChip(onBack)
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Menu", style = MaterialTheme.typography.titleLarge)
                Text(
                    place.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (loading) {
                CircularProgressIndicator(
                    Modifier.padding(end = 20.dp).size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            sections.forEachIndexed { index, section ->
                if (section.name.isNotBlank()) {
                    item("head-$index", contentType = "head") {
                        Text(
                            section.name,
                            Modifier.padding(top = if (index == 0) 0.dp else 18.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                itemsIndexed(
                    section.items,
                    key = { i, dish -> "$index-$i-${dish.name}" },
                    contentType = { _, _ -> "dish" },
                ) { _, dish ->
                    DishRow(dish)
                }
            }

            if (dishes > 0) {
                item("credit", contentType = "credit") {
                    Column(Modifier.padding(top = 20.dp)) {
                        Text(
                            "$dishes dish${if (dishes == 1) "" else "es"} published by the venue on its own site. " +
                                "Prices and availability change — the restaurant's page is the last word.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        link?.let {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { openMenuUrl(context, it) }) { Text("Open the full menu") }
                        }
                    }
                }
            } else if (!loading) {
                item("empty", contentType = "empty") { NoMenu(link) { openMenuUrl(context, it) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DishRow(dish: MenuDish) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                dish.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            dish.price?.let {
                Spacer(Modifier.width(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        dish.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // schema.org `suitableForDiet` — a claim the restaurant published about its own dish, which
        // is the only kind of dietary claim this app is willing to put on a menu line.
        if (dish.diet.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                dish.diet.forEach { tag ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            tag,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The honest empty state, which most venues will get: menus are published as PDFs, images and
 * ordering widgets, none of which carry structured data. It offers the venue's own page and the
 * suggestion — clearly as a suggestion — rather than inventing a menu to fill the screen.
 */
@Composable
private fun NoMenu(link: String?, onOpen: (String) -> Unit) {
    val place = AppState.selectedRestaurant
    val pick = remember(place?.id) { place?.let { DishPicks.suggest(it) }?.takeIf { !it.fromMenu } }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.RestaurantMenu,
            null,
            Modifier.size(34.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "This one hasn't published a machine-readable menu.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Plenty of restaurants put theirs up as a PDF or a photo. We'd rather send you to " +
                "theirs than print a menu we made up.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        link?.let {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { onOpen(it) }) { Text("Open their site") }
        }
        pick?.let {
            Spacer(Modifier.height(24.dp))
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Your pick",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(it.dish, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Chosen from this kitchen's cuisine and your taste, not from their menu — " +
                            "they may not serve it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** First non-empty answer across the sources already cached for this place. */
private fun seedFacts(placeId: String): PlaceFacts =
    CrowdRepository.snapshotSources(placeId).fold(PlaceFacts()) { acc: PlaceFacts, s: ExternalSource ->
        acc.fillFrom(s.facts)
    }

private fun openMenuUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
