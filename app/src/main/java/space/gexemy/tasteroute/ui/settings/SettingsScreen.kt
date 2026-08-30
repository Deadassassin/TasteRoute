package space.gexemy.tasteroute.ui.settings

import android.util.Log
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import space.gexemy.tasteroute.BuildConfig
import space.gexemy.tasteroute.data.ALL_ALLERGENS
import space.gexemy.tasteroute.data.ALL_CUISINES
import space.gexemy.tasteroute.data.ALL_DIETARY
import space.gexemy.tasteroute.data.ALL_VIBES
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.FontChoice
import space.gexemy.tasteroute.data.GexemyClient
import space.gexemy.tasteroute.data.LocationStatus
import space.gexemy.tasteroute.data.HttpException
import space.gexemy.tasteroute.data.Perf
import space.gexemy.tasteroute.data.Plans
import space.gexemy.tasteroute.data.Session
import space.gexemy.tasteroute.data.TasteAi
import space.gexemy.tasteroute.data.TasteProfile
import space.gexemy.tasteroute.data.ThemeMode
import space.gexemy.tasteroute.data.Tier
import space.gexemy.tasteroute.data.Units
import space.gexemy.tasteroute.data.Voice
import space.gexemy.tasteroute.ui.components.BackChip
import space.gexemy.tasteroute.ui.components.LocalRequestLocation
import space.gexemy.tasteroute.ui.theme.LocalBrandTones
import space.gexemy.tasteroute.ui.theme.fontFamilyFor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class PlanSpec(val tier: Tier, val title: String, val blurb: String)

private val plans = listOf(
    PlanSpec(Tier.FREE, "Free", "Top 8 nearby results · allergen filtering · Table Sync · 5 AI searches a day"),
    PlanSpec(Tier.PLUS, "Plus · ${Plans.PLUS_MONTHLY}/mo", "Unlimited AI search · on-my-way search · wider radius · full result list"),
    PlanSpec(Tier.PRO, "Pro · ${Plans.PRO_MONTHLY}/mo", "Everything in Plus · live wait times in minutes · wider route corridor"),
)

