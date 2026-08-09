package com.mapconductor.arcgis

import com.arcgismaps.mapping.view.LongPressEvent
import com.arcgismaps.mapping.view.PanChangeEvent
import com.arcgismaps.mapping.view.SingleTapConfirmedEvent
import com.arcgismaps.mapping.view.UpEvent
import com.arcgismaps.mapping.view.extensions.motionEvent
import com.mapconductor.arcgis.marker.ArcGISMarkerRenderer
import com.mapconductor.settings.Settings
import android.view.MotionEvent

// タップ・長押し・パンの処理。
// タップのカスケード（marker → circle → groundImage → polyline → polygon → map）は
// コアの BaseMapViewController.dispatchTap が回すので、ここは ArcGIS の
// ScreenCoordinate を地理座標へ直して渡すだけ。長押しはドラッグ開始の判定が要るので残す。
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
    val touchPosition = holder.map.screenToLocation(event.screenCoordinate)?.toGeoPoint() ?: return
    dispatchTap(touchPosition)
}
