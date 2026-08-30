package space.gexemy.tasteroute.data.repository

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
import space.gexemy.tasteroute.data.model.Coordinates
import space.gexemy.tasteroute.data.state.AppState

enum class LocationStatus { UNKNOWN, ASKING, DENIED, DISABLED, UNAVAILABLE, READY }

object LocationRepository {

    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private const val UPDATE_INTERVAL_MS = 15_000L
    private const val UPDATE_FASTEST_MS = 5_000L
    private const val UPDATE_DISPLACEMENT_M = 25f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var updates: LocationCallback? = null

    fun hasPermission(context: Context) = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun providersEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

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

        val priority = if (AppState.preciseLocation) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val cts = CancellationTokenSource()
        val fresh = withTimeoutOrNull(10_000) { client.getCurrentLocation(priority, cts.token).await() }
        if (fresh == null) {
            cts.cancel()
            if (cached == null) AppState.locationStatus = LocationStatus.UNAVAILABLE
            return
        }
        publish(context, Coordinates(fresh.latitude, fresh.longitude))
    }

    @SuppressLint("MissingPermission")
    fun startUpdates(context: Context) {
        if (updates != null || !hasPermission(context) || !providersEnabled(context)) return
        val priority = if (AppState.preciseLocation) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_FASTEST_MS)
            .setMinUpdateDistanceMeters(UPDATE_DISPLACEMENT_M)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val fix = result.lastLocation ?: return
                publish(context, Coordinates(fix.latitude, fix.longitude))
            }
        }
        updates = callback
        LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stopUpdates(context: Context) {
        val callback = updates ?: return
        updates = null
        LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(callback)
    }

    @Suppress("DEPRECATION")
    suspend fun geocode(context: Context, query: String): Pair<Coordinates, String>? {
        if (query.isBlank() || !Geocoder.isPresent()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val a = Geocoder(context).getFromLocationName(query.trim(), 1)?.firstOrNull() ?: return@runCatching null
                val label = a.getAddressLine(0) ?: listOfNotNull(a.featureName, a.locality).joinToString(", ").ifBlank { query.trim() }
                Coordinates(a.latitude, a.longitude) to label
            }.getOrNull()
        }
    }

    private fun publish(context: Context, fix: Coordinates) {
        val previous = AppState.origin
        AppState.noteFix(fix)
        AppState.locationStatus = LocationStatus.READY
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
