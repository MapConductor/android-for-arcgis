package com.mapconductor.arcgis.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arcgismaps.LoadStatus
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.view.MapView
import com.mapconductor.arcgis.ArcGISActualMarker
import com.mapconductor.arcgis.from
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.compose.MapViewBase
import com.mapconductor.core.map.MutableMapServiceRegistry
import com.mapconductor.core.map.OnCameraMoveHandler
import com.mapconductor.core.map.OnMapEventHandler
import com.mapconductor.core.map.OnMapLoadedHandler
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerRenderingStrategyInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerRenderingSupportKey
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.StrategyMarkerController
import java.util.concurrent.atomic.AtomicLong
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
    sdkInitialize: (suspend (android.content.Context) -> Boolean)? = null,
    onMapLoaded: OnMapLoadedHandler? = null,
    onCameraMoveStart: OnCameraMoveHandler? = null,
    onCameraMove: OnCameraMoveHandler? = null,
    onCameraMoveEnd: OnCameraMoveHandler? = null,
    onMapClick: OnMapEventHandler? = null,
    onMapLongClick: OnMapEventHandler? = null,
    content: (@Composable ArcGISMapViewScope.() -> Unit)? = null,
) {
    val scope = remember { ArcGISMapViewScope() }
    val context = LocalContext.current
    val registry = remember { scope.buildRegistry() }
    val serviceRegistry = remember { MutableMapServiceRegistry() }
    val owner = LocalLifecycleOwner.current
    val basemapStyle = remember { ArcGISDesign.toBasemapStyle(state.mapDesignType) }
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
        serviceRegistry = serviceRegistry,
        holderProvider = { wrapView ->
            val map = ArcGISMap(basemapStyle)
            wrapView.arcGISMapView.map = map

            val coroutine = CoroutineScope(Dispatchers.Default)
            suspendCancellableCoroutine<ArcGISMapView2DHolder> { cont ->
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
                                    onCancellation = {},
                                )
                            }
                            is LoadStatus.FailedToLoad -> {
                                if (cont.isActive) {
                                    cont.resume(
                                        ArcGISMapView2DHolder(
                                            mapView = wrapView,
                                            map = wrapView.arcGISMapView,
                                        ),
                                        onCancellation = {},
                                    )
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
            val markerController =
                getMarkerController(
                    holder = holder,
                    markerTiling = markerTiling ?: MarkerTilingOptions.Default,
                    useScenePlacement = false,
                )
            val polylineController = getPolylineController(holder, useScenePlacement = false)
            val rasterLayerController = getRasterLayerController(holder)
            val polygonController = getPolygonController(holder, rasterLayerController, useScenePlacement = false)
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
                serviceRegistry.clear()
                serviceRegistry.put(
                    MarkerRenderingSupportKey,
                    object : MarkerRenderingSupport<ArcGISActualMarker> {
                        override fun createMarkerRenderer(
                            strategy: MarkerRenderingStrategyInterface<ArcGISActualMarker>,
                        ): MarkerOverlayRendererInterface<ArcGISActualMarker> =
                            mapController.createMarkerRenderer(strategy)

                        override fun createMarkerEventController(
                            controller: StrategyMarkerController<ArcGISActualMarker>,
                            renderer: MarkerOverlayRendererInterface<ArcGISActualMarker>,
                        ): MarkerEventControllerInterface<ArcGISActualMarker> =
                            mapController.createMarkerEventController(controller, renderer)

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
        content = content,
    )
}
