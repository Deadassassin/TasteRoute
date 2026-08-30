package space.gexemy.tasteroute.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Account state and the sync loop between the device and the Gexemy API.
 *
 * The app stays fully usable signed out — an account buys sync across devices, Table Sync and the
 * ability to contribute reports. Nothing here blocks the UI: pushes are fire-and-forget and
 * coalesced, because a taste-profile write is not worth a spinner.
 */
object Session {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    var account by mutableStateOf<GexemyClient.Account?>(null)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    @Volatile
    var accessToken: String? = null
        private set

    /**
     * Snapshot state, not a plain field, and that is load-bearing.
     *
     * [signedIn] is read from composition — the sign-in prompts on the place screen, the Profile
     * tab, Table Sync — and it is written from Dispatchers.IO by [restore]. A plain field written
     * off the main thread invalidates nothing, so every one of those screens kept rendering its
     * signed-out branch after the token had already loaded. Snapshot state writes are safe from
     * any thread; that is what makes the background restore legal in the first place.
     */
    private var refreshToken by mutableStateOf<String?>(null)

    val signedIn: Boolean get() = refreshToken != null
    val available: Boolean get() = GexemyClient.isConfigured

    /**
     * The token and nothing else. One string, no JSON decode, so it is cheap enough for
     * [AppState.restoreCritical] — and it has to run there, because a first composition that reads
     * `signedIn == false` puts "Sign in to review" on screen before the real answer arrives.
     */
    fun restoreToken() {
        refreshToken = Prefs.getString(Prefs.REFRESH).takeIf { it.isNotBlank() }
    }

    fun restore() {
        // Already read in restoreCritical; re-reading would only risk clobbering a token that
        // rotated in between with the older one still on disk.
        if (refreshToken == null) restoreToken()
        Prefs.getString(Prefs.ACCOUNT).takeIf { it.isNotBlank() }?.let { json ->
            runCatching { AppJson.decodeFromString(GexemyClient.Account.serializer(), json) }
                .onSuccess { account = it }
        }
        if (refreshToken != null && GexemyClient.reachable(Backoff.ACCOUNT)) {
            scope.launch { runCatching { pull() }.onFailure { Backoff.record(Backoff.ACCOUNT, it) } }
        }
    }

    suspend fun signIn(email: String, password: String) = authenticate {
        GexemyClient.login(email.trim(), password)
    }

    suspend fun signUp(email: String, password: String, displayName: String) = authenticate {
        GexemyClient.register(email.trim(), password, displayName.trim())
    }

    private suspend fun authenticate(call: suspend () -> GexemyClient.AuthResult) {
        busy = true
        syncError = null
        try {
            val result = call()
            accessToken = result.access
            refreshToken = result.refresh
            Prefs.put(Prefs.REFRESH, result.refresh)
            result.user?.let { adopt(it) }
            pull()
        } catch (e: Exception) {
            syncError = friendly(e)
            throw e
        } finally {
            busy = false
        }
    }

    fun signOut() {
        val token = refreshToken
        accessToken = null
        refreshToken = null
        account = null
        // Friends, their visits and yours are all account-scoped. Leaving them in memory after a
        // sign-out means the next person to open the app sees the last person's friends.
        Social.clear()
        Prefs.remove(Prefs.REFRESH, Prefs.ACCOUNT)
        token?.let { scope.launch { GexemyClient.logout(it) } }
    }

