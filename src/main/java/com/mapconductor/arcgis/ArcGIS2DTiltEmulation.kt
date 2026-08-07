package com.mapconductor.arcgis

import com.mapconductor.arcgis.zoom.ZoomAltitudeConverter
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan

/**
 * ArcGIS の 2D [com.arcgismaps.mapping.view.MapView] 向けの tilt 擬似表現。
 *
 * 2D は Viewpoint（中心 + 縮尺 + 回転）ベースでカメラピッチを一切持てない。3D の SceneView
 * （[ArcGISMapViewController]）は calculateCameraForOrbitParameters で実際にピッチできるが、
 * 2D では遠近感を作れないため、**カメラが見ている地表範囲**を幾何的に近似する。
 *
 * 見た目の傾き（遠近感）は ArcGISMapView2D が MapView 自体を graphicsLayer で傾けて作る
 * （react-for-leaflet が CSS rotateX で行うのと同じ方式）。ここが受け持つのは
 * **カメラ位置の付け替え**だけ:
 *
 * - tilt >= 0: 指定位置は**ターゲット**（画面中心）でカメラが後方へ下がるだけなので、
 *   中心もズームも変えない。傾きは見た目の変換だけで表現される。
 * - tilt < 0: 指定位置は**カメラ位置**で、ターゲットが進行方向（bearing）へ前進する。
 *   前進量とズームオフセットは MapLibre / TomTom / Leaflet と同一の式・同一定数。
 *
 * ios-for-arcgis の `ArcGIS2DTiltEmulation.swift` と同一ロジック。
 */
internal object ArcGIS2DTiltEmulation {
    /** MapLibre / TomTom / Leaflet と同一値。プロバイダ間で挙動を揃えるため変えないこと。 */
    private const val TARGET_DISTANCE_SCALE = 1.83
    private const val ZOOM_OFFSET_AT_MAX_TILT = -0.9

    /**
     * 高度の算出にはプラットフォーム非依存の既定値（Google Maps 較正）を使う。
     *
     * [ZoomAltitudeConverter] の既定値（Android 136_500_000 / iOS 141_600_000）は
     * **3D SceneView の実効画角**を合わせるための較正値で、画角を持たない 2D MapView には
     * 当てはまらない。既定値を使うことでシフト量が iOS と厳密に一致する。
     */
    private val converter = ZoomAltitudeConverter(AbstractZoomAltitudeConverter.DEFAULT_ZOOM0_ALTITUDE)

    /**
     * 論理カメラ → 実際に Viewpoint へ渡す中心・ズーム。
     *
     * tilt < 0 のときだけ中心を進行方向へ前進させ、ズームを引く。tilt >= 0 は入力のまま
     * （position が**ターゲット**＝画面中心で、カメラが後方へ下がるだけのため）。
     */
    fun shiftedCamera(position: MapCameraPosition): Pair<GeoPointInterface, Double> {
        if (position.tilt >= 0) return position.position to position.zoom

        val tiltAbsDeg = abs(position.tilt).coerceIn(0.0, 60.0)
        val zoom = position.zoom + ZOOM_OFFSET_AT_MAX_TILT * (tiltAbsDeg / 60.0)
        val tiltAbsRad = Math.toRadians(tiltAbsDeg)
        val altitude = converter.zoomLevelToAltitude(position.zoom, position.position.latitude, 0.0)
        val distanceForward = altitude * cos(tiltAbsRad) * tan(tiltAbsRad) * TARGET_DISTANCE_SCALE
        val target = Spherical.computeOffset(position.position, distanceForward, position.bearing)
        return target to zoom
    }

    /**
     * Viewpoint から読み戻した中心・ズームを論理カメラへ戻す。
     *
     * tilt < 0 のときだけ中心とズームを巻き戻す。
     *
     * @param logicalTilt 直近に要求した論理 tilt。
     * @param bearing 前進に使った方位。
     */
    fun restoreLogicalCamera(
        center: GeoPointInterface,
        zoom: Double,
        bearing: Double,
        logicalTilt: Double,
    ): Pair<GeoPointInterface, Double> {
        val tiltAbsDeg = abs(logicalTilt).coerceIn(0.0, 60.0)
        if (logicalTilt >= 0 || tiltAbsDeg == 0.0) return center to zoom

        val originalZoom = zoom - ZOOM_OFFSET_AT_MAX_TILT * (tiltAbsDeg / 60.0)
        val tiltAbsRad = Math.toRadians(tiltAbsDeg)
        val altitude = converter.zoomLevelToAltitude(originalZoom, center.latitude, 0.0)
        val distanceBackward = altitude * cos(tiltAbsRad) * tan(tiltAbsRad) * TARGET_DISTANCE_SCALE
        val originalPosition: GeoPoint = Spherical.computeOffset(center, distanceBackward, bearing + 180.0)
        return originalPosition to originalZoom
    }
}
