package com.mapconductor.arcgis.raster

import com.arcgismaps.arcgisservices.LevelOfDetail
import com.arcgismaps.geometry.Envelope
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.layers.ArcGISTiledLayer
import com.arcgismaps.mapping.layers.Layer
import com.arcgismaps.mapping.layers.TileImageFormat
import com.arcgismaps.mapping.layers.TileInfo
import com.arcgismaps.mapping.layers.WebTiledLayer
import com.mapconductor.arcgis.ArcGISGeoViewHolder
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.projection.WEB_MERCATOR_MAX_EXTENT_METERS
import com.mapconductor.core.raster.RasterHeaderRuleSet
import com.mapconductor.core.raster.RasterLayerEntityInterface
import com.mapconductor.core.raster.RasterLayerOverlayRendererInterface
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import kotlin.math.PI
import kotlin.math.pow
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ArcGISRasterLayerOverlayRenderer(
    private val holder: ArcGISGeoViewHolder<*, *>,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : RasterLayerOverlayRendererInterface<Layer> {
    override suspend fun onAdd(data: List<RasterLayerOverlayRendererInterface.AddParamsInterface>): List<Layer?> {
        val results = ArrayList<Layer?>(data.size)
        for (params in data) {
            results.add(addLayer(params.state))
        }
        return results
    }

    override suspend fun onChange(
        data: List<RasterLayerOverlayRendererInterface.ChangeParamsInterface<Layer>>,
    ): List<Layer?> {
        val results = ArrayList<Layer?>(data.size)
        for (params in data) {
            val prev = params.prev
            val next = params.current.state
            if (prev.state.source != next.source) {
                // Add new layer first to avoid losing in-flight tile requests
                val newLayer = addLayer(next)
                results.add(newLayer)
                // Remove old layer after new one is added
                removeLayer(prev)
            } else {
                updateLayer(prev.layer, next)
                results.add(prev.layer)
            }
        }
        return results
    }

    override suspend fun onRemove(data: List<RasterLayerEntityInterface<Layer>>) {
        data.forEach { entity ->
            removeLayer(entity)
        }
    }

    override suspend fun onPostProcess() {}

    private suspend fun addLayer(state: RasterLayerState): Layer? {
        RasterHeaderRuleSet.warnUnsupported(provider = "ArcGIS", state = state)
        val operationalLayers = holder.operationalLayers ?: return null
        val layer =
            when (val source = state.source) {
                is RasterLayerSource.ArcGisService -> ArcGISTiledLayer(source.serviceUrl)
                is RasterLayerSource.UrlTemplate -> buildWebTiledLayer(source, state.id) ?: return null
                is RasterLayerSource.TileJson -> {
                    Log.w("ArcGIS", "ArcGIS raster layers do not support TileJson sources.")
                    return null
                }
            }

        // Load layer before adding to scene to ensure TileInfo is fully initialized
        val loadResult = layer.load()
        if (loadResult.isFailure) {
            val error = loadResult.exceptionOrNull()
            Log.e("ArcGIS", "Failed to load raster layer id=${state.id}: ${error?.message}", error)
            return null
        }

        // Add to scene only after successful initialization
        updateLayer(layer, state)
        operationalLayers.add(layer)
        return layer
    }

    private fun updateLayer(
        layer: Layer,
        state: RasterLayerState,
    ) {
        layer.opacity = state.opacity.coerceIn(0.0f, 1.0f)
        layer.isVisible = state.visible
    }

    private fun removeLayer(entity: RasterLayerEntityInterface<Layer>) {
        holder.operationalLayers?.remove(entity.layer)
    }

    private fun buildWebTiledLayer(
        source: RasterLayerSource.UrlTemplate,
        id: String,
    ): WebTiledLayer? {
        if (source.scheme == TileScheme.TMS) {
            Log.w("ArcGIS", "TMS scheme is not supported for WebTiledLayer.")
            return null
        }
        val template =
            source.template
                .replace("{z}", "{level}")
                .replace("{x}", "{col}")
                .replace("{y}", "{row}")
        val minZoom = source.minZoom ?: DEFAULT_MIN_ZOOM
        val maxZoom = source.maxZoom ?: DEFAULT_MAX_ZOOM
        val tileInfo = buildWebMercatorTileInfo(source.tileSize, minZoom, maxZoom)
        val fullExtent = buildWebMercatorExtent()
        return WebTiledLayer.create(template, emptyList(), tileInfo, fullExtent)
    }

    private fun buildWebMercatorTileInfo(
        tileSize: Int,
        minZoom: Int,
        maxZoom: Int,
    ): TileInfo {
        val spatialReference = SpatialReference(WEB_MERCATOR_WKID)
        val origin = Point(WEB_MERCATOR_MIN, WEB_MERCATOR_MAX, spatialReference)
        // LOD の解像度は **必ず tileSize 基準**で刻む。1 枚のタイルが覆う地面は
        // `resolution * tileWidth` なので、ここを 256 基準にしたまま tileWidth に
        // 512 を渡すと、レベル L のタイルが L-1 の広さを覆う。**番号だけ 1 段深い
        // 格子**になり、ArcGIS は「z=13, x=3637」のような噛み合わない組を要求する
        // （東京の z=13 は x=7276、x=3637 は z=12 の値）。実機の Pixel 5a では
        // ベクタータイルのサンプルが大西洋のタイルを引いて真っ青になった。
        //
        // 以前ここには「3D SceneView は 256 基準でないと何も要求しない」という
        // 計測メモがあり、512 のときだけ 256 へ読み替えていた。ArcGIS 300 では
        // 再現しない: 素直な tileSize 基準で 3D も 2D も 256/512 の両方を正しく
        // 引く（z=11 を要求し、MapLibre と一致）。256 のときは読み替えが恒等だった
        // ので、この分岐が壊していたのは 512 だけだった。
        val levels = buildWebMercatorLevels(tileSize, minZoom, maxZoom)
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

    private fun buildWebMercatorLevels(
        tileSize: Int,
        minZoom: Int,
        maxZoom: Int,
    ): List<LevelOfDetail> {
        val initialResolution =
            (2.0 * PI * WEB_MERCATOR_RADIUS_METERS) / tileSize.toDouble()
        val levels = mutableListOf<LevelOfDetail>()
        for (level in minZoom..maxZoom) {
            val resolution = initialResolution / 2.0.pow(level.toDouble())
            val scale = resolution * DEFAULT_DPI * INCHES_PER_METER
            levels.add(LevelOfDetail(level, resolution, scale))
        }
        return levels
    }

    private fun buildWebMercatorExtent(): Envelope =
        Envelope(
            WEB_MERCATOR_MIN,
            WEB_MERCATOR_MIN,
            WEB_MERCATOR_MAX,
            WEB_MERCATOR_MAX,
            spatialReference = SpatialReference(WEB_MERCATOR_WKID),
        )

    companion object {
        private const val WEB_MERCATOR_WKID = 3857
        private const val WEB_MERCATOR_RADIUS_METERS = Earth.RADIUS_METERS
        private const val WEB_MERCATOR_MAX = WEB_MERCATOR_MAX_EXTENT_METERS
        private const val WEB_MERCATOR_MIN = -WEB_MERCATOR_MAX
        private const val DEFAULT_DPI = 96
        private const val INCHES_PER_METER = 39.37
        private const val DEFAULT_MIN_ZOOM = 0
        private const val DEFAULT_MAX_ZOOM = 22
    }
}
