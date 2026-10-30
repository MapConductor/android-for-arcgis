package com.mapconductor.arcgis.marker

import com.mapconductor.arcgis.ArcGISActualMarker
import com.mapconductor.core.controller.OnCameraChangeReceiverInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.AbstractMarkerController
import com.mapconductor.core.marker.BitmapIcon
import com.mapconductor.core.marker.DefaultMarkerIcon
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerIngestionEngine
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTileRasterLayerCallback
import com.mapconductor.core.marker.MarkerTileRenderer
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.MarkerViewportSwitch
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.core.tileserver.TileServerRegistry
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

class ArcGISMarkerController(
    markerManager: MarkerManager<ArcGISActualMarker>,
    renderer: ArcGISMarkerRenderer,
    private val markerTiling: MarkerTilingOptions,
    /**
     * Size tiled icons by how far a tile's level is from the camera's (3D
     * `SceneView`). See [levelIconScale].
     */
    private val scaleIconsByLevel: Boolean = false,
    val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractMarkerController<ArcGISActualMarker>(
        markerManager = markerManager,
        renderer = renderer,
    ),
    OnCameraChangeReceiverInterface {
    /** 2D の tilt 表現が変わったときに、マーカーの縦補正を掛け直す。 */
    fun refreshVerticalStretch() {
        (renderer as? ArcGISMarkerRenderer)?.refreshVerticalStretch()
    }

    private val defaultMarkerIcon: BitmapIcon = DefaultMarkerIcon().toBitmapIcon()
    private val tiledMarkerIds = LinkedHashSet<String>()

    @Volatile
    private var lastKnownZoom: Double = 0.0

    /** The level the 2D map would draw at the current camera; see [levelIconScale]. */
    @Volatile
    private var cameraLevel: Int? = null

    private val tileServer = TileServerRegistry.get(forceNoStoreCache = true)
    private var markerTileRenderer: MarkerTileRenderer<ArcGISActualMarker>? = null
    private var markerTileGroupId: String? = null
    private var markerTileRasterLayerState: RasterLayerState? = null
    private var rasterLayerCallback: MarkerTileRasterLayerCallback? = null
    private var cacheVersion: Int = 0

    /**
     * ビューポート内が少ないときだけタイルをやめてネイティブマーカーで描く切り替え器。
     *
     * レンダラ／マネージャ／semaphore を共有するので、コントローラと同じ排他の下で動く。
     */
    private val viewportSwitch =
        MarkerViewportSwitch(
            markerManager = markerManager,
            renderer = renderer,
            defaultMarkerIcon = defaultMarkerIcon,
            semaphore = semaphore,
            policy = markerTiling.viewport,
            setTileLayerVisible = ::setTileLayerVisible,
            invalidateTiles = ::updateRasterLayerSource,
        )

    fun setRasterLayerCallback(callback: MarkerTileRasterLayerCallback?) {
        rasterLayerCallback = callback
    }

    override suspend fun add(data: List<MarkerState>) {
        // ingest はタイル担当 entity を marker = null で登録し直すので、先に昇格を戻す。
        // semaphore は再入不可なので withPermit の外で呼ぶこと。
        viewportSwitch.retract()
        semaphore.withPermit {
            val tilingEnabled =
                markerTiling.enabled && data.size >= markerManager.minMarkerCount
            val result =
                MarkerIngestionEngine.ingest(
                    data = data,
                    markerManager = markerManager,
                    renderer = renderer,
                    defaultMarkerIcon = defaultMarkerIcon,
                    tilingEnabled = tilingEnabled,
                    tiledMarkerIds = tiledMarkerIds,
                    shouldTile = { state -> !state.draggable && state.getAnimation() == null },
                )

            if (result.tiledDataChanged) {
                syncTiledOverlay()
            } else if (result.hasTiledMarkers) {
                if (markerTileRenderer == null || markerTileRasterLayerState == null) {
                    syncTiledOverlay()
                }
            } else {
                removeTileOverlay()
            }
        }
        viewportSwitch.requestReapply()
    }

    override suspend fun update(state: MarkerState) {
        if (!markerManager.hasEntity(state.id)) return

        // 昇格中の 1 件なら先に取り下げる（下で marker = null 登録があるため）。
        // 昇格していないマーカー（ドラッグ中など）では何もしないので、ドラッグは素通りする。
        if (viewportSwitch.isPromoted(state.id)) viewportSwitch.release(state.id)

        val prevEntity = markerManager.getEntity(state.id) ?: return
        val currentFinger = state.fingerPrint()
        val prevFinger = prevEntity.fingerPrint
        if (currentFinger == prevFinger) return

        semaphore.withPermit {
            val tilingEnabled =
                markerTiling.enabled && markerManager.allEntities().size >= markerManager.minMarkerCount
            val wantsTiled = tilingEnabled && !state.draggable && state.getAnimation() == null
            val wasTiled = tiledMarkerIds.contains(state.id)
            val markerIcon = state.icon?.toBitmapIcon() ?: defaultMarkerIcon

            if (wantsTiled) {
                if (!wasTiled) {
                    prevEntity.marker?.let { renderer.onRemove(listOf(prevEntity)) }
                    tiledMarkerIds.add(state.id)
                }
                markerManager.updateEntity(
                    MarkerEntity(
                        marker = null,
                        state = state,
                        visible = prevEntity.visible,
                        isRendered = true,
                        // tiling を立てないと MarkerTileRenderer の絞り込みから漏れ、
                        // タイル昇格したのにタイルへ描かれないマーカーになる。
                        tiling = true,
                    ),
                )
                renderer.onPostProcess()
                syncTiledOverlay()
                return@withPermit
            }

            if (wasTiled) {
                tiledMarkerIds.remove(state.id)
            }

            val params =
                object : MarkerOverlayRendererInterface.ChangeParamsInterface<ArcGISActualMarker> {
                    override val current: MarkerEntityInterface<ArcGISActualMarker> =
                        MarkerEntity(
                            marker = prevEntity.marker,
                            state = state,
                            visible = prevEntity.visible,
                            isRendered = true,
                        )
                    override val bitmapIcon: BitmapIcon = markerIcon
                    override val prev: MarkerEntityInterface<ArcGISActualMarker> = prevEntity
                }
            val markers = renderer.onChange(listOf(params))
            markers.firstOrNull()?.let { actual ->
                markerManager.updateEntity(
                    MarkerEntity(
                        marker = actual,
                        state = state,
                        visible = prevEntity.visible,
                        isRendered = true,
                    ),
                )
                if (prevFinger.animation != currentFinger.animation) {
                    state.getAnimation()?.let { renderer.onAnimate(markerManager.getEntity(state.id)!!) }
                }
            }
            renderer.onPostProcess()

            if (tiledMarkerIds.isNotEmpty()) {
                syncTiledOverlay()
            } else {
                removeTileOverlay()
            }
        }
        viewportSwitch.requestReapply()
    }

    override suspend fun clear() {
        viewportSwitch.destroy()
        semaphore.withPermit {
            val entities = markerManager.allEntities()
            val toRemove = entities.filter { it.marker != null }
            if (toRemove.isNotEmpty()) {
                renderer.onRemove(toRemove)
            }
            markerManager.clear()
            tiledMarkerIds.clear()
            removeTileOverlay()
        }
    }

    override suspend fun onCameraChanged(mapCameraPosition: MapCameraPosition) {
        // 判定と昇格は debounce したうえで切り替え器の中で走る（パン中は動かない）。
        viewportSwitch.onCameraChanged(mapCameraPosition)
        lastKnownZoom = mapCameraPosition.zoom
        if (scaleIconsByLevel) {
            val level = mapCameraPosition.zoom.roundToInt()
            if (level != cameraLevel) {
                cameraLevel = level
                // Every tile's icon size depends on the camera's level.
                semaphore.withPermit { updateRasterLayerSource() }
            }
        }
    }

    /**
     * Icon scale for a tile at [tileLevel], from the app's callback.
     *
     * The 2D map draws one level per camera, so a tile's level is the camera's
     * and the callback's scale goes in as it is. The 3D `SceneView` picks a
     * level per tile from the ground it covers on the globe and mixes two
     * levels on one screen (a Pixel at the post office sample asked for z=10
     * and z=11 side by side). A tile one level deeper is shown at half the
     * size, and its pins came out half as large as the 2D map's (26px against
     * 41px) next to correct ones. Drawn at 2^(tile level - camera level), every
     * pin comes out the size the 2D map gives it at that camera, and the
     * callback is asked for the camera's level as the 2D map does.
     */
    private fun levelIconScale(
        state: MarkerState,
        tileLevel: Int,
    ): Double {
        val level = cameraLevel?.takeIf { scaleIconsByLevel } ?: tileLevel
        val base = markerTiling.iconScaleCallback?.invoke(state, level) ?: 1.0
        return base * 2.0.pow(tileLevel - level)
    }

    /**
     * マーカータイルのラスターレイヤの表示だけを切り替える。
     *
     * source（URL）には触らない。触るとタイルを取り直すことになり、切り替えのたびに
     * タイルキャッシュを捨てるのと同じになる。
     */
    private suspend fun setTileLayerVisible(visible: Boolean) {
        val current = markerTileRasterLayerState ?: return
        if (current.visible == visible) return
        val newState = current.copy(visible = visible)
        markerTileRasterLayerState = newState
        rasterLayerCallback?.onRasterLayerUpdate(newState)
    }

    override fun destroy() {
        viewportSwitch.destroy()
        // Clean up tile server registration
        // Unregister this map's route only. Never stop the server here: it is
        // a process-wide singleton shared by all map controllers and overlay
        // modules; stopping it breaks tile loading for every other live map.
        markerTileGroupId?.let { groupId ->
            tileServer.unregister(groupId)
        }
        markerTileGroupId = null
        markerTileRenderer = null

        coroutine.launch {
            rasterLayerCallback?.onRasterLayerUpdate(null)
        }
        markerTileRasterLayerState = null
        super.destroy()
    }

    private suspend fun updateRasterLayerSource() {
        val groupId = markerTileGroupId ?: return
        val tileRenderer = markerTileRenderer ?: return
        val oldState = markerTileRasterLayerState ?: return
        cacheVersion = (cacheVersion + 1) and 0x7fffffff
        tileRenderer.invalidate()
        val cacheBuster = "${cacheVersion}_${SystemClock.elapsedRealtime()}"

        val newState =
            oldState.copy(
                source =
                    RasterLayerSource.UrlTemplate(
                        template = "${tileServer.urlTemplate(groupId, tileRenderer.tileSize)}?cb=$cacheBuster",
                        tileSize = tileRenderer.tileSize,
                        maxZoom = 22,
                        scheme = TileScheme.XYZ,
                    ),
                id = oldState.id,
            )
        markerTileRasterLayerState = newState
        rasterLayerCallback?.onRasterLayerUpdate(newState)
    }

    private suspend fun syncTiledOverlay() {
        if (tiledMarkerIds.isEmpty()) {
            removeTileOverlay()
            return
        }
        if (!markerTiling.enabled) {
            removeTileOverlay()
            tiledMarkerIds.clear()
            return
        }

        getOrCreateTileRenderer()
        updateRasterLayerSource()
    }

    private fun getOrCreateTileRenderer(): MarkerTileRenderer<ArcGISActualMarker> {
        markerTileRenderer?.let { return it }

        val groupId = UUID.randomUUID().toString()
        markerTileGroupId = groupId

        cacheVersion = (cacheVersion + 1) and 0x7fffffff
        val tileRenderer =
            MarkerTileRenderer(
                markerManager = markerManager,
                tileSize = 256,
                cacheSizeBytes = markerTiling.cacheSize,
                debugTileOverlay = markerTiling.debugTileOverlay,
                iconScaleCallback =
                    if (scaleIconsByLevel) ::levelIconScale else markerTiling.iconScaleCallback,
                declutterPx = markerTiling.declutterPx,
                // ArcGIS displays one 96dpi/256px LOD tile over 256dp on screen,
                // matching the renderer's density-scaled output 1:1 — no extra
                // icon scaling needed. (0.5 predates the MarkerTileRenderer fix
                // that stopped double-applying MarkerIconInterface.scale.)
                extraIconScale = 1.0,
            )
        markerTileRenderer = tileRenderer

        tileServer.register(groupId, tileRenderer)

        markerTileRasterLayerState =
            RasterLayerState(
                id = "marker-tile-$groupId",
                source =
                    RasterLayerSource.UrlTemplate(
                        template = "${tileServer.urlTemplate(groupId, tileRenderer.tileSize)}?cb=$cacheVersion",
                        tileSize = tileRenderer.tileSize,
                        maxZoom = 22,
                        scheme = TileScheme.XYZ,
                    ),
                opacity = 1.0f,
                visible = true,
            )

        return tileRenderer
    }

    private suspend fun removeTileOverlay() {
        markerTileGroupId?.let { groupId ->
            tileServer.unregister(groupId)
        }
        markerTileGroupId = null
        markerTileRenderer = null

        rasterLayerCallback?.onRasterLayerUpdate(null)
        markerTileRasterLayerState = null
    }
}
