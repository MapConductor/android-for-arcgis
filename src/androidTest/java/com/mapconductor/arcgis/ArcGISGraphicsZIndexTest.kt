package com.mapconductor.arcgis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.arcgismaps.mapping.view.MapView
import com.mapconductor.arcgis.circle.ArcGISCircleOverlayRenderer
import com.mapconductor.arcgis.marker.ArcGISMarkerRenderer
import com.mapconductor.arcgis.polygon.ArcGISPolygonOverlayRenderer
import com.mapconductor.arcgis.polyline.ArcGISPolylineOverlayRenderer
import com.mapconductor.core.circle.CircleEntity
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.DefaultMarkerIcon
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.polygon.PolygonEntity
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.PolylineEntity
import com.mapconductor.core.polyline.PolylineState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 帯の中の重なり順は zIndex で決まり、足した順に左右されないこと。
 *
 * ArcGIS は同じ GraphicsOverlay の中を Graphic の `zIndex` の順に描き、同じ値なら足した順に描く
 * （3D・2D とも。タブレットで確かめた）。以前は zIndex をどの Graphic にも載せておらず
 * （ポリゴンは属性に書いてコレクションを並べ替えていたが、2D はそれでは描く順が変わらない）、
 * zIndex=1 を先に、zIndex=0 を後に足すと zIndex=1 が下になった。
 */
