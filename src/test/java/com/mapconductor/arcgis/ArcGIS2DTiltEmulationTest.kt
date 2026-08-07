package com.mapconductor.arcgis

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.spherical.Spherical
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 2D MapView の tilt 擬似表現（[ArcGIS2DTiltEmulation]）のテスト。
 * ios-for-arcgis の `ArcGIS2DTiltEmulationTests.swift` と同じ観点。
 */
class ArcGIS2DTiltEmulationTest {
    private val tokyo = GeoPoint(latitude = 35.6812, longitude = 139.7671)

    private fun camera(
        tilt: Double,
        bearing: Double = 0.0,
        zoom: Double = 14.0,
    ) = MapCameraPosition(position = tokyo, zoom = zoom, bearing = bearing, tilt = tilt)

    /**
     * tilt >= 0 は完全な素通し。見た目の傾きは WrapMapView.visualTilt が
     * MapView 自体を回して作るので、中心もズームも触らない。
     */
    @Test
    fun nonNegativeTilt_isPassedThrough() {
        for (tilt in listOf(0.0, 30.0, 60.0)) {
            val (center, zoom) = ArcGIS2DTiltEmulation.shiftedCamera(camera(tilt, bearing = 45.0))
            assertEquals("tilt=$tilt", tokyo.latitude, center.latitude, 1e-9)
            assertEquals("tilt=$tilt", tokyo.longitude, center.longitude, 1e-9)
            assertEquals("tilt=$tilt", 14.0, zoom, 1e-9)
        }
    }

    /** 正の tilt の往復は恒等変換。 */
    @Test
    fun positiveTilt_roundTripsAsIdentity() {
        for (tilt in listOf(15.0, 30.0, 45.0, 60.0)) {
            val (center, zoom) = ArcGIS2DTiltEmulation.shiftedCamera(camera(tilt, bearing = 30.0))
            val (restored, restoredZoom) =
                ArcGIS2DTiltEmulation.restoreLogicalCamera(
                    center = center,
                    zoom = zoom,
                    bearing = 30.0,
                    logicalTilt = tilt,
                )
            assertEquals("tilt=$tilt", 14.0, restoredZoom, 1e-9)
            assertEquals("tilt=$tilt", tokyo.latitude, restored.latitude, 1e-9)
            assertEquals("tilt=$tilt", tokyo.longitude, restored.longitude, 1e-9)
        }
    }

    @Test
    fun negativeTilt_shiftsCenterAlongBearingAndPullsZoomOut() {
        val (center, zoom) = ArcGIS2DTiltEmulation.shiftedCamera(camera(-60.0))

        assertTrue(center.latitude > tokyo.latitude) // bearing 0 = 真北
        assertEquals(tokyo.longitude, center.longitude, 1e-6)
        assertEquals(14.0 - 0.9, zoom, 1e-9)
    }

    @Test
    fun shiftDirection_followsBearing() {
        val (east, _) = ArcGIS2DTiltEmulation.shiftedCamera(camera(-45.0, bearing = 90.0))
        assertTrue(east.longitude > tokyo.longitude)
        // 大円に沿って東進するため緯度はわずかに下がる
        assertTrue(abs(east.latitude - tokyo.latitude) < 1e-3)

        val (south, _) = ArcGIS2DTiltEmulation.shiftedCamera(camera(-45.0, bearing = 180.0))
        assertTrue(south.latitude < tokyo.latitude)
    }

    @Test
    fun shift_growsWithTiltMagnitude() {
        var previous = 0.0
        for (tilt in listOf(-15.0, -30.0, -45.0, -60.0)) {
            val (center, _) = ArcGIS2DTiltEmulation.shiftedCamera(camera(tilt))
            val distance = Spherical.computeDistanceBetween(tokyo, center)
            assertTrue("tilt=$tilt", distance > previous)
            previous = distance
        }
    }

    /**
     * 往復でズームは厳密に、位置はほぼ元へ戻る。位置が厳密に一致しないのは、前進時は元の
     * 緯度、復元時はシフト後の緯度で高度を計算するため（TomTom / MapLibre と同じ性質）。
     */
    @Test
    fun roundTrip_restoresLogicalCamera() {
        for (tilt in listOf(-15.0, -30.0, -45.0, -60.0)) {
            for (bearing in listOf(0.0, 90.0, 217.0)) {
                val label = "tilt=$tilt bearing=$bearing"
                val (shiftedCenter, shiftedZoom) =
                    ArcGIS2DTiltEmulation.shiftedCamera(camera(tilt, bearing))
                val (restored, restoredZoom) =
                    ArcGIS2DTiltEmulation.restoreLogicalCamera(
                        center = shiftedCenter,
                        zoom = shiftedZoom,
                        bearing = bearing,
                        logicalTilt = tilt,
                    )

                assertEquals(label, 14.0, restoredZoom, 1e-9)
                val drift = Spherical.computeDistanceBetween(tokyo, restored)
                assertTrue("$label: 復元位置のズレ ${drift}m", drift < 30.0)
            }
        }
    }

    /** 非負 tilt の復元は何もしない。 */
    @Test
    fun restore_isNoOpForNonNegativeTilt() {
        val (position, zoom) =
            ArcGIS2DTiltEmulation.restoreLogicalCamera(
                center = tokyo,
                zoom = 14.0,
                bearing = 30.0,
                logicalTilt = 45.0,
            )
        assertEquals(tokyo.latitude, position.latitude, 1e-9)
        assertEquals(tokyo.longitude, position.longitude, 1e-9)
        assertEquals(14.0, zoom, 1e-9)
    }
}
