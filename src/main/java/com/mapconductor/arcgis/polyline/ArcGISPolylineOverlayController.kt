package com.mapconductor.arcgis.polyline

import com.mapconductor.arcgis.ArcGISActualPolyline
import com.mapconductor.arcgis.finishEvenIfCancelled
import com.mapconductor.core.polyline.PolylineController
import com.mapconductor.core.polyline.PolylineManager
import com.mapconductor.core.polyline.PolylineManagerInterface
import com.mapconductor.core.polyline.PolylineState

class ArcGISPolylineOverlayController(
    polylineManager: PolylineManagerInterface<ArcGISActualPolyline> = PolylineManager(),
    override val renderer: ArcGISPolylineOverlayRenderer,
) : PolylineController<ArcGISActualPolyline>(polylineManager, renderer) {
    /** 取り消されても最後まで（[finishEvenIfCancelled]）。 */
    override suspend fun add(data: List<PolylineState>) = finishEvenIfCancelled { super.add(data) }
}
