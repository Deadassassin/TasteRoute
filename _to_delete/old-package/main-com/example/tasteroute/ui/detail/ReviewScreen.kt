package com.example.tasteroute.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.CrowdRepository
import com.example.tasteroute.data.GexemyClient
import com.example.tasteroute.ui.components.BackChip
import kotlinx.coroutines.launch

/**
 * Write or edit a review. One per person per place — submitting again edits the existing one, which
 * is the only way an average stays honest without a moderation queue, so the screen loads what you
 * wrote before rather than pretending it's new.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val place = AppState.selectedRestaurant
    val scope = rememberCoroutineScope()

    var rating by remember { mutableIntStateOf(0) }
    var body by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (place == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LaunchedEffect(place.id) {
        runCatching { GexemyClient.reviewsFor(place.id, place.coordinates) }.onSuccess { page ->
            page.mine?.let {
                rating = it.rating
                body = it.body
            }
        }
        loaded = true
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(64.dp))
            Text("Rate ${place.name}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { star ->
                    Icon(
                        if (star <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "$star star${if (star == 1) "" else "s"}",
                        tint = if (star <= rating) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(38.dp)
                            .clickable { rating = star },
                    )
                }
            }

            OutlinedTextField(
                value = body,
                onValueChange = { body = it.take(2000) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                placeholder = { Text("What was it like? Anything worth ordering?") },
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    if (sending || rating < 1) return@Button
                    sending = true
                    error = null
                    scope.launch {
                        runCatching { GexemyClient.writeReview(place.id, rating, body.trim()) }
                            .onSuccess { page ->
                                CrowdRepository.noteRating(place.id, page.summary)
                                AppState.selectedRestaurant = place.copy(
                                    rating = page.summary.rating,
                                    reviewCount = page.summary.count,
                                )
                                onBack()
                            }
                            .onFailure { error = it.message ?: "Couldn't post that review." }
                        sending = false
                    }
                },
                enabled = rating >= 1 && !sending && loaded,
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
                    Text(
                        if (rating < 1) "Pick a rating" else "Post review",
                        style = MaterialTheme.typography.labelLarge,
                    )
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