@RunWith(AndroidJUnit4::class)
class ArcGISGraphicsZIndexTest {
    private lateinit var holder: ArcGISMapView2DHolder

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ArcGISEnvironment.applicationContext = context.applicationContext
            val map = MapView(context)
            val wrap = WrapMapView(context).also { it.arcGISMapView = map }
            holder = ArcGISMapView2DHolder(wrap, map)
        }
    }

    private fun rect(offset: Double) =
        listOf(
            GeoPoint(35.0 + offset, 139.0 + offset),
            GeoPoint(35.0 + offset, 139.1 + offset),
            GeoPoint(35.1 + offset, 139.1 + offset),
            GeoPoint(35.1 + offset, 139.0 + offset),
        )

    /** ArcGIS が [upper] を [lower] より上に描くか（zIndex、同じなら足した順）。 */
    private fun drawsAbove(
        overlay: GraphicsOverlay,
        upper: Graphic,
        lower: Graphic,
    ): Boolean =
        if (upper.zIndex != lower.zIndex) {
            upper.zIndex > lower.zIndex
        } else {
            overlay.graphics.indexOfFirst { it === upper } > overlay.graphics.indexOfFirst { it === lower }
        }

    @Test
    fun polygonsStackByZIndexWhateverOrderTheyArrive() =
        runBlocking {
            val overlay = GraphicsOverlay()
            val renderer = ArcGISPolygonOverlayRenderer(overlay, holder)
            val topState = PolygonState(points = rect(0.0), id = "top", zIndex = 1)
            val top = renderer.createPolygon(topState)!!
            val bottom = renderer.createPolygon(PolygonState(points = rect(0.05), id = "bottom", zIndex = 0))!!
            renderer.onPostProcess()
            assertTrue("zIndex=1 added first must draw above zIndex=0", drawsAbove(overlay, top, bottom))
            // 2D はコレクションを並べ替えても描く順が変わらない。描く順を決めるのは Graphic の zIndex。
            assertTrue("the order must live in Graphic.zIndex", top.zIndex > bottom.zIndex)

            // zIndex を下げれば下へ。
            val lowered = PolygonState(points = rect(0.0), id = "top", zIndex = -1)
            renderer.updatePolygonProperties(top, PolygonEntity(top, lowered), PolygonEntity(top, topState))
            renderer.onPostProcess()
            assertTrue("lowered polygon must draw below", drawsAbove(overlay, bottom, top))
        }

    @Test
    fun circlesStackByZIndexWhateverOrderTheyArrive() =
        runBlocking {
            val overlay = GraphicsOverlay()
            val renderer = ArcGISCircleOverlayRenderer(overlay, holder)
            val topState = CircleState(center = GeoPoint(35.0, 139.0), radiusMeters = 1000.0, id = "top", zIndex = 1)
            val top = renderer.createCircle(topState)!!
            val bottom = renderer.createCircle(CircleState(center = GeoPoint(35.0, 139.01), radiusMeters = 1000.0, id = "bottom"))!!
            renderer.onPostProcess()
            assertTrue("zIndex=1 added first must draw above the default zIndex", drawsAbove(overlay, top, bottom))

            val lowered = CircleState(center = GeoPoint(35.0, 139.0), radiusMeters = 1000.0, id = "top", zIndex = -1)
            renderer.updateCircleProperties(top, CircleEntity(top, lowered), CircleEntity(top, topState))
            renderer.onPostProcess()
            assertTrue("lowered circle must draw below", drawsAbove(overlay, bottom, top))
        }

    @Test
    fun polylinesStackByZIndexWhateverOrderTheyArrive() =
        runBlocking {
            val overlay = GraphicsOverlay()
            val renderer = ArcGISPolylineOverlayRenderer(overlay, holder)
            val topState = PolylineState(points = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)), id = "top", zIndex = 1)
            val top = renderer.createPolyline(topState)!!
            val bottom =
                renderer.createPolyline(
                    PolylineState(points = listOf(GeoPoint(35.1, 139.0), GeoPoint(35.0, 139.1)), id = "bottom", zIndex = 0),
                )!!
            renderer.onPostProcess()
            assertTrue("zIndex=1 added first must draw above zIndex=0", drawsAbove(overlay, top, bottom))

            val lowered = PolylineState(points = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)), id = "top", zIndex = -1)
            renderer.updatePolylineProperties(top, PolylineEntity(top, lowered), PolylineEntity(top, topState))
            renderer.onPostProcess()
            assertTrue("lowered polyline must draw below", drawsAbove(overlay, bottom, top))
        }

    @Test
    fun markersStackByZIndexWhateverOrderTheyArrive() =
        runBlocking {
            val overlay = GraphicsOverlay()
            val renderer = ArcGISMarkerRenderer(overlay, holder)
            val icon = DefaultMarkerIcon().toBitmapIcon()

            fun params(state: MarkerState) =
                object : MarkerOverlayRendererInterface.AddParamsInterface {
                    override val state = state
                    override val bitmapIcon = icon
                }
            val added =
                renderer.onAdd(
                    listOf(
                        params(MarkerState(id = "top", position = GeoPoint(35.0, 139.0), zIndex = 1)),
                        params(MarkerState(id = "bottom", position = GeoPoint(35.0, 139.0001))),
                    ),
                )
            val top = added[0]!!
            val bottom = added[1]!!
            renderer.onPostProcess()
            assertTrue("zIndex=1 added first must draw above the default zIndex", drawsAbove(overlay, top, bottom))
        }

    /**
     * 同じ zIndex なら渡された順（後から渡したものが上）。ArcGIS は同じ Graphic.zIndex の並びを
     * 保証しない（2D で、zIndex=0 の 2 枚の間に zIndex=1 が挟まると後の方が下に描かれた）ので、
     * Graphic.zIndex は (zIndex, 渡された順) の順位で、どれも違う値になること。
     */
    @Test
    fun equalZIndexKeepsTheOrderTheyArrive() =
        runBlocking {
            val overlay = GraphicsOverlay()
            val renderer = ArcGISPolygonOverlayRenderer(overlay, holder)
            val hi = renderer.createPolygon(PolygonState(points = rect(0.0), id = "hi", zIndex = 1))!!
            val lo = renderer.createPolygon(PolygonState(points = rect(0.01), id = "lo", zIndex = 0))!!
            val first = renderer.createPolygon(PolygonState(points = rect(0.02), id = "a", zIndex = 0))!!
            val middle = renderer.createPolygon(PolygonState(points = rect(0.03), id = "m", zIndex = 1))!!
            val second = renderer.createPolygon(PolygonState(points = rect(0.04), id = "b", zIndex = 0))!!
            renderer.onPostProcess()
            val drawn = listOf(lo, first, second, hi, middle)
            val zs = drawn.map { it.zIndex }
            assertEquals("Graphic.zIndex must be distinct: $zs", zs.size, zs.toSet().size)
            assertEquals("bottom to top must be lo, a, b, hi, m: $zs", zs.sorted(), zs)
        }

    /** マーカーも同じ（zIndex が全部同じでも、渡された順に違う値）。 */
    @Test
    fun equalZIndexMarkersKeepTheOrderTheyArrive() =
        runBlocking {
            val overlay = GraphicsOverlay()
            val renderer = ArcGISMarkerRenderer(overlay, holder)
            val icon = DefaultMarkerIcon().toBitmapIcon()

            fun params(state: MarkerState) =
                object : MarkerOverlayRendererInterface.AddParamsInterface {
                    override val state = state
                    override val bitmapIcon = icon
                }
            val added =
                renderer.onAdd(
                    listOf("a", "b", "c").mapIndexed { i, id ->
                        params(MarkerState(id = id, position = GeoPoint(35.0, 139.0 + i * 0.0001)))
                    },
                )
            val zs = added.map { it!!.zIndex }
            assertEquals("Graphic.zIndex must be distinct: $zs", 3, zs.toSet().size)
            assertEquals("later markers must draw above: $zs", zs.sorted(), zs)
        }

    /** zIndex だけ変えたとき、同じ zIndex の中では最初に渡された順を保つ。外して足し直すと一番上。 */
    @Test
    fun zIndexChangeAndReAddKeepTheRules() =
        runBlocking {
            val overlay = GraphicsOverlay()
            val renderer = ArcGISCircleOverlayRenderer(overlay, holder)

            fun state(
                id: String,
                z: Int,
            ) = CircleState(center = GeoPoint(35.0, 139.0), radiusMeters = 1000.0, id = id, zIndex = z)
            val aState = state("a", 0)
            val a = renderer.createCircle(aState)!!
            val b = renderer.createCircle(state("b", 0))!!
            val cState = state("c", 5)
            val c = renderer.createCircle(cState)!!
            // c を 0 へ: a, b, c の順（c は最後に渡された）。
            renderer.updateCircleProperties(c, CircleEntity(c, state("c", 0)), CircleEntity(c, cState))
            assertTrue(a.zIndex < b.zIndex && b.zIndex < c.zIndex)
            // a を 0 → 2 → 0 と動かしても、0 の中では最初の順（一番下）に戻る。
            renderer.updateCircleProperties(a, CircleEntity(a, state("a", 2)), CircleEntity(a, aState))
            assertTrue(a.zIndex > c.zIndex)
            renderer.updateCircleProperties(a, CircleEntity(a, aState), CircleEntity(a, state("a", 2)))
            assertTrue(a.zIndex < b.zIndex)
            // b を外して足し直すと、zIndex=0 の中で一番上。
            renderer.removeCircle(CircleEntity(b, state("b", 0)))
            val b2 = renderer.createCircle(state("b", 0))!!
            assertTrue(c.zIndex < b2.zIndex)
            val zs = listOf(a, c, b2).map { it.zIndex }
            assertEquals(zs.toSet().size, 3)
        }
}
