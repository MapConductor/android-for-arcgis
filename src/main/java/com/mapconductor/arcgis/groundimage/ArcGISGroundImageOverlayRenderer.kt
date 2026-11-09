package com.mapconductor.arcgis.groundimage

import com.arcgismaps.arcgisservices.LevelOfDetail
import com.arcgismaps.geometry.Envelope
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.layers.TileImageFormat
import com.arcgismaps.mapping.layers.TileInfo
import com.arcgismaps.mapping.layers.WebTiledLayer
import com.mapconductor.arcgis.ArcGISGeoViewHolder
import com.mapconductor.arcgis.ArcGISOverlayStacking
import com.mapconductor.arcgis.WebMercatorTileLevels
import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.groundimage.AbstractGroundImageOverlayRenderer
import com.mapconductor.core.groundimage.GroundImageEntityInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.GroundImageTileProvider
import com.mapconductor.core.projection.WEB_MERCATOR_MAX_EXTENT_METERS
import com.mapconductor.core.tileserver.LocalTileServer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArcGISGroundImageOverlayRenderer(
    override val holder: ArcGISGeoViewHolder<*, *>,
    private val tileServer: LocalTileServer,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AbstractGroundImageOverlayRenderer<ArcGISGroundImageHandle>() {
    /**
     * operational layer の並び（`operationalLayers`）はスレッドセーフでない。ラスターの
     * レンダラ・マーカーのタイルはメインスレッドで触るので、ここも足す・外すはメインスレッドで行う
     * （以前は [coroutine]（Default）のまま触っていて、GroundImage とラスターを同時に外すと
     * `ConcurrentModificationException` で落ちた）。タイルの用意と層の load は Default のまま。
     */
    private suspend fun <T> onLayerList(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    override suspend fun createGroundImage(state: GroundImageState): ArcGISGroundImageHandle? =
        withContext(coroutine.coroutineContext) {
            val operationalLayers = holder.operationalLayers ?: return@withContext null
            val routeId = buildSafeRouteId(state.id)
            val provider = GroundImageTileProvider(tileSize = state.tileSize)
            // ArcGIS WebTiledLayer has a native opacity property, so avoid baking opacity into tiles.
            // This keeps slider-driven opacity updates lightweight (no cache eviction or PNG re-encode).
            provider.update(state, opacity = 1.0f)
            tileServer.register(routeId, provider)

            val handle =
                createHandle(
                    routeId = routeId,
                    generation = 0L,
                    cacheKey = tileCacheKey(state),
                    provider = provider,
                )
            val loadResult = handle.layer.load()
            if (loadResult.isFailure) {
                val error = loadResult.exceptionOrNull()
                Log.e("ArcGIS", "Failed to load ground image layer id=${state.id}: ${error?.message}", error)
                tileServer.unregister(routeId)
                return@withContext null
            }
            updateLayer(handle.layer, state)
            // GroundImage の帯（ラスターの上）。GraphicsOverlay（ポリゴン等）は常にこの上。
            onLayerList {
                ArcGISOverlayStacking.add(operationalLayers, handle.layer, ArcGISOverlayStacking.key(OverlayKind.GroundImage))
            }
            handle
        }

    override suspend fun updateGroundImageProperties(
        groundImage: ArcGISGroundImageHandle,
        current: GroundImageEntityInterface<ArcGISGroundImageHandle>,
        prev: GroundImageEntityInterface<ArcGISGroundImageHandle>,
    ): ArcGISGroundImageHandle? =
        withContext(coroutine.coroutineContext) {
            val operationalLayers = holder.operationalLayers ?: return@withContext null
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint

            val tileNeedsRefresh =
                finger.bounds != prevFinger.bounds ||
                    finger.image != prevFinger.image ||
                    finger.tileSize != prevFinger.tileSize

            val opacityChanged = finger.opacity != prevFinger.opacity

            if (!tileNeedsRefresh) {
                if (opacityChanged) {
                    updateLayer(groundImage.layer, current.state)
                }
                return@withContext groundImage
            }

            val provider =
                if (finger.tileSize != prevFinger.tileSize) {
                    GroundImageTileProvider(tileSize = current.state.tileSize).also {
                        tileServer.register(groundImage.routeId, it)
                    }
                } else {
                    groundImage.tileProvider
                }
            provider.update(current.state, opacity = 1.0f)

            val nextGeneration = groundImage.generation + 1L
            val nextHandle =
                createHandle(
                    routeId = groundImage.routeId,
                    generation = nextGeneration,
                    cacheKey = tileCacheKey(current.state),
                    provider = provider,
                )
            val loadResult = nextHandle.layer.load()
            if (loadResult.isFailure) {
                val error = loadResult.exceptionOrNull()
                Log.e(
                    "ArcGIS",
                    "Failed to load updated ground image layer id=${current.state.id}: ${error?.message}",
                    error,
                )
                return@withContext null
            }

            // Swap layers to ensure cache busting via generation query.
            onLayerList {
                operationalLayers.remove(groundImage.layer)
                ArcGISOverlayStacking.add(
                    operationalLayers,
                    nextHandle.layer,
                    ArcGISOverlayStacking.key(OverlayKind.GroundImage),
                )
            }
            updateLayer(nextHandle.layer, current.state)
            nextHandle
        }

    override suspend fun removeGroundImage(entity: GroundImageEntityInterface<ArcGISGroundImageHandle>) {
        coroutine.launch {
            val handle = entity.groundImage
            onLayerList { holder.operationalLayers?.remove(handle.layer) }
            tileServer.unregister(handle.routeId)
        }
    }

    private fun updateLayer(
        layer: WebTiledLayer,
        state: GroundImageState,
    ) {
        layer.opacity = state.opacity.coerceIn(0.0f, 1.0f)
        layer.isVisible = true
    }

    private fun createHandle(
        routeId: String,
        generation: Long,
        cacheKey: String,
        provider: GroundImageTileProvider,
    ): ArcGISGroundImageHandle {
        val urlTemplate = tileServer.urlTemplate(routeId, provider.tileSize, cacheKey)
        val template =
            urlTemplate
                .replace("{z}", "{level}")
                .replace("{x}", "{col}")
                .replace("{y}", "{row}")

        val tileInfo = buildWebMercatorTileInfo(provider.tileSize)
        val fullExtent = buildWebMercatorExtent()
        val layer = WebTiledLayer.create(template, emptyList(), tileInfo, fullExtent)
        return ArcGISGroundImageHandle(
            routeId = routeId,
            generation = generation,
            cacheKey = cacheKey,
            tileProvider = provider,
            layer = layer,
        )
    }

    private fun buildWebMercatorTileInfo(tileSize: Int): TileInfo {
        val spatialReference = SpatialReference(WEB_MERCATOR_WKID)
        val origin = Point(WEB_MERCATOR_MIN, WEB_MERCATOR_MAX, spatialReference)
        val levels = buildWebMercatorLevels(tileSize)
        return TileInfo(
            DEFAULT_DPI,
            TileImageFormat.Png,
            levels,
            origin,
            spatialReference,
            tileSize,
            tileSize,
        )
    }

    // 梯子はラスターと同じもの（[WebMercatorTileLevels]）を使う。以前は 512 のときだけ
    // 256 基準へ読み替えていて、ArcGIS 300 では level L に L-1 の格子の (x, y) を付けて
    // 要求された。GroundImageTileProvider はそれを素直に z=L として描くので、どのタイルも
    // 画像の外になり透明だった。
    private fun buildWebMercatorLevels(tileSize: Int): List<LevelOfDetail> =
        (MIN_ZOOM..MAX_ZOOM).map { level ->
            val resolution = WebMercatorTileLevels.resolution(level, tileSize)
            LevelOfDetail(level, resolution, WebMercatorTileLevels.scale(resolution))
        }

    private fun buildWebMercatorExtent(): Envelope =
        Envelope(
            WEB_MERCATOR_MIN,
            WEB_MERCATOR_MIN,
            WEB_MERCATOR_MAX,
            WEB_MERCATOR_MAX,
            spatialReference = SpatialReference(WEB_MERCATOR_WKID),
        )

    private fun buildSafeRouteId(id: String): String =
        buildString(id.length + 16) {
            append("groundimage-")
            id.forEach { ch ->
                when {
                    ch.isLetterOrDigit() -> append(ch)
                    ch == '-' || ch == '_' -> append(ch)
                    else -> append('_')
                }
            }
        }

    private fun tileCacheKey(state: GroundImageState): String =
        buildString(64) {
            append(state.bounds.hashCode())
            append('-')
            append(state.image.hashCode())
            append('-')
            append(state.tileSize.hashCode())
            append('-')
            append(state.extra?.hashCode() ?: 0)
        }

    companion object {
        private const val WEB_MERCATOR_WKID = 3857
        private const val WEB_MERCATOR_MAX = WEB_MERCATOR_MAX_EXTENT_METERS
        private const val WEB_MERCATOR_MIN = -WEB_MERCATOR_MAX
        private const val DEFAULT_DPI = WebMercatorTileLevels.DEFAULT_DPI
        private const val MIN_ZOOM = 0
        private const val MAX_ZOOM = 22
    }
}
