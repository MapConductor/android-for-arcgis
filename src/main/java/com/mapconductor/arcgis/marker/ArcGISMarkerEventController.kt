package com.mapconductor.arcgis.marker

import com.arcgismaps.geometry.Point
import com.mapconductor.arcgis.ArcGISActualMarker
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler

internal interface ArcGISMarkerEventControllerInterface : MarkerEventControllerInterface<ArcGISActualMarker> {
    fun find(position: GeoPoint): MarkerEntityInterface<ArcGISActualMarker>?

    fun getSelectedState(): MarkerState?

    fun startDrag(entity: MarkerEntityInterface<ArcGISActualMarker>)

    fun updateDrag(
        point: Point,
        position: GeoPoint,
    )

    fun endDrag(
        point: Point,
        position: GeoPoint,
    )

    fun dispatchClick(state: MarkerState)

    fun dispatchDragStart(state: MarkerState)

    fun dispatchDrag(state: MarkerState)

    fun dispatchDragEnd(state: MarkerState)

    fun setClickListener(listener: OnMarkerEventHandler?)

    fun setDragStartListener(listener: OnMarkerEventHandler?)

    fun setDragListener(listener: OnMarkerEventHandler?)

    fun setDragEndListener(listener: OnMarkerEventHandler?)

    fun setAnimateStartListener(listener: OnMarkerEventHandler?)

    fun setAnimateEndListener(listener: OnMarkerEventHandler?)
}

