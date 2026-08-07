package com.mapconductor.arcgis.marker

import androidx.core.graphics.drawable.toDrawable
import com.arcgismaps.geometry.GeometryEngine
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.symbology.PictureMarkerSymbol
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.mapconductor.arcgis.ArcGISActualMarker
import com.mapconductor.arcgis.ArcGISGeoViewHolder
import com.mapconductor.arcgis.WrapMapView
import com.mapconductor.arcgis.toPoint
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.AbstractMarkerOverlayRenderer
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArcGISMarkerRenderer(
    val markerLayer: GraphicsOverlay,
    holder: ArcGISGeoViewHolder<*, *>,
    coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractMarkerOverlayRenderer<ArcGISGeoViewHolder<*, *>, ArcGISActualMarker>(
        holder = holder,
        coroutine = coroutine,
    ) {
    override val supportsAnimationOverlay: Boolean = true

    /**
     * マーカーのアイコンを縦へ引き伸ばす倍率。
     *
     * 2D は [WrapMapView] 自体を傾けて tilt を表現するので、地図の中身が縦に cos(tilt) 倍へ
     * 潰れる。マーカーだけは立って見えてほしいので、先に 1/cos(tilt) 倍しておいて潰された後に
     * 元の高さになるようにする。3D（SceneView）はビューを傾けないので常に 1。
     */
    private val verticalStretch: Float
        get() {
            val wrap = holder.mapView as? WrapMapView ?: return 1.0f
            val angle = abs(wrap.visualTilt).coerceIn(0.0, 60.0)
            return (1.0 / max(cos(Math.toRadians(angle)), 0.5)).toFloat()
        }

    /**
     * tilt が変わったときに、既存マーカーの縦補正を掛け直す。
     * 元の寸法は生成時に属性へ控えてあるので、そこから毎回作り直す（倍率を累積させない）。
     */
    fun refreshVerticalStretch() {
        val stretch = verticalStretch
        markerLayer.graphics.forEach { graphic ->
            val symbol = graphic.symbol as? PictureMarkerSymbol ?: return@forEach
            val baseHeight = graphic.attributes[BASE_HEIGHT_KEY] as? Float ?: return@forEach
            val baseOffsetY = graphic.attributes[BASE_OFFSET_Y_KEY] as? Float ?: return@forEach
            symbol.height = baseHeight * stretch
            symbol.offsetY = baseOffsetY * stretch
        }
    }

    /**
     * マーカー位置をビューの空間参照に合わせた [Point] へ変換する。
     *
     * `GraphicsOverlay` の `Graphic` は、ジオメトリの空間参照がビュー（`SceneView` /
     * `MapView`）の空間参照と一致していないと描画されない。ArcGIS は表示時に自動で
     * 再投影しないため、WGS84 のまま渡すと 3D（`ArcGISScene` は WGS84）では出るのに
     * 2D（`ArcGISMap` は既定で Web メルカトル）では何も出ない、という差になる。
     *
     * `GeoPoint.toPoint(sr)` は値に空間参照を貼るだけで変換はしないので、まず WGS84 として
     * 組み立ててから [GeometryEngine.projectOrNull] で実際に投影する
     * （`ArcGISCircleOverlayRenderer.centerPoint` と同じ考え方）。
     */
    private fun GeoPoint.toViewPoint(): Point {
        val wgs84Point = toPoint(SpatialReference.wgs84())
        val target = holder.spatialReference ?: return wgs84Point
        if (target == SpatialReference.wgs84()) return wgs84Point
        return GeometryEngine.projectOrNull(wgs84Point, target) as? Point ?: wgs84Point
    }

    override fun setMarkerVisible(
        markerEntity: MarkerEntityInterface<Graphic>,
        visible: Boolean,
    ) {
        coroutine.launch {
            markerEntity.marker?.isVisible = visible
        }
    }

    override fun setMarkerPosition(
        markerEntity: MarkerEntityInterface<Graphic>,
        position: GeoPoint,
    ) {
        coroutine.launch {
            markerEntity.marker?.geometry = position.toViewPoint()
        }
    }

    override suspend fun onAdd(data: List<MarkerOverlayRendererInterface.AddParamsInterface>): List<Graphic?> {
        return withContext(coroutine.coroutineContext) {
            val results =
                data
                    .map { params ->
                        val bitmapDrawable = params.bitmapIcon.bitmap.toDrawable(holder.mapView.context.resources)
                        val density = ResourceProvider.getDensity()
                        val width = params.bitmapIcon.size.width / density
                        val height = params.bitmapIcon.size.height / density
                        val anchorX = (0.5 - params.bitmapIcon.anchor.x) * width
                        val anchorY = (params.bitmapIcon.anchor.y - 0.5) * height

                        val pictureSymbolFuture =
                            PictureMarkerSymbol.createWithImage(bitmapDrawable).also {
                                it.width = width
                                it.height = height * verticalStretch
                                it.offsetX = anchorX.toFloat()
                                it.offsetY = anchorY.toFloat() * verticalStretch
                            }

                        val marker =
                            Graphic(
                                geometry = GeoPoint.from(params.state.position).toViewPoint(),
                                symbol = pictureSymbolFuture,
                            ).also {
                                it.attributes["id"] = params.state.id
                                it.attributes[BASE_HEIGHT_KEY] = height
                                it.attributes[BASE_OFFSET_Y_KEY] = anchorY.toFloat()
                            }
                        return@map marker
                    }.also {
                        markerLayer.graphics.addAll(it)
                    }
            results
        }
    }

    override suspend fun onRemove(data: List<MarkerEntityInterface<ArcGISActualMarker>>) {
        coroutine.launch {
            val elements = data.map { params -> params.marker }
            markerLayer.graphics.removeAll(elements.toSet())
        }
    }

    override suspend fun onPostProcess() {
        // Do nothing here
    }

    override suspend fun onChange(
        data: List<MarkerOverlayRendererInterface.ChangeParamsInterface<ArcGISActualMarker>>,
    ): List<ArcGISActualMarker?> =
        withContext(coroutine.coroutineContext) {
            val results =
                data.map { params ->
                    val prevFinger = params.prev.fingerPrint
                    val currFinger = params.current.fingerPrint

                    val marker =
                        if (params.prev.marker == null) {
                            val bitmapDrawable = params.bitmapIcon.bitmap.toDrawable(holder.mapView.context.resources)
                            val density = ResourceProvider.getDensity()
                            val width = params.bitmapIcon.size.width / density
                            val height = params.bitmapIcon.size.height / density
                            val anchorX = (0.5 - params.bitmapIcon.anchor.x) * width
                            val anchorY = (params.bitmapIcon.anchor.y - 0.5) * height

                            val pictureSymbolFuture =
                                PictureMarkerSymbol.createWithImage(bitmapDrawable).also {
                                    it.width = width
                                    it.height = height * verticalStretch
                                    it.offsetX = anchorX.toFloat()
                                    it.offsetY = anchorY.toFloat() * verticalStretch
                                }
                            Graphic(
                                geometry = GeoPoint.from(params.current.state.position).toViewPoint(),
                                symbol = pictureSymbolFuture,
                            ).also {
                                it.attributes["id"] = params.current.state.id
                                it.attributes[BASE_HEIGHT_KEY] = height
                                it.attributes[BASE_OFFSET_Y_KEY] = anchorY.toFloat()
                            }
                        } else {
                            params.prev.marker!!
                        }

                    if (currFinger.icon != prevFinger.icon) {
                        val bitmapDrawable = params.bitmapIcon.bitmap.toDrawable(holder.mapView.context.resources)
                        val density = ResourceProvider.getDensity()
                        val width = (params.bitmapIcon.size.width / density)
                        val height = (params.bitmapIcon.size.height / density)
                        val anchorX = (params.bitmapIcon.anchor.x - 0.5) * width
                        val anchorY = (params.bitmapIcon.anchor.y - 0.5) * height

                        val pictureSymbolFuture =
                            PictureMarkerSymbol.createWithImage(bitmapDrawable).also {
                                it.width = width
                                it.height = height * verticalStretch
                                it.offsetX = anchorX.toFloat()
                                it.offsetY = anchorY.toFloat() * verticalStretch
                            }
                        marker.symbol = pictureSymbolFuture
                    }

                    marker.geometry = GeoPoint.from(params.current.state.position).toViewPoint()
                    // Always set visibility explicitly like Google Maps (remove conditional check)
                    marker.isVisible = params.current.visible

                    // ArcGISはマーカーを再作成しなくてよいので、同じマーカーのインスタンスを返す
                    marker
                }
            results
        }

    companion object {
        /** 縦補正の掛け直しに使う素の寸法（属性キー）。 */
        private const val BASE_HEIGHT_KEY = "mcBaseHeight"
        private const val BASE_OFFSET_Y_KEY = "mcBaseOffsetY"
    }
}
