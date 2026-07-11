package com.mapconductor.arcgis

import androidx.compose.ui.geometry.Offset
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.Basemap
import com.arcgismaps.mapping.Viewpoint
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.arcgismaps.mapping.view.LongPressEvent
import com.arcgismaps.mapping.view.PanChangeEvent
import com.arcgismaps.mapping.view.SingleTapConfirmedEvent
import com.arcgismaps.mapping.view.UpEvent
import com.arcgismaps.mapping.view.extensions.motionEvent
import com.mapconductor.arcgis.circle.ArcGISCircleOverlayController
import com.mapconductor.arcgis.groundimage.ArcGISGroundImageController
import com.mapconductor.arcgis.marker.ArcGISMarkerController
import com.mapconductor.arcgis.marker.ArcGISMarkerEventControllerInterface
import com.mapconductor.arcgis.marker.ArcGISMarkerRenderer
import com.mapconductor.arcgis.marker.DefaultArcGISMarkerEventController
import com.mapconductor.arcgis.marker.StrategyArcGISMarkerEventController
import com.mapconductor.arcgis.polygon.ArcGISPolygonOverlayController
import com.mapconductor.arcgis.polyline.ArcGISPolylineOverlayController
import com.mapconductor.arcgis.raster.ArcGISRasterLayerController
import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageEvent
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapPaddings
import com.mapconductor.core.map.VisibleRegion
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonEvent
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineEvent
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.settings.Settings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ArcGISMapView2DController(
    override val holder: ArcGISMapView2DHolder,
    private val markerController: ArcGISMarkerController,
    private val polylineController: ArcGISPolylineOverlayController,
    private val polygonController: ArcGISPolygonOverlayController,
    private val circleController: ArcGISCircleOverlayController,
    private val groundImageController: ArcGISGroundImageController,
    private val rasterLayerController: ArcGISRasterLayerController,
    override val defaultCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
    override val mainCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : BaseMapViewController(),
    ArcGISMapViewControllerInterface {
    private val markerEventControllers = mutableListOf<ArcGISMarkerEventControllerInterface>()
    private var activeDragController: ArcGISMarkerEventControllerInterface? = null
    private var markerClickListener: OnMarkerEventHandler? = null
    private var markerDragStartListener: OnMarkerEventHandler? = null
    private var markerDragListener: OnMarkerEventHandler? = null
    private var markerDragEndListener: OnMarkerEventHandler? = null
    private var markerAnimateStartListener: OnMarkerEventHandler? = null
    private var markerAnimateEndListener: OnMarkerEventHandler? = null
    private var cameraMoveEndJob: Job? = null
    private val cameraMoveEndDebounceMs = 180L
    private var mapDesignType: ArcGISDesignTypeInterface = ArcGISDesign.Streets
    private var mapDesignTypeChangeListener: ArcGISDesignTypeChangeHandler? = null

    init {
        holder.map.graphicsOverlays.clear()
        holder.map.graphicsOverlays.add(circleController.renderer.circleLayer)
        holder.map.graphicsOverlays.add(polygonController.renderer.polygonLayer)
        holder.map.graphicsOverlays.add(polylineController.renderer.polylineLayer)
        holder.map.graphicsOverlays.add((markerController.renderer as ArcGISMarkerRenderer).markerLayer)
        setupListeners()
        registerOverlayController(markerController)
        registerOverlayController(polygonController)
        registerOverlayController(polylineController)
        registerOverlayController(circleController)
        registerOverlayController(groundImageController)
        registerOverlayController(rasterLayerController)
        registerMarkerEventController(DefaultArcGISMarkerEventController(markerController))

        markerController.setRasterLayerCallback { state ->
            if (state != null) {
                rasterLayerController.upsert(state)
            } else {
                rasterLayerController.rasterLayerManager
                    .allEntities()
                    .filter { it.state.id.startsWith("marker-tile-") }
                    .forEach { entity -> rasterLayerController.removeById(entity.state.id) }
            }
        }
    }

    fun setupListeners() {
        defaultCoroutine.launch { holder.map.onSingleTapConfirmed.collect { onMapTap(it) } }
        defaultCoroutine.launch { holder.map.viewpointChanged.collect { onViewpointChange() } }
        defaultCoroutine.launch { holder.map.onInteractiveZooming.collect { invokeCameraMoveCallback() } }
        defaultCoroutine.launch { holder.map.onRotate.collect { invokeCameraMoveCallback() } }
        defaultCoroutine.launch { holder.map.onLongPress.collect { onMapLongPress(it) } }
        defaultCoroutine.launch { holder.map.onUp.collect { onMapUp(it) } }
        defaultCoroutine.launch { holder.map.onPan.collect { onMapPan(it) } }
    }

    override fun hasMarker(state: MarkerState): Boolean = markerController.markerManager.hasEntity(state.id)

    override fun hasPolyline(state: PolylineState): Boolean = polylineController.polylineManager.hasEntity(state.id)

    override fun hasPolygon(state: PolygonState): Boolean = polygonController.polygonManager.hasEntity(state.id)

    override fun hasCircle(state: CircleState): Boolean = circleController.circleManager.hasEntity(state.id)

    override fun hasGroundImage(state: GroundImageState): Boolean =
        groundImageController.groundImageManager.hasEntity(state.id)

    override fun hasRasterLayer(state: RasterLayerState): Boolean =
        rasterLayerController.rasterLayerManager.hasEntity(state.id)

    private suspend fun onViewpointChange() {
        mapInitializedCallback?.invoke()
        mapInitializedCallback = null

        getMapCameraPosition()?.let { mapCameraPosition ->
            notifyMapCameraPosition(mapCameraPosition)
            scheduleCameraMoveEndCallback()
        }
    }

    private suspend fun invokeCameraMoveStartCallback() {
        cameraMoveStartCallback?.let { cb ->
            getMapCameraPosition()?.let(cb)
        }
    }

    private suspend fun invokeCameraMoveCallback() {
        cameraMoveCallback?.let { cb ->
            getMapCameraPosition()?.let(cb)
        }
        scheduleCameraMoveEndCallback()
    }

    private suspend fun invokeCameraMoveEndCallback() {
        cameraMoveEndCallback?.let { cb ->
            getMapCameraPosition()?.let(cb)
        }
    }

    private fun scheduleCameraMoveEndCallback() {
        if (cameraMoveEndCallback == null) return
        cameraMoveEndJob?.cancel()
        cameraMoveEndJob =
            defaultCoroutine.launch {
                delay(cameraMoveEndDebounceMs.milliseconds)
                invokeCameraMoveEndCallback()
            }
    }

    private suspend fun getMapCameraPosition(): MapCameraPosition? {
        val mapWidth = holder.map.width.toFloat() - 1.0f
        val mapHeight = holder.map.height.toFloat() - 1.0f
        val nearLeft = holder.fromScreenOffset(Offset(1.0f, mapHeight)) ?: return null
        val nearRight = holder.fromScreenOffsetSync(Offset(mapWidth, mapHeight)) ?: return null
        val farLeft = holder.fromScreenOffsetSync(Offset(1.0f, 1.0f)) ?: return null
        val farRight = holder.fromScreenOffsetSync(Offset(mapWidth, 1.0f)) ?: return null
        val center = holder.fromScreenOffsetSync(Offset(mapWidth / 2.0f, mapHeight / 2.0f)) ?: return null

        val bounds = GeoRectBounds()
        bounds.extend(nearLeft)
        bounds.extend(nearRight)
        bounds.extend(farLeft)
        bounds.extend(farRight)

        val visibleRegion =
            VisibleRegion(
                bounds = bounds,
                nearLeft = nearLeft,
                nearRight = nearRight,
                farLeft = farLeft,
                farRight = farRight,
            )

        return MapCameraPosition(
            position = center,
            zoom = scaleToZoom(holder.map.mapScale.value, center.latitude),
            bearing = ((holder.map.mapRotation.value % 360) + 360) % 360,
            tilt = 0.0,
            paddings = MapPaddings.Zeros,
            visibleRegion = visibleRegion,
        )
    }

    private suspend fun onMapPan(event: PanChangeEvent) {
        val controller = activeDragController
        if (controller != null) {
            val point = holder.map.screenToLocation(event.screenCoordinate) ?: return
            val position = point.toGeoPoint()
            controller.updateDrag(point, position)
            controller.getSelectedState()?.let { state -> controller.dispatchDrag(state) }
        }
        invokeCameraMoveCallback()
    }

    private fun onMapUp(event: UpEvent) {
        val controller = activeDragController
        if (controller != null) {
            val point = holder.map.screenToLocation(event.screenCoordinate) ?: return
            val position = point.toGeoPoint()
            val selectedState = controller.getSelectedState()
            controller.endDrag(point, position)
            selectedState?.let { state -> controller.dispatchDragEnd(state) }
            activeDragController = null
            holder.setNavigationEnabled(true)
        }
    }

    private suspend fun onMapLongPress(event: LongPressEvent) {
        if (event.motionEvent.action != MotionEvent.ACTION_MOVE) return

        val screenPoint = event.screenCoordinate
        val point = holder.map.screenToLocation(screenPoint) ?: return
        val position = point.toGeoPoint()
        val identifyResult =
            holder.map.identifyGraphicsOverlay(
                graphicsOverlay = (markerController.renderer as ArcGISMarkerRenderer).markerLayer,
                screenCoordinate = screenPoint,
                tolerance =
                    Settings.Default.tapTolerance.value
                        .toDouble(),
                returnPopupsOnly = false,
            )
        val graphics = identifyResult.getOrNull()?.graphics
        graphics?.firstOrNull()?.let { graphic ->
            (graphic.attributes["id"] as? String)?.let { markerId ->
                markerController.markerManager.getEntity(markerId)?.let { entity ->
                    if (entity.state.draggable) {
                        activeDragController = markerEventControllers.firstOrNull()
                        activeDragController?.startDrag(entity)
                        holder.setNavigationEnabled(false)
                        activeDragController?.dispatchDragStart(entity.state)
                        return
                    }
                }
            }
        }
        markerEventControllers
            .drop(1)
            .forEach { controller ->
                controller.find(position)?.let { entity ->
                    if (entity.state.draggable) {
                        activeDragController = controller
                        controller.startDrag(entity)
                        holder.setNavigationEnabled(false)
                        controller.dispatchDragStart(entity.state)
                        return
                    }
                }
            }
        mapLongClickCallback?.invoke(position)
    }

    private fun onMapTap(event: SingleTapConfirmedEvent) {
        val screenPoint = event.screenCoordinate
        val touchPosition = holder.map.screenToLocation(screenPoint)?.toGeoPoint() ?: return

        markerEventControllers.forEach { controller ->
            controller.find(touchPosition)?.let { markerEntity ->
                controller.dispatchClick(markerEntity.state)
                return
            }
        }

        circleController.find(touchPosition)?.let { circleEntity ->
            circleController.dispatchClick(CircleEvent(state = circleEntity.state, clicked = touchPosition))
            return
        }

        groundImageController.find(touchPosition)?.let { entity ->
            groundImageController.dispatchClick(GroundImageEvent(state = entity.state, clicked = touchPosition))
            return
        }

        polylineController.findWithClosestPoint(touchPosition)?.let { hitResult ->
            polylineController.dispatchClick(
                PolylineEvent(
                    state = hitResult.entity.state,
                    clicked = hitResult.closestPoint,
                ),
            )
            return
        }

        polygonController.find(touchPosition)?.let { polygonEntity ->
            polygonController.dispatchClick(PolygonEvent(state = polygonEntity.state, clicked = touchPosition))
            return
        }

        mapClickCallback?.invoke(touchPosition)
    }

    override suspend fun clearOverlays() {
        markerController.clear()
        groundImageController.clear()
        polylineController.clear()
        polygonController.clear()
        circleController.clear()
        rasterLayerController.clear()
    }

    override suspend fun compositionMarkers(data: List<MarkerState>) = markerController.add(data)

    override suspend fun updateMarker(state: MarkerState) = markerController.update(state)

    override suspend fun compositionPolylines(data: List<PolylineState>) = polylineController.add(data)

    override suspend fun updatePolyline(state: PolylineState) = polylineController.update(state)

    override suspend fun compositionPolygons(data: List<PolygonState>) = polygonController.add(data)

    override suspend fun updatePolygon(state: PolygonState) = polygonController.update(state)

    override suspend fun compositionCircles(data: List<CircleState>) = circleController.add(data)

    override suspend fun updateCircle(state: CircleState) = circleController.update(state)

    override suspend fun compositionGroundImages(data: List<GroundImageState>) = groundImageController.add(data)

    override suspend fun updateGroundImage(state: GroundImageState) = groundImageController.update(state)

    override suspend fun compositionRasterLayers(data: List<RasterLayerState>) = rasterLayerController.add(data)

    override suspend fun updateRasterLayer(state: RasterLayerState) = rasterLayerController.update(state)

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        circleController.clickListener = listener
    }

    @Deprecated("Use GroundImageState.onClick instead.")
    override fun setOnGroundImageClickListener(listener: OnGroundImageEventHandler?) {
        groundImageController.clickListener = listener
    }

    override fun moveCamera(position: MapCameraPosition) {
        val viewpoint = toViewpoint(position)
        mainCoroutine.launch {
            if (!holder.mapView.isAttachedToWindow) return@launch
            holder.map.setViewpoint(viewpoint)
        }
    }

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) {
        val viewpoint = toViewpoint(position)
        defaultCoroutine.launch {
            invokeCameraMoveStartCallback()
            mainCoroutine.launch {
                if (!holder.mapView.isAttachedToWindow) return@launch
                holder.map.setViewpointAnimated(
                    viewpoint = viewpoint,
                    durationSeconds = duration.toFloat() / 1000.0f,
                )
            }
            scheduleCameraMoveEndCallback()
        }
    }

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) {
        val envelope = bounds.toEnvelope() ?: return

        mainCoroutine.launch {
            if (holder.mapView.isAttachedToWindow) {
                holder.map.setViewpoint(Viewpoint(envelope))
            }
        }
    }

    override fun getControllers(): List<OverlayControllerInterface<*, *, *>> = listOf(
        markerController,
        polylineController,
        polygonController,
        circleController,
        groundImageController,
        rasterLayerController,
    )

    private fun toViewpoint(position: MapCameraPosition): Viewpoint {
        val point = GeoPoint.from(position.position).toPoint(SpatialReference.wgs84())
        return Viewpoint(
            center = point,
            scale = zoomToScale(position.zoom, position.position.latitude),
            rotation = position.bearing,
        )
    }

    @Deprecated("Use MarkerState.onDragStart instead.")
    override fun setOnMarkerDragStart(listener: OnMarkerEventHandler?) {
        markerDragStartListener = listener
        markerEventControllers.forEach { it.setDragStartListener(listener) }
    }

    @Deprecated("Use MarkerState.onDrag instead.")
    override fun setOnMarkerDrag(listener: OnMarkerEventHandler?) {
        markerDragListener = listener
        markerEventControllers.forEach { it.setDragListener(listener) }
    }

    @Deprecated("Use MarkerState.onDragEnd instead.")
    override fun setOnMarkerDragEnd(listener: OnMarkerEventHandler?) {
        markerDragEndListener = listener
        markerEventControllers.forEach { it.setDragEndListener(listener) }
    }

    @Deprecated("Use MarkerState.onAnimateStart instead.")
    override fun setOnMarkerAnimateStart(listener: OnMarkerEventHandler?) {
        markerAnimateStartListener = listener
        markerEventControllers.forEach { it.setAnimateStartListener(listener) }
    }

    @Deprecated("Use MarkerState.onAnimateEnd instead.")
    override fun setOnMarkerAnimateEnd(listener: OnMarkerEventHandler?) {
        markerAnimateEndListener = listener
        markerEventControllers.forEach { it.setAnimateEndListener(listener) }
    }

    @Deprecated("Use MarkerState.onClick instead.")
    override fun setOnMarkerClickListener(listener: OnMarkerEventHandler?) {
        markerClickListener = listener
        markerEventControllers.forEach { it.setClickListener(listener) }
    }

    @Deprecated("Use PolylineState.onClick instead.")
    override fun setOnPolylineClickListener(listener: OnPolylineEventHandler?) {
        polylineController.clickListener = listener
    }

    @Deprecated("Use PolygonState.onClick instead.")
    override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) {
        polygonController.clickListener = listener
    }

    override fun setMapDesignType(value: ArcGISDesignTypeInterface) {
        val baseMap = Basemap(ArcGISDesign.toBasemapStyle(value))
        holder.map.map?.setBasemap(baseMap)
    }

    override fun setMapDesignTypeChangeListener(listener: ArcGISDesignTypeChangeHandler) {
        mapDesignTypeChangeListener = listener
        listener(mapDesignType)
    }

    fun sendInitialCameraUpdate() {
        defaultCoroutine.launch {
            if (holder.map.width <= 0 || holder.map.height <= 0) return@launch
            getMapCameraPosition()?.let { notifyMapCameraPosition(it) }
        }
    }

    internal fun registerMarkerEventController(controller: ArcGISMarkerEventControllerInterface) {
        if (markerEventControllers.contains(controller)) return
        markerEventControllers.add(controller)
        controller.setClickListener(markerClickListener)
        controller.setDragStartListener(markerDragStartListener)
        controller.setDragListener(markerDragListener)
        controller.setDragEndListener(markerDragEndListener)
        controller.setAnimateStartListener(markerAnimateStartListener)
        controller.setAnimateEndListener(markerAnimateEndListener)
    }

    fun createMarkerRenderer(): MarkerOverlayRendererInterface<ArcGISActualMarker> {
        val markerLayer = GraphicsOverlay()
        registerMarkerOverlayLayer(markerLayer)
        return ArcGISMarkerRenderer(
            markerLayer = markerLayer,
            holder = holder,
        )
    }

    fun createMarkerEventController(
        controller: StrategyMarkerController<ArcGISActualMarker>,
    ): MarkerEventControllerInterface<ArcGISActualMarker> = StrategyArcGISMarkerEventController(controller)

    fun registerMarkerEventController(controller: MarkerEventControllerInterface<ArcGISActualMarker>) {
        val typed = controller as? ArcGISMarkerEventControllerInterface ?: return
        registerMarkerEventController(typed)
    }

    internal fun registerMarkerOverlayLayer(layer: GraphicsOverlay) {
        if (holder.map.graphicsOverlays.contains(layer)) return
        holder.map.graphicsOverlays.add(layer)
    }

    private fun zoomToScale(
        zoom: Double,
        latitude: Double,
    ): Double {
        val resolution = WEB_MERCATOR_CIRCUMFERENCE_METERS * cos(Math.toRadians(latitude)) / (TILE_SIZE * 2.0.pow(zoom))
        return resolution * DPI * INCHES_PER_METER
    }

    private fun scaleToZoom(
        scale: Double,
        latitude: Double,
    ): Double {
        val resolution = scale / (DPI * INCHES_PER_METER)
        val numerator = WEB_MERCATOR_CIRCUMFERENCE_METERS * cos(Math.toRadians(latitude))
        return ln(numerator / (TILE_SIZE * resolution)) / ln(2.0)
    }

    companion object {
        private const val WEB_MERCATOR_CIRCUMFERENCE_METERS = 2.0 * PI * 6378137.0
        private const val TILE_SIZE = 256.0
        private const val DPI = 96.0
        private const val INCHES_PER_METER = 39.37
    }
}
