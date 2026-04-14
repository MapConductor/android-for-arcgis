# ArcGISMapViewController

The `ArcGISMapViewController` class is the primary controller for managing the ArcGIS map view.
It orchestrates interactions between the map, camera, and various data overlays like markers,
polylines, and polygons. This controller handles user input events (taps, drags), manages
the lifecycle of map features, and provides an interface for programmatic camera control.

## Signature

```kotlin
class ArcGISMapViewController(
    override val holder: ArcGISMapViewHolder,
    private val markerController: ArcGISMarkerController,
    private val polylineController: ArcGISPolylineOverlayController,
    private val polygonController: ArcGISPolygonOverlayController,
    private val circleController: ArcGISCircleOverlayController,
    private val groundImageController: ArcGISGroundImageController,
    private val rasterLayerController: ArcGISRasterLayerController,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : BaseMapViewController(), ArcGISMapViewControllerInterface
```

## Constructor

Initializes a new instance of the `ArcGISMapViewController`.


- `holder`
    - Type: `ArcGISMapViewHolder`
    - Description:
      The view holder that contains the ArcGIS `MapView` instance.
- `markerController`
    - Type: `ArcGISMarkerController`
    - Description:
      The controller responsible for managing marker overlays.
- `polylineController`
    - Type: `ArcGISPolylineOverlayController`
    - Description:
      The controller for managing polyline overlays.
- `polygonController`
    - Type: `ArcGISPolygonOverlayController`
    - Description:
      The controller for managing polygon overlays.
- `circleController`
    - Type: `ArcGISCircleOverlayController`
    - Description:
      The controller for managing circle overlays.
- `groundImageController`
    - Type: `ArcGISGroundImageController`
    - Description:
      The controller for managing ground image overlays.
- `rasterLayerController`
    - Type: `ArcGISRasterLayerController`
    - Description:
      The controller for managing raster layer overlays.
- `coroutine`
    - Type: `CoroutineScope`
    - Description:
      The coroutine scope used for managing asynchronous operations.
      Defaults to `CoroutineScope(Dispatchers.Default)`.

## Properties

These properties are inherited from `BaseMapViewController` and are used to set callbacks for various map events.


- `mapClickCallback`
    - Type: `((GeoPoint) -> Unit)?`
    - Description:
      A callback invoked when the user taps on the map at a location
      where no other overlay was tapped.
      The `GeoPoint` represents the coordinate of the tap.

- `mapLongClickCallback`
    - Type: `((GeoPoint) -> Unit)?`
    - Description:
      A callback invoked when the user long-presses on the map.
      The `GeoPoint` represents the coordinate of the long-press.

- `cameraMoveStartCallback`
    - Type: `((MapCameraPosition) -> Unit)?`
    - Description:
      A callback invoked when the map camera starts moving.

- `cameraMoveCallback`
    - Type: `((MapCameraPosition) -> Unit)?`
    - Description:
      A callback invoked continuously while the map camera is moving.

- `cameraMoveEndCallback`
    - Type: `((MapCameraPosition) -> Unit)?`
    - Description:
      A callback invoked when the map camera has finished moving.
      This is debounced to prevent rapid firing.

- `mapLoadedCallback`
    - Type: `((MapCameraPosition) -> Unit)?`
    - Description:
      A one-time callback invoked when the map has finished its initial load.
      It is set to `null` after being called.


## Methods

### Camera Control

#### moveCamera

Instantly moves the map camera to a specified position without animation.

**Signature**
```kotlin
fun moveCamera(position: MapCameraPosition)
```

**Parameters**

- `position`
    - Type: `MapCameraPosition`
    - Description:
      The target camera position, including location, zoom, bearing, and tilt.

---

#### animateCamera

Animates the map camera from its current position to a specified position over a given duration.

**Signature**
```kotlin
suspend fun animateCamera(
    position: MapCameraPosition,
    duration: Long
)
```

**Parameters**

- `position`
    - Type: `MapCameraPosition`
    - Description:
      The target camera position to animate to.

- `duration`
    - Type: `Long`
    - Description:
      The duration of the animation in milliseconds.

---

### Overlay Management

#### clearOverlays

Removes all overlays (markers, polylines, polygons, circles, ground images, and raster layers) from the map.

**Signature**
```kotlin
suspend fun clearOverlays()
```

---

#### compositionMarkers

Adds a list of new markers to the map.

**Signature**
```kotlin
suspend fun compositionMarkers(data: List<MarkerState>)
```

**Parameters**

- `data`
    - Type: `List<MarkerState>`
    - Description:
      A list of `MarkerState` objects to be added to the map.

---

#### updateMarker

Updates the state of an existing marker on the map.

**Signature**
```kotlin
suspend fun updateMarker(state: MarkerState)
```

**Parameters**

