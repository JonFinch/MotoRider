package com.motorider.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.motorider.R
import com.motorider.activities.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Result of a location update */
data class LocationResult(
    val location: Location,
    val speed: Float,
    val accuracy: Float,
    val bearing: Float
)

/**
 * Foreground service that collects GPS fixes for the duration of a ride.
 *
 * It deliberately knows nothing about routes or instructions — it publishes raw
 * fixes on a [StateFlow] and renders whatever notification text it is handed.
 * All navigation logic lives in `NavigationManager`.
 */
class NavigationService : Service() {

    companion object {
        private const val CHANNEL_ID = "MotoRiderNavigationChannel"
        private const val NAVIGATION_NOTIFICATION_ID = 1
        private const val TAG = "NavigationService"
        private const val GPS_INTERVAL_MS = 1000L
        private const val GPS_INTERVAL_SCREEN_OFF_MS = 2000L

        /** Notification refreshes are throttled to this; fixes arrive far faster. */
        private const val NOTIFICATION_MIN_INTERVAL_MS = 1000L

        const val ACTION_PAUSE = "com.motorider.action.PAUSE"
        const val ACTION_END = "com.motorider.action.END"
    }

    private val binder = NavigationBinder()
    private val locationUpdater = LocationUpdater()

    private val _locationFlow = MutableStateFlow<LocationResult?>(null)
    private val _isTracking = MutableStateFlow(false)

    /** Set when the rider taps Pause or End on the notification. */
    private val _notificationAction = MutableStateFlow<String?>(null)

    private lateinit var locationManager: LocationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var powerManager: PowerManager

    private var gpsIntervalMs = GPS_INTERVAL_MS
    private var isScreenOn = true

