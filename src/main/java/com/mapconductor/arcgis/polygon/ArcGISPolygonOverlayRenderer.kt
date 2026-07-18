package com.mapconductor.arcgis.polygon

import com.arcgismaps.geometry.Geometry
import com.arcgismaps.geometry.MutablePart
import com.arcgismaps.geometry.PolygonBuilder
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.symbology.SimpleFillSymbol
import com.arcgismaps.mapping.symbology.SimpleFillSymbolStyle
import com.arcgismaps.mapping.symbology.SimpleLineSymbol
import com.arcgismaps.mapping.symbology.SimpleLineSymbolStyle
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.mapconductor.arcgis.ArcGISActualPolygon
import com.mapconductor.arcgis.ArcGISGeoViewHolder
import com.mapconductor.arcgis.toArcGISColor
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.polygon.AbstractPolygonOverlayRenderer
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polygon.unionHoles
import com.mapconductor.core.spherical.createInterpolatePoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArcGISPolygonOverlayRenderer(
    val polygonLayer: GraphicsOverlay,
    override val holder: ArcGISGeoViewHolder<*, *>,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractPolygonOverlayRenderer<ArcGISActualPolygon>() {
    override suspend fun createPolygon(state: PolygonState): ArcGISActualPolygon? =
        withContext(coroutine.coroutineContext) {
            val resolved = resolveHoles(state)
            val geometry = createGeometry(resolved)
            val outlineSymbol =
                SimpleLineSymbol().apply {
                    style = SimpleLineSymbolStyle.Solid
                    color = state.strokeColor.toArcGISColor()
                    width = state.strokeWidth.value
                }

            val fillSymbol =
                SimpleFillSymbol().apply {
                    style = SimpleFillSymbolStyle.Solid
                    color = state.fillColor.toArcGISColor()
                    outline = outlineSymbol
                }

            val graphic =
                Graphic(geometry, fillSymbol).also {
                    it.attributes.set("id", state.id)
                    it.attributes.set("zIndex", state.zIndex)
                }

            polygonLayer.graphics.add(graphic)
            graphic
        }

    override suspend fun updatePolygonProperties(
        polygon: ArcGISActualPolygon,
        current: PolygonEntityInterface<ArcGISActualPolygon>,
        prev: PolygonEntityInterface<ArcGISActualPolygon>,
    ): ArcGISActualPolygon? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint
            if (
                finger.points != prevFinger.points ||
                finger.holes != prevFinger.holes ||
                finger.geodesic != prevFinger.geodesic
            ) {
                val resolved = resolveHoles(current.state)
                current.polygon.geometry = createGeometry(resolved)
            }

            (current.polygon.symbol as SimpleFillSymbol).let { symbol ->
                if (finger.fillColor != prevFinger.fillColor) {
                    symbol.color = current.state.fillColor.toArcGISColor()
                }
                symbol.outline?.let { outline ->
                    if (finger.strokeColor != prevFinger.strokeColor) {
                        outline.color = current.state.strokeColor.toArcGISColor()
                    }
                    if (finger.strokeWidth != prevFinger.strokeWidth) {
                        outline.width = ResourceProvider.dpToPx(current.state.strokeWidth).toFloat()
                    }
                }
            }
            if (finger.zIndex != prevFinger.zIndex) {
                current.polygon.attributes.set("zIndex", current.state.zIndex)
            }
            polygon
        }

    override suspend fun removePolygon(entity: PolygonEntityInterface<ArcGISActualPolygon>) {
        // Launch on the renderer's own scope so the removal survives cancellation of
        // the calling sync coroutine, and remove the single graphic in place instead
        // of rebuilding the whole graphics list.
        coroutine.launch {
            polygonLayer.graphics.remove(entity.polygon)
        }
    }

    override suspend fun onPostProcess() {
        // Sort graphics by zIndex to ensure correct rendering order
        withContext(coroutine.coroutineContext) {
            val graphics = polygonLayer.graphics.toList()
            if (graphics.size <= 1) return@withContext

            val sortedGraphics =
                graphics.sortedBy { graphic ->
                    (graphic.attributes.get("zIndex") as? Int) ?: 0
                }
            if (graphics == sortedGraphics) return@withContext

            polygonLayer.graphics.clear()
            polygonLayer.graphics.addAll(sortedGraphics)
        }
    }

    private suspend fun resolveHoles(state: PolygonState): PolygonState =
        if (state.holes.size > 1) {
            withContext(Dispatchers.Default) { state.unionHoles() }
        } else {
            state
        }

    private fun createGeometry(state: PolygonState): Geometry {
        // ArcGIS polygons can become extremely dense when geodesic=true (especially for world-mask rings),
        // which may fail to render. Use a larger segment length to keep the geometry size reasonable.
        val geodesicMaxSegmentLengthMeters = 100_000.0

        fun toRing(
            points: List<GeoPointInterface>,
            geodesic: Boolean,
        ): List<GeoPointInterface> =
            when (geodesic) {
                true -> createInterpolatePoints(points, maxSegmentLength = geodesicMaxSegmentLengthMeters)
                false -> points
            }

        fun openRing(points: List<GeoPointInterface>): List<GeoPointInterface> {
            if (points.size < 2) return points
            val first = points.first()
            val last = points.last()
            return if (first.latitude == last.latitude && first.longitude == last.longitude) {
                points.dropLast(1)
            } else {
                points
            }
        }

        val outer: List<GeoPointInterface> =
            openRing(toRing(state.points, state.geodesic)).let(::ensureClockwise)
        val holes: List<List<GeoPointInterface>> =
            state.holes
                .map { ring -> openRing(toRing(ring, state.geodesic)).let(::ensureCounterClockwise) }
                .filter { it.size >= 3 }

        val spatialReference = SpatialReference.wgs84()
        val parts =
            (listOf(outer) + holes).map { ring ->
                MutablePart(spatialReference).apply {
                    ring.forEach { point ->
                        addPoint(point.longitude, point.latitude)
                    }
                }
            }
        return PolygonBuilder(parts).toGeometry()
    }

    private fun signedAreaLonLat(ring: List<GeoPointInterface>): Double {
        if (ring.size < 3) return 0.0
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += (a.longitude * b.latitude) - (b.longitude * a.latitude)
        }
        return sum / 2.0
    }

    private fun ensureClockwise(ring: List<GeoPointInterface>): List<GeoPointInterface> =
        if (signedAreaLonLat(ring) < 0.0) ring else ring.asReversed()

    private fun ensureCounterClockwise(ring: List<GeoPointInterface>): List<GeoPointInterface> =
        if (signedAreaLonLat(ring) > 0.0) ring else ring.asReversed()
}
