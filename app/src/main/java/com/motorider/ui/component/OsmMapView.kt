package com.motorider.ui.component

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.motorider.utils.MapTileSource
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Backs the "you are here" overlay with whichever GPS subscription is authoritative
 * right now, so the app never holds two at once.
 *
 * Off navigation, nothing else on screen wants location, so this behaves like the
 * overlay's own default provider and subscribes to GPS itself. While navigating,
 * `NavigationService` already holds the one subscription driving turn-by-turn
 * guidance; this relays its fixes instead of opening a second one purely to keep
 * the blue dot moving — pure battery cost for no extra accuracy on a ride.
 */
private class RelayLocationProvider(context: Context) : IMyLocationProvider, IMyLocationConsumer {
    private val gpsProvider = GpsMyLocationProvider(context)
    private var consumer: IMyLocationConsumer? = null
    private var started = false
    private var navigating = false
    private var lastPushed: Location? = null

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        started = true
        // Subscribe as the consumer ourselves rather than handing the overlay
        // straight to the GPS provider, so every fix reaching the overlay goes
        // through [emit] and gets the same treatment whether it came from here or
        // was relayed from NavigationService.
        return if (navigating) true else gpsProvider.startLocationProvider(this)
    }

    /** Fixes from the internal GPS provider, when not navigating. */
    override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
        location?.let { emit(it) }
    }

    override fun stopLocationProvider() {
        started = false
        gpsProvider.stopLocationProvider()
    }

    override fun getLastKnownLocation(): Location? =
        (if (navigating) lastPushed else gpsProvider.lastKnownLocation)?.let(::uprightFix)

    override fun destroy() {
        gpsProvider.destroy()
    }

    /** Fed a fix relayed from NavigationService; ignored unless currently navigating. */
    fun push(location: Location) {
        lastPushed = location
        if (navigating && started) emit(location)
    }

    private fun emit(location: Location) {
        consumer?.onLocationChanged(uprightFix(location), this)
    }

    fun setNavigating(isNavigating: Boolean) {
        if (navigating == isNavigating) return
        navigating = isNavigating
        consumer ?: return
        if (!started) return
        if (isNavigating) {
            gpsProvider.stopLocationProvider()
        } else {
            gpsProvider.startLocationProvider(this)
        }
    }
}

/**
 * Rasterise the motorcycle marker at the screen's density.
 *
 * osmdroid draws the position marker with `Canvas.drawBitmap`, so the vector has to
 * be baked once rather than scaled per frame.
 */
private fun Context.motorcycleMarkerBitmap(): Bitmap {
    val drawable = requireNotNull(
        androidx.core.content.ContextCompat.getDrawable(this, com.motorider.R.drawable.ic_motorcycle_marker)
    )
    val size = (44 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(size, size)
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap
}

/**
 * The same fix with its bearing removed.
 *
 * `MyLocationNewOverlay` turns the marker by the bearing on the fix, which points
 * the bike along the true heading on a map that is itself already turned heading-up
 * — the two rotations fight, and the bike wobbles about vertical as the camera's
 * smoothing lags the raw course. Without a bearing the overlay takes its other
 * path, which counter-rotates the icon by the map's orientation and so holds it
 * upright on screen at all times.
 *
 * That is the behaviour wanted: the map turns so the way ahead is up, and the bike
 * points up because up *is* the way ahead. The heading itself is not lost — it is
 * carried in `LocationResult.bearing`, which is what turns the camera.
 */
private fun uprightFix(fix: Location): Location =
    Location(fix.provider ?: LocationManager.GPS_PROVIDER).apply {
        latitude = fix.latitude
        longitude = fix.longitude
        time = fix.time
        elapsedRealtimeNanos = fix.elapsedRealtimeNanos
        if (fix.hasAccuracy()) accuracy = fix.accuracy
        if (fix.hasSpeed()) speed = fix.speed
        if (fix.hasAltitude()) altitude = fix.altitude
    }

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    onLocationReceived: ((GeoPoint) -> Unit)? = null,
    onMapViewReady: ((MapView) -> Unit)? = null,
    onMapTapped: (() -> Unit)? = null,
    /**
     * Fired while the rider is pinching. Reported from here rather than by watching
     * osmdroid's zoom events, because those fire for programmatic zooms too and a
     * following camera could not tell its own changes from the rider's.
     */
    onMapPinched: (() -> Unit)? = null,
    /** True for the duration of a ride — see [RelayLocationProvider]. */
    isNavigating: Boolean = false,
    /** Latest fix from NavigationService, relayed to the overlay while navigating. */
    navigationFix: Location? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { android.os.Handler(Looper.getMainLooper()) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(MapTileSource.get())
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            controller?.setZoom(12.0)
            controller?.setCenter(GeoPoint(51.5074, -0.1278))
            Configuration.getInstance().userAgentValue = context.packageName
        }
    }

    val relayProvider = remember { RelayLocationProvider(context) }

    val locationOverlay = remember {
        MyLocationNewOverlay(relayProvider, mapView).apply {
            // A motorcycle from above rather than osmdroid's default dot-and-arrow.
            // Both slots get the same bitmap because [uprightFix] strips the bearing
            // from every fix, so the overlay always takes its upright path — but
            // setting only one would leave the other as osmdroid's default man if
            // that ever changed.
            val bike = context.motorcycleMarkerBitmap()
            setPersonIcon(bike)
            setDirectionIcon(bike)
            // Both anchors, explicitly. osmdroid's constructor anchors the person
            // icon at (0.5, 0.8125) — the feet of the walking figure it ships — and
            // the deprecated setDirectionArrow() does not touch it. Left alone, the
            // bike hangs off the fix by its rear wheel.
            setPersonAnchor(0.5f, 0.5f)
            setDirectionAnchor(0.5f, 0.5f)
            enableMyLocation()
            runOnFirstFix {
                myLocation?.let { loc ->
                    mainHandler.post {
                        mapView.controller?.setCenter(loc)
                        mapView.controller?.setZoom(15.0)
                        onLocationReceived?.invoke(loc)
                    }
                }
            }
        }
    }

    LaunchedEffect(isNavigating) {
        relayProvider.setNavigating(isNavigating)
    }

    LaunchedEffect(navigationFix) {
        navigationFix?.let(relayProvider::push)
    }

    DisposableEffect(Unit) {
        mapView.overlays.add(locationOverlay)
        onMapViewReady?.invoke(mapView)
        onDispose {
            mapView.onDetach()
        }
    }

    DisposableEffect(onMapTapped, onMapPinched) {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (e.pointerCount == 1) {
                    onMapTapped?.invoke()
                }
                return false
            }
        })
        val listener = android.view.View.OnTouchListener { _, event ->
            detector.onTouchEvent(event)
            // Two pointers down can only be the rider; osmdroid never synthesises it.
            if (event.pointerCount >= 2) {
                onMapPinched?.invoke()
            }
            false
        }
        mapView.setOnTouchListener(listener)
        onDispose {
            mapView.setOnTouchListener(null)
        }
    }

    val observer = remember {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    locationOverlay.enableMyLocation()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapView.onPause()
                    locationOverlay.disableMyLocation()
                    Configuration.getInstance()
                        .save(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                }
                else -> {}
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView }
    )
}
