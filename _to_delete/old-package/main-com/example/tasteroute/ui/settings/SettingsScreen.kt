package com.example.tasteroute.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.tasteroute.BuildConfig
import com.example.tasteroute.data.ALL_ALLERGENS
import com.example.tasteroute.data.ALL_CUISINES
import com.example.tasteroute.data.ALL_DIETARY
import com.example.tasteroute.data.ALL_VIBES
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.Backoff
import com.example.tasteroute.data.FontChoice
import com.example.tasteroute.data.GexemyClient
import com.example.tasteroute.data.HttpException
import com.example.tasteroute.data.NimClient
import com.example.tasteroute.data.Perf
import com.example.tasteroute.data.Session
import com.example.tasteroute.data.TasteAi
import com.example.tasteroute.data.TasteProfile
import com.example.tasteroute.data.ThemeMode
import com.example.tasteroute.data.Tier
import com.example.tasteroute.data.Units
import com.example.tasteroute.data.Voice
import com.example.tasteroute.ui.components.LocalRequestLocation
import com.example.tasteroute.ui.components.WarningNote
import com.example.tasteroute.ui.theme.LocalBrandTones
import com.example.tasteroute.ui.theme.fontFamilyFor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class PlanSpec(val tier: Tier, val title: String, val blurb: String)

private val plans = listOf(
    PlanSpec(Tier.FREE, "Free", "Top 8 nearby results · allergen filtering · Table Sync · 5 AI searches a day"),
    PlanSpec(Tier.PLUS, "Plus · $6/mo", "Unlimited AI search · on-my-way search · wider radius · full result list"),
    PlanSpec(Tier.PRO, "Pro · $12/mo", "Everything in Plus · live wait times in minutes · wider route corridor"),
)

