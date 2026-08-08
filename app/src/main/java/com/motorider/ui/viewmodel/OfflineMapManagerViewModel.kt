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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
                // values can only be computed at runtime from the bounds. Backfill both
                // when they haven't been sized yet: a fresh install (existing == null) OR
                // a region persisted with a zero count by an earlier build (which showed
                // "0 tiles / 0.0MB" and a broken 1/0 progress bar).
                if (existing != null && existing.tileCount > 0) continue

                val tileCount = TileStorageManager.calculateTileCount(
                    region.minLat,
                    region.maxLat,
                    region.minLon,
                    region.maxLon,
                    region.minZoom,
                    region.maxZoom
                )
                val estimatedSize = TileStorageManager.estimateStorageSize(tileCount)

                if (existing != null) {
                    repository.updateRegion(
                        existing.copy(tileCount = tileCount, estimatedSizeMB = estimatedSize)
                    )
                } else {
                    repository.insertRegion(
                        region.copy(tileCount = tileCount, estimatedSizeMB = estimatedSize)
                    )
                }
            }
        }
    }

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
            
            storageManager.deleteRegionTiles(
                region.minLat,
                region.maxLat,
                region.minLon,
                region.maxLon,
                region.minZoom,
                region.maxZoom
            )
            
            val updatedRegion = region.copy(
                downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                downloadedAt = null,
                downloadedTiles = 0
            )
            
            repository.updateRegion(updatedRegion)
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
