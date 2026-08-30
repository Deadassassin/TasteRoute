package space.gexemy.tasteroute.data.model

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import space.gexemy.tasteroute.data.repository.LocationRepository

data class Fix(
    val at: Coordinates,
    val bearing: Float?,
    val speedMps: Float,
    val accuracyMetres: Float,
)

/**
 * Continuous fixes, only while a navigation screen is on top.
 *
 * Deliberately separate from [LocationRepository], which asks for one fix and stops: that is right
 * for search and useless for guidance, where the announcement schedule depends on knowing where you
 * are every second. The flow is cold and cancels its request the moment the screen leaves
 * composition, so nothing here keeps the GPS awake in the background.
 */
object NavLocation {

    @SuppressLint("MissingPermission")
    fun fixes(context: Context, intervalMs: Long = 1_000L): Flow<Fix> = callbackFlow {
        if (!LocationRepository.hasPermission(context)) {
            close()
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(0f)
            // Guidance needs the first fix immediately; refining it is what the next second is for.
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                trySend(
                    Fix(
                        at = Coordinates(location.latitude, location.longitude),
                        bearing = if (location.hasBearing()) location.bearing else null,
                        speedMps = if (location.hasSpeed()) location.speed else 0f,
                        accuracyMetres = location.accuracy,
                    )
                )
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
