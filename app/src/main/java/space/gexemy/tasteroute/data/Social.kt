package space.gexemy.tasteroute.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Friends, and the two things friends are for: comparing taste, and seeing where somebody ate.
 *
 * All of it needs an account, and none of it is on any hot path — a signed-out device never calls
 * anything here and the Discover feed does not know this object exists. Failures are held in
 * [error] rather than thrown: a friend list that could not load is a quiet line on one screen, not
 * a banner over the app.
 *
 * WHAT THIS DELIBERATELY DOES NOT HOLD: anybody's location, live or historic. A visit is a place
 * id and a name that its owner chose to log, and only the ones they marked shareable ever arrive
 * here. There is no background reporting of where the device is, to friends or to anyone.
 */
object Social {

    val friends = mutableStateListOf<GexemyClient.FriendLink>()
    val incoming = mutableStateListOf<GexemyClient.FriendLink>()
    val outgoing = mutableStateListOf<GexemyClient.FriendLink>()

    /** What friends shared, newest first. */
    var feed by mutableStateOf<List<GexemyClient.Visit>>(emptyList())
        private set

    /** Your own visit log — every entry, shared or not. Yours to see either way. */
    var visits by mutableStateOf<List<GexemyClient.Visit>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    val requestCount: Int get() = incoming.size

    /** Which friend the pushed profile screen is showing. A nav argument would be tidier, but the
     *  graph is built from plain string routes and one Long does not justify rebuilding it. */
    var openFriendId by mutableStateOf(0L)

    /** Whether this build's server knows about any of this. Old deploys 404 every route here. */
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

    /**
     * Log a visit. [share] is the whole privacy contract in one boolean, and it is decided per
     * visit at the moment of logging — there is no setting anywhere that retroactively shares a
     * back catalogue, because "I'll share this one" and "I'll share everywhere I've ever been" are
     * not the same consent.
     */
    suspend fun logVisit(place: RestaurantResult, share: Boolean, note: String = ""): String? =
        runCatching { GexemyClient.logVisit(place.id, place.name, share, note) }
            .fold(
                onSuccess = { entry ->
                    visits = listOf(entry) + visits.filterNot { it.id == entry.id }
                    null
                },
                onFailure = { friendly(it) },
            )

    fun clear() {
        friends.clear(); incoming.clear(); outgoing.clear()
        feed = emptyList()
        visits = emptyList()
        error = null
    }

    private fun note(e: Throwable) {
        // A 404 here means the box predates the social routes, which is a deploy fact, not a
        // failure the person can do anything about — so it silences the section instead.
        if ((e as? HttpException)?.code.let { it == 404 || it == 501 }) {
            supported = false
            error = null
            return
        }
        error = friendly(e)
    }

    private fun friendly(e: Throwable): String = when ((e as? HttpException)?.code) {
        401, 403 -> "Sign in again to keep using friends."
        429 -> "Too many requests just now — try in a minute."
        else -> e.message?.take(120) ?: "Couldn't reach TasteRoute."
    }
}
