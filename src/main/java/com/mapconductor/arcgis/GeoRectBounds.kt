package com.mapconductor.arcgis

import com.arcgismaps.geometry.Envelope
import com.arcgismaps.geometry.SpatialReference
import com.mapconductor.core.features.GeoRectBounds

fun GeoRectBounds.toEnvelope(): Envelope? {
    val sw = southWest ?: return null
    val ne = northEast ?: return null

    return Envelope(
        xMin = sw.longitude,
        yMin = sw.latitude,
        xMax = ne.longitude,
        yMax = ne.latitude,
        spatialReference = SpatialReference.wgs84(),
    )
}
