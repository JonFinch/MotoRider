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
    val maxZoom: Int = 14,
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
            maxZoom = 14
        )

        val SOUTH_WEST_UK = OfflineRegion(
            id = "south_west_uk",
            name = "South West England",
            description = "Devon, Cornwall, Dorset",
            minLat = 49.9,
            maxLat = 51.3,
            minLon = -5.75,
            maxLon = -2.9,
            minZoom = 10,
            maxZoom = 14
        )

        val WALES = OfflineRegion(
            id = "wales",
            name = "Wales",
            description = "Snowdonia, Brecon Beacons, Pembrokeshire",
            minLat = 51.3,
            maxLat = 53.4,
            minLon = -5.3,
            maxLon = -2.6,
            minZoom = 10,
            maxZoom = 14
        )

        val PEAK_AND_LAKE_DISTRICT = OfflineRegion(
            id = "peak_lake_district",
            name = "Peak District & Lake District",
            description = "Derbyshire uplands to Cumbria",
            minLat = 52.9,
            maxLat = 54.8,
            minLon = -3.4,
            maxLon = -1.4,
            minZoom = 10,
            maxZoom = 14
        )

        val SCOTTISH_HIGHLANDS = OfflineRegion(
            id = "scottish_highlands",
            name = "Scottish Highlands",
            description = "Fort William, Glencoe, the North Coast",
            minLat = 56.0,
            maxLat = 58.7,
            minLon = -6.0,
            maxLon = -2.8,
            minZoom = 10,
            maxZoom = 14
        )

        /** Regions offered out of the box. Custom user-drawn regions are a future addition. */
        val DEFAULTS = listOf(
            SOUTH_EAST_UK,
            SOUTH_WEST_UK,
            WALES,
            PEAK_AND_LAKE_DISTRICT,
            SCOTTISH_HIGHLANDS
        )
    }
}
