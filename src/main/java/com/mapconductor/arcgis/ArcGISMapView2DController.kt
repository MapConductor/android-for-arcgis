package com.mapconductor.arcgis

import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.Basemap
import com.arcgismaps.mapping.Viewpoint
import com.arcgismaps.mapping.view.GraphicsOverlay
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
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.marker.dispatchGeoMarkerClick
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.raster.RasterLayerState
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ArcGISMapView2DController(
    override val holder: ArcGISMapView2DHolder,
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
    internal val markerEventControllers = mutableListOf<ArcGISMarkerEventControllerInterface>()
    internal var activeDragController: ArcGISMarkerEventControllerInterface? = null
    internal var markerClickListener: OnMarkerEventHandler? = null
    internal var markerDragStartListener: OnMarkerEventHandler? = null
    internal var markerDragListener: OnMarkerEventHandler? = null
    internal var markerDragEndListener: OnMarkerEventHandler? = null
    internal var markerAnimateStartListener: OnMarkerEventHandler? = null
    internal var markerAnimateEndListener: OnMarkerEventHandler? = null
    internal var cameraMoveEndJob: Job? = null

    // 直近に要求した論理カメラ位置。2D はカメラピッチを持てず tilt を擬似表現しているため、
    // カメラ状態の読み戻し時に元の tilt・位置・ズームを復元するヒントとして保持する
    // （android-for-tomtom / ios-for-arcgis と同方針）。
    internal var lastLogicalCameraPosition: MapCameraPosition? = null
    internal val cameraMoveEndDebounceMs = 180L
    internal var mapDesignType: ArcGISDesignTypeInterface = ArcGISDesign.Streets
    internal var mapDesignTypeChangeListener: ArcGISDesignTypeChangeHandler? = null

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

    override suspend fun clearOverlays() {
        markerController.clear()
        groundImageController.clear()
        polylineController.clear()
        polygonController.clear()
        circleController.clear()
        rasterLayerController.clear()
    }

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        circleController.clickListener = listener
    }

    @Deprecated("Use GroundImageState.onClick instead.")
    override fun setOnGroundImageClickListener(listener: OnGroundImageEventHandler?) {
        groundImageController.clickListener = listener
    }

    /**
     * 2D の MapView はカメラピッチを持てないため、tilt < 0（上向き）は中心の前進と
     * ズーム補正で近似する（[ArcGIS2DTiltEmulation]）。tilt >= 0 は他プロバイダと同じく
     * 指定位置がそのまま画面中心に来る。
     */
    internal fun toViewpoint(position: MapCameraPosition): Viewpoint {
        val (center, zoom) = ArcGIS2DTiltEmulation.shiftedCamera(position)
        val point = GeoPoint.from(center).toPoint(SpatialReference.wgs84())
        return Viewpoint(
            center = point,
            scale = zoomToScale(zoom),
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
    internal fun scaleToZoom(scale: Double): Double {
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
