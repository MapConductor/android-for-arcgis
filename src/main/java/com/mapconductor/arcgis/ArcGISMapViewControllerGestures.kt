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

// タップ・長押し・パンの処理。
// タップは**マーカーが先**で、どのマーカーにも当たらなかったときだけ
// 地図のタップとして扱う（android の他プロバイダと同じ順序）。
internal fun ArcGISMapViewController.onMapPan(event: PanChangeEvent) {
    val controller = activeDragController
    if (controller != null) {
        val screenPoint = event.screenCoordinate
        // screenToLocation waits for an asynchronous scene intersection. During a drag
        // that queues pointer frames and makes the graphic trail behind the finger.
        // Markers are placed relative to the base surface, so use the synchronous base
        // surface intersection just as the iOS drag path uses the gesture's map point.
        val point = holder.map.screenToBaseSurface(screenPoint) ?: return
        val position = point.toGeoPoint()
        controller.updateDrag(point, position)
        controller.getSelectedState()?.let { state ->
            controller.dispatchDrag(state)
        }
        return
    }
    invokeCameraMoveCallback()
}

internal fun ArcGISMapViewController.onMapUp(event: UpEvent) {
    val controller = activeDragController
    if (controller != null) {
        val point = event.mapPoint ?: holder.map.screenToBaseSurface(event.screenCoordinate) ?: return
        val position = point.toGeoPoint()
        val selectedState = controller.getSelectedState()
        controller.endDrag(point, position)
        selectedState?.let { state ->
            controller.dispatchDragEnd(state)
        }
        activeDragController = null

        with(holder.map) {
            interactionOptions.isPanEnabled = appliedUISettings.scrollGesture
            interactionOptions.isRotateEnabled = appliedUISettings.rotateGesture
            interactionOptions.isZoomEnabled = appliedUISettings.zoomGesture
        }
    }
}

internal suspend fun ArcGISMapViewController.onMapLongPress(event: LongPressEvent) {
    if (event.motionEvent.action != MotionEvent.ACTION_MOVE) return

    val screenPoint = event.screenCoordinate
    val point = event.mapPoint ?: holder.map.screenToLocation(screenPoint).getOrNull() ?: return
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
                    // 3Dナビゲーションを無効化
                    with(holder.map) {
                        interactionOptions.isPanEnabled = false
                        interactionOptions.isRotateEnabled = false
                        interactionOptions.isZoomEnabled = false
                    }
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
                    with(holder.map) {
                        interactionOptions.isPanEnabled = false
                        interactionOptions.isRotateEnabled = false
                        interactionOptions.isZoomEnabled = false
                    }
                    controller.dispatchDragStart(entity.state)
                    return
                }
            }
        }
    emitMapLongClick(position)
}

internal suspend fun ArcGISMapViewController.onMapTap(event: SingleTapConfirmedEvent) {
    val screenPoint = event.screenCoordinate
    val touchPosition =
        holder.map
            .screenToLocation(screenPoint)
            .getOrNull()
            ?.toGeoPoint() ?: return

    markerEventControllers.forEach { controller ->
        controller.find(touchPosition)?.let { markerEntity ->
            controller.dispatchClick(markerEntity.state)
            return
        }
    }

    circleController.find(touchPosition)?.let { circleEntity ->
        val event =
            CircleEvent(
                state = circleEntity.state,
                clicked = touchPosition,
            )
        circleController.dispatchClick(event)
        return
    }

    groundImageController.find(touchPosition)?.let { entity ->
        val event =
            GroundImageEvent(
                state = entity.state,
                clicked = touchPosition,
            )
        groundImageController.dispatchClick(event)
        return
    }

    polylineController.findWithClosestPoint(touchPosition)?.let { hitResult ->
        val event =
            PolylineEvent(
                state = hitResult.entity.state,
                clicked = hitResult.closestPoint,
            )
        polylineController.dispatchClick(event)
        return
    }

    polygonController.find(touchPosition)?.let { polygonEntity ->
        val event =
            PolygonEvent(
                state = polygonEntity.state,
                clicked = touchPosition,
            )
        polygonController.dispatchClick(event)
        return
    }

    holder.map.screenToLocation(screenPoint).getOrNull()?.also {
        emitMapClick(it.toGeoPoint())
    }
}