    private var currentInstruction: String? = null
    private var currentDistance: String? = null
    private var currentEta: String? = null
    private var lastNotificationAtMs = 0L

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> applyScreenState(true)
                Intent.ACTION_SCREEN_OFF -> applyScreenState(false)
                ACTION_PAUSE -> {
                    Log.d(TAG, "Pause action received from notification")
                    _notificationAction.value = ACTION_PAUSE
                    stopTracking()
                }
                ACTION_END -> {
                    Log.d(TAG, "End action received from notification")
                    _notificationAction.value = ACTION_END
                    stopTracking()
                    stopSelf()
                }
            }
        }
    }

    inner class NavigationBinder : Binder() {
        /** The service itself, so callers can drive tracking and the notification. */
        fun getService(): NavigationService = this@NavigationService

        fun getLocationFlow(): StateFlow<LocationResult?> = _locationFlow.asStateFlow()

        fun getIsTracking(): StateFlow<Boolean> = _isTracking.asStateFlow()

        /** Emits ACTION_PAUSE / ACTION_END when the rider uses the notification. */
        fun getNotificationActions(): StateFlow<String?> = _notificationAction.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NavigationService created")

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MotoRider:NavigationWakeLock"
        )
        isScreenOn = powerManager.isInteractive

        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_PAUSE)
            addAction(ACTION_END)
        }
        // Android 14 requires every runtime-registered receiver to declare whether
        // other apps may reach it; omitting the flag throws on API 34+.
        ContextCompat.registerReceiver(
            this, actionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "NavigationService started")
        try {
            startForeground(NAVIGATION_NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Android 12+ refuses a foreground start from the background, and 14+
            // refuses a "location" type without the location permission held. Dying
            // quietly beats an ANR-style crash the rider cannot act on.
            Log.e(TAG, "Could not enter the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopTracking()
        try { unregisterReceiver(actionReceiver) } catch (_: Exception) {}
        Log.d(TAG, "NavigationService destroyed")
        super.onDestroy()
    }

    // ─── Tracking ────────────────────────────────────────────────────────────

    /**
     * Start GPS tracking. Returns false when location permission is missing or no
     * provider could be engaged, so the caller can tell the rider rather than
     * leaving them on a screen that never updates.
     */
    fun startTracking(): Boolean {
        if (_isTracking.value) {
            Log.d(TAG, "Tracking already active")
            return true
        }

        val hasFineLocation = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation) {
            Log.w(TAG, "ACCESS_FINE_LOCATION permission not granted")
            return false
        }

        _notificationAction.value = null
        isScreenOn = powerManager.isInteractive
        gpsIntervalMs = if (isScreenOn) GPS_INTERVAL_MS else GPS_INTERVAL_SCREEN_OFF_MS

        if (!requestUpdates()) return false

        _isTracking.value = true
        acquireWakeLock()
        return true
    }

    /**
     * Hold the CPU awake so fixes keep arriving with the screen off.
     *
     * Never fatal: losing the wake lock degrades navigation to whatever the system
     * delivers while dozing, but throwing here would kill the app mid-ride, which
     * is far worse than a stale position.
     */
    private fun acquireWakeLock() {
        try {
            if (!wakeLock.isHeld) wakeLock.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Could not acquire wake lock; GPS may pause when the screen is off", e)
        }
    }

    fun stopTracking() {
        if (!_isTracking.value) return
        _isTracking.value = false

        try {
            locationManager.removeUpdates(locationUpdater)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates", e)
        }

        if (wakeLock.isHeld) {
            try { wakeLock.release() } catch (_: Exception) {}
        }
        Log.d(TAG, "GPS tracking stopped")
    }

    /**
     * Ask the platform for fixes. GPS is the source that matters on a bike; the
     * network provider is added only as a coarse stand-in while GPS acquires.
     */
    private fun requestUpdates(): Boolean {
        var engaged = false

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                gpsIntervalMs,
                0f,
                locationUpdater,
                Looper.getMainLooper()
            )
            engaged = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start GPS: permission denied", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "GPS provider unavailable", e)
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    gpsIntervalMs * 4,
                    10f,
                    locationUpdater,
                    Looper.getMainLooper()
                )
                engaged = true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for network location", e)
        } catch (e: Exception) {
            Log.w(TAG, "Network provider unavailable", e)
        }

        if (!engaged) Log.e(TAG, "No location provider could be engaged")
        return engaged
    }

    private fun applyScreenState(screenOn: Boolean) {
        if (isScreenOn == screenOn) return
        isScreenOn = screenOn

        val newInterval = if (screenOn) GPS_INTERVAL_MS else GPS_INTERVAL_SCREEN_OFF_MS
        if (newInterval == gpsIntervalMs) return
        gpsIntervalMs = newInterval
        Log.d(TAG, "Screen ${if (screenOn) "on" else "off"} — GPS interval ${gpsIntervalMs}ms")

        if (!_isTracking.value) return
        try {
            locationManager.removeUpdates(locationUpdater)
        } catch (_: Exception) {}
        requestUpdates()
    }

    // ─── Notification ────────────────────────────────────────────────────────

    /**
     * Update the ongoing notification with the live manoeuvre, so the rider can
     * read the next turn from the lock screen without unlocking the phone.
     */
    fun updateNavigationInstruction(instruction: String?, distance: String?, eta: String?) {
        val unchanged = instruction == currentInstruction &&
            distance == currentDistance && eta == currentEta
        val now = System.currentTimeMillis()
        if (unchanged || now - lastNotificationAtMs < NOTIFICATION_MIN_INTERVAL_MS) return

        currentInstruction = instruction
        currentDistance = distance
        currentEta = eta
        lastNotificationAtMs = now

        getSystemService(NotificationManager::class.java)
            .notify(NAVIGATION_NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val speedText = _locationFlow.value?.let { "${(it.speed * 3.6f).toInt()} km/h" }
        val summary = listOfNotNull(currentDistance, currentEta, speedText).joinToString(" · ")

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_motorcycle)
            .setContentTitle(currentInstruction ?: getString(R.string.notification_title))
            .setContentText(summary.ifEmpty { getString(R.string.notification_navigating) })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (!currentInstruction.isNullOrEmpty()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(currentInstruction)
                    .setSummaryText(summary)
            )
        }

        builder.addAction(
            NotificationCompat.Action(
                R.drawable.ic_motorcycle, getString(R.string.nav_pause), broadcastIntent(ACTION_PAUSE, 0)
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.ic_close, getString(R.string.nav_end_ride), broadcastIntent(ACTION_END, 1)
            )
        )

        return builder.build()
    }

    /** Explicitly package-scoped so Android 14 does not drop it as an implicit broadcast. */
    private fun broadcastIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this, requestCode,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ─── Location callback ───────────────────────────────────────────────────

    private inner class LocationUpdater : LocationListener {

        override fun onLocationChanged(location: Location) {
            // hasSpeed()/hasBearing() rather than a `> 0` test: a stationary rider
            // legitimately reports 0 m/s, and due north is a legitimate bearing of 0.
            val result = LocationResult(
                location = location,
                speed = if (location.hasSpeed()) location.speed else 0f,
                accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
                bearing = if (location.hasBearing()) location.bearing else 0f
            )

            _locationFlow.value = result
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Location provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "Location provider disabled: $provider")
        }

        @Deprecated("Required by LocationListener on API < 30")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }
}
