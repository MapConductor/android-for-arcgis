package com.mapconductor.arcgis.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mapconductor.arcgis.map.ArcGISMapView
import com.mapconductor.arcgis.map.rememberArcGISMapViewState
import com.mapconductor.core.circle.Circle
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.info.InfoBubble
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.DefaultMarkerIcon
import com.mapconductor.core.marker.Marker
import com.mapconductor.core.marker.MarkerAnimation
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.polygon.Polygon
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.Polyline
import com.mapconductor.core.polyline.PolylineState

// Optional: set an ArcGIS API key before showing the map.
// Call ArcGISEnvironment.apiKey = ApiKey.create("YOUR_API_KEY") in Application.onCreate().

private val TOKYO = GeoPoint(35.6762, 139.6503)
private val AIRPORTS = listOf(
    GeoPoint(35.5489, 139.7840), // HND
    GeoPoint(21.3245, -157.9251), // HNL
    GeoPoint(37.6152, -122.3900), // SFO
)
private val GORYOKAKU = listOf(
    GeoPoint(41.7988, 140.7568),
    GeoPoint(41.7992, 140.7588),
    GeoPoint(41.7977, 140.7591),
    GeoPoint(41.7964, 140.7602),
    GeoPoint(41.7957, 140.7585),
    GeoPoint(41.7945, 140.7571),
    GeoPoint(41.7950, 140.7561),
    GeoPoint(41.7948, 140.7548),
    GeoPoint(41.7958, 140.7548),
    GeoPoint(41.7962, 140.7536),
    GeoPoint(41.7974, 140.7545),
    GeoPoint(41.7991, 140.7547),
    GeoPoint(41.7988, 140.7567),
)

enum class Demo { Home, Marker, Circle, Polyline, Polygon }

private val Demo.label
    get() = when (this) {
        Demo.Home -> "Home"
        Demo.Marker -> "Marker + InfoBubble"
        Demo.Circle -> "Circle"
        Demo.Polyline -> "Polyline"
        Demo.Polygon -> "Polygon"
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SampleApp()
            }
        }
    }
}

@Composable
fun SampleApp() {
    var demo by rememberSaveable { mutableStateOf(Demo.Home) }
    when (demo) {
        Demo.Home -> HomeScreen(onSelect = { demo = it })
        else -> MapDemoScreen(demo = demo, onBack = { demo = Demo.Home })
    }
}

@Composable
private fun HomeScreen(onSelect: (Demo) -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ArcGIS Sample", style = MaterialTheme.typography.headlineSmall)
            Demo.entries.drop(1).forEach { demo ->
                Button(
                    onClick = { onSelect(demo) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(demo.label)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapDemoScreen(demo: Demo, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(demo.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (demo) {
                Demo.Marker -> MarkerDemo()
                Demo.Circle -> CircleDemo()
                Demo.Polyline -> PolylineDemo()
                Demo.Polygon -> PolygonDemo()
                else -> {}
            }
        }
    }
}

@Composable
private fun MarkerDemo(modifier: Modifier = Modifier) {
    var selectedMarker by remember { mutableStateOf<MarkerState?>(null) }

    val mapViewState = rememberArcGISMapViewState(
        cameraPosition = MapCameraPosition(position = TOKYO, zoom = 11.0),
    )

    val markerState = remember {
        MarkerState(
            position = TOKYO,
            icon = DefaultMarkerIcon().copy(label = "Tokyo"),
            onClick = {
                it.animate(MarkerAnimation.Bounce)
                selectedMarker = it
            },
        )
    }

    ArcGISMapView(state = mapViewState, modifier = modifier.fillMaxSize()) {
        Marker(markerState)

        selectedMarker?.let {
            InfoBubble(marker = it) {
                Text("Hello from Tokyo!")
            }
        }
    }
}

@Composable
private fun CircleDemo(modifier: Modifier = Modifier) {
    val mapViewState = rememberArcGISMapViewState(
        cameraPosition = MapCameraPosition(position = TOKYO, zoom = 12.0),
    )

    val circleState = remember {
        CircleState(
            center = TOKYO,
            radiusMeters = 1000.0,
            fillColor = Color.Blue.copy(alpha = 0.3f),
            strokeColor = Color.Blue,
        )
    }

    ArcGISMapView(state = mapViewState, modifier = modifier.fillMaxSize()) {
        Circle(circleState)
    }
}

@Composable
private fun PolylineDemo(modifier: Modifier = Modifier) {
    val mapViewState = rememberArcGISMapViewState(
        cameraPosition = MapCameraPosition(position = GeoPoint(30.0, -160.0), zoom = 3.0),
    )

    val polylineState = remember {
        PolylineState(
            points = AIRPORTS,
            strokeColor = Color.Blue,
            strokeWidth = 4.dp,
        )
    }

    ArcGISMapView(state = mapViewState, modifier = modifier.fillMaxSize()) {
        Polyline(polylineState)
    }
}

@Composable
private fun PolygonDemo(modifier: Modifier = Modifier) {
    val mapViewState = rememberArcGISMapViewState(
        cameraPosition = MapCameraPosition(position = GeoPoint(41.7988, 140.7568), zoom = 14.0),
    )

    val polygonState = remember {
        PolygonState(
            points = GORYOKAKU,
            fillColor = Color.Red.copy(alpha = 0.4f),
            strokeColor = Color.Red,
            strokeWidth = 2.dp,
        )
    }

    ArcGISMapView(state = mapViewState, modifier = modifier.fillMaxSize()) {
        Polygon(polygonState)
    }
}
