package com.mapconductor.arcgis.raster

import com.arcgismaps.mapping.layers.Layer
import com.mapconductor.core.raster.RasterLayerController
import com.mapconductor.core.raster.RasterLayerManager
import com.mapconductor.core.raster.RasterLayerManagerInterface

class ArcGISRasterLayerController(
    rasterLayerManager: RasterLayerManagerInterface<Layer> = RasterLayerManager(),
    override val renderer: ArcGISRasterLayerOverlayRenderer,
) : RasterLayerController<Layer>(rasterLayerManager, renderer) {
    /**
     * Builds the in-process layers again, so ArcGIS drops the tiles it holds
     * for them. Wanted after the camera settles on a level that was answered
     * transparent while it was an ancestor (see the renderer's gate).
     */
    suspend fun rebuildLocalLayersIfNeeded() {
        if (!renderer.localLayersNeedRebuild()) return
        val states =
            rasterLayerManager.allEntities()
                .map { it.state }
                .filter { renderer.isLocalLayer(it) }
                .sortedBy { it.zIndex }
        for (state in states) {
            removeById(state.id)
            upsert(state)
        }
        renderer.localLayersRebuilt()
    }
}
