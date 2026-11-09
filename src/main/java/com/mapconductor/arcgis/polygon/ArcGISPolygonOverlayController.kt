package com.mapconductor.arcgis.polygon

import com.mapconductor.arcgis.ArcGISActualPolygon
import com.mapconductor.arcgis.finishEvenIfCancelled
import com.mapconductor.core.polygon.PolygonController
import com.mapconductor.core.polygon.PolygonManager
import com.mapconductor.core.polygon.PolygonManagerInterface
import com.mapconductor.core.polygon.PolygonState

class ArcGISPolygonOverlayController(
    polygonManager: PolygonManagerInterface<ArcGISActualPolygon> = PolygonManager(),
    override val renderer: ArcGISPolygonOverlayRenderer,
) : PolygonController<ArcGISActualPolygon>(polygonManager, renderer) {
    /** 取り消されても最後まで（[finishEvenIfCancelled]）。 */
    override suspend fun add(data: List<PolygonState>) = finishEvenIfCancelled { super.add(data) }
}
