package com.mapconductor.arcgis

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapPaddings
import com.mapconductor.core.map.VisibleRegion
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// カメラの読み取りと通知（2D の MapView 版）。
// 3D の ArcGISMapViewControllerCamera.kt と同じ形だが、こちらは Viewpoint
// ベースで tilt を持たない。「止まった」はデバウンスで作る。
internal suspend fun ArcGISMapView2DController.onViewpointChange() {
    emitMapInitialized()

    getMapCameraPosition()?.let { mapCameraPosition ->
        emitCameraPosition(mapCameraPosition)
        scheduleCameraMoveEndCallback()
    }
}

internal suspend fun ArcGISMapView2DController.invokeCameraMoveStartCallback() {
    cameraMoveStartHandler()?.let { cb ->
        getMapCameraPosition()?.let(cb)
    }
}

internal suspend fun ArcGISMapView2DController.invokeCameraMoveCallback() {
    cameraMoveHandler()?.let { cb ->
        getMapCameraPosition()?.let(cb)
    }
    scheduleCameraMoveEndCallback()
}

internal suspend fun ArcGISMapView2DController.invokeCameraMoveEndCallback() {
    val mapCameraPosition = getMapCameraPosition() ?: return
    // 範囲制限に違反していれば矩形内へ引き戻す（ArcGIS はカメラ中心基準の範囲制限 API を
    // 持たないため）。再適用すると viewpointChanged が再発火し、そこでは補正不要になり
    // 通常フローへ進む。3D 側（ArcGISMapViewController）と同一仕様。
    correctForCameraRestriction(mapCameraPosition)?.let { corrected ->
        moveCamera(corrected)
        return
    }
    emitCameraMoveEnd(mapCameraPosition)
}

internal fun ArcGISMapView2DController.scheduleCameraMoveEndCallback() {
    if (!needsCameraMoveEndWork()) return
    cameraMoveEndJob?.cancel()
    cameraMoveEndJob =
        defaultCoroutine.launch {
            delay(cameraMoveEndDebounceMs.milliseconds)
            invokeCameraMoveEndCallback()
        }
}

internal suspend fun ArcGISMapView2DController.getMapCameraPosition(): MapCameraPosition? {
    val mapWidth = holder.map.width.toFloat() - 1.0f
    val mapHeight = holder.map.height.toFloat() - 1.0f
    val nearLeft = holder.fromScreenOffset(Offset(1.0f, mapHeight)) ?: return null
    val nearRight = holder.fromScreenOffsetSync(Offset(mapWidth, mapHeight)) ?: return null
    val farLeft = holder.fromScreenOffsetSync(Offset(1.0f, 1.0f)) ?: return null
    val farRight = holder.fromScreenOffsetSync(Offset(mapWidth, 1.0f)) ?: return null
    val center = holder.fromScreenOffsetSync(Offset(mapWidth / 2.0f, mapHeight / 2.0f)) ?: return null

    val bounds = GeoRectBounds()
    bounds.extend(nearLeft)
    bounds.extend(nearRight)
    bounds.extend(farLeft)
    bounds.extend(farRight)

    val visibleRegion =
        VisibleRegion(
            bounds = bounds,
            nearLeft = nearLeft,
            nearRight = nearRight,
            farLeft = farLeft,
            farRight = farRight,
        )

    // 2D はカメラピッチを持てないため tilt は擬似表現（[ArcGIS2DTiltEmulation]）。
    // 直近に要求した論理 tilt を手掛かりに、前進させた中心とズームを巻き戻して返す。
    val bearing = ((holder.map.mapRotation.value % 360) + 360) % 360
    val logicalTilt = lastLogicalCameraPosition?.tilt ?: 0.0
    val (restoredCenter, restoredZoom) =
        ArcGIS2DTiltEmulation.restoreLogicalCamera(
            center = center,
            zoom = scaleToZoom(holder.map.mapScale.value),
            bearing = bearing,
            logicalTilt = logicalTilt,
        )

    return MapCameraPosition(
        position = restoredCenter,
        zoom = restoredZoom,
        bearing = bearing,
        tilt = logicalTilt,
        paddings = MapPaddings.Zeros,
        visibleRegion = visibleRegion,
    )
}

internal fun ArcGISMapView2DController.handleMoveCamera(position: MapCameraPosition) {
    lastLogicalCameraPosition = position
    // 2D はカメラピッチを持てないので、ビュー自体を傾けて遠近感を作る（WrapMapView.visualTilt）。
    // tilt が変わると MapView のサイズも変わるため、ビューポートは必ずレイアウト確定後に
    // 適用する（先に適用すると ArcGIS がリサイズで表示範囲を取り直して縮尺がずれる）。
    val tiltChanged = holder.mapView.visualTilt != position.tilt
    holder.mapView.visualTilt = position.tilt
    // ビューを傾けると地図の中身は縦に潰れる。マーカーだけは立って見えるよう、
    // アイコンを先に縦へ引き伸ばす（他のオーバーレイは寝たままでよい）。
    if (tiltChanged) markerController.refreshVerticalStretch()
    val viewpoint = toViewpoint(position)
    val applyViewpoint = {
        mainCoroutine.launch {
            if (!holder.mapView.isAttachedToWindow) return@launch
            holder.map.setViewpoint(viewpoint)
        }
        Unit
    }
    if (tiltChanged) holder.mapView.post { applyViewpoint() } else applyViewpoint()
}

internal fun ArcGISMapView2DController.handleAnimateCamera(
    position: MapCameraPosition,
    duration: Long,
) {
    lastLogicalCameraPosition = position
    val tiltChanged = holder.mapView.visualTilt != position.tilt
    holder.mapView.visualTilt = position.tilt
    if (tiltChanged) markerController.refreshVerticalStretch()
    val viewpoint = toViewpoint(position)
    val animate = {
        defaultCoroutine.launch {
            invokeCameraMoveStartCallback()
            mainCoroutine.launch {
                if (!holder.mapView.isAttachedToWindow) return@launch
                holder.map.setViewpointAnimated(
                    viewpoint = viewpoint,
                    durationSeconds = duration.toFloat() / 1000.0f,
                )
            }
            scheduleCameraMoveEndCallback()
        }
        Unit
    }
    // tilt 変更時はレイアウト確定を待つ（handleMoveCamera と同じ理由）。
    if (tiltChanged) holder.mapView.post { animate() } else animate()
}

internal fun ArcGISMapView2DController.handleFitBounds(
    bounds: GeoRectBounds,
    padding: Int,
) {
    val envelope = bounds.toEnvelope() ?: return

    mainCoroutine.launch {
        if (holder.mapView.isAttachedToWindow) {
            // padding は setViewpointGeometry の第2引数（デバイス非依存ピクセル）へ渡す。
            holder.map.setViewpointGeometry(envelope, padding.toDouble())
        }
    }
}
