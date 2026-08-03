package com.motorider.models

enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

data class OfflineRegion(
    val id: String,
    val name: String,
    val description: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val minZoom: Int = 10,
    val maxZoom: Int = 16,
    val tileCount: Long = 0,
    val estimatedSizeMB: Double = 0.0,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadedAt: Long? = null,
    val downloadedTiles: Long = 0
) {
    val centerLat: Double get() = (minLat + maxLat) / 2
    val centerLon: Double get() = (minLon + maxLon) / 2
    
    companion object {
        val SOUTH_EAST_UK = OfflineRegion(
            id = "south_east_uk",
            name = "South East UK",
            description = "Kent, Sussex, London, Essex",
            minLat = 50.7,
            maxLat = 51.9,
            minLon = -0.8,
            maxLon = 1.5,
            minZoom = 10,
            maxZoom = 16
        )
    }
}
