package com.mapconductor.arcgis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.view.MapView
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.projection.ProjectedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2D の投影座標は地図の空間参照で返る。
 *
 * サンプルの 2D 地図は Web Mercator 固定なので、それ以外の空間参照（UTM・WGS84）は
 * ここでしか通らない。
 */
@RunWith(AndroidJUnit4::class)
class ArcGISProjectedPointTest {
    private val tokyo = GeoPoint(35.681236, 139.767125)

    private fun holderWith(spatialReference: SpatialReference): ArcGISMapView2DHolder {
        lateinit var holder: ArcGISMapView2DHolder
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ArcGISEnvironment.applicationContext = context.applicationContext
            val map = MapView(context)
            map.map = ArcGISMap(spatialReference)
            val wrap = WrapMapView(context).also { it.arcGISMapView = map }
            holder = ArcGISMapView2DHolder(wrap, map)
        }
        return holder
    }

    @Test
    fun utmZone54NReturnsEastingNorthingInMeters() {
        val holder = holderWith(SpatialReference(32654))
        assertEquals("EPSG:32654", holder.coordinateSystem.code)
        assertEquals(ProjectedUnit.Meters, holder.coordinateSystem.unit)

        val projected = holder.toProjectedPoint(tokyo)
        assertNotNull(projected)
        // 東京駅は中央子午線（東経 141 度）の西 1.23 度: 偽東距 500000 m から約 11 万 m 西。
        assertTrue("easting ${projected!!.x}", projected.x in 380_000.0..400_000.0)
        assertTrue("northing ${projected.y}", projected.y in 3_940_000.0..3_960_000.0)

        val back = holder.fromProjectedPoint(projected)
        assertNotNull(back)
        assertEquals(tokyo.latitude, back!!.latitude, 1e-7)
        assertEquals(tokyo.longitude, back.longitude, 1e-7)
    }

    @Test
    fun wgs84ReturnsDegrees() {
        val holder = holderWith(SpatialReference.wgs84())
        assertEquals("EPSG:4326", holder.coordinateSystem.code)
        assertEquals(ProjectedUnit.Degrees, holder.coordinateSystem.unit)

        val projected = holder.toProjectedPoint(tokyo)!!
        assertEquals(tokyo.longitude, projected.x, 1e-9)
        assertEquals(tokyo.latitude, projected.y, 1e-9)
    }

    @Test
    fun webMercatorMatchesTheCoreDefault() {
        val holder = holderWith(SpatialReference.webMercator())
        assertEquals("EPSG:3857", holder.coordinateSystem.code)
        assertEquals(ProjectedUnit.Meters, holder.coordinateSystem.unit)

        val projected = holder.toProjectedPoint(tokyo)!!
        assertEquals(15558805.18, projected.x, 0.01)
        assertEquals(4256848.12, projected.y, 0.01)
    }
}