- `state`
    - Type: `MarkerState`
    - Description:
      The new state for the marker to be updated.

---

#### compositionPolylines

Adds a list of new polylines to the map.

**Signature**
```kotlin
suspend fun compositionPolylines(data: List<PolylineState>)
```

**Parameters**

- `data`
    - Type: `List<PolylineState>`
    - Description:
      A list of `PolylineState` objects to be added.

---

#### updatePolyline

Updates the state of an existing polyline on the map.

**Signature**
```kotlin
suspend fun updatePolyline(state: PolylineState)
```

**Parameters**

- `state`
    - Type: `PolylineState`
    - Description:
      The new state for the polyline to be updated.

---

#### compositionPolygons

Adds a list of new polygons to the map.

**Signature**
```kotlin
suspend fun compositionPolygons(data: List<PolygonState>)
```

**Parameters**

- `data`
    - Type: `List<PolygonState>`
    - Description:
      A list of `PolygonState` objects to be added.

---

#### updatePolygon

Updates the state of an existing polygon on the map.

**Signature**
```kotlin
suspend fun updatePolygon(state: PolygonState)
```

**Parameters**

- `state`
    - Type: `PolylineState`
    - Description:
      The new state for the polygon to be updated.

---

#### compositionCircles

Adds a list of new circles to the map.

**Signature**
```kotlin
suspend fun compositionCircles(data: List<CircleState>)
```

**Parameters**

- `data`
    - Type: `List<CircleState>`
    - Description:
      A list of `CircleState` objects to be added.

---

#### updateCircle

Updates the state of an existing circle on the map.

**Signature**
```kotlin
suspend fun updateCircle(state: CircleState)
```

**Parameters**

- `state`
    - Type: `CircleState`
    - Description:
      The new state for the circle to be updated.

---

#### compositionGroundImages

Adds a list of new ground images to the map.

**Signature**
```kotlin
suspend fun compositionGroundImages(data: List<GroundImageState>)
```

**Parameters**

- `data`
    - Type: `List<GroundImageState>`
    - Description:
      A list of `GroundImageState` objects to be added.

---

#### updateGroundImage

Updates the state of an existing ground image on the map.

**Signature**
```kotlin
suspend fun updateGroundImage(state: GroundImageState)
```

**Parameters**

- `state`
    - Type: `GroundImageState`
    - Description:
      The new state for the ground image to be updated.

---

#### compositionRasterLayers

Adds a list of new raster layers to the map.

**Signature**
```kotlin
suspend fun compositionRasterLayers(data: List<RasterLayerState>)
```

**Parameters**
- `data`
    - Type: `List<RasterLayerState>`
    - Description:
      A list of `RasterLayerState` objects to be added.

---

#### updateRasterLayer

Updates the state of an existing raster layer on the map.

**Signature**
```kotlin
suspend fun updateRasterLayer(state: RasterLayerState)
```

**Parameters**
- `state`
    - Type: `RasterLayerState`
    - Description:
      The new state for the raster layer to be updated.

---

### Overlay Checks

These methods check for the existence of a specific overlay entity on the map.

- `hasMarker(state: MarkerState)`
    - Description:
      Returns `true` if a marker with the given state's ID exists.
- `hasPolyline(state: PolylineState)`
    - Description:
      Returns `true` if a polyline with the given state's ID exists.
- `hasPolygon(state: PolygonState)`
    - Description:
      Returns `true` if a polygon with the given state's ID exists.
- `hasCircle(state: CircleState)`
    - Description:
      Returns `true` if a circle with the given state's ID exists.
- `hasGroundImage(state: GroundImageState)`
    - Description:
      Returns `true` if a ground image with the given state's ID exists.
- `hasRasterLayer(state: RasterLayerState)`
    - Description:
      Returns `true` if a raster layer with the given state's ID exists.

---

### Map Configuration

#### setMapDesignType

Sets the visual style (basemap) of the map.

**Signature**
```kotlin
fun setMapDesignType(value: ArcGISDesignTypeInterface)
```

**Parameters**
- `value`
    - Type: `ArcGISDesignTypeInterface`
    - Description:
      The desired map design type (e.g., `ArcGISDesign.Streets`).

---

#### setMapDesignTypeChangeListener

Registers a listener that is invoked when the map's design type changes.
The listener is also immediately called with the current design type upon registration.

**Signature**
```kotlin
fun setMapDesignTypeChangeListener(listener: ArcGISDesignTypeChangeHandler)
```

**Parameters**
- `listener`
    - Type: `ArcGISDesignTypeChangeHandler`
    - Description:
      The callback to be invoked with the map design type.

---

### Initialization

#### sendInitialCameraUpdate

Manually triggers a camera position update. This is useful for notifying listeners of
the initial camera state after the map view is ready.

**Signature**
```kotlin
fun sendInitialCameraUpdate()
```