    /**
     * A fresh access token, or null if the device really does have to sign in again.
     *
     * Concurrent callers COLLAPSE. Opening the app fires several authed calls at once and each of
     * them needs the first access token of the session; whoever takes the lock rotates, and
     * everybody queued behind them takes the token that rotation produced instead of rotating
     * again. Six rotations in a second are six chances for a write to be lost, and a lost rotation
     * write is indistinguishable from a replayed token the next time the app starts.
     */
    suspend fun refreshAccess(): String? = withContext(Dispatchers.IO) {
        val spent = refreshToken ?: return@withContext null
        refreshLock.withLock {
            // Somebody else rotated while we were queued. Theirs is the live token; take it.
            if (refreshToken != spent) return@withLock accessToken
            try {
                val result = GexemyClient.refresh(spent)
                accessToken = result.access
                refreshToken = result.refresh
                // commit(), not apply(). If the process dies between the server rotating and this
                // write reaching disk, the next launch presents a token the server has already
                // marked used — which is a replay, and a replay revokes the whole family. That is
                // a permanent, silent sign-out caused by a write nobody waited for. This is the
                // one value in the app where losing a write costs the account.
                Prefs.putNow(Prefs.REFRESH, result.refresh)
                result.access
            } catch (e: HttpException) {
                // 401 here means the refresh token is dead — revoked, replayed or expired. Anything
                // else (offline, 5xx) is transient and must not throw the user out of their account.
                if (e.code == 401) {
                    signOut()
                } else {
                    syncError = friendly(e)
                    // Backoff ignores 401s, so this only ever trips on the transient classes — and
                    // it is what makes the reachable(ACCOUNT) guards elsewhere mean anything when
                    // the server is down at launch.
                    Backoff.record(Backoff.ACCOUNT, e)
                }
                null
            } catch (e: Exception) {
                syncError = friendly(e)
                Backoff.record(Backoff.ACCOUNT, e)
                null
            }
        }
    }

    /** Server is the source of truth on sign-in: another device may have retuned the profile. */
    suspend fun pull() {
        val remote = GexemyClient.me()
        adopt(remote)
    }

    private fun adopt(remote: GexemyClient.Account) {
        account = remote
        Prefs.put(Prefs.ACCOUNT, AppJson.encodeToString(GexemyClient.Account.serializer(), remote))
        AppState.tier = Tier.fromWire(remote.tier)
        // A brand-new account has an empty taste blob; adopting it verbatim would wipe the profile
        // the person just tuned on this device. Empty server side means this device is the source.
        val remoteTaste = remote.taste?.takeIf {
            it.preferredCuisines.isNotEmpty() || it.vibeTags.isNotEmpty() || it.dietaryRestrictions.isNotEmpty()
        }
        if (remoteTaste != null) AppState.profile = remoteTaste
        if (remote.tasteText.isNotBlank()) AppState.tasteText = remote.tasteText
        if (remote.allergens.isNotEmpty()) {
            AppState.allergens.clear()
            AppState.allergens.addAll(remote.allergens)
            Prefs.put(Prefs.ALLERGENS, remote.allergens.toSet())
        }
        if (remote.favorites.isNotEmpty()) {
            AppState.favorites.clear()
            AppState.favorites.addAll(remote.favorites.map { it.placeId })
            remote.favorites.forEach { AppState.knownNames[it.placeId] = it.name }
            Prefs.put(Prefs.FAVORITES, AppState.favorites.toSet())
        }
        AppState.onboarded = AppState.onboarded || remote.tasteText.isNotBlank()
        AppState.persistProfile()
        Recommender.invalidate()
        if (remoteTaste == null) pushProfile()
    }

    // Debounced pushes. Retuning a profile fires a state change per keystroke-driven recomposition;
    // one write per burst is plenty.
    private var profileJob: Job? = null
    private var favoritesJob: Job? = null

    fun pushProfile() {
        if (!signedIn || !available) return
        profileJob?.cancel()
        profileJob = scope.launch {
            delay(600)
            runCatching {
                GexemyClient.putProfile(AppState.profile, AppState.allergens.toList(), AppState.tasteText, AppState.tier)
            }.onFailure { syncError = friendly(it) }
        }
    }

    fun pushFavorites() {
        if (!signedIn || !available) return
        favoritesJob?.cancel()
        favoritesJob = scope.launch {
            delay(600)
            val payload = AppState.favorites.associateWith { AppState.knownNames[it].orEmpty() }
            runCatching { GexemyClient.putFavorites(payload) }.onFailure { syncError = friendly(it) }
        }
    }

    fun clearError() {
        syncError = null
    }

    private fun friendly(e: Throwable): String = when {
        e is HttpException && e.code == 409 -> "That email already has an account."
        e is HttpException && e.code == 401 -> "Email or password is wrong."
        e is HttpException && e.code == 429 -> "Too many attempts — wait a minute and try again."
        e is HttpException && e.code == 0 -> "Account sync isn't configured in this build."
        e is HttpException && e.message.orEmpty().contains("password_too_short") -> "Use at least 10 characters."
        else -> e.message ?: "Couldn't reach the server."
    }
}
