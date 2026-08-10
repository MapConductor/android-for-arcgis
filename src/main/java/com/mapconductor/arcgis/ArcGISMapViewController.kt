package com.mapconductor.arcgis

import com.arcgismaps.mapping.Basemap
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.mapconductor.arcgis.circle.ArcGISCircleOverlayController
import com.mapconductor.arcgis.groundimage.ArcGISGroundImageController
import com.mapconductor.arcgis.marker.ArcGISMarkerController
import com.mapconductor.arcgis.marker.ArcGISMarkerEventController
import com.mapconductor.arcgis.marker.ArcGISMarkerRenderer
import com.mapconductor.arcgis.polygon.ArcGISPolygonOverlayController
import com.mapconductor.arcgis.polyline.ArcGISPolylineOverlayController
import com.mapconductor.arcgis.raster.ArcGISRasterLayerController
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapGesture
import com.mapconductor.core.map.MapUISettings
import com.mapconductor.core.map.MapUISettingsDiagnostics
import com.mapconductor.core.marker.MarkerAnimationOverlayHost
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.marker.dispatchGeoMarkerClick
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ArcGISMapViewController(
    override val holder: ArcGISMapViewHolder,
    internal val markerController: ArcGISMarkerController,
    internal val polylineController: ArcGISPolylineOverlayController,
    internal val polygonController: ArcGISPolygonOverlayController,
    internal val circleController: ArcGISCircleOverlayController,
    internal val groundImageController: ArcGISGroundImageController,
    internal val rasterLayerController: ArcGISRasterLayerController,
    override val defaultCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
    override val mainCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : BaseMapViewController(),
    ArcGISMapViewControllerInterface {
    internal val markerEventControllers = mutableListOf<ArcGISMarkerEventController>()
    internal var activeDragController: ArcGISMarkerEventController? = null
    internal var markerClickListener: OnMarkerEventHandler? = null
    internal var markerDragStartListener: OnMarkerEventHandler? = null
    internal var markerDragListener: OnMarkerEventHandler? = null
    internal var markerDragEndListener: OnMarkerEventHandler? = null
    internal var markerAnimateStartListener: OnMarkerEventHandler? = null
    internal var markerAnimateEndListener: OnMarkerEventHandler? = null

    // ArcGIS updates the viewpoint asynchronously; firing "move end" immediately after setViewpointCamera()
    // can read a stale camera and cause feedback loops in camera sync scenarios.
    internal var cameraMoveEndJob: Job? = null
    internal val cameraMoveEndDebounceMs = 180L

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
        registerMarkerEventController(ArcGISMarkerEventController(markerController))

        markerController.setRasterLayerCallback { state ->
            if (state != null) {
                rasterLayerController.upsert(state)
            } else {
                val markerTileLayers =
                    rasterLayerController.rasterLayerManager
                        .allEntities()
                        .filter { it.state.id.startsWith("marker-tile-") }
                markerTileLayers.forEach { entity -> rasterLayerController.removeById(entity.state.id) }
            }
        }
    }

    override fun moveCamera(position: MapCameraPosition) = handleMoveCamera(position)

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) = handleAnimateCamera(position, duration)

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) = handleFitBounds(bounds, padding)

    /**
     * マーカーのヒットテスト。クリックカスケードの先頭。
     *
     * ArcGIS は地図タップの座標からそのまま引けるので、コアの
     * [dispatchGeoMarkerClick] に委ねる（`clickable = false` の透過もそちら）。
     * 長押し（ドラッグ開始）だけは identifyGraphicsOverlay を使うので Gestures 側に残る。
     */
    override fun dispatchMarkerTap(position: GeoPointInterface): Boolean =
        markerEventControllers.dispatchGeoMarkerClick(position)

    // 拡張ファイル（Camera / Gestures）からは基底クラスの protected へ触れないため、
    // ここで internal の入口を用意しておく。
    internal fun cameraMoveStartHandler(): ((MapCameraPosition) -> Unit)? = cameraMoveStartCallback

    internal fun cameraMoveHandler(): ((MapCameraPosition) -> Unit)? = cameraMoveCallback

    internal fun needsCameraMoveEndWork(): Boolean = cameraMoveEndCallback != null || hasCameraRestriction()

    internal fun emitCameraMoveEnd(position: MapCameraPosition) {
        cameraMoveEndCallback?.invoke(position)
    }

    internal fun emitMapInitialized() {
        notifyMapInitialized()
    }

    internal suspend fun emitCameraPosition(position: MapCameraPosition) {
        notifyMapCameraPosition(position)
    }

    internal fun correctForCameraRestriction(current: MapCameraPosition): MapCameraPosition? =
        cameraRestrictionCorrection(current)

    fun setupListeners() {
        mainCoroutine.launch {
            holder.map.onSingleTapConfirmed.collect { onMapTap(it) }
        }
        defaultCoroutine.launch {
            holder.map.viewpointChanged.collect { onViewpointChange() }
        }
        defaultCoroutine.launch {
            holder.map.onInteractiveZooming.collect { invokeCameraMoveCallback() }
        }
        defaultCoroutine.launch {
            holder.map.onRotate.collect { invokeCameraMoveCallback() }
        }
        mainCoroutine.launch {
            holder.map.onLongPress.collect { onMapLongPress(it) }
        }
        mainCoroutine.launch {
            holder.map.onUp.collect { onMapUp(it) }
        }
        mainCoroutine.launch {
            holder.map.onPan.collect { onMapPan(it) }
        }
    }

    override fun hasPolyline(state: PolylineState): Boolean =
        this.polylineController.polylineManager
            .hasEntity(state.id)

    override fun hasGroundImage(state: GroundImageState): Boolean =
        this.groundImageController.groundImageManager
            .hasEntity(state.id)

    internal var appliedUISettings: MapUISettings = MapUISettings.Default

    override fun applyUISettings(settings: MapUISettings) {
        appliedUISettings = settings
        MapUISettingsDiagnostics.warnIfRequested(
            settings.tiltGesture,
            gesture = MapGesture.Tilt,
            provider = "ArcGIS",
            reason = "InteractionOptions has no separate tilt toggle",
        )
        with(holder.map) {
            interactionOptions.isPanEnabled = settings.scrollGesture
            interactionOptions.isRotateEnabled = settings.rotateGesture
            interactionOptions.isZoomEnabled = settings.zoomGesture
        }
    }

    override suspend fun clearOverlays() {
        markerController.clear()
        groundImageController.clear()
        polylineController.clear()
        polygonController.clear()
        circleController.clear()
        rasterLayerController.clear()
    }

    override fun setMarkerAnimationOverlayHost(host: MarkerAnimationOverlayHost?) {
        (markerController.renderer as ArcGISMarkerRenderer).animationOverlayHost = host
    }

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        this.circleController.clickListener = listener
    }

    @Deprecated("Use GroundImageState.onClick instead.")
    override fun setOnGroundImageClickListener(listener: OnGroundImageEventHandler?) {
        this.groundImageController.clickListener = listener
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
        this.polylineController.clickListener = listener
    }

    @Deprecated("Use PolygonState.onClick instead.")
    override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) {
        this.polygonController.clickListener = listener
    }

    internal var mapDesignType: ArcGISDesignTypeInterface = ArcGISDesign.Streets
    internal var mapDesignTypeChangeListener: ArcGISDesignTypeChangeHandler? = null

    override fun setMapDesignType(value: ArcGISDesignTypeInterface) {
        holder.map.scene?.let { scene ->
            val baseMapStyle = ArcGISDesign.toBasemapStyle(value)
            val baseMap = Basemap(baseMapStyle)
            defaultCoroutine.launch {
                scene.setBasemap(baseMap)
                // Basemap changes can reset the viewpoint; mark the current request as pending so that
                // the next viewpointChanged can restore the last requested camera if needed.
//                pendingCameraRestoreRequest = cameraRequestGeneration.get()
            }
        }
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

    // Trigger an initial camera update after the view and scene are ready
    fun sendInitialCameraUpdate() {
        defaultCoroutine.launch {
            val mapWidth = holder.map.width
            val mapHeight = holder.map.height
            if (mapWidth <= 0 || mapHeight <= 0) return@launch
            getMapCameraPosition()?.let { mapCameraPosition ->
                notifyMapCameraPosition(mapCameraPosition)
            }
        }
    }

    internal fun registerMarkerEventController(controller: ArcGISMarkerEventController) {
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
        val markerLayer =
            GraphicsOverlay()
        registerMarkerOverlayLayer(markerLayer)
        return ArcGISMarkerRenderer(
            markerLayer = markerLayer,
            holder = holder,
        )
    }

    fun createMarkerEventController(
        controller: StrategyMarkerController<ArcGISActualMarker>,
    ): MarkerEventControllerInterface<ArcGISActualMarker> = ArcGISMarkerEventController(controller)

    fun registerMarkerEventController(controller: MarkerEventControllerInterface<ArcGISActualMarker>) {
        val typed = controller as? ArcGISMarkerEventController ?: return
        registerMarkerEventController(typed)
    }

    internal fun registerMarkerOverlayLayer(layer: GraphicsOverlay) {
        if (holder.map.graphicsOverlays.contains(layer)) return
        holder.map.graphicsOverlays.add(layer)
    }
}
