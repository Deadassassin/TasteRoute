package space.gexemy.tasteroute.ui.map

import android.content.Context
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import space.gexemy.tasteroute.data.USER_AGENT

/**
 * OpenStreetMap's own tile servers are volunteer-run and their usage policy asks apps not to use
 * them, so we render CARTO's OSM-derived basemaps instead. Attribution still credits OSM.
 *
 * CARTO's free tier is for low-volume, non-commercial use. Before launch, swap these for a keyed
 * provider (MapTiler, Stadia, Thunderforest) — only these constants and ATTRIBUTION need to change.
 */

const val ATTRIBUTION = "© OpenStreetMap contributors, © CARTO"

private fun carto(name: String, style: String) = XYTileSource(
    name,
    0,
    20,
    256,
    ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/$style/",
        "https://b.basemaps.cartocdn.com/rastertiles/$style/",
        "https://c.basemaps.cartocdn.com/rastertiles/$style/",
    ),
    ATTRIBUTION,
)

val BASEMAP: OnlineTileSourceBase = carto("CartoVoyager", "voyager")

/**
 * A white map inside a dark app was the single most jarring thing in TasteRoute — you leave a dark
 * feed, a floodlit rectangle arrives, and the route line disappears into it. Raster tiles cannot be
 * recoloured client-side without wrecking the labels, so the fix is a genuinely dark basemap.
 * Same provider, same tier, same attribution.
 */
val BASEMAP_DARK: OnlineTileSourceBase = carto("CartoDarkMatter", "dark_all")

fun basemapFor(dark: Boolean): OnlineTileSourceBase = if (dark) BASEMAP_DARK else BASEMAP

/**
 * osmdroid's Configuration.load() reads SharedPreferences and creates cache directories, which is
 * blocking disk I/O. It used to run in MainActivity.onCreate, so every cold start paid for the map
 * whether or not the map was ever opened. It runs here instead, the first time a MapView is about
 * to be built — idempotent, so every map screen can call it without coordinating.
 */
private object OsmdroidBoot {
    private var configured = false

    fun ensure(context: Context) = synchronized(this) {
        if (configured) return@synchronized
        val cache = File(context.cacheDir, "osmdroid")
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = USER_AGENT
            osmdroidBasePath = cache
            osmdroidTileCache = File(cache, "tiles")
        }
        configured = true
    }
}

fun ensureOsmdroid(context: Context) = OsmdroidBoot.ensure(context)
