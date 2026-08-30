package com.example.tasteroute.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.ChatMessage
import com.example.tasteroute.data.ChatTurn
import com.example.tasteroute.data.Entitlements
import com.example.tasteroute.data.NimClient
import com.example.tasteroute.data.RecommendationRequest
import com.example.tasteroute.data.Recommender
import com.example.tasteroute.data.ResultSource
import com.example.tasteroute.data.TasteAi
import com.example.tasteroute.data.Tier
import com.example.tasteroute.ui.components.LocationBanner
import com.example.tasteroute.ui.components.RestaurantCard
import com.example.tasteroute.ui.components.ThinkingDots
import kotlinx.coroutines.launch

/**
 * Openers chosen to advertise that this is not a search box. Two of them ask for places, two of
 * them want an answer — if every suggestion returned a list, nobody would ever discover that they
 * can just ask a question.
 */
private val suggestions = listOf(
    "What should I eat tonight?",
    "How do I get more protein without meat?",
    "Somewhere quiet to actually talk",
    "Is pho a healthy lunch?",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(onOpenPlace: () -> Unit) {
    val tier = AppState.tier
    val messages = AppState.chatMessages
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    var thinking by remember { mutableStateOf(false) }

    fun say(text: String) = AppState.addChat(ChatMessage(fromUser = false, text = text))

    /**
     * Runs the place lookup the model asked for and folds the results into the reply it already
     * wrote. Separate from the conversation turn on purpose: the prose is on screen the moment the
     * model finishes writing it, and the restaurants slide in underneath a second or two later
     * rather than holding the whole reply hostage to a places call.
     */
    suspend fun attachPlaces(index: Int, query: String) {
        val reply = messages.getOrNull(index) ?: return
        val origin = AppState.origin
        if (origin == null) {
            AppState.replaceChat(
                index,
                reply.copy(
                    searching = false,
                    text = reply.text + "\n\nI need your location before I can pull up actual places — " +
                        "tap Allow on the banner above and ask me again.",
                ),
            )
            return
        }
        val cap = Entitlements.aiQueriesPerDay(tier)
        if (AppState.aiQueriesUsedToday >= cap) {
            AppState.replaceChat(
                index,
                reply.copy(
                    searching = false,
                    text = reply.text + "\n\nThat's your $cap free place searches for today, though — " +
                        "Plus lifts the cap. We can keep talking either way.",
                ),
            )
            return
        }
        AppState.aiQueriesUsedToday++

        val outcome = Recommender.recommend(
            RecommendationRequest(
                origin = origin,
                profile = AppState.profile,
                query = query,
                source = ResultSource.AI_CHAT,
                tier = tier,
                allergens = AppState.allergens.toList(),
            ),
            cityLabel = AppState.cityLabel,
        )
        AppState.replaceChat(
            index,
            reply.copy(
                searching = false,
                results = outcome.results,
                text = if (outcome.results.isEmpty()) {
                    reply.text + "\n\nNothing nearby matched that" +
                        (outcome.error?.let { " ($it)" } ?: "") + " — want me to widen it?"
                } else {
                    reply.text
                },
            ),
        )
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isBlank() || thinking) return
        AppState.addChat(ChatMessage(fromUser = true, text = prompt))
        input = ""

        if (!NimClient.chatReady) {
            say(
                if (NimClient.isConfigured) {
                    "I can't reach my language model right now (${NimClient.lastFailure ?: "no connection"}). " +
                        "Discover still works — it ranks places on the phone without me."
                } else {
                    "I'm not switched on yet — NIM_API_KEY is missing. Everything else in the app works without me."
                }
            )
            return
        }

        thinking = true
        scope.launch {
            val turn = runCatching {
                NimClient.converse(
                    history = messages.map { ChatTurn(it.fromUser, it.text) },
                    profile = AppState.profile,
                    allergens = AppState.allergens.toList(),
                    cityLabel = AppState.cityLabel,
                )
            }.getOrElse {
                thinking = false
                say("I lost that one — ${NimClient.lastFailure ?: it.message ?: "no reply"}. Ask me again?")
                return@launch
            }

            // A stated, lasting preference updates the profile the whole app scores on. One meal's
            // craving must not: "I fancy Thai tonight" is not "I am now a Thai person".
            turn.profile?.let { hint ->
                val next = TasteAi.merge(hint, AppState.profile)
                if (next != AppState.profile) AppState.applyProfile(next)
            }

            val wantsPlaces = turn.search.isNotBlank()
            val index = messages.size
            AppState.addChat(
                ChatMessage(
                    fromUser = false,
                    text = turn.say.ifBlank { "Tell me a bit more and I'll take a proper run at it." },
                    searching = wantsPlaces,
                )
            )
            thinking = false
            if (wantsPlaces) runCatching { attachPlaces(index, turn.search) }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Assistant", style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (thinking) "Thinking" else "Ask me anything about food",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (thinking) {
                        Spacer(Modifier.width(6.dp))
                        ThinkingDots()
                    }
                }
            }
            if (messages.isNotEmpty()) {
                TextButton(onClick = { AppState.clearChat() }) { Text("New chat") }
            }
        }

        LocationBanner(Modifier.padding(bottom = 8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssistantSurface {
                            Text(
                                "Hey. I can talk food, work around a diet, or go find you somewhere to eat — " +
                                    "whichever you're after.",
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { s ->
                                AssistChip(onClick = { send(s) }, label = { Text(s) })
                            }
                        }
                    }
                }
            }
            itemsIndexed(messages) { index, msg ->
                if (msg.fromUser) UserBubble(msg.text) else AssistantBubble(msg, tier, onOpenPlace)
                if (index == messages.lastIndex && msg.searching) SearchingRow()
            }
        }

        if (tier == Tier.FREE) {
            val cap = Entitlements.aiQueriesPerDay(Tier.FREE)
            Text(
                // Talking is free; only a real places lookup is metered, because that is the part
                // with a cost attached. A conversation that runs out after five messages is not a
                // conversation, it is a trial.
                "Chat is unlimited · ${(cap - AppState.aiQueriesUsedToday).coerceAtLeast(0)} of $cap " +
                    "place searches left today",
                Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask, or just say what you're after…") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { send(input) }, enabled = !thinking) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun SearchingRow() {
    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Finding places",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        ThinkingDots()
    }
}

@Composable
private fun AssistantSurface(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        content = content,
    )
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = MaterialTheme.colorScheme.primary) {
            Text(
                text,
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AssistantBubble(msg: ChatMessage, tier: Tier, onOpenPlace: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AssistantSurface {
            Text(
                msg.text,
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        msg.results.forEach { r ->
            RestaurantCard(
                result = r,
                userTier = tier,
                isFavorite = r.id in AppState.favorites,
                onToggleFavorite = { AppState.toggleFavorite(r.id) },
                onClick = {
                    AppState.selectedRestaurant = r
                    onOpenPlace()
                },
                showReasoning = true,
            )
        }
    }
}
