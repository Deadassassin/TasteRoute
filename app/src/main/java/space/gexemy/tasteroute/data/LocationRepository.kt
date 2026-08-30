package space.gexemy.tasteroute.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class LocationStatus { UNKNOWN, ASKING, DENIED, DISABLED, UNAVAILABLE, READY }

object LocationRepository {

    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Live tracking cadence. Fifteen seconds and twenty-five metres keeps a distance label honest
     * at walking pace without holding the GPS chip on: the fused provider serves most of these
     * from wifi and the cell network and only wakes the radio when it has to.
     */
    private const val UPDATE_INTERVAL_MS = 15_000L
    private const val UPDATE_FASTEST_MS = 5_000L
    private const val UPDATE_DISPLACEMENT_M = 25f

    /** Geocoding a label is slow and cosmetic, so it never blocks the fix it describes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var updates: LocationCallback? = null

    fun hasPermission(context: Context) = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun providersEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Publishes the cached fix immediately so search can start within a second, then refines it.
     * Waiting on getCurrentLocation before showing anything was most of the cold-start delay.
     */
    @SuppressLint("MissingPermission")
    suspend fun refresh(context: Context) {
        if (!hasPermission(context)) {
            AppState.locationStatus = LocationStatus.DENIED
            return
        }
        if (!providersEnabled(context)) {
            AppState.locationStatus = LocationStatus.DISABLED
            return
        }
        if (AppState.origin == null) AppState.locationStatus = LocationStatus.ASKING

        val client = LocationServices.getFusedLocationProviderClient(context)
        val cached = withTimeoutOrNull(1_500) { client.lastLocation.await() }
        if (cached != null) {
            publish(context, Coordinates(cached.latitude, cached.longitude))
        }

        val priority =
            if (AppState.preciseLocation) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val cts = CancellationTokenSource()
        val fresh = withTimeoutOrNull(10_000) { client.getCurrentLocation(priority, cts.token).await() }
        if (fresh == null) {
            cts.cancel()
            if (cached == null) AppState.locationStatus = LocationStatus.UNAVAILABLE
            return
        }
        publish(context, Coordinates(fresh.latitude, fresh.longitude))
    }

    /**
     * Follow the person while the app is in front of them.
     *
     * [refresh] answers the question "where are you" once. This answers "where are you now",
     * continuously, which is what a distance on a card has to be measured against — otherwise a
     * place stays "400 m away" for the entire ten-minute walk towards it. It does NOT re-run the
     * search on every fix: [AppState.noteFix] latches a separate search origin that only moves
     * once you have genuinely gone somewhere. Idempotent, so a second call is free.
     */
    @SuppressLint("MissingPermission")
    fun startUpdates(context: Context) {
        if (updates != null || !hasPermission(context) || !providersEnabled(context)) return
        val priority =
            if (AppState.preciseLocation) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_FASTEST_MS)
            .setMinUpdateDistanceMeters(UPDATE_DISPLACEMENT_M)
            // A first fix that is a few seconds old beats a perfect one that arrives after the
            // person has already read the screen and put the phone away.
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val fix = result.lastLocation ?: return
                publish(context, Coordinates(fix.latitude, fix.longitude))
            }
        }
        updates = callback
        LocationServices.getFusedLocationProviderClient(context)
            .requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    /** Must be called when the app goes to the background: a forgotten callback is a battery bug. */
    fun stopUpdates(context: Context) {
        val callback = updates ?: return
        updates = null
        LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(callback)
    }

    /**
     * Address to coordinates for on-my-way and city search. The platform Geocoder is free and needs
     * no key, which is why the server deliberately has no geocoding endpoint.
     */
    @Suppress("DEPRECATION") // async Geocoder needs API 33; minSdk is 23
    suspend fun geocode(context: Context, query: String): Pair<Coordinates, String>? {
        if (query.isBlank() || !Geocoder.isPresent()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val a = Geocoder(context).getFromLocationName(query.trim(), 1)?.firstOrNull()
                    ?: return@runCatching null
                val label = a.getAddressLine(0)
                    ?: listOfNotNull(a.featureName, a.locality).joinToString(", ").ifBlank { query.trim() }
                Coordinates(a.latitude, a.longitude) to label
            }.getOrNull()
        }
    }

    private fun publish(context: Context, fix: Coordinates) {
        val previous = AppState.origin
        AppState.noteFix(fix)
        AppState.locationStatus = LocationStatus.READY
        // Re-geocode only on a meaningful move; the label is cosmetic and Geocoder is slow. This is
        // now on a live update path, so it launches rather than blocking the fix behind it.
        if (previous == null || RecommendationEngine.distanceMeters(previous, fix) > 1_000 || AppState.cityLabel == null) {
            scope.launch { describe(context, fix)?.let { AppState.cityLabel = it } }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun describe(context: Context, c: Coordinates): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val a = Geocoder(context).getFromLocation(c.lat, c.lng, 1)?.firstOrNull() ?: return@runCatching null
            listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea).distinct().joinToString(", ").ifBlank { null }
        }.getOrNull()
    }
}

private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t -> cont.resume(if (t.isSuccessful) t.result else null) }
}
