package space.gexemy.tasteroute

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.Perf
import space.gexemy.tasteroute.data.Prefs

class TasteRouteApplication : Application(), ImageLoaderFactory {

    /**
     * Application.onCreate runs before the first frame can be drawn, so everything in it is time
     * the user spends looking at a blank window. It used to deserialize the whole warm result list
     * and the chat log here — two JSON documents, on the main thread, on the coldest possible
     * cache. That was most of the wait before the app appeared.
     *
     * Now only the scalars the first composition actually reads are restored synchronously; the
     * rest lands a few frames later, by which time the UI has been on screen for a while.
     */
    override fun onCreate() {
        super.onCreate()
        // Prefs first: Perf reads the class measured on this handset last time, and a default it
        // has to re-derive is a first launch that animates like a flagship on a phone that isn't.
        Prefs.init(this)
        Perf.init(this)
        AppState.restoreCritical()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { AppState.restoreRest() }
    }

    /**
     * Coil's defaults assume a flagship: 25% of heap in memory and a crossfade on every load.
     * On a 96MB-heap phone that evicts list bitmaps while you are still scrolling past them.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(Perf.imageCacheFraction).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(48L * 1024 * 1024).build() }
        .bitmapConfig(if (Perf.lowColorImages) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        .crossfade(Perf.richMotion)
        .respectCacheHeaders(false)
        .build()
}
