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
import com.mapconductor.core.map.CameraRestriction
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
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.settings.Settings
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
        mainCoroutine.launch { holder.map.onSingleTapConfirmed.collect { onMapTap(it) } }
        defaultCoroutine.launch { holder.map.viewpointChanged.collect { onViewpointChange() } }
        defaultCoroutine.launch { holder.map.onInteractiveZooming.collect { invokeCameraMoveCallback() } }
        defaultCoroutine.launch { holder.map.onRotate.collect { invokeCameraMoveCallback() } }
        mainCoroutine.launch { holder.map.onLongPress.collect { onMapLongPress(it) } }
        mainCoroutine.launch { holder.map.onUp.collect { onMapUp(it) } }
        mainCoroutine.launch { holder.map.onPan.collect { onMapPan(it) } }
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
        notifyMapInitialized()

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

    /**
     * カメラの可動範囲を制限する。
     *
     * ズームは ArcGIS のネイティブなスケール制限（[com.arcgismaps.mapping.ArcGISMap.minScale] /
     * [com.arcgismaps.mapping.ArcGISMap.maxScale]）へ変換して適用する。ArcGIS の縮尺は分母（1:N）で
     * 表され、`minScale` が「最も引いた側」＝ N が大きい方、`maxScale` が「最も寄せた側」＝ N が
     * 小さい方に対応するため、統一ズームとは大小が逆になる（`minZoom → minScale` /
     * `maxZoom → maxScale`）。
     *
     * パン範囲（矩形）は ArcGIS に中心基準の制限 API が無いため、これまでどおりカメラ停止時の
     * クランプで制限する（[invokeCameraMoveEndCallback]）。
     *
     * 縮尺は投影座標系上の公称縮尺で緯度に依存しないため（[zoomToScale]）、`minScale` /
     * `maxScale` のような緯度に依らない定数へそのまま変換できる。
     */
    override fun setCameraRestriction(restriction: CameraRestriction?) {
        super<BaseMapViewController>.setCameraRestriction(restriction)
        // ビュー破棄後はネイティブアクセサが例外を投げるため runCatching で保護する
        // （このホルダの他アクセサと同じ扱い）。
        runCatching {
            holder.map.map?.let { arcGISMap ->
                arcGISMap.minScale = restriction?.minZoom?.let { zoomToScale(it) }
                arcGISMap.maxScale = restriction?.maxZoom?.let { zoomToScale(it) }
            }
        }
    }

    private suspend fun invokeCameraMoveEndCallback() {
        val mapCameraPosition = getMapCameraPosition() ?: return
        // 範囲制限に違反していれば矩形内へ引き戻す（ArcGIS はカメラ中心基準の範囲制限 API を
        // 持たないため）。再適用すると viewpointChanged が再発火し、そこでは補正不要になり
        // 通常フローへ進む。3D 側（ArcGISMapViewController）と同一仕様。
        cameraRestrictionCorrection(mapCameraPosition)?.let { corrected ->
            moveCamera(corrected)
            return
        }
        cameraMoveEndCallback?.invoke(mapCameraPosition)
    }

    private fun scheduleCameraMoveEndCallback() {
        if (cameraMoveEndCallback == null && !hasCameraRestriction()) return
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
            zoom = scaleToZoom(holder.map.mapScale.value),
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
            return
        }
        invokeCameraMoveCallback()
    }

    private fun onMapUp(event: UpEvent) {
        val controller = activeDragController
        if (controller != null) {
            val point = event.mapPoint ?: holder.map.screenToLocation(event.screenCoordinate) ?: return
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
        val point = event.mapPoint ?: holder.map.screenToLocation(screenPoint) ?: return
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
                // padding は setViewpointGeometry の第2引数（デバイス非依存ピクセル）へ渡す。
                holder.map.setViewpointGeometry(envelope, padding.toDouble())
            }
        }
    }

    override fun getControllers(): Map<String, OverlayControllerInterface<*, *>> = mapOf(
        "marker" to markerController,
        "polyline" to polylineController,
        "polygon" to polygonController,
        "circle" to circleController,
        "ground_image" to groundImageController,
        "raster_layer" to rasterLayerController,
    )

    private fun toViewpoint(position: MapCameraPosition): Viewpoint {
        val point = GeoPoint.from(position.position).toPoint(SpatialReference.wgs84())
        return Viewpoint(
            center = point,
            scale = zoomToScale(position.zoom),
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

    /**
     * 「マップの準備ができた」ことをコンポーズ側へ通知する。
     *
     * これまで ArcGIS は基底の [notifyMapInitialized]（sticky。リスナー未登録でも記録して後で
     * 配送する）を使わず、[onViewpointChange] で `mapInitializedCallback` を直接呼んでいた。
     * `MapViewBase` は `InitState.MapLoaded` になるまで `CollectAndRenderOverlays` を
     * コンポーズしないため、この 1 回きりのイベントを取り逃すとマーカー・ポリゴン・InfoBubble が
     * 一切描画されない。`viewpointChanged` は replay を持たないホットフローで、購読は
     * `setupListeners()` がコルーチンで非同期に始めるので、初期ビューポート確定がその購読より
     * 早いと通知が失われる。2D は初期ビューポートが即座に確定して以後動かないため、
     * 起動のたびにマーカーが出たり出なかったりする形で表面化した。
     *
     * そこでレイアウト確定後に呼び出し側から明示的に通知する。基底の実装が sticky かつ
     * 一度しか配送しないので、[onViewpointChange] 側と重なっても二重通知にはならない。
     * Compose の状態を書き換えるためメインディスパッチャで呼ぶ。
     */
    fun markMapInitialized() {
        mainCoroutine.launch { notifyMapInitialized() }
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

    /**
     * 統一ズーム（Google 準拠）→ ArcGIS の縮尺分母。
     *
     * ArcGIS が扱う縮尺は Web メルカトルの **投影座標系上** の縮尺（公称縮尺）で、緯度による
     * 補正は含まない。Google のズームも同じく投影座標系（256px タイルで世界一周）で定義されて
     * いるため、両者は緯度に依存しない 1 対 1 の対応になる。
     *
     * 以前はここに `cos(latitude)` を掛けて「地表の実距離」に直していたため、高緯度ほど縮尺が
     * 小さく（＝寄りすぎに）なっていた。緯度 65 度で Google Maps 比 2.4 倍ほど拡大されており、
     * 同じ `MapCameraPosition` を渡しても表示範囲が一致しなかった。
     */
    private fun zoomToScale(zoom: Double): Double {
        val resolution = WEB_MERCATOR_CIRCUMFERENCE_METERS / (TILE_SIZE * 2.0.pow(zoom))
        return resolution * PIXELS_PER_METER_AT_96_DPI
    }

    /** [zoomToScale] の逆変換。 */
    private fun scaleToZoom(scale: Double): Double {
        val resolution = scale / PIXELS_PER_METER_AT_96_DPI
        return ln(WEB_MERCATOR_CIRCUMFERENCE_METERS / (TILE_SIZE * resolution)) / ln(2.0)
    }

    companion object {
        private const val WEB_MERCATOR_CIRCUMFERENCE_METERS = Earth.CIRCUMFERENCE_METERS
        private const val TILE_SIZE = 256.0

        /** 96 DPI（ESRI が縮尺計算に用いる標準値）における 1 メートルあたりのピクセル数。 */
        private const val PIXELS_PER_METER_AT_96_DPI = 96.0 / 0.0254
    }
}
