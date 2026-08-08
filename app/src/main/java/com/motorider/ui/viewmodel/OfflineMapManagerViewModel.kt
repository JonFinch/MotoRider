package com.motorider.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motorider.data.OfflineRegionRepository
import com.motorider.models.DownloadStatus
import com.motorider.models.OfflineRegion
import com.motorider.services.TileDownloadService
import com.motorider.services.TileStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfflineMapManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = OfflineRegionRepository.getInstance(application)
    private val storageManager = TileStorageManager(application)

    val regions: StateFlow<List<OfflineRegion>> = repository.regions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = OfflineRegion.DEFAULTS
        )

    val storageStats: StateFlow<TileStorageManager.StorageStats> = kotlinx.coroutines.flow.flow {
        emit(storageManager.getStorageStats())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = TileStorageManager.StorageStats(0, 0, 0)
    )

    init {
        initializeDefaultRegions()
    }

    private fun initializeDefaultRegions() {
        viewModelScope.launch {
            for (region in OfflineRegion.DEFAULTS) {
                val existing = repository.getRegionById(region.id)

                // The DEFAULTS constants carry tileCount/estimatedSizeMB of 0 - the real
                // values can only be computed at runtime from the bounds.
                val tileCount = TileStorageManager.calculateTileCount(
                    region.minLat,
                    region.maxLat,
                    region.minLon,
                    region.maxLon,
                    region.minZoom,
                    region.maxZoom
                )
                val estimatedSize = TileStorageManager.estimateStorageSize(tileCount)
                val sized = region.copy(tileCount = tileCount, estimatedSizeMB = estimatedSize)

                when {
                    // Fresh install - seed with the sized definition.
                    existing == null -> repository.insertRegion(sized)

                    // The bundled definition changed (e.g. a lower max zoom): the stored
                    // tileCount and any prior download no longer describe this region, so
                    // reset it to the new definition (NOT_DOWNLOADED, count recomputed).
                    definitionChanged(existing, region) -> repository.updateRegion(sized)

                    // Same definition but never sized (persisted 0 by an earlier build,
                    // which showed "0 tiles / 0.0MB" and a broken 1/0 bar) - backfill.
                    existing.tileCount != tileCount || existing.estimatedSizeMB != estimatedSize ->
                        repository.updateRegion(
                            existing.copy(tileCount = tileCount, estimatedSizeMB = estimatedSize)
                        )
                }
            }
        }
    }

    /** True when a stored region's bounds or zoom range differ from the bundled default. */
    private fun definitionChanged(stored: OfflineRegion, default: OfflineRegion): Boolean =
        stored.minZoom != default.minZoom ||
            stored.maxZoom != default.maxZoom ||
            stored.minLat != default.minLat ||
            stored.maxLat != default.maxLat ||
            stored.minLon != default.minLon ||
            stored.maxLon != default.maxLon

    fun downloadRegion(regionId: String) {
        viewModelScope.launch {
            val region = repository.getRegionById(regionId) ?: return@launch
            
            val stats = storageManager.getStorageStats()
            val requiredBytes = (region.estimatedSizeMB * 1024 * 1024).toLong()
            
            if (stats.availableBytes < requiredBytes) {
                return@launch
            }
            
            TileDownloadService.startDownload(getApplication(), regionId)
        }
    }

    fun deleteRegion(regionId: String) {
        viewModelScope.launch {
            val region = repository.getRegionById(regionId) ?: return@launch

            // Flip the card straight away so the UI feels instant, then do the actual
            // work off the main thread.
            repository.updateRegion(
                region.copy(
                    downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                    downloadedAt = null,
                    downloadedTiles = 0
                )
            )

            // deleteRegionTiles is a blocking SQLite sweep over every zoom level. On
            // viewModelScope's Dispatchers.Main.immediate it would run synchronously and
            // freeze the UI (the confirm dialog visibly hangs before dismissing), so push
            // it to the IO dispatcher.
            withContext(Dispatchers.IO) {
                storageManager.deleteRegionTiles(
                    region.minLat,
                    region.maxLat,
                    region.minLon,
                    region.maxLon,
                    region.minZoom,
                    region.maxZoom
                )
            }
        }
    }

    fun cancelDownload(regionId: String) {
        TileDownloadService.stopDownload(getApplication())
        
        viewModelScope.launch {
            repository.updateDownloadStatus(regionId, DownloadStatus.NOT_DOWNLOADED, null)
        }
    }

    fun refreshStorageStats() {
        viewModelScope.launch {
            val stats = storageManager.getStorageStats()
            storageStats
        }
    }

    fun hasEnoughStorage(regionId: String): Boolean {
        val region = regions.value.find { it.id == regionId } ?: return false
        val stats = storageManager.getStorageStats()
        val requiredBytes = (region.estimatedSizeMB * 1024 * 1024).toLong()
        return stats.availableBytes >= requiredBytes
    }

    fun getStorageRequiredMB(regionId: String): Double {
        val region = regions.value.find { it.id == regionId } ?: return 0.0
        return region.estimatedSizeMB
    }

    fun getAvailableStorageMB(): Double {
        val stats = storageManager.getStorageStats()
        return stats.availableBytes / (1024.0 * 1024.0)
    }

    /**
     * A region download is tens to hundreds of MB - worth a confirmation before
     * silently burning a rider's mobile data plan.
     */
    fun isOnMeteredConnection(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }
}
