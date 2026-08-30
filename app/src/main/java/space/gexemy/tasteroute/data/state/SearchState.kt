package space.gexemy.tasteroute.data.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.gexemy.tasteroute.data.model.*

object SearchState {
    var searchMode by mutableStateOf(SearchMode.NEARBY)
    var searchArea by mutableStateOf<Coordinates?>(null)
    var searchAreaLabel by mutableStateOf("")
    var destination by mutableStateOf<Coordinates?>(null)
    var destinationLabel by mutableStateOf("")
    var routeGeometry by mutableStateOf<List<Coordinates>>(emptyList())
    var navRoute by mutableStateOf<NavRoute?>(null)
}
