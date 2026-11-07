package com.mapconductor.arcgis

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.AttributionRule
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapUISettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Bundle
import android.os.Parcel

/**
 * 回転などの構成変更で state が [ArcGISMapViewSaver] を通って作り直されても、デザイン（id・
 * attributionRules ほか）と uiSettings・カメラが落ちないこと。Bundle は実機の Parcel を一度通す。
 */
@RunWith(AndroidJUnit4::class)
class ArcGISMapViewSaverTest {
    private val rules =
        listOf(AttributionRule("© test"), AttributionRule("© ranged", minZoom = 3, maxZoom = 15))

    private val camera =
        MapCameraPosition(
            position = GeoPoint(21.38, -157.93),
            zoom = 12.0,
            bearing = 20.0,
            tilt = 10.0,
        )

    @Test
    fun keepsElevationSourcesAndAttributionRules() {
        val design =
            ArcGISDesign.Imagery.copy(
                elevationSources = listOf("https://elevation.example.com/ImageServer"),
                attributionRules = rules,
            )
        val state = ArcGISMapViewState(mapDesignType = design, id = "arcgis-1", cameraPosition = camera)
        state.uiSettings = MapUISettings(rotateGesture = false, tiltGesture = false)

        val restored = roundTrip(ArcGISMapViewSaver().createSaver(), state)

        // 以前は id しか保存しておらず、elevationSources と attributionRules が落ちていた。
        assertEquals(design, restored.mapDesignType)
        assertCommon(restored, "arcgis-1")
    }

    @Test
    fun keepsPresetDesign() {
        val state = ArcGISMapViewState(mapDesignType = ArcGISDesign.DarkGray, id = "arcgis-2")
        assertEquals(ArcGISDesign.DarkGray, roundTrip(ArcGISMapViewSaver().createSaver(), state).mapDesignType)
    }

    private fun assertCommon(
        restored: com.mapconductor.core.map.MapViewStateInterface<*>,
        id: String,
    ) {
        assertEquals(id, restored.id)
        assertEquals(false, restored.uiSettings.rotateGesture)
        assertEquals(false, restored.uiSettings.tiltGesture)
        assertEquals(true, restored.uiSettings.scrollGesture)
        assertEquals(12.0, restored.cameraPosition.zoom, 1e-9)
        assertEquals(20.0, restored.cameraPosition.bearing, 1e-9)
        assertEquals(21.38, restored.cameraPosition.position.latitude, 1e-9)
    }

    private fun <T : Any> roundTrip(
        saver: Saver<T, Bundle>,
        value: T,
    ): T {
        val saved = with(saver) { SaverScope { true }.save(value) }!!
        // 一度 Parcel に書いて読み戻す（プロセスが落ちたあとの復元と同じ経路）。
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            return saver.restore(parcel.readBundle(javaClass.classLoader)!!)!!
        } finally {
            parcel.recycle()
        }
    }
}
