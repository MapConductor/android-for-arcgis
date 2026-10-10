package com.mapconductor.arcgis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arcgismaps.LoadStatus
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.Basemap
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.arcgismaps.mapping.view.GraphicsRenderingMode
import com.arcgismaps.mapping.view.MapView
import com.mapconductor.compose.map.MapViewBase
import com.mapconductor.core.OnCameraMoveHandler
import com.mapconductor.core.OnMapEventHandler
import com.mapconductor.core.OnMapLoadedHandler
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapViewStyle
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerRenderingStrategyInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerRenderingSupportKey
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.StrategyMarkerController
import java.util.concurrent.atomic.AtomicLong
import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
fun ArcGISMapView2D(
    state: ArcGISMapViewState,
    modifier: Modifier = Modifier,
    markerTiling: MarkerTilingOptions? = null,
    cameraRestriction: CameraRestriction? = null,
    sdkInitialize: (suspend (Context) -> Boolean)? = null,
    onMapLoaded: OnMapLoadedHandler? = null,
    onCameraMoveStart: OnCameraMoveHandler? = null,
    onCameraMove: OnCameraMoveHandler? = null,
    onCameraMoveEnd: OnCameraMoveHandler? = null,
    onMapClick: OnMapEventHandler? = null,
    onMapLongClick: OnMapEventHandler? = null,
    /**
     * How the map looks, when the app states it rather than naming a design.
     *
     * A vector style *is* the basemap. `com.mapconductor:vectorstyle` builds
     * one; what happens underneath depends on this backend and the app does
     * not have to know.
     */
    style: MapViewStyle? = null,
    onStyleDiagnostics: ((List<String>) -> Unit)? = null,
    content: (@Composable ArcGISMapViewScope.() -> Unit)? = null,
) {
    val scope = remember { ArcGISMapViewScope() }
    val context = LocalContext.current
    val registry = remember { scope.buildRegistry() }
    val owner = LocalLifecycleOwner.current
    // The design the map was built with; a design set on the state before the
    // controller existed is applied once it does.
    val createdDesign = remember { Ref<String>() }
    val cameraState = remember { mutableStateOf<MapCameraPositionInterface?>(state.cameraPosition) }
    val controllerRef = remember { Ref<ArcGISMapView2DController>() }
    val controllerGeneration = remember { AtomicLong(0L) }

    MapViewBase(
        state = state,
        cameraState = cameraState,
        modifier = modifier,
        viewProvider = {
            val mapView = MapView(context)
            val wrapView =
                WrapMapView(context).apply {
                    addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
            wrapView.arcGISMapView = mapView
            mapView.onCreate(owner)
            mapView.onResume(owner)
            wrapView
        },
        scope = scope,
        registry = registry,
        holderProvider = { wrapView ->
            // The map is told its spatial reference up front instead of
            // learning it from the basemap: a basemap style lives on
            // arcgis.com, and a map that waited for it to find out where it
            // is can draw nothing while the network is away. Told, it loads
            // at once with no basemap, and with one it keeps drawing its own
            // layers (a raster layer served on the device, say) when the
            // basemap is out of reach.
            val design = state.mapDesignType
            createdDesign.value = design.getValue()
            val map = ArcGISMap(SpatialReference.webMercator())
            ArcGISDesign.toBasemapStyleOrNull(design)?.let { map.setBasemap(Basemap(it)) }
            wrapView.arcGISMapView.map = map
            var retried = false

            val coroutine = CoroutineScope(Dispatchers.Default)
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { coroutine.cancel() }
                coroutine.launch {
                    map.loadStatus.collect {
                        when (it) {
                            is LoadStatus.Loaded -> {
                                cont.resume(
                                    ArcGISMapView2DHolder(
                                        mapView = wrapView,
                                        map = wrapView.arcGISMapView,
                                    ),
                                ) { _, _, _ -> }
                            }
                            is LoadStatus.FailedToLoad -> {
                                Log.w("ArcGISMapView2D", "map failed to load: ${it.error.message}")
                                // The basemap was taken away while it was being
                                // fetched (the app went basemap-less, say for an
                                // offline raster): without one the map needs no
                                // network, so it gets one more go.
                                if (map.basemap.value == null && !retried) {
                                    retried = true
                                    map.retryLoad()
                                    return@collect
                                }
                                if (cont.isActive) {
                                    cont.resume(
                                        ArcGISMapView2DHolder(
                                            mapView = wrapView,
                                            map = wrapView.arcGISMapView,
                                        ),
                                    ) { _, _, _ -> }
                                }
                            }
                            else -> {
                                // Do nothing here
                            }
                        }
                    }
                }
            }
        },
        controllerProvider = { holder ->
            val markerLayer: GraphicsOverlay =
                GraphicsOverlay().apply {
                    renderingMode = GraphicsRenderingMode.Dynamic
                }
            val markerController =
                getMarkerController(
                    holder = holder,
                    markerLayer = markerLayer,
                    markerTiling = markerTiling ?: MarkerTilingOptions.Default,
                )
            val polylineController = getPolylineController(holder, useScenePlacement = false)
            val rasterLayerController = getRasterLayerController(holder)
            val polygonController = getPolygonController(holder, useScenePlacement = false)
            val circleController = getCircleController(holder, useScenePlacement = false)
            val groundImageController = getGroundImageController(holder)

            ArcGISMapView2DController(
                holder = holder,
                markerController = markerController,
                polylineController = polylineController,
                polygonController = polygonController,
                circleController = circleController,
                groundImageController = groundImageController,
                rasterLayerController = rasterLayerController,
            ).also { mapController ->
                state.serviceRegistry.put(
                    MarkerRenderingSupportKey,
                    object : MarkerRenderingSupport<ArcGISActualMarker> {
                        override fun createMarkerRenderer(
                            strategy: MarkerRenderingStrategyInterface<ArcGISActualMarker>,
                        ): MarkerOverlayRendererInterface<ArcGISActualMarker> = mapController.createMarkerRenderer()

                        override fun createMarkerEventController(
                            controller: StrategyMarkerController<ArcGISActualMarker>,
                            renderer: MarkerOverlayRendererInterface<ArcGISActualMarker>,
                        ): MarkerEventControllerInterface<ArcGISActualMarker> =
                            mapController
                                .createMarkerEventController(controller)

                        override fun registerMarkerEventController(
                            controller: MarkerEventControllerInterface<ArcGISActualMarker>,
                        ) {
                            mapController.registerMarkerEventController(controller)
                        }

                        override fun onMarkerRenderingReady() {
                            mapController.sendInitialCameraUpdate()
                        }
                    },
                )

                controllerRef.value = mapController
                mapController.setMapClickListener(onMapClick)
                mapController.setMapLongClickListener(onMapLongClick)
                mapController.setMapDesignTypeChangeListener(state::onMapDesignTypeChange)
                state.setController(mapController)
                if (state.mapDesignType.getValue() !=
                    createdDesign.value
                ) {
                    mapController.setMapDesignType(state.mapDesignType)
                }
                // 他プロバイダの *MapView と同じく、コントローラ生成直後に適用する。
                cameraRestriction?.let { mapController.setCameraRestriction(it) }

                mapController.setCameraMoveStartListener {
                    cameraState.value = it
                    state.updateCameraPosition(it)
                    onCameraMoveStart?.invoke(it)
                }
                mapController.setCameraMoveListener {
                    cameraState.value = it
                    state.updateCameraPosition(it)
                    onCameraMove?.invoke(it)
                }
                mapController.setCameraMoveEndListener {
                    cameraState.value = it
                    state.updateCameraPosition(it)
                    onCameraMoveEnd?.invoke(it)
                }

                val initialCameraPosition = state.cameraPosition
                val generation = controllerGeneration.incrementAndGet()
                holder.mapView.post {
                    if (controllerGeneration.get() != generation) return@post
                    mapController.moveCamera(MapCameraPosition.from(initialCameraPosition))
                    mapController.sendInitialCameraUpdate()
                    // viewpointChanged の初回発火に頼らず、レイアウト確定のこの時点で
                    // 「準備完了」を通知する（取り逃すとオーバーレイが一切描画されない）。
                    mapController.markMapInitialized()
                }
            }
        },
        sdkInitialize = {
            sdkInitialize?.invoke(context) ?: defaultArcGISInitialize(context)
        },
        onMapLoaded = onMapLoaded,
        customDisposableEffect = { _, holderRef ->
            DisposableEffect(state.id) {
                onDispose {
                    controllerGeneration.incrementAndGet()
                    controllerRef.value?.apply {
                        setCameraMoveStartListener(null)
                        setCameraMoveListener(null)
                        setCameraMoveEndListener(null)
                        setMapClickListener(null)
                        setMapLongClickListener(null)
                    }
                    controllerRef.value = null
                    state.clearController()
                    holderRef.value?.mapView?.apply {
                        onPause(owner)
                        onStop(owner)
                        onDestroy(owner)
                    }
                }
            }
        },
        style = style,
        onStyleDiagnostics = onStyleDiagnostics,
        content = content,
    )
}
