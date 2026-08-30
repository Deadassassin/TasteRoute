package space.gexemy.tasteroute.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.GexemyClient
import space.gexemy.tasteroute.data.Session
import space.gexemy.tasteroute.data.Social
import space.gexemy.tasteroute.ui.components.BackChip
import kotlinx.coroutines.launch

/**
 * Add by handle, and nothing else.
 *
 * No contact upload, no phone number matching, no "people you may know". Those all work by taking
 * something the person did not offer, and the whole point of the handle is that it is the one piece
 * of identity they chose to be findable by — and can drop at any time, which unlists them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(onBack: () -> Unit, onOpenFriend: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var handle by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Social.refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackChip(onBack)
            Text("Friends", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("add") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = handle,
                            onValueChange = { handle = it; message = null },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("@handle", maxLines = 1) },
                            singleLine = true,
                            shape = CircleShape,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (!busy) scope.launch {
                                    busy = true; message = Social.add(handle); busy = false
                                    if (message == null) handle = ""
                                }
                            }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !busy && handle.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    busy = true; message = Social.add(handle); busy = false
                                    if (message == null) handle = ""
                                }
                            },
                        ) { Text("Add", maxLines = 1) }
                    }
                    message?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                    val mine = Session.account?.handle
                    Text(
                        if (mine != null) "Yours is @$mine — that's what people type to find you."
                        else "Pick your own handle from the profile screen so people can find you back.",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (Social.incoming.isNotEmpty()) {
                item("inhead") { Header("Waiting on you") }
                items(Social.incoming, key = { "in-${it.linkId}" }) { link ->
                    PersonRow(link.user) {
                        TextButton(onClick = { scope.launch { Social.respond(link.linkId, true) } }) {
                            Text("Accept", maxLines = 1)
                        }
                        TextButton(onClick = { scope.launch { Social.respond(link.linkId, false) } }) {
                            Text(
                                "Ignore",
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (Social.outgoing.isNotEmpty()) {
                item("outhead") { Header("Asked") }
                items(Social.outgoing, key = { "out-${it.linkId}" }) { link ->
                    PersonRow(link.user) {
                        Text(
                            "Pending",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item("friendshead") { Header("Friends") }
            if (Social.friends.isEmpty()) {
                item("none") {
                    Text(
                        "Nobody yet. A friendship is mutual here — they have to accept, and either of " +
                            "you can undo it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(Social.friends, key = { "f-${it.linkId}" }) { link ->
                PersonRow(link.user, onClick = { onOpenFriend(link.user.id) }) {
                    TextButton(onClick = { scope.launch { Social.remove(link.linkId) } }) {
                        Text("Remove", maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
    )
}

@Composable
private fun PersonRow(
    person: GexemyClient.Person,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Text(
                        person.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(person.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                person.handle?.let {
                    Text(
                        "@$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            trailing()
        }
    }
}
