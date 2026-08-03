package com.motorider.services

import android.content.Context
import android.os.Environment
import android.os.StatFs
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class TileStorageManager(private val context: Context) {
    
    private val tileSource = XYTileSource(
        "Mapnik",
        0, 19, 256, ".png",
        arrayOf("https://tile.openstreetmap.org/")
    )
    
    fun getTileStorageDir(): File {
        val storageDir = context.getExternalFilesDir("tiles")
            ?: File(context.filesDir, "tiles")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return storageDir
    }
    
    fun getTileFile(zoom: Int, x: Int, y: Int): File {
        val tileDir = File(getTileStorageDir(), "$zoom/$x")
        if (!tileDir.exists()) {
            tileDir.mkdirs()
        }
        return File(tileDir, "$y.png")
    }
    
    fun isTileDownloaded(zoom: Int, x: Int, y: Int): Boolean {
        return getTileFile(zoom, x, y).exists()
    }
    
    suspend fun downloadTile(zoom: Int, x: Int, y: Int): Boolean {
        val tileFile = getTileFile(zoom, x, y)
        
        if (tileFile.exists()) {
            return true
        }
        
        val url = buildTileUrl(zoom, x, y)
        
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "MotoRider/1.0")
            
            if (connection.responseCode == 200) {
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(tileFile)
                
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                
                connection.disconnect()
                true
            } else {
                connection.disconnect()
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (tileFile.exists()) {
                tileFile.delete()
            }
            false
        }
    }
    
    private fun buildTileUrl(zoom: Int, x: Int, y: Int): String {
        return "https://tile.openstreetmap.org/$zoom/$x/$y.png"
    }
    
    fun deleteTile(zoom: Int, x: Int, y: Int): Boolean {
        val tileFile = getTileFile(zoom, x, y)
        return if (tileFile.exists()) {
            tileFile.delete()
        } else {
            true
        }
    }
    
    fun deleteRegionTiles(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, minZoom: Int, maxZoom: Int) {
        for (zoom in minZoom..maxZoom) {
            val minX = lonToTileX(minLon, zoom)
            val maxX = lonToTileX(maxLon, zoom)
            val minY = latToTileY(maxLat, zoom)
            val maxY = latToTileY(minLat, zoom)
            
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    deleteTile(zoom, x, y)
                }
            }
        }
    }
    
    fun getStorageStats(): StorageStats {
        val storageDir = getTileStorageDir()
        val totalSize = calculateDirSize(storageDir)
        val availableSpace = getAvailableSpace()
        
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
    
    private fun getAvailableSpace(): Long {
        val stat = StatFs(getTileStorageDir().absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
    
    companion object {
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
