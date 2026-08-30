package space.gexemy.tasteroute.data.repository

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.gexemy.tasteroute.data.model.RestaurantResult
import space.gexemy.tasteroute.data.network.GexemyClient
import space.gexemy.tasteroute.data.network.Backoff
import space.gexemy.tasteroute.data.network.HttpException
import space.gexemy.tasteroute.data.state.Session

/**
 * Friends, and the two things friends are for: comparing taste, and seeing where somebody ate.
 */
object Social {

    val friends = mutableStateListOf<GexemyClient.FriendLink>()
    val incoming = mutableStateListOf<GexemyClient.FriendLink>()
    val outgoing = mutableStateListOf<GexemyClient.FriendLink>()

    var feed by mutableStateOf<List<GexemyClient.Visit>>(emptyList())
        private set
    var visits by mutableStateOf<List<GexemyClient.Visit>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    val requestCount: Int get() = incoming.size
    var openFriendId by mutableStateOf(0L)
    var supported by mutableStateOf(true)
        private set

    suspend fun refresh() {
        if (!Session.signedIn || !GexemyClient.reachable(Backoff.ACCOUNT)) return
        loading = true
        runCatching { GexemyClient.friends() }
            .onSuccess { result ->
                friends.clear(); friends.addAll(result.friends)
                incoming.clear(); incoming.addAll(result.incoming)
                outgoing.clear(); outgoing.addAll(result.outgoing)
                error = null
                supported = true
            }
            .onFailure { note(it) }
        runCatching { GexemyClient.friendVisits() }.onSuccess { feed = it }
        runCatching { GexemyClient.myVisits() }.onSuccess { visits = it }
        loading = false
    }

    suspend fun add(handle: String): String? {
        val clean = handle.trim().removePrefix("@")
        if (clean.isBlank()) return "Type a handle first."
        return runCatching { GexemyClient.addFriend(clean) }
            .fold(
                onSuccess = { refresh(); null },
                onFailure = { e ->
                    when ((e as? HttpException)?.code) {
                        404 -> "No one has claimed @$clean."
                        400 -> "That's your own handle."
                        409 -> "You're already connected."
                        else -> friendly(e)
                    }
                },
            )
    }

    suspend fun respond(linkId: Long, accept: Boolean) {
        runCatching { GexemyClient.respondFriend(linkId, accept) }.onFailure { note(it) }
        refresh()
    }

    suspend fun remove(linkId: Long) {
        runCatching { GexemyClient.removeFriend(linkId) }.onFailure { note(it) }
        refresh()
    }

    suspend fun logVisit(place: RestaurantResult, share: Boolean, note: String = ""): String? =
        runCatching { GexemyClient.logVisit(place.id, place.name, share, note) }
            .fold(
                onSuccess = { entry -> visits = listOf(entry) + visits.filterNot { it.id == entry.id }; null },
                onFailure = { friendly(it) },
            )

    fun clear() {
        friends.clear(); incoming.clear(); outgoing.clear()
        feed = emptyList(); visits = emptyList(); error = null
    }

    private fun note(e: Throwable) {
        if ((e as? HttpException)?.code.let { it == 404 || it == 501 }) {
            supported = false; error = null; return
        }
        error = friendly(e)
    }

    private fun friendly(e: Throwable): String = when ((e as? HttpException)?.code) {
        401, 403 -> "Sign in again to keep using friends."
        429 -> "Too many requests just now — try in a minute."
        else -> e.message?.take(120) ?: "Couldn't reach TasteRoute."
    }
}
