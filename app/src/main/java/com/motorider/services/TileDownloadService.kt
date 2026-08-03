package com.motorider.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.motorider.R
import com.motorider.data.OfflineRegionRepository
import com.motorider.models.DownloadStatus
import com.motorider.models.OfflineRegion
import kotlinx.coroutines.*

class TileDownloadService : Service() {

    private lateinit var storageManager: TileStorageManager
    private lateinit var repository: OfflineRegionRepository
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    
    private var currentRegionId: String? = null
    private var isCancelled = false

    override fun onCreate() {
        super.onCreate()
        storageManager = TileStorageManager(this)
        repository = OfflineRegionRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val regionId = intent?.getStringExtra(EXTRA_REGION_ID)
        
        if (regionId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        currentRegionId = regionId
        
        val notification = createNotification(getString(R.string.downloading_tiles), 0, 100)
        startForeground(NOTIFICATION_ID, notification)
        
        startDownload(regionId)
        
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cancelDownload()
        serviceScope.cancel()
    }

    private fun startDownload(regionId: String) {
        downloadJob = serviceScope.launch {
            try {
                val region = repository.getRegionById(regionId)
                
                if (region == null) {
                    stopSelf()
                    return@launch
                }
                
                repository.updateDownloadStatus(regionId, DownloadStatus.DOWNLOADING, null)
                
                val totalTiles = calculateTotalTiles(region)
                var downloadedTiles = 0L
                
                for (zoom in region.minZoom..region.maxZoom) {
                    if (isCancelled) break
                    
                    val minX = TileStorageManager.lonToTileX(region.minLon, zoom)
                    val maxX = TileStorageManager.lonToTileX(region.maxLon, zoom)
                    val minY = TileStorageManager.latToTileY(region.maxLat, zoom)
                    val maxY = TileStorageManager.latToTileY(region.minLat, zoom)
                    
                    for (x in minX..maxX) {
                        if (isCancelled) break
                        
                        for (y in minY..maxY) {
                            if (isCancelled) break
                            
                            if (!storageManager.isTileDownloaded(zoom, x, y)) {
                                val success = storageManager.downloadTile(zoom, x, y)
                                
                                if (success) {
                                    downloadedTiles++
                                    
                                    val progress = ((downloadedTiles.toDouble() / totalTiles) * 100).toInt()
                                    updateNotification(getString(R.string.downloading_tiles), progress, 100)
                                    
                                    if (downloadedTiles % 10 == 0L) {
                                        repository.updateDownloadProgress(regionId, DownloadStatus.DOWNLOADING, downloadedTiles)
                                    }
                                }
                            } else {
                                downloadedTiles++
                            }
                        }
                    }
                }
                
                if (!isCancelled) {
                    repository.updateDownloadStatus(regionId, DownloadStatus.DOWNLOADED, System.currentTimeMillis())
                    repository.updateDownloadProgress(regionId, DownloadStatus.DOWNLOADED, downloadedTiles)
                    
                    showCompleteNotification(getString(R.string.download_complete))
                } else {
                    repository.updateDownloadStatus(regionId, DownloadStatus.NOT_DOWNLOADED, null)
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                
                if (currentRegionId != null) {
                    repository.updateDownloadStatus(currentRegionId!!, DownloadStatus.FAILED, null)
                }
                
                showErrorNotification(getString(R.string.download_failed))
            } finally {
                stopSelf()
            }
        }
    }

    private fun calculateTotalTiles(region: OfflineRegion): Long {
        return TileStorageManager.calculateTileCount(
            region.minLat,
            region.maxLat,
            region.minLon,
            region.maxLon,
            region.minZoom,
            region.maxZoom
        )
    }

    fun cancelDownload() {
        isCancelled = true
        downloadJob?.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.offline_maps),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.offline_maps_download_progress)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, progress: Int, max: Int): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .build()
    }

    private fun updateNotification(title: String, progress: Int, max: Int) {
        val notification = createNotification(title, progress, max)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompleteNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }

    companion object {
        private const val CHANNEL_ID = "tile_download_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_REGION_ID = "extra_region_id"
        
        fun startDownload(context: Context, regionId: String) {
            val intent = Intent(context, TileDownloadService::class.java).apply {
                putExtra(EXTRA_REGION_ID, regionId)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopDownload(context: Context) {
            val intent = Intent(context, TileDownloadService::class.java)
            context.stopService(intent)
        }
    }
}
