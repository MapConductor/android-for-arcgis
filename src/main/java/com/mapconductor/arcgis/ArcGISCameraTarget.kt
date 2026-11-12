package com.mapconductor.arcgis

import com.arcgismaps.mapping.view.Camera
import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPoint
import kotlin.math.tan

// 公開 API のスナップショットから外すため、注釈付きの関数だけをこのファイルに置く
// （注釈はファイル単位のクラスごと除外されるので、他の公開関数と同居させない）。

/**
 * カメラが見ている地上の点（画面中央）。
 *
 * ArcGIS の [Camera] が持つのは**目の位置**で、傾けると見ている点は目の真下から向きの方へ
 * `高さ × tan(pitch)` 先になる（カメラを置くときの [calculateCameraForOrbitParameters] の逆）。
 * 目の位置をそのまま MapCameraPosition.position にすると、tilt 60° では数 km 手前の点を
 * 返してしまう。地形の高さは見ないので、起伏のある所では画面中央の逆投影の方が正確。
 */
@InternalMapConductorApi
internal fun Camera.lookAtTarget(): GeoPoint {
    val altitude = location.z ?: 0.0
    val pitchDeg = pitch.coerceIn(MIN_ANGLE, ARCGIS_MAX_PITCH - 1.0)
    if (pitchDeg == 0.0 || altitude <= 0.0) {
        return GeoPoint.fromLongLat(longitude = location.x, latitude = location.y)
    }
    return calculateDestinationPoint(
        lat = location.y,
        lon = location.x,
        bearing = heading,
        distance = altitude * tan(pitchDeg.toRadians()),
    )
}
