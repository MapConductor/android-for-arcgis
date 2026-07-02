package com.mapconductor.arcgis.marker

import com.arcgismaps.geometry.Point
import com.mapconductor.arcgis.ArcGISActualMarker
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController

internal class StrategyArcGISMarkerEventController(
    private val controller: StrategyMarkerController<ArcGISActualMarker>,
) : ArcGISMarkerEventControllerInterface {
    private var selectedMarker: MarkerEntityInterface<ArcGISActualMarker>? = null

    override fun find(position: GeoPoint): MarkerEntityInterface<ArcGISActualMarker>? = controller.find(position)

    override fun getSelectedState(): MarkerState? = selectedMarker?.state

    override fun startDrag(entity: MarkerEntityInterface<ArcGISActualMarker>) {
        selectedMarker = entity
    }

    override fun updateDrag(
        point: Point,
        position: GeoPoint,
    ) {
        selectedMarker?.also { entity ->
            entity.marker?.geometry = point
            entity.state.position = position
        }
    }

    override fun endDrag(
        point: Point,
        position: GeoPoint,
    ) {
        selectedMarker?.also { entity ->
            entity.marker?.geometry = point
            entity.state.position = position
        }
        selectedMarker = null
    }

    override fun dispatchClick(state: MarkerState) = controller.dispatchClick(state)

    override fun dispatchDragStart(state: MarkerState) = controller.dispatchDragStart(state)

    override fun dispatchDrag(state: MarkerState) = controller.dispatchDrag(state)

    override fun dispatchDragEnd(state: MarkerState) = controller.dispatchDragEnd(state)

    override fun setClickListener(listener: OnMarkerEventHandler?) {
        controller.clickListener = listener
    }

    override fun setDragStartListener(listener: OnMarkerEventHandler?) {
        controller.dragStartListener = listener
    }

    override fun setDragListener(listener: OnMarkerEventHandler?) {
        controller.dragListener = listener
    }

    override fun setDragEndListener(listener: OnMarkerEventHandler?) {
        controller.dragEndListener = listener
    }

    override fun setAnimateStartListener(listener: OnMarkerEventHandler?) {
        controller.animateStartListener = listener
    }

    override fun setAnimateEndListener(listener: OnMarkerEventHandler?) {
        controller.animateEndListener = listener
    }
}
