package space.gexemy.tasteroute.data.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.repository.RecommendationEngine
import space.gexemy.tasteroute.data.repository.LocationStatus

private const val RESEARCH_DRIFT_M = 450
private const val RESEARCH_MAX_AGE_MS = 5 * 60_000L

object LocationState {
    var origin by mutableStateOf<Coordinates?>(null)
    var searchOrigin by mutableStateOf<Coordinates?>(null)
    private var searchOriginAt = 0L

    var cityLabel by mutableStateOf<String?>(null)
    var mall by mutableStateOf<Mall?>(null)
    var locationStatus by mutableStateOf(LocationStatus.UNKNOWN)

    fun noteFix(fix: Coordinates): Boolean {
        origin = fix
        val anchor = searchOrigin
        val now = System.currentTimeMillis()
        val moved = anchor == null ||
            RecommendationEngine.distanceMeters(anchor, fix) >= RESEARCH_DRIFT_M ||
            now - searchOriginAt >= RESEARCH_MAX_AGE_MS
        if (moved) {
            searchOrigin = fix
            searchOriginAt = now
        }
        return moved
    }
}
