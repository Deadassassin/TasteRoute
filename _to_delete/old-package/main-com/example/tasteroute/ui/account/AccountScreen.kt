package com.example.tasteroute.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.tasteroute.data.Session
import com.example.tasteroute.ui.components.BackChip
import kotlinx.coroutines.launch

/**
 * Sign in / sign up against the Gexemy accounts service. The app works fully signed out — an
 * account buys sync across devices, Table Sync, and the ability to contribute reports — so this
 * screen never blocks anything and is always reachable from Profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onBack: () -> Unit, onDone: () -> Unit) {
    var registering by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val canSubmit = email.contains('@') && password.length >= 10 && !Session.busy

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(72.dp))
            Text(
                if (registering) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Your taste profile, saved places and check-ins follow you to any device. " +
                    "You need an account to start a Table Sync or report a wait.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!Session.available) {
                Text(
                    "This build has no server configured — set GEXEMY_BASE_URL in local.properties.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (registering) {
                Field(displayName, { displayName = it }, "Name", KeyboardType.Text, ImeAction.Next)
            }
            Field(email, { email = it; Session.clearError() }, "Email", KeyboardType.Email, ImeAction.Next)
            Field(
                value = password,
                onValue = { password = it; Session.clearError() },
                label = "Password",
                keyboard = KeyboardType.Password,
                ime = ImeAction.Done,
                masked = true,
                helper = if (registering) "At least 10 characters" else null,
            )

            Session.syncError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            if (registering) Session.signUp(email, password, displayName)
                            else Session.signIn(email, password)
                        }.onSuccess { onDone() }
                    }
                },
                enabled = canSubmit && Session.available,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                if (Session.busy) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(if (registering) "Create account" else "Sign in", style = MaterialTheme.typography.labelLarge)
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { registering = !registering; Session.clearError() }) {
                    Text(if (registering) "I already have an account" else "Create an account instead")
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        BackChip(onBack, Modifier.align(Alignment.TopStart))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    keyboard: KeyboardType,
    ime: ImeAction,
    masked: Boolean = false,
    helper: String? = null,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ime),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        helper?.let {
            Text(
                it,
                Modifier.padding(start = 14.dp, top = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
