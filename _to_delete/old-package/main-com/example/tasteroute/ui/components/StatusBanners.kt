package com.example.tasteroute.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.LocationStatus

/** Provided by MainActivity so any screen can trigger the permission prompt / a re-fix. */
val LocalRequestLocation = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun LocationBanner(modifier: Modifier = Modifier) {
    val request = LocalRequestLocation.current
    val (message, action) = when (AppState.locationStatus) {
        LocationStatus.READY -> return
        LocationStatus.UNKNOWN, LocationStatus.ASKING ->
            "Finding you…" to null
        LocationStatus.DENIED ->
            "TasteRoute needs location access to find places near you." to "Allow"
        LocationStatus.DISABLED ->
            "Location is turned off on this device." to "Retry"
        LocationStatus.UNAVAILABLE ->
            "Couldn't get a location fix. Step outside or try again." to "Retry"
    }

    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (action == null) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                TextButton(onClick = request) {
                    Text(action, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

/** Shown where a location string belongs, e.g. "Las Vegas, NV". */
@Composable
fun OriginLabel(modifier: Modifier = Modifier) {
    val city = AppState.cityLabel ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.LocationOn, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text(
            city,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Pushed screens hide the bottom bar, so each one carries its own way back. */
@Composable
fun BackChip(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.padding(12.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                "Back",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun WarningNote(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
