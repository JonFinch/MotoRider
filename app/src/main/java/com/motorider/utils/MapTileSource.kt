package com.motorider.utils

import com.motorider.config.ApiConfig
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * The single source of truth for which tile server the app uses.
 *
 * Both the live map ([com.motorider.ui.component.OsmMapView]) and the offline
 * pre-downloader ([com.motorider.services.TileStorageManager]) must obtain their tile
 * source from here. osmdroid keys its on-disk cache by the source *name*, so as long as
 * both sides use the identical source, a tile written by the pre-downloader is
 * indistinguishable from one the live map fetched itself - which is what makes offline
 * caching actually work.
 *
 * The base URL is build-configured ([ApiConfig.TILE_SERVER_BASE_URL]):
 * - Default: public OSM Mapnik (unchanged behaviour, subdomain rotation).
 * - Self-hosted: a tileserver-gl base such as
 *   `http://192.168.68.52:8081/styles/basic-preview`, which serves the same
 *   `{z}/{x}/{y}.png` slippy format with no bulk-download restriction.
 */
object MapTileSource {

    fun get(): OnlineTileSourceBase {
        // Keep osmdroid's built-in Mapnik source (name "Mapnik", subdomain rotation,
        // sensible zoom bounds) when pointed at public OSM.
        if (ApiConfig.usingDefaultOsmTiles) return TileSourceFactory.MAPNIK

        // XYTileSource appends "{z}/{x}/{y}.png" to the base, so it must end with "/".
        // A distinct name gives the self-hosted tiles their own cache namespace, so they
        // never visually mix with any previously cached OSM tiles.
        val base = ApiConfig.TILE_SERVER_BASE_URL.trimEnd('/') + "/"
        return XYTileSource(
            "MotoRiderTiles",
            0,
            20,
            256,
            ".png",
            arrayOf(base)
        )
    }
}
