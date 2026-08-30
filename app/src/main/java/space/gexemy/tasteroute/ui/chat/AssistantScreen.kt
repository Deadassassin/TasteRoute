package space.gexemy.tasteroute.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.ChatMessage
import space.gexemy.tasteroute.data.ChatTurn
import space.gexemy.tasteroute.data.Entitlements
import space.gexemy.tasteroute.data.LocationRepository
import space.gexemy.tasteroute.data.NimClient
import space.gexemy.tasteroute.data.Openers
import space.gexemy.tasteroute.data.Perf
import space.gexemy.tasteroute.data.RecommendationRequest
import space.gexemy.tasteroute.data.Recommender
import space.gexemy.tasteroute.data.RestaurantResult
import space.gexemy.tasteroute.data.ResultSource
import space.gexemy.tasteroute.data.SearchMode
import space.gexemy.tasteroute.data.TasteAi
import space.gexemy.tasteroute.data.Tier
import space.gexemy.tasteroute.ui.components.LocationBanner
import space.gexemy.tasteroute.ui.components.RestaurantCard
import space.gexemy.tasteroute.ui.components.ThinkingDots
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many of the places already on screen the model is allowed to see and talk about. */
private const val GROUNDING_LIMIT = 12

/**
 * The conversation, laid out the way a conversation actually reads.
 *
 * The rework is one decision applied consistently: **only the person gets a bubble.** A chat where
 * both sides are boxed is two columns of shouting, and it caps the reply at a bubble's width just
 * when the reply is the longest thing on screen. The assistant's prose sits flat on the background
 * under a small mark, so a four-line answer reads as text rather than as a container, and the place
 * cards below it belong to the same block instead of hanging off a speech bubble.
 *
 * There is no "New chat" control here any more. That job moved to the raised button in the
 * navigation bar, which turns into a plus once you are on this screen — see AiFab. Two controls for
 * one action is how you end up with people hunting for the one they remember.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onOpenPlace: () -> Unit) {
    val context = LocalContext.current
    val tier = AppState.tier
    val messages = AppState.chatMessages
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    var thinking by remember { mutableStateOf(false) }

    // The reply as it is being written. Kept out of the persisted message list on purpose: a
    // half-finished sentence has no business in the chat log if the process dies mid-stream.
    var streaming by remember { mutableStateOf<String?>(null) }

    // The navigation bar's button pulses while this is true, from any tab. Mirrored rather than
    // hoisted so the local `thinking` stays the single thing this screen's own branches read.
    LaunchedEffect(thinking) { AppState.assistantBusy = thinking }

    val isStreaming by remember { derivedStateOf { !streaming.isNullOrEmpty() } }

    // Leaving mid-reply must not leave the button pulsing forever. The coroutine that clears
    // `thinking` is cancelled with the screen, so this is the only thing that would.
    DisposableEffect(Unit) { onDispose { AppState.assistantBusy = false } }

    // Rebuilt every time the screen is entered, from this person's taste and the time of day.
    // A fixed four-item list was the same four questions forever, which taught people the
    // assistant had exactly four things to say.
    val suggestions = remember(AppState.profile) { Openers.forProfile(AppState.profile) }

    fun say(text: String) = AppState.addChat(ChatMessage(fromUser = false, text = text))

    /**
     * Runs the place lookup the model asked for and folds the results into the reply it already
     * wrote. Separate from the conversation turn on purpose: the prose is on screen the moment the
     * model finishes writing it, and the restaurants slide in underneath a second or two later
     * rather than holding the whole reply hostage to a places call.
     */
    suspend fun attachPlaces(index: Int, query: String, area: String, keep: List<RestaurantResult>) {
        val reply = messages.getOrNull(index) ?: return
        // "Somewhere in Vegas" asked from Boulder City is an ordinary question, and answering it
        // with "you're in Boulder City" was the app refusing to do the one thing it was asked. When
        // the model names an area, geocode it and search THERE — distances are then measured from
        // that centre, which is the same rule Anywhere mode already follows, so a place four
        // minutes off the Strip reads as four minutes off the Strip and not forty from here.
        val target = area.takeIf { it.isNotBlank() }?.let { LocationRepository.geocode(context, it) }
        if (area.isNotBlank() && target == null) {
            AppState.replaceChat(
                index,
                reply.copy(
                    searching = false,
                    text = reply.text + "\n\nI couldn't place \"" + area.take(60) +
                        "\" on the map, though — try naming the city or a street in it?",
                ),
            )
            return
        }
        val origin = target?.first ?: AppState.origin
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
                // ANYWHERE is what makes the radius city-scale rather than the three kilometres
                // that a walk-there search is sized for. Same code path, correct number.
                mode = if (target != null) SearchMode.ANYWHERE else SearchMode.NEARBY,
            ),
            cityLabel = target?.second ?: AppState.cityLabel,
        )
        // Places it named from what was already on screen lead, because it actually meant those.
        val merged = (keep + outcome.results).distinctBy { it.id }
        AppState.replaceChat(
            index,
            reply.copy(
                searching = false,
                results = merged,
                text = if (merged.isEmpty()) {
                    reply.text + "\n\nNothing " + (target?.let { "in " + it.second.take(40) } ?: "nearby") +
                        " matched that. Want me to widen the search?"
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
                    "I can't answer right now. Discover still works: it ranks places on your " +
                        "phone without me."
                } else {
                    "I'm not switched on in this build. Everything else in the app works without me."
                }
            )
            return
        }

        thinking = true
        streaming = ""
        // What the app already knows is around this person. Without it the model was answering
        // "what's good near me" from nothing while a screenful of real, ranked places sat one tab
        // away — and its only honest move was to refuse.
        val nearby = AppState.lastResults.ifEmpty { AppState.warmResults }.take(GROUNDING_LIMIT)

        scope.launch {
            val turn = runCatching {
                NimClient.converse(
                    history = messages.map { ChatTurn(it.fromUser, it.text) },
                    profile = AppState.profile,
                    allergens = AppState.allergens.toList(),
                    cityLabel = AppState.cityLabel,
                    nearby = nearby,
                    onDelta = { partial -> streaming = partial },
                )
            }.getOrElse {
                thinking = false
                streaming = null
                say("I lost that one. Ask me again?")
                return@launch
            }

            // A stated, lasting preference updates the profile the whole app scores on. One meal's
            // craving must not: "I fancy Thai tonight" is not "I am now a Thai person".
            turn.profile?.let { hint ->
                val next = TasteAi.merge(hint, AppState.profile)
                if (next != AppState.profile) AppState.applyProfile(next)
            }

            // Indices into the list it was shown, resolved back to the real objects. The model
            // never produces a name, an address or a rating — only a number.
            val cited = turn.places.mapNotNull { nearby.getOrNull(it - 1) }.distinctBy { it.id }
            // An area with no search phrase still means "go and look over there" — falling back to
            // the person's own words is better than dropping the request on the floor, which is
            // what ignoring it would do.
            val wantsPlaces = turn.search.isNotBlank() || turn.area.isNotBlank()
            val index = messages.size
            AppState.addChat(
                ChatMessage(
                    fromUser = false,
                    // Two different blanks. If it asked for places but wrote no introduction, the
                    // cards ARE the answer and "tell me more" sitting above them reads as a
                    // non-sequitur; if it wrote nothing at all, there is genuinely nothing to show.
                    text = turn.say.ifBlank {
                        if (wantsPlaces || cited.isNotEmpty()) "Here's what I found for that:"
                        else "Tell me a bit more and I'll take a proper run at it."
                    },
                    results = cited,
                    searching = wantsPlaces,
                )
            )
            thinking = false
            streaming = null
            if (wantsPlaces) runCatching {
                attachPlaces(index, turn.search.ifBlank { prompt }, turn.area, cited)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // A 140ms scrollToItem loop, not animateScrollToItem: retargeting an animated scroll on every
    // token cancels it before it has moved anywhere, so the list appears to stick.
    LaunchedEffect(streaming != null) {
        while (streaming != null) {
            listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            delay(140)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Header(thinking = thinking, isStreaming = isStreaming)

        LocationBanner(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (messages.isEmpty() && streaming == null) {
                item(key = "opening", contentType = "opening") { Opening(suggestions) { send(it) } }
            }
            itemsIndexed(
                items = messages,
                key = { index, msg -> "msg-$index-${msg.text.hashCode()}" },
                contentType = { _, msg -> if (msg.fromUser) "user" else "assistant" }
            ) { index, msg ->
                if (msg.fromUser) UserBubble(msg.text) else AssistantTurnBlock(msg, tier, onOpenPlace)
                if (index == messages.lastIndex && msg.searching) SearchingRow()
            }
            streaming?.takeIf { it.isNotEmpty() }?.let { partial ->
                item(key = "streaming", contentType = "assistant") {
                    AssistantRow {
                        TypingText(partial, MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        Composer(
            value = input,
            onValue = { input = it },
            onSend = { send(input) },
            enabled = !thinking,
        )

        if (tier == Tier.FREE) {
            val cap = Entitlements.aiQueriesPerDay(Tier.FREE)
            Text(
                // Talking is free; only a real places lookup is metered, because that is the part
                // with a cost attached. A conversation that runs out after five messages is not a
                // conversation, it is a trial.
                "Chat is unlimited · ${(cap - AppState.aiQueriesUsedToday).coerceAtLeast(0)} of $cap " +
                    "searches left today",
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(thinking: Boolean, isStreaming: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Assistant",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        isStreaming -> "Writing"
                        thinking -> "Thinking"
                        else -> "Ask me anything about food"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (thinking) {
                    Spacer(Modifier.width(8.dp))
                    ThinkingDots()
                }
            }
        }
    }
}

/**
 * The empty state, which is the screen most people see most often.
 *
 * Full-width rows rather than a wrap of small chips: a chip that has to fit "somewhere warm and
 * quiet for a late dinner" either truncates it or wraps to three lines, and a row of half-read
 * questions teaches people the assistant only handles short ones.
 */
@Composable
private fun Opening(suggestions: List<String>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(8.dp))
        Mark(size = 44.dp, icon = 24.dp)
        Spacer(Modifier.height(2.dp))
        Text(
            "What are you in the mood for?",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "I can talk food, work around a diet, or go and find you somewhere to eat — " +
                "whichever you're after.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        suggestions.forEach { s ->
            Surface(
                Modifier.fillMaxWidth().clickable { onPick(s) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * The assistant's mark. Same glyph as the navigation button, so the two read as one thing.
 *
 * It was `AutoAwesome` — the four-pointed sparkle. That glyph has become the universal badge for
 * "a language model wrote this", and on a button whose whole job is to start a conversation it
 * says less than a speech bubble does while carrying a claim nobody asked for. The bubble also
 * makes the morph honest: a conversation turning into a plus reads as "start another one".
 */
@Composable
private fun Mark(size: Dp = 26.dp, icon: Dp = 15.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.ChatBubbleOutline,
            null,
            Modifier.size(icon),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Mark on the left, content in the remaining width. No container — see the file header. */
@Composable
private fun AssistantRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Mark()
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).padding(top = 4.dp)) { content() }
    }
}

@Composable
private fun AssistantTurnBlock(msg: ChatMessage, tier: Tier, onOpenPlace: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AssistantRow {
            Text(msg.text, style = MaterialTheme.typography.bodyLarge)
        }
        // Cards sit under the whole turn rather than inside a bubble, and are indented to the
        // prose's left edge so the block reads as one answer.
        msg.results.forEach { r ->
            RestaurantCard(
                result = r,
                userTier = tier,
                isFavorite = r.id in AppState.favorites,
                onToggleFavorite = { AppState.toggleFavorite(r) },
                onClick = {
                    AppState.selectedRestaurant = r
                    onOpenPlace()
                },
                showReasoning = true,
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            // fillMaxWidth caps it at 86% of the row; wrapContentWidth then relaxes the MINIMUM
            // so a three-word reply is a three-word bubble rather than an 86%-wide one.
            Modifier.fillMaxWidth(0.86f).wrapContentWidth(Alignment.End),
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * The composer: one pill, holding a field with no chrome of its own and a send button that grows
 * in when there is something to send. An always-visible send on an empty field is a control that
 * does nothing, and a disabled one is a control that says no.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(value: String, onValue: (String) -> Unit, onSend: () -> Unit, enabled: Boolean) {
    val ready by remember(value, enabled) {
        derivedStateOf { value.isNotBlank() && enabled }
    }
    val grow by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "send",
    )
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message TasteRoute…", maxLines = 1) },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                // Every border and container is transparent: the pill around it is the field's
                // only outline, and TextField drawing a second one inside it is the reason a
                // wrapped field usually looks like a mistake.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Box(
                Modifier
                    .padding(4.dp)
                    .size(44.dp)
                    .scale(0.6f + 0.4f * grow)
                    .alpha(grow)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = ready, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    "Send",
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Reveals text at a readable rate instead of pasting it.
 *
 * Even a real token stream arrives in lumps — a network buffer flushes and forty characters land
 * on one frame, which looks like stuttering rather than writing. This drains whatever has arrived
 * at a steady pace, so a fast reply and a slow one both read as someone typing. It never falls
 * behind: the step scales with how much is waiting, so it always catches up within a few frames.
 */
@Composable
private fun TypingText(full: String, style: TextStyle) {
    var shown by remember(full) { mutableIntStateOf(0) }
    LaunchedEffect(full) {
        while (shown < full.length) {
            shown += ((full.length - shown) / 6).coerceIn(1, 5)
            delay(16)
        }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(remember(full, shown) { full.take(shown.coerceAtMost(full.length)) }, style = style)
        Caret()
    }
}

/** A blinking block at the end of a reply that is still being written. */
@Composable
private fun Caret() {
    if (!Perf.richMotion) return
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "blink",
    )
    Box(
        Modifier
            .padding(start = 4.dp, bottom = 4.dp)
            .size(width = 8.dp, height = 15.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}

@Composable
private fun SearchingRow() {
    Row(Modifier.padding(start = 36.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Finding places",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        ThinkingDots()
    }
}
