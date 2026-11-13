package com.mapconductor.arcgis

import com.arcgismaps.geometry.GeometryEngine
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.SpatialReference
import com.mapconductor.core.features.GeoPoint

/**
 * GeoPointInterface を ArcGIS の Point に変換
 */
fun GeoPoint.toPoint(spatialReference: SpatialReference? = null): Point =
    Point(x = longitude, y = latitude, z = altitude, spatialReference = spatialReference)

fun GeoPoint.Companion.fromLatLongAltitude(
    latitude: Double,
    longitude: Double,
    altitude: Double,
) = GeoPoint(latitude = latitude, longitude = longitude, altitude = altitude)

fun GeoPoint.Companion.fromLongLat(
    longitude: Double,
    latitude: Double,
    altitude: Double,
) = GeoPoint(latitude = latitude, longitude = longitude, altitude = altitude)

// fun GeoPointInterface.Companion.from(point: Point): GeoPointInterface {
//    val wgs84Point =
//        if (point.spatialReference != SpatialReference.wgs84()) {
//            GeometryEngine.projectOrNull(point, SpatialReference.wgs84()) as Point
//        } else {
//            point
//        }
//
//    return GeoPointInterface(
//        longitude = wgs84Point.x,
//        latitude = wgs84Point.y,
//        altitude = wgs84Point.z ?: 0.0,
//    )
// }

fun Point.toGeoPoint(): GeoPoint {
    val wgs84Point =
        if (this.spatialReference != null && this.spatialReference != SpatialReference.wgs84()) {
            GeometryEngine.projectOrNull(this, SpatialReference.wgs84()) as? Point ?: this
        } else {
            this
        }

    return GeoPoint(
        longitude = wgs84Point.x,
        latitude = wgs84Point.y,
        altitude = wgs84Point.z ?: 0.0,
    )
}

/**
 * Null when the projection could not answer.
 *
 * ArcGIS says "no" with a point full of NaN rather than with a null: ask it to
 * unproject a screen corner before the view has a viewpoint -- the first frame
 * of a 2D MapView, a SceneView still loading -- and it hands back a `Point`
 * whose x and y are NaN. Passed on as a coordinate it spreads: the visible
 * region built from those corners carries NaN, and the sample that prints it
 * took the process down in `BigDecimal(Double.NaN)`.
 *
 * Callers already handle "the view cannot say yet" by returning null, so this
 * turns the one answer into the other.
 */
internal fun GeoPoint.orNullIfNotFinite(): GeoPoint? =
    if (latitude.isFinite() && longitude.isFinite() && altitude.isFinite()) this else null
