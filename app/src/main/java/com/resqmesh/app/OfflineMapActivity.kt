package com.resqmesh.app

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class OfflineMapActivity : ComponentActivity() {
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        val latitude = intent.getDoubleExtra("latitude", Double.NaN)
        val longitude = intent.getDoubleExtra("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            finish()
            return
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val note = TextView(this).apply {
            text = "SOS location · ${"%.5f".format(latitude)}, ${"%.5f".format(longitude)}\nOffline use requires this area and zoom level to have been cached while online. Blank tiles mean map data is not cached. Map data © OpenStreetMap contributors."
            setPadding(24)
            textSize = 14f
        }
        root.addView(note, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(latitude, longitude))
        }
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(latitude, longitude)
            title = "RESQMesh SOS location"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        })
        root.addView(map, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        mapView = map
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mapView?.onDetach()
        mapView = null
        super.onDestroy()
    }
}
