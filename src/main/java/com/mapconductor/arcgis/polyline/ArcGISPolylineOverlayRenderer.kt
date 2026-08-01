package com.mapconductor.arcgis.polyline

import com.arcgismaps.geometry.Geometry
import com.arcgismaps.geometry.PolylineBuilder
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.symbology.SimpleLineSymbol
import com.arcgismaps.mapping.symbology.SimpleLineSymbolStyle
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.mapconductor.arcgis.ArcGISActualPolyline
import com.mapconductor.arcgis.ArcGISGeoViewHolder
import com.mapconductor.arcgis.toArcGISColor
import com.mapconductor.arcgis.toPoint
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.polyline.AbstractPolylineOverlayRenderer
import com.mapconductor.core.polyline.PolylineEntityInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.spherical.createInterpolatePoints
import com.mapconductor.core.spherical.createLinearInterpolatePoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArcGISPolylineOverlayRenderer(
    val polylineLayer: GraphicsOverlay,
    override val holder: ArcGISGeoViewHolder<*, *>,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AbstractPolylineOverlayRenderer<ArcGISActualPolyline>() {
    override suspend fun createPolyline(state: PolylineState): ArcGISActualPolyline? =
        withContext(coroutine.coroutineContext) {
            val geometry = createGeometry(state)

            val lineSymbol =
                SimpleLineSymbol().apply {
                    style = SimpleLineSymbolStyle.Solid
                    color = state.strokeColor.toArcGISColor()
                    width = state.strokeWidth.value
                }

            val graphic =
                Graphic(geometry, lineSymbol).also {
                    it.attributes.set("id", state.id)
                }

            polylineLayer.graphics.add(graphic)
            graphic
        }

    override suspend fun updatePolylineProperties(
        polyline: ArcGISActualPolyline,
        current: PolylineEntityInterface<ArcGISActualPolyline>,
        prev: PolylineEntityInterface<ArcGISActualPolyline>,
    ): ArcGISActualPolyline? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint

            if (finger.points != prevFinger.points || finger.geodesic != prevFinger.geodesic) {
                polyline.geometry = createGeometry(current.state)
            }

            (polyline.symbol as SimpleLineSymbol).let { symbol ->
                if (finger.strokeColor != prevFinger.strokeColor) {
                    symbol.color = current.state.strokeColor.toArcGISColor()
                }
                if (finger.strokeWidth != prevFinger.strokeWidth) {
                    symbol.width = current.state.strokeWidth.value
                }
            }

            polyline
        }

    override suspend fun removePolyline(entity: PolylineEntityInterface<ArcGISActualPolyline>) {
        coroutine.launch {
            polylineLayer.graphics.remove(entity.polyline)
        }
    }

    private fun createGeometry(state: PolylineState): Geometry {
        // The builder (and every point added to it) must share the same explicit spatial
        // reference. WGS84 is used, matching the raw lon/lat degrees GeoPoint always carries;
        // ArcGIS reprojects the resulting geometry onto the map's own (projected) spatial
        // reference automatically when it's rendered. A null spatial reference builds without
        // error but the resulting geometry doesn't render on the map (see ArcGISMarkerRenderer/
        // ArcGISCircleOverlayRenderer for the same fix applied to markers and circles).
        val spatialReference = SpatialReference.wgs84()
        // 他プロバイダと同様、測地線・直線ともコアの共通補間で頂点列を生成し、ArcGIS には
        // 密な頂点列をそのまま渡す（ArcGIS の再投影が生の辺を測地線状に描く挙動へは依存しない）。
        val points =
            when (state.geodesic) {
                true -> createInterpolatePoints(state.points)
                false -> createLinearInterpolatePoints(state.points)
            }
        val polylineBuilder =
            PolylineBuilder(spatialReference).also { builder ->
                points.forEach {
                    builder.addPoint(GeoPoint.from(it).toPoint(spatialReference))
                }
            }
        return polylineBuilder.toGeometry()
    }
}
