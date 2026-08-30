package com.example.tasteroute

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.Perf
import com.example.tasteroute.data.Prefs

class TasteRouteApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Perf.init(this)
        Prefs.init(this)
        AppState.restore()
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
