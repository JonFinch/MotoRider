package com.motorider.ui.component

import android.content.Context
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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    onLocationReceived: ((GeoPoint) -> Unit)? = null,
    onMapViewReady: ((MapView) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            controller?.setZoom(12.0)
            controller?.setCenter(GeoPoint(51.5074, -0.1278))
            Configuration.getInstance().userAgentValue = context.packageName

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent): Boolean = false
                override fun onZoom(event: ZoomEvent): Boolean = false
            })
        }
    }

    val locationOverlay = remember {
        MyLocationNewOverlay(mapView).apply {
            enableMyLocation()
            runOnFirstFix {
                myLocation?.let { loc ->
                    mapView.controller?.setCenter(loc)
                    mapView.controller?.setZoom(15.0)
                    onLocationReceived?.invoke(loc)
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

    val observer = remember {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
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
