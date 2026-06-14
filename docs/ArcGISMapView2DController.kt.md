# `ArcGISMapView2DController`

Controller implementation for the 2D ArcGIS `MapView`. It connects Map Conductor state and overlay
controllers to the native ArcGIS map, translating camera, gesture, and overlay operations into
ArcGIS Maps SDK calls.

## Signature

```kotlin
class ArcGISMapView2DController(
    private val mapView: MapView,
    private val map: ArcGISMap,
    private val markerController: ArcGISMarkerController,
    private val polylineOverlayController: ArcGISPolylineOverlayController,
    private val polygonOverlayController: ArcGISPolygonOverlayController,
    private val circleOverlayController: ArcGISCircleOverlayController,
    private val groundImageController: ArcGISGroundImageController,
    private val rasterLayerController: ArcGISRasterLayerController,
    private val coroutineScope: CoroutineScope,
) : BaseMapViewController(), ArcGISMapViewControllerInterface
```

## Description

`ArcGISMapView2DController` is installed by `ArcGISMapView2D` after the native `MapView` and
`ArcGISMap` are ready. It maintains a shared graphics overlay for vector features, delegates
overlay composition to dedicated controllers, and dispatches map callbacks to Map Conductor event
handlers.

The controller intentionally treats the map as 2D:

- Camera tilt is reported as `0.0`.
- Camera zoom is derived from ArcGIS map scale.
- Camera updates are applied with 2D viewpoints.
- Marker placement uses the 2D renderer path.

## Responsibilities

- Move the map camera to `MapCameraPosition` or `GeoPoint` targets.
- Report camera movement start, move, and end events.
- Dispatch map click and long-click events.
- Coordinate marker, polyline, polygon, circle, ground image, and raster layer updates.
- Clean up native listeners and overlay resources when the map view leaves composition.

## Notes

This controller is an internal implementation detail for the ArcGIS module. Applications normally
interact with it through `ArcGISMapViewState` and the composable overlay APIs rather than creating
the controller directly.

