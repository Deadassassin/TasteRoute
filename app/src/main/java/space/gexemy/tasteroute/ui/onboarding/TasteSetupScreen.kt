package space.gexemy.tasteroute.ui.onboarding

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.TasteAi
import kotlinx.coroutines.launch

private val examples = listOf(
    "Thai and Vietnamese, the spicier the better, cheap and quick",
    "Vegan, quiet places I can work in, no chains",
    "Date night sushi, happy to splurge",
    "Halal, family friendly, big portions",
)

/**
 * Free text in, structured taste profile out. Everything downstream still filters on the app's
 * own categories — the model only picks from them, so nothing here can produce a value a screen
 * doesn't know how to render.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TasteSetupScreen(onDone: () -> Unit, isFirstRun: Boolean) {
    var text by remember { mutableStateOf(AppState.tasteText) }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun apply() {
        if (text.isBlank() || working) return
        working = true
        failure = null
        scope.launch {
            runCatching { TasteAi.parse(text, AppState.profile) }
                .onSuccess {
                    AppState.onboarded = true
                    AppState.applyProfile(it, text)
                    onDone()
                }
                .onFailure { failure = it.message ?: "Couldn't read that — try the Profile tab instead." }
            working = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            if (isFirstRun) "What do you like to eat?" else "Retune your taste",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            "Say it however you want — cuisines, mood, budget, anything you avoid. " +
                "I'll turn it into your profile and use it everywhere in the app.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            placeholder = { Text("I love spicy Thai food and ramen, hate loud places, usually spending about $15…") },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Text("Or start from one of these", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            examples.forEach { example ->
                AssistChip(onClick = { text = example }, label = { Text(example) })
            }
        }

        failure?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = { apply() },
            enabled = text.isNotBlank() && !working,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp))
                Text("Building your profile…", style = MaterialTheme.typography.labelLarge)
            } else {
                Text(if (isFirstRun) "Start eating well" else "Update my profile", style = MaterialTheme.typography.labelLarge)
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(
                onClick = {
                    AppState.onboarded = true
                    AppState.persistProfile()
                    onDone()
                },
                enabled = !working,
            ) {
                Text(if (isFirstRun) "Skip for now" else "Cancel")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun TasteSummaryRow(summary: String, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            summary,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onEdit) { Text("Retune") }
    }
}