private fun List<String>.toggle(v: String) = if (v in this) this - v else this + v

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onRetune: () -> Unit, onAccount: () -> Unit, onAbout: () -> Unit = {}) {
    val profile = AppState.profile
    val requestLocation = LocalRequestLocation.current
    fun update(transform: (TasteProfile) -> TasteProfile) {
        AppState.applyProfile(transform(AppState.profile))
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Profile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))

        SectionCard("Account") {
            val account = Session.account
            if (Session.signedIn && account != null) {
                Text(account.displayName.ifBlank { account.email }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Signed in · taste profile, saved places and check-ins sync to this account.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { Session.signOut() }) { Text("Sign out") }
            } else {
                Text(
                    "Sign in to sync across devices, start a Table Sync and report waits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAccount, modifier = Modifier.fillMaxWidth()) { Text("Sign in or create an account") }
            }
            Session.syncError?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }

        SectionCard("Appearance") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = AppState.themeMode == mode,
                        onClick = { AppState.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                    ) {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "Automatic"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                        )
                    }
                }
            }
            Text(
                if (Perf.richMotion) {
                    "Automatic follows your phone's dark mode schedule."
                } else {
                    "Automatic follows your phone's dark mode. Animations are simplified on this device to keep scrolling smooth."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Font") {
            FontPicker()
            Label("Text size · ${(AppState.fontScale * 100).roundToInt()}%")
            Slider(
                value = AppState.fontScale,
                onValueChange = { AppState.updateFontScale(it) },
                valueRange = 0.85f..1.35f,
                steps = 9,
            )
            Text(
                "This stacks on top of your phone's own text size setting rather than replacing it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Allergens") {
            Text(
                "Hard filter. Places the community has reported unsafe for these are removed from every result list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipFlow(ALL_ALLERGENS, AppState.allergens.toList()) { AppState.toggleAllergen(it) }
            if (AppState.allergens.isNotEmpty()) {
                Text(
                    "Reports come from other diners, not from restaurants. Always confirm at the table.",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalBrandTones.current.allergenContested,
                )
            }
        }

        SectionCard("Taste profile") {
            Text(
                TasteAi.summarize(profile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetune, modifier = Modifier.fillMaxWidth()) {
                Text("Describe your taste in your own words")
            }

            Label("Cuisines you love")
            ChipFlow(ALL_CUISINES, profile.preferredCuisines) { c ->
                update { it.copy(preferredCuisines = it.preferredCuisines.toggle(c)) }
            }
            Label("Dietary preferences")
            ChipFlow(ALL_DIETARY, profile.dietaryRestrictions) { d ->
                update { it.copy(dietaryRestrictions = it.dietaryRestrictions.toggle(d)) }
            }
            Label("Price comfort · ${"$".repeat(profile.priceComfort)}")
            Slider(
                value = profile.priceComfort.toFloat(),
                onValueChange = { v -> update { it.copy(priceComfort = v.roundToInt().coerceIn(1, 4)) } },
                valueRange = 1f..4f,
                steps = 2,
            )
            Label("Vibes")
            ChipFlow(ALL_VIBES, profile.vibeTags) { v ->
                update { it.copy(vibeTags = it.vibeTags.toggle(v)) }
            }
        }

        SectionCard("Subscription") {
            plans.forEach { plan ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            AppState.tier = plan.tier
                            AppState.persistProfile()
                            Session.pushProfile()
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = AppState.tier == plan.tier,
                        onClick = {
                            AppState.tier = plan.tier
                            AppState.persistProfile()
                            Session.pushProfile()
                        },
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(plan.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            plan.blurb,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard("Saved places") {
            if (AppState.favorites.isEmpty()) {
                Text(
                    "Tap the heart on any restaurant to save it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AppState.favorites.forEach { id ->
                    val name = AppState.knownNames[id] ?: id
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { AppState.toggleFavorite(id) }) {
                            Icon(Icons.Filled.Favorite, "Remove", tint = LocalBrandTones.current.favorite)
                        }
                    }
                }
            }
        }

        ServiceSettings()

        NavigationSettings()

        SectionCard("About TasteRoute") {
            Text(
                "Version ${BuildConfig.VERSION_NAME} · what the app does, where its ratings come " +
                    "from, and who to credit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("About and credits") }
        }

        SectionCard("Location") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    AppState.cityLabel ?: "Location not set (${AppState.locationStatus.name.lowercase()})",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = requestLocation) { Text("Update") }
            }
            SwitchRow("Precise location", AppState.preciseLocation) {
                AppState.preciseLocation = it
                com.example.tasteroute.data.Prefs.put(com.example.tasteroute.data.Prefs.PRECISE, it)
                requestLocation()
            }
            SwitchRow("Save dining history", AppState.saveHistory) {
                AppState.saveHistory = it
                com.example.tasteroute.data.Prefs.put(com.example.tasteroute.data.Prefs.HISTORY, it)
            }
            Text(
                "Location is used only to rank nearby matches. History personalizes future recommendations.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
    }
}

/**
 * A reachable server running last week's build fails in a way that looks nothing like a failure:
 * routes come back without turn directions and Yelp quietly returns nothing. Naming it is the
 * difference between a redeploy and an afternoon of debugging the app.
 */
private fun missingCapabilities(health: GexemyClient.Health): String? {
    if (!health.reportsCapabilities) {
        return "This build predates the capability list, so it has neither turn directions nor Yelp. " +
            "Redeploy gexemy-api.tar.gz on the server."
    }
    val missing = buildList {
        if (!health.supports("route-steps")) add("turn directions")
        if (!health.supports("yelp")) add("Yelp ratings")
        if (!health.supports("sources")) add("Google/Tripadvisor content")
        if (!health.supports("catalog")) add("the open place catalog")
    }
    if (missing.isEmpty()) return null
    return "Server is behind this app: no ${missing.joinToString(" or ")}. Redeploy gexemy-api.tar.gz."
}

/**
 * The one screen that can answer "is it me or is it the server". A wrong `GEXEMY_BASE_URL` shows up
 * everywhere else as a vague failure to find restaurants, which is unfixable without this: it names
 * the host it is actually calling and says what came back.
 */
@Composable
private fun ServiceSettings() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var healthy by remember { mutableStateOf(false) }
    var behind by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var rankerStatus by remember { mutableStateOf<String?>(null) }
    var rankerOk by remember { mutableStateOf(false) }
    var rankerChecking by remember { mutableStateOf(false) }
    var probes by remember { mutableStateOf(NimClient.lastProbe) }
    var probing by remember { mutableStateOf(false) }
    var activeModel by remember { mutableStateOf(NimClient.activeModel) }

    SectionCard("Services") {
        Label("Places, waits and accounts")
        Text(
            GexemyClient.baseUrl.ifBlank { "GEXEMY_BASE_URL is not set in local.properties" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = !checking && GexemyClient.isConfigured,
            onClick = {
                checking = true
                status = null
                scope.launch {
                    runCatching { GexemyClient.health() }.fold(
                        onSuccess = {
                            healthy = it.ok && it.db
                            status = buildString {
                                append(if (it.ok) "Reachable" else "Answered, but not with ok")
                                if (it.version.isNotBlank()) append(" · v${it.version}")
                                append(if (it.db) " · database up" else " · DATABASE UNREACHABLE")
                            }
                            behind = missingCapabilities(it)
                            // A good probe clears the breaker, so the next search doesn't sit out
                            // the rest of a window opened by an earlier failure.
                            if (it.ok) Backoff.clear(Backoff.PLACES)
                        },
                        onFailure = { error ->
                            healthy = false
                            behind = null
                            status = when ((error as? HttpException)?.code) {
                                404, 501 -> "404 — that host answered but doesn't serve this API. " +
                                    "Routes live at /v1/…, so the base URL must not include a path."
                                else -> error.message?.take(140) ?: "Unreachable"
                            }
                        },
                    )
                    checking = false
                }
            },
        ) {
            Text(if (checking) "Checking…" else "Test connection")
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (healthy) LocalBrandTones.current.live else MaterialTheme.colorScheme.error,
            )
        }
        behind?.let { WarningNote(it) }
        Text(
            "Live waits, allergen reports, photos, reviews, Yelp ratings and Table Sync come from " +
                "this service. Search still works without it, straight from OpenStreetMap.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Label("AI ranker")
        Text(
            if (NimClient.isConfigured) "$activeModel\n${BuildConfig.NIM_BASE_URL}"
            else "NIM_API_KEY is not set in local.properties",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Shown without pressing anything: by the time someone opens this screen the failure has
        // already happened, and asking them to reproduce it on demand is how a bug goes unreported.
        NimClient.lastFailure?.let { failure ->
            Text(
                "Last failure: $failure",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            enabled = !rankerChecking && NimClient.isConfigured,
            onClick = {
                rankerChecking = true
                rankerStatus = null
                scope.launch {
                    // ping() goes through the same path as a real rank, so a pass here clears the
                    // ranker's backoff and the next search uses it immediately.
                    runCatching { NimClient.ping() }.fold(
                        onSuccess = {
                            rankerOk = true
                            rankerStatus = "Answering — $it"
                        },
                        onFailure = { error ->
                            rankerOk = false
                            rankerStatus = when ((error as? HttpException)?.code) {
                                401, 403 -> "Rejected the key — check NIM_API_KEY in local.properties."
                                404 -> "No such model — check NIM_MODEL (${BuildConfig.NIM_MODEL})."
                                429 -> "Rate limited by NVIDIA. It'll come back on its own."
                                else -> error.message?.take(140) ?: "Unreachable"
                            }
                        },
                    )
                    rankerChecking = false
                }
            },
        ) {
            Text(if (rankerChecking) "Checking…" else "Test AI ranker")
        }
        rankerStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (rankerOk) LocalBrandTones.current.live else MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(
            enabled = !probing && NimClient.isConfigured,
            onClick = {
                probing = true
                scope.launch {
                    probes = NimClient.probeModels()
                    activeModel = NimClient.activeModel
                    probing = false
                }
            },
        ) {
            Text(if (probing) "Racing the models…" else "Find the fastest model")
        }
        probes.forEach { probe ->
            val winner = probe.model == activeModel && probe.ok
            Text(
                "${if (winner) "→ " else "   "}${probe.model} — ${if (probe.ok) "${probe.millis} ms" else probe.error}",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    winner -> LocalBrandTones.current.live
                    probe.ok -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
        Text(
            "The ranker only reorders places the app already found — it can't invent one, so a small " +
                "fast model is the right tool. Whichever answers a ping quickest is the one it uses; " +
                "when none can, the local scorer ranks and results still look right.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Voice settings need the engine attached to know what the device actually has installed, so the
 * card starts it. Speaking a sample on selection is the point: nobody can pick a navigation voice
 * from a name like "en-us-x-tpd-network".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationSettings() {
    val context = LocalContext.current
    var voices by remember { mutableStateOf(Voice.installed) }

    LaunchedEffect(Unit) {
        Voice.attach(context) { voices = Voice.installed }
    }

    SectionCard("Navigation") {
        SwitchRow("Voice guidance", AppState.navVoice) { AppState.updateNavVoice(it) }

        Label("Distances")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Units.entries.forEachIndexed { index, unit ->
                SegmentedButton(
                    selected = AppState.units == unit,
                    onClick = { AppState.updateUnits(unit) },
                    shape = SegmentedButtonDefaults.itemShape(index, Units.entries.size),
                ) {
                    Text(
                        when (unit) {
                            Units.AUTO -> "Auto"
                            Units.METRIC -> "km"
                            Units.IMPERIAL -> "miles"
                        }
                    )
                }
            }
        }

        Label("Speaking speed")
        Slider(
            value = AppState.voiceSpeed,
            onValueChange = { AppState.updateVoiceSpeed(it) },
            valueRange = 0.7f..1.4f,
            steps = 6,
        )

        if (voices.isNotEmpty()) {
            Label("Voice")
            VoiceRow("Automatic", AppState.voiceName.isEmpty()) { AppState.updateVoiceName("") }
            voices.forEach { option ->
                VoiceRow(option.label, AppState.voiceName == option.id) { AppState.updateVoiceName(option.id) }
            }
        }

        OutlinedButton(onClick = { Voice.say("In a quarter mile, turn right onto Market Street.", force = true) }) {
            Text("Hear it")
        }
        Text(
            "Guidance runs inside TasteRoute — no handoff to another maps app. Music ducks under " +
                "instructions rather than stopping.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Rows preview in their own face — a font list rendered in one font is useless. System faces sit at
 * the top because they need no download; the rest stream in through Play Services' font provider
 * the first time they are used, and fall back to the system face if that isn't available.
 */
@Composable
private fun FontPicker() {
    var expanded by remember { mutableStateOf(false) }
    val current = AppState.fontChoice
    val shown = if (expanded) FontChoice.entries else FontChoice.entries.take(6)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEach { choice ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { AppState.setFont(choice) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == choice, onClick = { AppState.setFont(choice) })
                Column(Modifier.padding(start = 4.dp).weight(1f)) {
                    Text(
                        choice.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = fontFamilyFor(choice),
                    )
                    Text(
                        choice.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Show fewer" else "More fonts (${FontChoice.entries.size - 6})")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(all: List<String>, selected: List<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        all.forEach { item ->
            FilterChip(
                selected = item in selected,
                onClick = { onToggle(item) },
                label = { Text(item) },
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Perf.cardElevationDp.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
