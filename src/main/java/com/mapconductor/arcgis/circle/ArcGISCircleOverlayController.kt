package com.mapconductor.arcgis.circle

import com.mapconductor.arcgis.ArcGISActualCircle
import com.mapconductor.arcgis.finishEvenIfCancelled
import com.mapconductor.core.circle.CircleController
import com.mapconductor.core.circle.CircleManager
import com.mapconductor.core.circle.CircleManagerInterface
import com.mapconductor.core.circle.CircleState

class ArcGISCircleOverlayController(
    circleManager: CircleManagerInterface<ArcGISActualCircle> = CircleManager(),
    override val renderer: ArcGISCircleOverlayRenderer,
) : CircleController<ArcGISActualCircle>(circleManager, renderer) {
    /** 取り消されても最後まで（[finishEvenIfCancelled]）。 */
    override suspend fun add(data: List<CircleState>) = finishEvenIfCancelled { super.add(data) }
}
