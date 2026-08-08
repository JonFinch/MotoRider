package com.motorider.services

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.os.StatFs
import com.motorider.utils.MapTileSource
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pre-fetches map tiles into osmdroid's own on-disk cache (a single SQLite db,
 * `SqlTileWriter`), rather than a separate file tree.
 *
 * The live map (`OsmMapView`) renders via `TileSourceFactory.MAPNIK`, whose default
 * `MapTileProviderBasic` always backs onto a `SqlTileWriter` keyed by tile-source name
 * + zoom/x/y. Writing pre-fetched tiles into that exact cache means the live map picks
 * them up with no additional wiring - a tile downloaded here is indistinguishable from
 * one the map fetched itself while online. Using `TileSourceFactory.MAPNIK` directly
 * (rather than a separately constructed source) guarantees the cache key matches.
 */
class TileStorageManager(private val context: Context) {

    private val tileSource = MapTileSource.get()
    private val tileWriter = SqlTileWriter()

    fun isTileDownloaded(zoom: Int, x: Int, y: Int): Boolean {
        return tileWriter.exists(tileSource, MapTileIndex.getTileIndex(zoom, x, y))
    }

    suspend fun downloadTile(zoom: Int, x: Int, y: Int): Boolean {
        if (isTileDownloaded(zoom, x, y)) return true

        val url = buildTileUrl(zoom, x, y)

        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "MotoRider/1.0")

