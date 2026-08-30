package space.gexemy.tasteroute.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.BuildConfig
import space.gexemy.tasteroute.R
import space.gexemy.tasteroute.data.Attribution
import space.gexemy.tasteroute.data.GexemyClient

/**
 * What this app is, where its information comes from, and what it does with yours — written for
 * the person holding the phone.
 *
 * Rewritten 2026-08-27. It used to end with a service block naming the API's version number and
 * the content sources currently switched on, and to label its credits "Built on". None of that is
 * about the app from the outside: a version number is a thing to reproduce a bug against, and
 * "built on" describes the construction rather than the result. Anything a developer needs to
 * read lives in Settings, on purpose.
 *
 * The credits list is still FETCHED, with a hardcoded fallback, and that is not a detail: which
 * sources are switched on is a property of the server, so a static list would credit sources that
 * are not running and omit ones that are.
 */
private val FALLBACK_CREDITS = listOf(
    Attribution("osm", "OpenStreetMap", "https://www.openstreetmap.org/copyright", "Places and map data"),
    Attribution("carto", "CARTO", "https://carto.com/attributions", "Map styling"),
    Attribution("osrm", "OSRM", "https://project-osrm.org", "Driving directions"),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var credits by remember { mutableStateOf(FALLBACK_CREDITS) }

    LaunchedEffect(Unit) {
        if (!GexemyClient.isConfigured) return@LaunchedEffect
        runCatching { GexemyClient.attributions() }.onSuccess { found ->
            // Merge rather than replace: the server knows about content sources, the app knows
            // about the tiles and the router it talks to directly.
            if (found.isNotEmpty()) {
                credits = (found + FALLBACK_CREDITS).distinctBy { it.source }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("About", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The two adaptive layers, composed the way a launcher composes them. Referencing
                // @mipmap/ic_launcher here instead would resolve to an AdaptiveIconDrawable on API
                // 26+, which painterResource cannot load — it only understands vectors and bitmaps.
                Box(
                    Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Adaptive layers are 108dp with only the centre 72dp guaranteed visible, so
                    // the launcher scales them by 108/72. Skipping that draws the mark two-thirds
                    // the size it appears on the home screen.
                    Image(
                        painterResource(R.drawable.ic_launcher_background),
                        contentDescription = null,
                        Modifier.fillMaxSize().scale(1.5f),
                    )
                    Image(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        Modifier.fillMaxSize().scale(1.5f),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("TasteRoute", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "TasteRoute learns what you like and finds you somewhere to eat — near you, along a " +
                    "drive, or in a city you are about to visit. Tell it what you are in the mood " +
                    "for and it will do the rest.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Block(
                "Ratings you can place",
                "Stars on a TasteRoute card come from people using TasteRoute, plus openly-licensed " +
                    "reviews. Ratings from Google, Tripadvisor and Yelp are shown separately, each " +
                    "under its own name, so you can see who is saying what. They are never blended " +
                    "into a single number.",
            )

            Block(
                "Allergens",
                "Allergen notes come from other diners and from what venues publish about themselves. " +
                    "One report of a bad experience marks a place as contested however many good " +
                    "reports it has. Treat all of it as a starting point, and always tell the " +
                    "restaurant yourself.",
            )

            Block(
                "Your data",
                "Your location is used to search and to navigate, and is never stored on our servers. " +
                    "Your taste, your saved places and your reviews are kept with your account so " +
                    "they follow you to a new phone. You can delete your account, and everything in " +
                    "it, from Profile → Account.",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column {
                Text("Thanks to", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "TasteRoute is built on work other people share openly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                credits.forEach { credit ->
                    // A row and a divider rather than a filled card each: five stacked surfaces
                    // read as five buttons competing for a tap, when this is a list to be read.
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(credit.label, style = MaterialTheme.typography.bodyLarge)
                            if (credit.note.isNotBlank()) {
                                Text(
                                    credit.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (credit.url.isNotBlank()) {
                            TextButton(onClick = { open(context, credit.url) }) { Text("Open") }
                        }
                    }
                }
            }

            Text(
                "Map data © OpenStreetMap contributors, available under the Open Database License.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Block(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
