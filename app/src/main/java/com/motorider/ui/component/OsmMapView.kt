package com.motorider.ui.component

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.location.Location
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
private class RelayLocationProvider(context: Context) : IMyLocationProvider {
    private val gpsProvider = GpsMyLocationProvider(context)
    private var consumer: IMyLocationConsumer? = null
    private var started = false
    private var navigating = false
    private var lastPushed: Location? = null

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        started = true
        return if (navigating) true else gpsProvider.startLocationProvider(myLocationConsumer)
    }

    override fun stopLocationProvider() {
        started = false
        gpsProvider.stopLocationProvider()
    }

    override fun getLastKnownLocation(): Location? =
        if (navigating) lastPushed else gpsProvider.lastKnownLocation

    override fun destroy() {
        gpsProvider.destroy()
    }

    /** Fed a fix relayed from NavigationService; ignored unless currently navigating. */
    fun push(location: Location) {
        lastPushed = location
        if (navigating && started) consumer?.onLocationChanged(location, this)
    }

    fun setNavigating(isNavigating: Boolean) {
        if (navigating == isNavigating) return
        navigating = isNavigating
        val c = consumer ?: return
        if (!started) return
        if (isNavigating) {
            gpsProvider.stopLocationProvider()
        } else {
            gpsProvider.startLocationProvider(c)
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
            // The same bitmap serves both states on purpose: osmdroid swaps to the
            // "person" icon whenever a fix arrives without a bearing, which happens
            // every time the rider stops, and a marker that changes shape at every
            // set of lights is worse than one that simply stops turning.
            val bike = context.motorcycleMarkerBitmap()
            setDirectionArrow(bike, bike)
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
