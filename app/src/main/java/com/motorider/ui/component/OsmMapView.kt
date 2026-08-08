package com.motorider.ui.component

import android.content.Context
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    onLocationReceived: ((GeoPoint) -> Unit)? = null,
    onMapViewReady: ((MapView) -> Unit)? = null,
    onMapTapped: (() -> Unit)? = null
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

    val locationOverlay = remember {
        MyLocationNewOverlay(mapView).apply {
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

    DisposableEffect(Unit) {
        mapView.overlays.add(locationOverlay)
        onMapViewReady?.invoke(mapView)
        onDispose {
            mapView.onDetach()
        }
    }

    DisposableEffect(onMapTapped) {
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
