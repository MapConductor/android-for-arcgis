package com.mapconductor.arcgis.raster

import com.mapconductor.arcgis.ArcGISMapViewHolder
import com.mapconductor.core.tileserver.TileServerRegistry
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
import com.mapconductor.core.projection.WEB_MERCATOR_MAX_EXTENT_METERS
import com.mapconductor.core.raster.RasterHeaderRuleSet
import com.mapconductor.core.raster.RasterLayerEntityInterface
import com.mapconductor.core.raster.RasterLayerOverlayRendererInterface
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import kotlin.math.log2
import kotlin.math.pow
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ArcGISRasterLayerOverlayRenderer(
    private val holder: ArcGISGeoViewHolder<*, *>,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : RasterLayerOverlayRendererInterface<Layer> {
    /**
     * Which level the camera looks at, and which levels were answered with a
     * transparent tile since the local layers were last built.
     *
     * The 3D view asks for every level above the one on screen and draws none
     * of them once that level is in, so a request two or more levels above the
     * camera is answered transparent, without a render. ArcGIS keeps what it
     * is given, so a level answered that way stays blank if the camera later
     * lands on it -- unless the layer is rebuilt, which [takeRebuildWanted]
     * asks for. The 3D view sizes its levels in dp, so the level is the
     * unified zoom itself (ios-for-arcgis adds log2 of the screen scale).
     */
    internal class AncestorGate {
        private var level: Int? = null
        private val stubbed = HashSet<Int>()
        private var rebuildWanted = false

        @Synchronized
        fun set(unifiedZoom: Double) {
            val wanted = Math.round(unifiedZoom).toInt()
            if (wanted != level) {
                level = wanted
                if (stubbed.contains(wanted) || stubbed.contains(wanted - 1) || stubbed.contains(wanted + 1)) {
                    rebuildWanted = true
                }
            }
        }

        @Synchronized
        fun isAncestor(requested: Int): Boolean {
            val level = level ?: return false
            return requested < level - 1
        }

        @Synchronized
        fun markStubbed(requested: Int) {
            stubbed.add(requested)
        }

        @Synchronized
        fun layerRebuilt() {
            stubbed.clear()
            rebuildWanted = false
        }

        @Synchronized
        fun takeRebuildWanted(): Boolean {
            val wanted = rebuildWanted
            rebuildWanted = false
            return wanted
        }
    }

    private val ancestors = AncestorGate()

    /** The tile server route each local layer was built from, for its gate. */
    private val localRoutes = java.util.IdentityHashMap<Layer, String>()

    /** Only the 3D view loads the pyramid above the level on screen. */
    private val isSceneView: Boolean get() = holder is ArcGISMapViewHolder

    fun cameraMoved(unifiedZoom: Double) {
        if (isSceneView) ancestors.set(unifiedZoom)
    }

    /** Once per need: whether the camera settled on a level answered transparent. */
    fun localLayersNeedRebuild(): Boolean = ancestors.takeRebuildWanted()

    fun localLayersRebuilt() = ancestors.layerRebuilt()

    /** Whether this layer is served by the in-process tile server. */
    fun isLocalLayer(state: RasterLayerState): Boolean = localRouteOf(state) != null

    private fun localRouteOf(state: RasterLayerState): String? {
        val template = (state.source as? RasterLayerSource.UrlTemplate)?.template ?: return null
        val base = TileServerRegistry.get().baseUrl + "/tiles/"
        if (!template.startsWith(base)) return null
        return template.removePrefix(base).substringBefore('/')
    }
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
        localRouteOf(state)?.let { routeId ->
            if (isSceneView) {
                localRoutes[layer] = routeId
                TileServerRegistry.get().setLevelGate(routeId) { level ->
                    ancestors.isAncestor(level).also { if (it) ancestors.markStubbed(level) }
                }
            }
        }
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
        localRoutes.remove(entity.layer)?.let { TileServerRegistry.get().setLevelGate(it, null) }
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

    /**
     * レベルの梯子を**統一ズーム**（Google Maps 基準・256px タイル）から組む。
     *
     * MapConductor のズームはどのプロバイダでも Google のズームなので、level `z` の
     * タイルが持つべき解像度も 1 本の物差しから出す:
     * [AbstractZoomAltitudeConverter.WEB_MERCATOR_INITIAL_MPP_256] が統一ズーム 0 の
     * 1px あたりメートル数（256px タイル基準）。
     *
     * 512px のタイルは 1 枚で 256px タイル 2 枚ぶんを覆うので、level `z` は統一ズーム
     * `z + 1` に対応する（コアの `WebMercatorZoomAltitudeConverter` が言う
     * `zoomOffset`。MapLibre / Mapbox / MapTiler が 1.0、Google / MapKit が 0.0）。
     * その対応を [unifiedZoomForLevel] に書き下しておけば、解像度も縮尺も
     * 「統一ズームいくつのときにこの level を引くか」から一意に決まる。
     *
     * 自前で `2πR / tileSize` と DPI を掛け合わせても同じ数にはなるが、どの物差しに
     * 合わせているのかがコードから消える。実際、以前ここには 512 のときだけ 256 基準へ
     * 読み替える細工が入っていて、(z,x,y) が噛み合わずに地球の裏側のタイルを引いていた。
     */
    private fun buildWebMercatorLevels(
        tileSize: Int,
        minZoom: Int,
        maxZoom: Int,
    ): List<LevelOfDetail> {
        val levels = mutableListOf<LevelOfDetail>()
        for (level in minZoom..maxZoom) {
            val unifiedZoom = unifiedZoomForLevel(level, tileSize)
            val resolution =
                AbstractZoomAltitudeConverter.WEB_MERCATOR_INITIAL_MPP_256 /
                    2.0.pow(unifiedZoom)
            // 縮尺の分母。2D の MapView はこの梯子を見て level を選ぶので、
            // 解像度と同じ比率で刻んでいないと 1 段ずれる。
            //
            // 3D の SceneView は**見ていない**。1 段浅い縮尺を申告して選択を
            // 2D とそろえられないか実機で試したが、要求される level は変わらな
            // かった（統一ズーム 6.5 で z=7 のまま）。SceneView は自前の
            // 「タイルは 256px」という梯子で選ぶ。解像度のほうは見ているので、
            // タイルの位置は申告どおりに決まる。
            val scale = resolution * DEFAULT_DPI * INCHES_PER_METER
            levels.add(LevelOfDetail(level, resolution, scale))
        }
        return levels
    }

    /** この level のタイルを引くべき統一ズーム。256px なら level そのもの。 */
    private fun unifiedZoomForLevel(
        level: Int,
        tileSize: Int,
    ): Double = level + log2(tileSize.toDouble() / UNIFIED_TILE_SIZE)

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
        private const val WEB_MERCATOR_MAX = WEB_MERCATOR_MAX_EXTENT_METERS
        private const val WEB_MERCATOR_MIN = -WEB_MERCATOR_MAX
        private const val DEFAULT_DPI = 96
        private const val INCHES_PER_METER = 39.37

        /** 統一ズームが基準にしているタイルの一辺（Google 準拠の 256px）。 */
        private const val UNIFIED_TILE_SIZE = 256.0
        private const val DEFAULT_MIN_ZOOM = 0
        private const val DEFAULT_MAX_ZOOM = 22
    }
}
