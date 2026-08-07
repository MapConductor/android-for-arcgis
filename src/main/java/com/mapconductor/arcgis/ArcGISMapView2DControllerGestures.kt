package com.mapconductor.arcgis

import com.arcgismaps.mapping.view.LongPressEvent
import com.arcgismaps.mapping.view.PanChangeEvent
import com.arcgismaps.mapping.view.SingleTapConfirmedEvent
import com.arcgismaps.mapping.view.UpEvent
import com.arcgismaps.mapping.view.extensions.motionEvent
import com.mapconductor.arcgis.marker.ArcGISMarkerRenderer
import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.groundimage.GroundImageEvent
import com.mapconductor.core.polygon.PolygonEvent
import com.mapconductor.core.polyline.PolylineEvent
import com.mapconductor.settings.Settings
import android.view.MotionEvent

// タップ・長押し・パンの処理（2D の MapView 版）。
// タップは**マーカーが先**で、どのマーカーにも当たらなかったときだけ
// 地図のタップとして扱う。
internal suspend fun ArcGISMapView2DController.onMapPan(event: PanChangeEvent) {
    val controller = activeDragController
    if (controller != null) {
        val point = holder.map.screenToLocation(event.screenCoordinate) ?: return
        val position = point.toGeoPoint()
        controller.updateDrag(point, position)
        controller.getSelectedState()?.let { state -> controller.dispatchDrag(state) }
        return
    }
    invokeCameraMoveCallback()
}

internal fun ArcGISMapView2DController.onMapUp(event: UpEvent) {
    val controller = activeDragController
    if (controller != null) {
        val point = event.mapPoint ?: holder.map.screenToLocation(event.screenCoordinate) ?: return
        val position = point.toGeoPoint()
        val selectedState = controller.getSelectedState()
        controller.endDrag(point, position)
        selectedState?.let { state -> controller.dispatchDragEnd(state) }
        activeDragController = null
        holder.setNavigationEnabled(true)
    }
}

internal suspend fun ArcGISMapView2DController.onMapLongPress(event: LongPressEvent) {
    if (event.motionEvent.action != MotionEvent.ACTION_MOVE) return

    val screenPoint = event.screenCoordinate
    val point = event.mapPoint ?: holder.map.screenToLocation(screenPoint) ?: return
    val position = point.toGeoPoint()
    val identifyResult =
        holder.map.identifyGraphicsOverlay(
            graphicsOverlay = (markerController.renderer as ArcGISMarkerRenderer).markerLayer,
            screenCoordinate = screenPoint,
            tolerance =
                Settings.Default.tapTolerance.value
                    .toDouble(),
            returnPopupsOnly = false,
        )
    val graphics = identifyResult.getOrNull()?.graphics
    graphics?.firstOrNull()?.let { graphic ->
        (graphic.attributes["id"] as? String)?.let { markerId ->
            markerController.markerManager.getEntity(markerId)?.let { entity ->
                if (entity.state.draggable) {
                    activeDragController = markerEventControllers.firstOrNull()
                    activeDragController?.startDrag(entity)
                    holder.setNavigationEnabled(false)
                    activeDragController?.dispatchDragStart(entity.state)
                    return
                }
            }
        }
    }
    markerEventControllers
        .drop(1)
        .forEach { controller ->
            controller.find(position)?.let { entity ->
                if (entity.state.draggable) {
                    activeDragController = controller
                    controller.startDrag(entity)
                    holder.setNavigationEnabled(false)
                    controller.dispatchDragStart(entity.state)
                    return
                }
            }
        }
    emitMapLongClick(position)
}

internal fun ArcGISMapView2DController.onMapTap(event: SingleTapConfirmedEvent) {
    val screenPoint = event.screenCoordinate
    val touchPosition = holder.map.screenToLocation(screenPoint)?.toGeoPoint() ?: return

    markerEventControllers.forEach { controller ->
        controller.find(touchPosition)?.let { markerEntity ->
            controller.dispatchClick(markerEntity.state)
            return
        }
    }

    circleController.find(touchPosition)?.let { circleEntity ->
        circleController.dispatchClick(CircleEvent(state = circleEntity.state, clicked = touchPosition))
        return
    }

    groundImageController.find(touchPosition)?.let { entity ->
        groundImageController.dispatchClick(GroundImageEvent(state = entity.state, clicked = touchPosition))
        return
    }

    polylineController.findWithClosestPoint(touchPosition)?.let { hitResult ->
        polylineController.dispatchClick(
            PolylineEvent(
                state = hitResult.entity.state,
                clicked = hitResult.closestPoint,
            ),
        )
        return
    }

    polygonController.find(touchPosition)?.let { polygonEntity ->
        polygonController.dispatchClick(PolygonEvent(state = polygonEntity.state, clicked = touchPosition))
        return
    }

    emitMapClick(touchPosition)
}
