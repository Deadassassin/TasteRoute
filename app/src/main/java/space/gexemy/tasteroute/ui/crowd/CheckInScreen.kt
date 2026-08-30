package space.gexemy.tasteroute.ui.crowd

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.CrowdRepository
import space.gexemy.tasteroute.ui.components.BackChip
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val busyLabels = listOf(1 to "Quiet", 2 to "Steady", 3 to "Busy", 4 to "Packed", 5 to "Slammed")

/**
 * The supply side of live wait times. Kept to four taps: minutes, how full it looks, party size,
 * seated yet. Anything longer and nobody reports, and the feature has no data to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(onBack: () -> Unit) {
    val place = AppState.selectedRestaurant
    val scope = rememberCoroutineScope()

    var minutes by remember { mutableIntStateOf(10) }
    var busy by remember { mutableIntStateOf(3) }
    var partySize by remember { mutableIntStateOf(2) }
    var seated by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    if (place == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(64.dp))
            Text("How's the wait?", style = MaterialTheme.typography.headlineSmall)
            Text(
                place.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column {
                Text(
                    if (minutes == 0) "Walked straight in" else "$minutes min",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = { minutes = it.roundToInt() },
                    valueRange = 0f..90f,
                    steps = 17,
                )
            }

            Column {
                Text("How full does it look?", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(busyLabels, key = { it.first }) { (value, label) ->
                        FilterChip(
                            selected = busy == value,
                            onClick = { busy = value },
                            label = { Text(label) },
                            shape = CircleShape,
                        )
                    }
                }
            }

            Column {
                Text("Party of $partySize", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = partySize.toFloat(),
                    onValueChange = { partySize = it.roundToInt().coerceIn(1, 12) },
                    valueRange = 1f..12f,
                    steps = 10,
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Already seated", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = seated, onCheckedChange = { seated = it })
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (done) {
                Text(
                    "Thanks — everyone searching here sees that now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = {
                    if (sending) return@Button
                    sending = true
                    error = null
                    scope.launch {
                        runCatching { CrowdRepository.checkIn(place.id, minutes, busy, seated, partySize) }
                            .onSuccess {
                                done = true
                                AppState.selectedRestaurant = place.copy(pulse = it ?: place.pulse)
                                onBack()
                            }
                            .onFailure { error = it.message ?: "Couldn't send that report." }
                        sending = false
                    }
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Post report", style = MaterialTheme.typography.labelLarge)
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onBack) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }

        BackChip(onBack, Modifier.align(Alignment.TopStart))
    }
}
