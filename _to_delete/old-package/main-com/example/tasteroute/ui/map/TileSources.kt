package com.example.tasteroute.ui.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * OpenStreetMap's own tile servers are volunteer-run and their usage policy asks apps not to use
 * them, so we render CARTO's OSM-derived basemap instead. Attribution still credits OSM.
 *
 * CARTO's free tier is for low-volume, non-commercial use. Before launch, swap this for a keyed
 * provider (MapTiler, Stadia, Thunderforest) — only this constant and ATTRIBUTION need to change.
 */
val BASEMAP: OnlineTileSourceBase = XYTileSource(
    "CartoVoyager",
    0,
    20,
    256,
    ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
    ),
    "© OpenStreetMap contributors, © CARTO",
)

const val ATTRIBUTION = "© OpenStreetMap contributors, © CARTO"
