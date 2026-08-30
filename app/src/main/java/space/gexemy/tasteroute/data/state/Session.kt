package space.gexemy.tasteroute.data.state

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
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.network.*
import space.gexemy.tasteroute.data.local.Prefs
import space.gexemy.tasteroute.data.repository.Social
import space.gexemy.tasteroute.data.repository.Recommender

/**
 * Account state and the sync loop between the device and the Gexemy API.
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

    private var refreshToken by mutableStateOf<String?>(null)

    val signedIn: Boolean get() = refreshToken != null
    val available: Boolean get() = GexemyClient.isConfigured

    fun restoreToken() {
        refreshToken = Prefs.getString(Prefs.REFRESH).takeIf { it.isNotBlank() }
    }

    fun restore() {
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
        Social.clear()
        Prefs.remove(Prefs.REFRESH, Prefs.ACCOUNT)
        token?.let { scope.launch { GexemyClient.logout(it) } }
    }

    suspend fun refreshAccess(): String? = withContext(Dispatchers.IO) {
        val spent = refreshToken ?: return@withContext null
        refreshLock.withLock {
            if (refreshToken != spent) return@withLock accessToken
            try {
                val result = GexemyClient.refresh(spent)
                accessToken = result.access
                refreshToken = result.refresh
                Prefs.putNow(Prefs.REFRESH, result.refresh)
                result.access
            } catch (e: HttpException) {
                if (e.code == 401) {
                    signOut()
                } else {
                    syncError = friendly(e)
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

    suspend fun pull() {
        val remote = GexemyClient.me()
        adopt(remote)
    }

    private fun adopt(remote: GexemyClient.Account) {
        account = remote
        Prefs.put(Prefs.ACCOUNT, AppJson.encodeToString(GexemyClient.Account.serializer(), remote))
        UserState.tier = Tier.fromWire(remote.tier)
        val remoteTaste = remote.taste?.takeIf {
            it.preferredCuisines.isNotEmpty() || it.vibeTags.isNotEmpty() || it.dietaryRestrictions.isNotEmpty()
        }
        if (remoteTaste != null) UserState.profile = remoteTaste
        if (remote.tasteText.isNotBlank()) UserState.tasteText = remote.tasteText
        if (remote.allergens.isNotEmpty()) {
            UserState.allergens.clear()
            UserState.allergens.addAll(remote.allergens)
            Prefs.put(Prefs.ALLERGENS, remote.allergens.toSet())
        }
        if (remote.favorites.isNotEmpty()) {
            UserState.favorites.clear()
            UserState.favorites.addAll(remote.favorites.map { it.placeId })
            remote.favorites.forEach { UserState.knownNames[it.placeId] = it.name }
            Prefs.put(Prefs.FAVORITES, UserState.favorites.toSet())
        }
        PreferenceState.onboarded = PreferenceState.onboarded || remote.tasteText.isNotBlank()
        UserState.persistProfile()
        Recommender.invalidate()
        if (remoteTaste == null) pushProfile()
    }

    private var profileJob: Job? = null
    private var favoritesJob: Job? = null

    fun pushProfile() {
        if (!signedIn || !available) return
        profileJob?.cancel()
        profileJob = scope.launch {
            delay(600)
            runCatching {
                GexemyClient.putProfile(UserState.profile, UserState.allergens.toList(), UserState.tasteText, UserState.tier)
            }.onFailure { syncError = friendly(it) }
        }
    }

    fun pushFavorites() {
        if (!signedIn || !available) return
        favoritesJob?.cancel()
        favoritesJob = scope.launch {
            delay(600)
            val payload = UserState.favorites.associateWith { UserState.knownNames[it].orEmpty() }
            runCatching { GexemyClient.putFavorites(payload) }.onFailure { syncError = friendly(it) }
        }
    }

    fun clearError() { syncError = null }

    private fun friendly(e: Throwable): String = when {
        e is HttpException && e.code == 409 -> "That email already has an account."
        e is HttpException && e.code == 401 -> "Email or password is wrong."
        e is HttpException && e.code == 429 -> "Too many attempts — wait a minute and try again."
        e is HttpException && e.code == 0 -> "Account sync isn't configured in this build."
        e is HttpException && e.message.orEmpty().contains("password_too_short") -> "Use at least 10 characters."
        else -> e.message ?: "Couldn't reach the server."
    }
}
