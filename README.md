# ArcGIS SDK for MapConductor Android

## Description

MapConductor provides a unified API for Android Jetpack Compose.
You can use Google Maps view with Jetpack Compose, but you can also switch to other Maps SDKs (such as Mapbox, HERE, and so on), anytimes.

Even you use the wrapper API, but you can still access to the native ArcGIS view if you want.

## Usage

```kotlin
@Composable
fun MapView(modifier: Modififer = Modififer) {
    val center = GeoPoint(
        latitude = 35.0,
        logitude = 137.0,
    )
    val mapViewState = rememberArcGISMapViewState(
        position = center,
        zoom = 10.0,
    )
    val markerState = MarkerState(
        position = center,
        icon = DefaultIcon.copy(
            label = "Hello, World!",
        ),
    )

    ArcGISMapView(
        state = mapViewState,
        modififer = modifier,
    ) {
        Marker(
            state = markerState,
        )
    }
)

```

## Setup

https://docs-android.mapconductor.com/setup/arcgis/



