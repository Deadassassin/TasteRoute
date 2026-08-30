package space.gexemy.tasteroute.data

/**
 * Stops the app from re-dialling a service that is down.
 *
 * Without this, an unreachable host costs a full connect+read timeout on *every* search, so one
 * dead dependency makes the whole app feel broken. After a failure we skip that service outright
 * until the window expires and take the fallback path immediately.
 */
object Backoff {

    const val PLACES = "places"
    const val RANKER = "ranker"
    const val CROWD = "crowd"
    const val ACCOUNT = "account"

    /**
     * Conversation has its own key rather than sharing RANKER. They hit the same host, but a rank
     * that times out must not also silence the assistant for five minutes — one is a background
     * reorder nobody asked for, the other is the person waiting on a reply they just typed.
     */
    const val CHAT = "chat"

    /**
     * Yelp has its own key rather than sharing CROWD on purpose: the Yelp routes are the newest on
     * the server, so an app running against a deploy that predates them gets a 404 there — and that
     * must not take pulse, allergens and cover photos down with it for the next quarter of an hour.
     */
    const val YELP = "yelp"

    /**
     * The multi-source content routes, separate again from YELP. They are the newest thing on the
     * server, so an app running against a deploy that predates them gets a 404 — and that must not
     * take Yelp, pulse, allergens and cover photos down with it for the next quarter of an hour.
     */
    const val SOURCES = "sources"

    /**
     * The map itself, dialled straight from the detail screen for the tags a search response does
     * not carry. Separate from PLACES because that key means "our own host is sick", and Overpass
     * rate-limiting us says nothing at all about our own host.
     */
    const val OSM = "osm"

    private val blockedUntil = HashMap<String, Long>()

    fun blocked(key: String): Boolean = synchronized(blockedUntil) {
        val until = blockedUntil[key] ?: return false
        if (until > System.currentTimeMillis()) return true
        blockedUntil.remove(key)
        false
    }

    fun trip(key: String, millis: Long) {
        synchronized(blockedUntil) { blockedUntil[key] = System.currentTimeMillis() + millis }
    }

    fun clear(key: String) {
        synchronized(blockedUntil) { blockedUntil.remove(key) }
    }

    /**
     * A timeout or 5xx means the host is sick. A 404 or 501 on one of our own endpoints means
     * something else entirely: the base URL points at a host that does not serve this API, or at a
     * deploy that predates the route. That is a fact about the host, not about this request, so it
     * gets a much longer window — re-dialling it on every single search is precisely what made a
     * wrong GEXEMY_BASE_URL feel like a broken app instead of a one-line misconfiguration.
     */
    fun record(key: String, error: Throwable, millis: Long = 3 * 60_000L) {
        val code = (error as? HttpException)?.code
        when {
            code == 404 || code == 501 -> trip(key, MISSING_MS)
            code == null || code == 0 || code >= 500 || code == 408 || code == 429 -> trip(key, millis)
        }
    }

    private const val MISSING_MS = 15 * 60_000L
}
