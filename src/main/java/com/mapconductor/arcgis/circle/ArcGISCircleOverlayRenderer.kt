package com.mapconductor.arcgis.circle

import com.arcgismaps.geometry.GeodeticCurveType
import com.arcgismaps.geometry.GeometryEngine
import com.arcgismaps.geometry.LinearUnit
import com.arcgismaps.geometry.LinearUnitId
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.symbology.SimpleFillSymbol
import com.arcgismaps.mapping.symbology.SimpleFillSymbolStyle
import com.arcgismaps.mapping.symbology.SimpleLineSymbol
import com.arcgismaps.mapping.symbology.SimpleLineSymbolStyle
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.mapconductor.arcgis.ArcGISActualCircle
import com.mapconductor.arcgis.ArcGISGeoViewHolder
import com.mapconductor.arcgis.toArcGISColor
import com.mapconductor.arcgis.toPoint
import com.mapconductor.core.circle.AbstractCircleOverlayRenderer
import com.mapconductor.core.circle.CircleEntityInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArcGISCircleOverlayRenderer(
    val circleLayer: GraphicsOverlay,
    override val holder: ArcGISGeoViewHolder<*, *>,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractCircleOverlayRenderer<ArcGISActualCircle>() {
    override suspend fun createCircle(state: CircleState): ArcGISActualCircle? =
        withContext(coroutine.coroutineContext) {
            val centerPoint = centerPoint(state.center, state.geodesic)
            val circleGeometry =
                if (state.geodesic) {
                    GeometryEngine.bufferGeodeticOrNull(
                        geometry = centerPoint,
                        distance = state.radiusMeters,
                        distanceUnit = LinearUnit(LinearUnitId.Meters),
                        maxDeviation = Double.NaN,
                        curveType = GeodeticCurveType.NormalSection,
                    )
                } else {
                    // Planar buffer in the map's spatial reference
                    GeometryEngine.bufferOrNull(
                        geometry = centerPoint,
                        distance = state.radiusMeters,
                    )
                }
            val stroke =
                SimpleLineSymbol(
                    style = SimpleLineSymbolStyle.Solid,
                    color = state.strokeColor.toArcGISColor(),
                    width = state.strokeWidth.value,
                )
            val fillSymbol =
                SimpleFillSymbol(
                    style = SimpleFillSymbolStyle.Solid,
                    color = state.fillColor.toArcGISColor(),
                    outline = stroke,
                )
            val circle = Graphic(circleGeometry, fillSymbol)

            circleLayer.graphics.add(circle)
            circle
        }

    override suspend fun removeCircle(entity: CircleEntityInterface<ArcGISActualCircle>) {
        coroutine.launch {
            val circles = listOf(entity.circle)
            circleLayer.graphics.removeAll(circles)
        }
    }

    override suspend fun updateCircleProperties(
        circle: ArcGISActualCircle,
        current: CircleEntityInterface<ArcGISActualCircle>,
        prev: CircleEntityInterface<ArcGISActualCircle>,
    ): ArcGISActualCircle? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint
            val graphic = current.circle

            if (finger.center != prevFinger.center ||
                finger.radiusMeters != prevFinger.radiusMeters ||
                finger.geodesic != prevFinger.geodesic
            ) {
                val centerPoint = centerPoint(current.state.center, current.state.geodesic)
                val newGeometry =
                    if (current.state.geodesic) {
                        GeometryEngine.bufferGeodeticOrNull(
                            geometry = centerPoint,
                            distance = current.state.radiusMeters,
                            distanceUnit = LinearUnit(LinearUnitId.Meters),
                            maxDeviation = Double.NaN,
                            curveType = GeodeticCurveType.NormalSection,
                        )
                    } else {
                        GeometryEngine.bufferOrNull(
                            geometry = centerPoint,
                            distance = current.state.radiusMeters,
                        )
                    }
                newGeometry?.let {
                    graphic.geometry = it
                }
            }

            (graphic.symbol as SimpleFillSymbol).let { symbol ->
                if (finger.fillColor != prevFinger.fillColor) {
                    symbol.color =
                        current.state.fillColor.toArcGISColor()
                }
                symbol.outline?.let { outline ->
                    if (finger.strokeColor != prevFinger.strokeColor) {
                        outline.color =
                            current.state.strokeColor.toArcGISColor()
                    }
                    if (finger.strokeWidth != prevFinger.strokeWidth) {
                        outline.width = current.state.strokeWidth.value
                    }
                }
            }
            return@withContext graphic
        }

    /**
     * `GeoPoint.toPoint(spatialReference)` only tags raw lon/lat degrees with the given
     * spatial reference - it does not transform the coordinates - so passing the map's
     * (projected) spatial reference directly produced a point sitting at (longitude, latitude)
     * *meters* from that projection's origin, nowhere near the real location. Geodesic buffering
     * operates on the ellipsoid and wants WGS84 coordinates as-is; planar buffering needs a
     * genuinely projected point (so `radiusMeters` means meters in that projection), which
     * requires an actual reprojection via [GeometryEngine.projectOrNull].
     */
    private fun centerPoint(
        center: com.mapconductor.core.features.GeoPointInterface,
        geodesic: Boolean,
    ): Point {
        val wgs84Point = GeoPoint.from(center).toPoint(SpatialReference.wgs84())
        if (geodesic) return wgs84Point
        val mapSpatialReference = holder.spatialReference ?: return wgs84Point
        return GeometryEngine.projectOrNull(wgs84Point, mapSpatialReference) as? Point ?: wgs84Point
    }
}
