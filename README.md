# ArcGIS SDK for MapConductor Android

## Description

MapConductor provides a unified API for Android Jetpack Compose.
You can use ArcGIS view with Jetpack Compose, but you can also switch to other Maps SDKs (such as Mapbox, HERE, and so on), anytimes.

Even you use the wrapper API, but you can still access to the native ArcGIS view if you want.

## Setup

https://docs-android.mapconductor.com/setup/arcgis/

## Usage

```kotlin
@Composable
fun MapView(modifier: Modififer = Modififer) {
    val state = rememberArcGISMapViewState(
        cameraPosition =
            MapCameraPosition(
                position = GeoPoint.fromLatLong(0.0, 0.0),
                zoom = 5.0,
            ),
    )

    ArcGISMapView(
        modifier = modifier,
        state = state,
    ) {
        // If this displays correctly, your setup is working
    }
}
```

![](docs/images/basic-setup-arcgis.png)
