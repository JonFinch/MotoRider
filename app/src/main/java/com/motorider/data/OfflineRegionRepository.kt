package com.motorider.data

import android.content.Context
import com.motorider.models.DownloadStatus
import com.motorider.models.OfflineRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OfflineRegionRepository(private val context: Context) {
    
    private val regionsFile = File(context.filesDir, "offline_regions.json")
    private val _regions = MutableStateFlow<List<OfflineRegion>>(emptyList())
    val regions: StateFlow<List<OfflineRegion>> = _regions.asStateFlow()
    
    init {
        loadRegions()
    }
    
    private fun loadRegions() {
        if (regionsFile.exists()) {
            try {
                val json = regionsFile.readText()
                val jsonArray = JSONArray(json)
                val regionList = mutableListOf<OfflineRegion>()
                
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    regionList.add(
                        OfflineRegion(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            description = obj.getString("description"),
                            minLat = obj.getDouble("minLat"),
                            maxLat = obj.getDouble("maxLat"),
                            minLon = obj.getDouble("minLon"),
                            maxLon = obj.getDouble("maxLon"),
                            minZoom = obj.getInt("minZoom"),
                            maxZoom = obj.getInt("maxZoom"),
                            tileCount = obj.getLong("tileCount"),
                            estimatedSizeMB = obj.getDouble("estimatedSizeMB"),
                            downloadStatus = DownloadStatus.valueOf(obj.getString("downloadStatus")),
                            downloadedAt = if (obj.has("downloadedAt") && !obj.isNull("downloadedAt")) obj.getLong("downloadedAt") else null,
                            downloadedTiles = obj.getLong("downloadedTiles")
                        )
                    )
                }
                
                _regions.value = regionList
            } catch (e: Exception) {
                e.printStackTrace()
                _regions.value = listOf(OfflineRegion.SOUTH_EAST_UK)
            }
        } else {
            _regions.value = listOf(OfflineRegion.SOUTH_EAST_UK)
            saveRegions()
        }
    }
    
    private fun saveRegions() {
        try {
            val jsonArray = JSONArray()
            
            _regions.value.forEach { region ->
                val obj = JSONObject()
                obj.put("id", region.id)
                obj.put("name", region.name)
                obj.put("description", region.description)
                obj.put("minLat", region.minLat)
                obj.put("maxLat", region.maxLat)
                obj.put("minLon", region.minLon)
                obj.put("maxLon", region.maxLon)
                obj.put("minZoom", region.minZoom)
                obj.put("maxZoom", region.maxZoom)
                obj.put("tileCount", region.tileCount)
                obj.put("estimatedSizeMB", region.estimatedSizeMB)
                obj.put("downloadStatus", region.downloadStatus.name)
                obj.put("downloadedAt", region.downloadedAt)
                obj.put("downloadedTiles", region.downloadedTiles)
                jsonArray.put(obj)
            }
            
            regionsFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getRegionById(regionId: String): OfflineRegion? {
        return _regions.value.find { it.id == regionId }
    }
    
    fun updateRegion(region: OfflineRegion) {
        val index = _regions.value.indexOfFirst { it.id == region.id }
        if (index >= 0) {
            _regions.value = _regions.value.toMutableList().apply {
                set(index, region)
            }
            saveRegions()
        }
    }
    
    fun updateDownloadProgress(regionId: String, status: DownloadStatus, downloadedTiles: Long) {
        val region = getRegionById(regionId) ?: return
        updateRegion(region.copy(downloadStatus = status, downloadedTiles = downloadedTiles))
    }
    
    fun updateDownloadStatus(regionId: String, status: DownloadStatus, downloadedAt: Long?) {
        val region = getRegionById(regionId) ?: return
        updateRegion(region.copy(downloadStatus = status, downloadedAt = downloadedAt))
    }
    
    fun insertRegion(region: OfflineRegion) {
        if (_regions.value.none { it.id == region.id }) {
            _regions.value = _regions.value + region
            saveRegions()
        } else {
            updateRegion(region)
        }
    }
}
