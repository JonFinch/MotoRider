package com.motorider

import android.app.Application
import android.util.Log
import com.motorider.services.TileStorageManager
import org.osmdroid.config.Configuration

class MotoRiderApplication : Application() {
    private companion object {
        const val TAG = "MotoRiderApplication"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Configuration.getInstance().userAgentValue = packageName

            try {
                Configuration.getInstance().load(
                    this,
                    getSharedPreferences("osmdroid", MODE_PRIVATE)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not load osmdroid configuration (first launch?): ${e.message}")
            }

            // osmdroid keeps these in its own SharedPreferences, so a value set once
            // survives reinstalls and upgrades. Left on, it paints tile borders, tile
            // coordinates and "Scaled" markers over the map - in release builds too,
            // since the flags are read from prefs and not from the build type. Forcing
            // them off after load() is what actually clears a stale `true`.
            Configuration.getInstance().isDebugMode = false
            Configuration.getInstance().isDebugTileProviders = false
            Configuration.getInstance().isDebugMapView = false
            Configuration.getInstance().isDebugMapTileDownloader = false

            // Raise osmdroid's tile-cache cap. The default is 600MB (trim to 500MB), and
            // when the cache exceeds it osmdroid *deletes* tiles to trim back down - which
            // silently purges pre-downloaded offline regions (observed: ~30% of an offline
            // region gone). Offline downloads are the whole point here, so lift the cap
            // well above any realistic offline set; the user manages space via delete.
            Configuration.getInstance().tileFileSystemCacheMaxBytes = 8L * 1024 * 1024 * 1024
            Configuration.getInstance().tileFileSystemCacheTrimBytes = 7L * 1024 * 1024 * 1024

            // Repair any cached tiles missing an expiry (written by earlier builds), off
            // the main thread, so offline browsing serves them directly instead of as
            // stale/approximated tiles. New downloads already set an expiry at write time.
            Thread {
                try {
                    TileStorageManager(this).refreshTileExpirations()
                } catch (e: Exception) {
                    Log.w(TAG, "Tile expiry repair failed: ${e.message}")
                }
            }.start()

            Log.d(TAG, "MotoRiderApplication initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MotoRiderApplication", e)
        }
    }
}