            if (connection.responseCode == 200) {
                connection.inputStream.use { input ->
                    // saveFile() always returns false even on success - a quirk in
                    // osmdroid's SqlTileWriter (the success path falls through to an
                    // unconditional `return false`). Success is determined by the HTTP
                    // response, not this call.
                    //
                    // Pass a far-future expiry, NOT null. osmdroid stores null as an
                    // already-past expiration, so offline it treats the tile as stale:
                    // it renders it only after a failed network refresh, often as a
                    // blurry lower-zoom approximation. A pre-downloaded offline tile
                    // should simply never expire, so it is served directly (fresh).
                    tileWriter.saveFile(
                        tileSource,
                        MapTileIndex.getTileIndex(zoom, x, y),
                        input,
                        System.currentTimeMillis() + TILE_EXPIRY_MS
                    )
                }
                connection.disconnect()
                true
            } else {
                connection.disconnect()
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun buildTileUrl(zoom: Int, x: Int, y: Int): String {
        return tileSource.getTileURLString(MapTileIndex.getTileIndex(zoom, x, y))
    }

    fun deleteRegionTiles(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, minZoom: Int, maxZoom: Int) {
        for (zoom in minZoom..maxZoom) {
            val minX = lonToTileX(minLon, zoom)
            val maxX = lonToTileX(maxLon, zoom)
            val minY = latToTileY(maxLat, zoom)
            val maxY = latToTileY(minLat, zoom)

            // Rect fields are tile coordinates here, not pixels: left/right = min/max
            // tile X, top/bottom = min/max tile Y, for this zoom level.
            tileWriter.delete(
                tileSource.name(),
                zoom,
                listOf(Rect(minX, minY, maxX, maxY)),
                null
            )
        }
    }

    /**
     * One-time repair: stamps a far-future expiry on cached tiles that have none. Tiles
     * written by earlier builds carry a null expiry, which osmdroid reads as "stale" - so
     * offline it renders them only after a failed network refresh, often as a blurry
     * lower-zoom approximation instead of the sharp tile it actually has.
     *
     * This rewrites every null-expiry row (potentially the whole cache), so it is guarded
     * to run at most once. New downloads already set an expiry at write time, so there is
     * nothing to repair on later installs. Safe to interrupt: the guard flag is only set
     * after the update commits, so a killed migration simply retries next launch.
     */
    fun refreshTileExpirations() {
        val prefs = context.getSharedPreferences("motorider_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_TILES_EXPIRY_REPAIRED, false)) return

        val cacheDb = File(Configuration.getInstance().osmdroidTileCache, "cache.db")
        if (!cacheDb.exists()) {
            // No cache yet - nothing legacy to repair; don't keep retrying.
            prefs.edit().putBoolean(PREF_TILES_EXPIRY_REPAIRED, true).apply()
            return
        }
        try {
            val expiry = System.currentTimeMillis() + TILE_EXPIRY_MS
            SQLiteDatabase.openDatabase(
                cacheDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                // Update in small committed batches rather than one transaction. Each tile
                // row carries a ~15KB blob, so a single UPDATE over the whole cache rewrites
                // hundreds of MB into one rollback journal - slow, and lost entirely if the
                // process dies mid-way. Batching keeps each journal tiny and makes the
                // migration resumable: whatever committed stays committed, and the guard
                // flag is only set once no null-expiry rows remain.
                val update = db.compileStatement(
                    "UPDATE tiles SET expires = ? " +
                        "WHERE rowid IN (SELECT rowid FROM tiles WHERE expires IS NULL LIMIT ?)"
                )
                while (true) {
                    update.bindLong(1, expiry)
                    update.bindLong(2, EXPIRY_REPAIR_BATCH)
                    val changed = update.executeUpdateDelete()
                    if (changed == 0) break
                }
            }
            prefs.edit().putBoolean(PREF_TILES_EXPIRY_REPAIRED, true).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getStorageStats(): StorageStats {
        val cacheDb = org.osmdroid.config.Configuration.getInstance().osmdroidTileCache
        val totalSize = calculateDirSize(cacheDb)
        val availableSpace = getAvailableSpace(cacheDb)

        return StorageStats(
            usedBytes = totalSize,
            availableBytes = availableSpace,
            totalBytes = totalSize + availableSpace
        )
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        if (dir.exists()) {
            dir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    size += file.length()
                }
            }
        }
        return size
    }

    private fun getAvailableSpace(dir: File): Long {
        val stat = StatFs(if (dir.exists()) dir.absolutePath else context.filesDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    companion object {
        // Offline tiles should effectively never expire. ~10 years out.
        private const val TILE_EXPIRY_MS = 10L * 365 * 24 * 60 * 60 * 1000
        private const val PREF_TILES_EXPIRY_REPAIRED = "tiles_expiry_repaired_v1"
        // Rows per committed batch during the expiry repair - small enough to keep each
        // transaction's journal tiny and the migration resumable.
        private const val EXPIRY_REPAIR_BATCH = 1000L

        fun lonToTileX(lon: Double, zoom: Int): Int {
            return ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
        }

        fun latToTileY(lat: Double, zoom: Int): Int {
            val latRad = Math.toRadians(lat)
            return ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * (1 shl zoom)).toInt()
        }

        fun tileXToLon(x: Int, zoom: Int): Double {
            return x.toDouble() / (1 shl zoom) * 360.0 - 180.0
        }

        fun tileYToLat(y: Int, zoom: Int): Double {
            val n = Math.PI - 2.0 * Math.PI * y.toDouble() / (1 shl zoom)
            return Math.toDegrees(Math.atan(Math.sinh(n)))
        }

        fun calculateTileCount(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, minZoom: Int, maxZoom: Int): Long {
            var totalTiles = 0L

            for (zoom in minZoom..maxZoom) {
                val minX = lonToTileX(minLon, zoom)
                val maxX = lonToTileX(maxLon, zoom)
                val minY = latToTileY(maxLat, zoom)
                val maxY = latToTileY(minLat, zoom)

                val width = maxX - minX + 1
                val height = maxY - minY + 1

                totalTiles += width.toLong() * height.toLong()
            }

            return totalTiles
        }

        fun estimateStorageSize(tileCount: Long): Double {
            val averageTileSizeBytes = 15000.0
            return (tileCount * averageTileSizeBytes) / (1024.0 * 1024.0)
        }
    }

    data class StorageStats(
        val usedBytes: Long,
        val availableBytes: Long,
        val totalBytes: Long
    )
}
