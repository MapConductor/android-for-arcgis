package com.mapconductor.arcgis.raster

import com.arcgismaps.mapping.layers.Layer
import com.mapconductor.core.raster.RasterLayerController
import com.mapconductor.core.raster.RasterLayerEntity
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
        val entities =
            rasterLayerManager.allEntities()
                .filter { renderer.isLocalLayer(it.state) }
                .sortedBy { it.state.zIndex }
        for (entity in entities) {
            // In place, over the old one; the renderer removes the old layer
            // once the new one has had time to draw.
            val layer = renderer.rebuildLayer(entity) ?: continue
            rasterLayerManager.removeEntity(entity.state.id)
            rasterLayerManager.registerEntity(RasterLayerEntity(layer, entity.state))
        }
        renderer.localLayersRebuilt()
    }
}
