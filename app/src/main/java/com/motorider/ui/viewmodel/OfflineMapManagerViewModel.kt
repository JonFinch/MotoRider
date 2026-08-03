package com.motorider.ui.viewmodel

import android.app.Application
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

    private val repository = OfflineRegionRepository(application)
    private val storageManager = TileStorageManager(application)

    val regions: StateFlow<List<OfflineRegion>> = repository.regions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = listOf(OfflineRegion.SOUTH_EAST_UK)
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
            val existingRegion = repository.getRegionById(OfflineRegion.SOUTH_EAST_UK.id)
            
            if (existingRegion == null) {
                val region = OfflineRegion.SOUTH_EAST_UK
                val tileCount = TileStorageManager.calculateTileCount(
                    region.minLat,
                    region.maxLat,
                    region.minLon,
                    region.maxLon,
                    region.minZoom,
                    region.maxZoom
                )
                val estimatedSize = TileStorageManager.estimateStorageSize(tileCount)
                
                val regionWithStats = region.copy(
                    tileCount = tileCount,
                    estimatedSizeMB = estimatedSize
                )
                
                repository.insertRegion(regionWithStats)
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
}
