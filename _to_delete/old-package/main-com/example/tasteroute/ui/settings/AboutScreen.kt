package com.example.tasteroute.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tasteroute.BuildConfig
import com.example.tasteroute.R
import com.example.tasteroute.data.Attribution
import com.example.tasteroute.data.GexemyClient

/**
 * What this app is, who made the data in it, and what it does with yours.
 *
 * The credits list is FETCHED, with a hardcoded fallback. Which sources are switched on is a
 * property of the server's configuration, not of the installed APK — turning Tripadvisor on should
 * not require a Play Store release before its name appears next to its content, and shipping a
 * static list would mean crediting sources that aren't running and omitting ones that are.
 */
private val FALLBACK_CREDITS = listOf(
    Attribution("osm", "OpenStreetMap", "https://www.openstreetmap.org/copyright", "Map and place data, ODbL"),
    Attribution("carto", "CARTO", "https://carto.com/attributions", "Map tiles"),
    Attribution("osrm", "OSRM", "https://project-osrm.org", "Driving directions"),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var credits by remember { mutableStateOf(FALLBACK_CREDITS) }
    var health by remember { mutableStateOf<GexemyClient.Health?>(null) }

    LaunchedEffect(Unit) {
        if (!GexemyClient.isConfigured) return@LaunchedEffect
        runCatching { GexemyClient.attributions() }.onSuccess { found ->
            // Merge rather than replace: the server knows about content sources, the app knows
            // about the tiles and the router it talks to directly.
            if (found.isNotEmpty()) {
                credits = (found + FALLBACK_CREDITS).distinctBy { it.source }
            }
        }
        runCatching { GexemyClient.health() }.onSuccess { health = it }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.mipmap.ic_launcher_round),
                    contentDescription = null,
                    Modifier.size(56.dp),
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("TasteRoute", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Block(
                "What it does",
                "TasteRoute learns what you like, then finds you somewhere to eat — near you, along " +
                    "a drive, or in a city you're about to visit. It ranks real places against your " +
                    "own taste profile, warns you about allergens other diners have reported, and " +
                    "drives you there turn by turn.",
            )

            Block(
                "Where the ratings come from",
                "Stars on a TasteRoute card are TasteRoute's own — written by people using this app, " +
                    "plus openly-licensed reviews. Ratings from Google, Tripadvisor and Yelp are shown " +
                    "separately, each under its own name and linked back to the original. They are " +
                    "never averaged together, because four platforms counting four different " +
                    "populations do not add up to a fifth number.",
            )

            Block(
                "Allergens",
                "Allergen information is reported by other diners and by what venues publish about " +
                    "themselves. Neither is a guarantee, and a single report of an unsafe experience " +
                    "marks a place as contested however many safe reports it has. Always tell the " +
                    "restaurant directly.",
            )

            Block(
                "Your data",
                "Your location is used to search and to navigate, and is never stored on our servers. " +
                    "Your taste profile, favourites and reviews are saved to your account so they " +
                    "follow you between devices — you can delete the account, and everything in it, " +
                    "from Profile → Account.",
            )

            Column {
                Text("Built on", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                credits.forEach { credit ->
                    Surface(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(credit.label, style = MaterialTheme.typography.bodyLarge)
                                if (credit.note.isNotBlank()) {
                                    Text(
                                        credit.note,
                                        style = MaterialTheme.typography.labelMedium,
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
            }

            health?.let { h ->
                Block(
                    "Service",
                    buildList {
                        add("TasteRoute service ${h.version.ifBlank { "" }}".trim() + if (h.ok) " · reachable" else " · unreachable")
                        if (h.sources.isNotEmpty()) add("Content sources live: ${h.sources.joinToString(", ")}")
                    }.joinToString("\n"),
                )
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
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
