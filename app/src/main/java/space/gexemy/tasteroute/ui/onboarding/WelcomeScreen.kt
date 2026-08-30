package space.gexemy.tasteroute.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.R
import space.gexemy.tasteroute.data.Session
import space.gexemy.tasteroute.ui.account.AccountScreen

/**
 * First thing anyone sees. Signing in is offered up front because it is the only way a returning
 * user gets their taste profile back — but it is never required: skipping lands on the same app,
 * just device-local. The taste setup comes after this, and is skipped entirely if the account you
 * signed into already has a profile.
 */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    var showAccount by remember { mutableStateOf(false) }

    if (showAccount) {
        AccountScreen(
            onBack = { showAccount = false },
            onDone = onContinue,
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The app's own mark, composed from the two adaptive layers the way a launcher composes
        // them (108/72 scale — see AboutScreen). It was the letters "TR" in a coloured circle:
        // the default placeholder avatar, on the one screen whose entire job is the first
        // impression, while a real icon shipped in the same APK.
        Box(
            Modifier.size(72.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
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

        Spacer(Modifier.height(24.dp))
        Text(
            "TasteRoute",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Places that fit how you actually eat — with live waits, real allergen reports, " +
                "and picks that work for a whole table.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { showAccount = true },
            enabled = Session.available,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Text("Sign in or create an account", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Text("Skip for now", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            if (Session.available) {
                "An account syncs your profile across devices and lets you report waits. " +
                    "Everything else works without one."
            } else {
                "Account sync isn't configured in this build — skip for now."
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
