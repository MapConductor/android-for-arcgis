package com.mapconductor.arcgis.groundimage

import com.mapconductor.arcgis.finishEvenIfCancelled
import com.mapconductor.core.groundimage.GroundImageController
import com.mapconductor.core.groundimage.GroundImageManager
import com.mapconductor.core.groundimage.GroundImageState

class ArcGISGroundImageController(
    renderer: ArcGISGroundImageOverlayRenderer,
) : GroundImageController<ArcGISGroundImageHandle>(
        groundImageManager = GroundImageManager(),
        renderer = renderer,
    ) {
    /** 取り消されても最後まで（[finishEvenIfCancelled]）。 */
    override suspend fun add(data: List<GroundImageState>) = finishEvenIfCancelled { super.add(data) }
}
