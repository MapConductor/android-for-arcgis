package com.mapconductor.arcgis

import androidx.compose.ui.geometry.Offset
import com.arcgismaps.mapping.Viewpoint
import com.arcgismaps.mapping.view.Camera
import com.mapconductor.arcgis.zoom.ZoomAltitudeConverter
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapPaddings
import com.mapconductor.core.map.VisibleRegion
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// カメラの読み取りと通知。
// ArcGIS は連続的な viewpoint 変化しか通知しないので、「止まった」は
// デバウンス（cameraMoveEndDebounceMs）で作っている。範囲制限もネイティブ
// API が無く、停止時に矩形内へクランプして再適用する。
internal fun ArcGISMapViewController.getFastMapCameraPosition(): MapCameraPosition? =
    try {
        holder.map.getCurrentViewpointCamera()?.toMapCameraPosition()
    } catch (_: Exception) {
        null
    }

internal fun ArcGISMapViewController.invokeCameraMoveStartCallback() {
    cameraMoveStartHandler()?.let { cb ->
        getFastMapCameraPosition()?.let { mapCameraPosition ->
            cb(mapCameraPosition)
        }
    }
}

internal fun ArcGISMapViewController.invokeCameraMoveCallback() {
    cameraMoveHandler()?.let { cb ->
        getFastMapCameraPosition()?.let { mapCameraPosition ->
            cb(mapCameraPosition)
        }
    }
    scheduleCameraMoveEndCallback()
}

internal suspend fun ArcGISMapViewController.invokeCameraMoveEndCallback() {
    val mapCameraPosition = getMapCameraPosition() ?: return
    // 範囲・ズーム制限に違反していれば矩形内へ引き戻す（ArcGIS はネイティブの範囲制限 API が無いため）。
    // 再適用すると viewpointChanged が再発火し、そこでは補正不要になり通常フローへ進む。
    correctForCameraRestriction(mapCameraPosition)?.let { corrected ->
        moveCamera(corrected)
        return
    }
    emitCameraMoveEnd(mapCameraPosition)
}

internal fun ArcGISMapViewController.scheduleCameraMoveEndCallback() {
    if (!needsCameraMoveEndWork()) return
    cameraMoveEndJob?.cancel()
    cameraMoveEndJob =
        defaultCoroutine.launch {
            delay(cameraMoveEndDebounceMs.milliseconds)
            invokeCameraMoveEndCallback()
        }
}

internal fun ArcGISMapViewController.currentViewportSizeInDp(): Pair<Int, Int> {
    val density =
        holder.mapView.resources.displayMetrics.density
            .coerceAtLeast(0.1f)
    val widthDp = (holder.map.width / density).toInt().coerceAtLeast(1)
    val heightDp = (holder.map.height / density).toInt().coerceAtLeast(1)
    return Pair(widthDp, heightDp)
}

internal suspend fun ArcGISMapViewController.onViewpointChange() {
    emitMapInitialized()

    getFastMapCameraPosition()?.let { mapCameraPosition ->
        emitCameraPosition(mapCameraPosition)
        scheduleCameraMoveEndCallback()
    }
}

internal suspend fun ArcGISMapViewController.getMapCameraPosition(): MapCameraPosition? {
    val mapWidth = holder.map.width.toFloat() - 1.0f
    val mapHeight = holder.map.height.toFloat() - 1.0f
    val nearLeft =
        holder.fromScreenOffset(
            Offset(1.0f, mapHeight),
        ) ?: return null

    val nearRight =
        holder.fromScreenOffsetSync(
            Offset(mapWidth, mapHeight),
        ) ?: return null
    val farLeft =
        holder.fromScreenOffsetSync(
            Offset(1.0f, 1.0f),
        ) ?: return null
    val farRight =
        holder.fromScreenOffsetSync(
            Offset(mapWidth, 1.0f),
        ) ?: return null

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

    val arcCamera = holder.map.getCurrentViewpointCamera() ?: return null
    val lat = arcCamera.location.y
    val lon = arcCamera.location.x
    val alt = arcCamera.location.z ?: 0.0
    val tilt = arcCamera.pitch
    val bearing = ((arcCamera.heading % 360) + 360) % 360

    val conv = ZoomAltitudeConverter()
    val (viewportWidthDp, viewportHeightDp) = currentViewportSizeInDp()
    val zoom =
        conv.altitudeToZoomLevel(
            altitude = alt,
            latitude = lat,
            tilt = tilt,
            viewportWidthPx = viewportWidthDp,
            viewportHeightPx = viewportHeightDp,
        )

    val camera =
        MapCameraPosition(
            position =
                GeoPoint
                    .fromLongLat(lon, lat, alt),
            zoom = zoom,
            bearing = bearing,
            tilt = tilt,
            paddings = MapPaddings.Zeros,
            visibleRegion = visibleRegion,
        )
    return camera
}

internal fun ArcGISMapViewController.handleMoveCamera(position: MapCameraPosition) {
    val dstCameraPosition = toCameraWithView(position)

    mainCoroutine.launch {
        if (!holder.mapView.isAttachedToWindow) return@launch
        holder.map.setViewpointCamera(camera = dstCameraPosition)
    }
}

internal fun ArcGISMapViewController.handleAnimateCamera(
    position: MapCameraPosition,
    duration: Long,
) {
    val dstCameraPosition = toCameraWithView(position)

    defaultCoroutine.launch {
        invokeCameraMoveStartCallback()
        mainCoroutine.launch {
            if (!holder.mapView.isAttachedToWindow) return@launch
            holder.map.setViewpointCameraAnimated(
                camera = dstCameraPosition,
                duration = duration.toFloat() / 1000.0f,
            )
        }
        scheduleCameraMoveEndCallback()
    }
}

internal fun ArcGISMapViewController.handleFitBounds(
    bounds: GeoRectBounds,
    padding: Int,
) {
    val envelope = bounds.toEnvelope() ?: return

    mainCoroutine.launch {
        if (!holder.mapView.isAttachedToWindow) return@launch
        // NOTE: 3D の SceneView は setViewpointGeometry(geometry, padding) を持たず、
        // GeoView.setViewpoint(Viewpoint) には padding の概念が無いため padding は反映できない。
        // （padding 対応は 2D の ArcGISMapView2DController のみ。）
        holder.map.setViewpoint(Viewpoint(envelope))
    }
}

internal fun ArcGISMapViewController.toCameraWithView(position: MapCameraPosition): Camera {
    val targetPoint =
        GeoPoint
            .from(position.position)
            .toPoint()
    val conv = ZoomAltitudeConverter()
    val (viewportWidthDp, viewportHeightDp) = currentViewportSizeInDp()
    val distance =
        conv.zoomLevelToDistance(
            zoomLevel = position.zoom,
            latitude = position.position.latitude,
            viewportWidthPx = viewportWidthDp,
            viewportHeightPx = viewportHeightDp,
        )
    return calculateCameraForOrbitParameters(
        targetPoint = targetPoint,
        distance = distance,
        cameraHeadingOffset = position.bearing + 180,
        cameraPitchOffset = position.tilt,
    )
}
