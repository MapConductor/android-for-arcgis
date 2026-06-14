# `ArcGISMapView2D`

A Jetpack Compose composable that displays an interactive 2D ArcGIS map. It uses the ArcGIS Maps
SDK for Android `MapView` and provides the same Map Conductor overlay model as the 3D
`ArcGISMapView`, while keeping camera behavior limited to 2D map concepts.

Use this component when the application needs a flat map instead of a `SceneView`. Markers,
polylines, polygons, circles, ground images, and raster layers can be declared in the `content`
lambda through `ArcGISMapViewScope`.

## Signature

```kotlin
@Composable
fun ArcGISMapView2D(
    state: ArcGISMapViewState,
    modifier: Modifier = Modifier,
    markerTiling: MarkerTilingOptions? = null,
    sdkInitialize: (suspend (android.content.Context) -> Boolean)? = null,
    onMapLoaded: OnMapLoadedHandler? = null,
    onCameraMoveStart: OnCameraMoveHandler? = null,
    onCameraMove: OnCameraMoveHandler? = null,
    onCameraMoveEnd: OnCameraMoveHandler? = null,
    onMapClick: OnMapEventHandler? = null,
    onMapLongClick: OnMapEventHandler? = null,
    content: (@Composable ArcGISMapViewScope.() -> Unit)? = null,
)
```

## Description

`ArcGISMapView2D` creates and owns an ArcGIS `MapView`, wires it into `ArcGISMapViewState`, and
keeps Map Conductor overlays synchronized with the native map. The composable handles SDK
initialization, map creation, camera callbacks, click callbacks, and lifecycle cleanup.

Unlike `ArcGISMapView`, this composable is backed by a 2D `MapView`. Camera tilt is not applied, and
overlay placement uses the 2D map implementation.

## Parameters

- `state`
    - Type: `ArcGISMapViewState`
    - Description: State object that stores map style, camera position, initialization state, and
      the active controller.
- `modifier`
    - Type: `Modifier`
    - Description: Compose modifier applied to the hosted map view.
- `markerTiling`
    - Type: `MarkerTilingOptions?`
    - Description: Optional marker tiling configuration. If omitted, default marker tiling settings
      are used.
- `sdkInitialize`
    - Type: `(suspend (Context) -> Boolean)?`
    - Description: Optional ArcGIS SDK initialization hook. Return `true` when initialization
      succeeds.
- `onMapLoaded`
    - Type: `OnMapLoadedHandler?`
    - Description: Called after the map has loaded.
- `onCameraMoveStart`
    - Type: `OnCameraMoveHandler?`
    - Description: Called when camera movement starts.
- `onCameraMove`
    - Type: `OnCameraMoveHandler?`
    - Description: Called while the camera is moving.
- `onCameraMoveEnd`
    - Type: `OnCameraMoveHandler?`
    - Description: Called when camera movement ends.
- `onMapClick`
    - Type: `OnMapEventHandler?`
    - Description: Called for map click events not consumed by overlays.
- `onMapLongClick`
    - Type: `OnMapEventHandler?`
    - Description: Called for map long-click events.
- `content`
    - Type: `(@Composable ArcGISMapViewScope.() -> Unit)?`
    - Description: Overlay content declared inside the ArcGIS map scope.

## Example

```kotlin
ArcGISMapView2D(
    state = mapState,
    modifier = Modifier.fillMaxSize(),
    onMapLoaded = { println("2D ArcGIS map loaded") },
    onMapClick = { point -> println("Clicked: $point") },
) {
    Marker(state = markerState)
}
```