private fun List<String>.toggle(v: String) = if (v in this) this - v else this + v

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onRetune: () -> Unit,
    onAccount: () -> Unit,
    onAbout: () -> Unit = {},
    onBack: () -> Unit = {},
) {
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackChip(onBack, Modifier.padding(start = 0.dp))
            Text("Settings", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        }

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
                    AppState.cityLabel ?: locationHint(AppState.locationStatus),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = requestLocation) { Text("Update") }
            }
            SwitchRow("Precise location", AppState.preciseLocation) {
                AppState.preciseLocation = it
                space.gexemy.tasteroute.data.Prefs.put(space.gexemy.tasteroute.data.Prefs.PRECISE, it)
                requestLocation()
            }
            SwitchRow("Save dining history", AppState.saveHistory) {
                AppState.updateSaveHistory(it)
            }
            Text(
                "Location is used only to rank nearby matches. History personalizes future recommendations.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * A reachable server running last week's build fails in a way that looks nothing like a failure:
 * routes come back without turn directions and Yelp quietly returns nothing.
 *
 * TWO AUDIENCES, TWO STRINGS, and that separation is the point. The person holding the phone gets
 * the names of the features that are off, because that is the part they can see and act on — they
 * can stop waiting for a turn direction that is never coming. The instruction to redeploy a
 * tarball is addressed to whoever runs the server; it went to the screen for a year, and a
 * shipping app telling its user to redeploy anything is the clearest possible sign that nobody
 * separated the two. It goes to the log now.
 */
private fun missingCapabilities(health: GexemyClient.Health): List<String> {
    if (!health.reportsCapabilities) {
        Log.w(TAG, "Server predates the capability list — redeploy gexemy-api.tar.gz")
        return listOf("turn directions", "Yelp ratings")
    }
    val missing = buildList {
        if (!health.supports("route-steps")) add("turn directions")
        if (!health.supports("yelp")) add("Yelp ratings")
        if (!health.supports("sources")) add("Google and Tripadvisor ratings")
        if (!health.supports("catalog")) add("the wider place catalogue")
        if (!health.supports("ai-models")) add("the live model list")
    }
    if (missing.isNotEmpty()) {
        Log.w(TAG, "Server is behind this app: no ${missing.joinToString(", ")} — redeploy gexemy-api.tar.gz")
    }
    return missing
}

/** Filter Logcat on this to read what the connection card decided not to put on screen. */
private const val TAG = "TasteRouteService"

/**
 * The one screen that can answer "is it me or is it the server". A wrong `GEXEMY_BASE_URL` shows up
 * everywhere else as a vague failure to find restaurants, which is unfixable without this: it names
 * the host it is actually calling and says what came back.
 */
/**
 * One button and one line of answer.
 *
 * This used to be a diagnostics console — every capability the build knows about, every model
 * candidate with its milliseconds, the last failure string. All of it was real and all of it was
 * for whoever was building the app, not for whoever was using it, and it sat on the screen that
 * should have been about the person.
 *
 * 2026-08-27 finished that job. Three things were still speaking to a developer: the host name
 * stood at the top of the card as permanent furniture, a failure could print a raw exception
 * message, and a paragraph underneath explained how model selection works internally. The host
 * now appears only inside a FAILED result — which is the only moment it answers a question anyone
 * is asking — raw exception text never reaches the screen, and the paragraph is one sentence
 * about what happens to the person's search when the assistant is unreachable.
 */
@Composable
private fun ServiceSettings() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var healthy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    SectionCard("Connection") {
        Text(
            "Check that TasteRoute can reach its service. Everything on this screen works either " +
                "way — this only affects results, ratings and directions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = !checking && GexemyClient.isConfigured,
            onClick = {
                checking = true
                scope.launch {
                    runCatching { GexemyClient.health() }.fold(
                        onSuccess = { health ->
                            healthy = health.ok
                            val missing = missingCapabilities(health)
                            status = when {
                                !health.ok -> "Connected, but the service is reporting a problem. Try again shortly."
                                missing.isNotEmpty() ->
                                    "Connected. Not available right now: ${missing.joinToString(", ")}."
                                else -> "Connected. Everything is working."
                            }
                        },
                        onFailure = { error ->
                            healthy = false
                            Log.w(TAG, "Health check failed against ${GexemyClient.baseUrl}", error)
                            // The host is named ONLY here. On a working install nobody ever sees
                            // it; on a broken one it is the single fact that shortens the hunt,
                            // and burying it in the log would mean plugging in a cable to read it.
                            val host = GexemyClient.baseUrl.ifBlank { "no address is set" }
                            status = when ((error as? HttpException)?.code) {
                                404, 501 -> "Something answered at $host, but it isn't TasteRoute's service."
                                in 500..599 -> "The service is having a problem. Nothing to fix on this end."
                                else -> "Couldn't reach $host. Check your connection and try again."
                            }
                        },
                    )
                    checking = false
                }
            },
        ) {
            Text(if (checking) "Checking…" else "Test connection", maxLines = 1)
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (healthy) LocalBrandTones.current.live else MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "If the assistant can't be reached, TasteRoute still ranks places on your phone — " +
                "you get results either way, just not ones sorted to your taste.",
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
                    .padding(vertical = 8.dp),
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

/**
 * Was `"Location not set (${AppState.locationStatus.name.lowercase()})"` — the enum constant, in
 * brackets, in the middle of a sentence. `permission_denied` is the name of a value in this
 * codebase; it is not a thing to tell somebody about their phone.
 */
private fun locationHint(status: LocationStatus): String = when (status) {
    LocationStatus.DENIED -> "Location is turned off for TasteRoute"
    LocationStatus.DISABLED -> "Location is turned off on this phone"
    LocationStatus.ASKING -> "Waiting for permission…"
    LocationStatus.UNAVAILABLE -> "Can't get a location right now"
    else -> "Location not set"
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
