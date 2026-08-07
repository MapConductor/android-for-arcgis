package com.mapconductor.arcgis

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.LifecycleOwner
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.layers.Layer
import com.arcgismaps.mapping.view.DoubleXY
import com.arcgismaps.mapping.view.GeoView
import com.arcgismaps.mapping.view.MapView
import com.arcgismaps.mapping.view.SceneView
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapViewHolderInterface
import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlinx.coroutines.runBlocking

interface ArcGISGeoViewHolder<ActualMapView : FrameLayout, ActualMap : GeoView> :
    MapViewHolderInterface<ActualMapView, ActualMap> {
    val rootView: FrameLayout
    val geoView: GeoView
    val spatialReference: SpatialReference?
    val operationalLayers: MutableList<Layer>?

    fun setNavigationEnabled(enabled: Boolean) {
        geoView.interactionOptions.isPanEnabled = enabled
        geoView.interactionOptions.isRotateEnabled = enabled
        geoView.interactionOptions.isZoomEnabled = enabled
    }
}

class WrapSceneView : FrameLayout {
    lateinit var sceneView: SceneView

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun onCreate(owner: LifecycleOwner) {
        this.sceneView.onCreate(owner)
    }

    fun onPause(owner: LifecycleOwner) {
        this.sceneView.onPause(owner)
    }

    fun onResume(owner: LifecycleOwner) {
        this.sceneView.onResume(owner)
    }

    fun onStop(owner: LifecycleOwner) {
        this.sceneView.onStop(owner)
    }

    fun onDestroy(owner: LifecycleOwner) {
        this.sceneView.onDestroy(owner)
    }
}

class WrapMapView : FrameLayout {
    lateinit var arcGISMapView: MapView

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun onCreate(owner: LifecycleOwner) {
        this.arcGISMapView.onCreate(owner)
    }

    fun onPause(owner: LifecycleOwner) {
        this.arcGISMapView.onPause(owner)
    }

    fun onResume(owner: LifecycleOwner) {
        this.arcGISMapView.onResume(owner)
    }

    fun onStop(owner: LifecycleOwner) {
        // MapView does not expose onStop; keep this wrapper symmetrical with WrapSceneView.
    }

    fun onDestroy(owner: LifecycleOwner) {
        this.arcGISMapView.onDestroy(owner)
    }
}

class ArcGISMapViewHolder(
    override val mapView: WrapSceneView,
    override val map: SceneView,
) : ArcGISGeoViewHolder<WrapSceneView, SceneView> {
    override val rootView: FrameLayout = mapView
    override val geoView: GeoView = map

    // The native SceneView throws (ArcGISException wrapping a native NPE)
    // instead of returning null once the view is destroyed. Teardown
    // callbacks and per-frame projections can arrive just after
    // mapView.onDestroy(), so degrade to null instead of crashing.
    override val spatialReference: SpatialReference?
        get() = runCatching { map.scene?.spatialReference }.getOrNull()
    override val operationalLayers: MutableList<Layer>?
        get() = runCatching { map.scene?.operationalLayers }.getOrNull()

    override fun toScreenOffset(position: GeoPointInterface): Offset? {
        val result =
            runCatching {
                mapView.sceneView.locationToScreen(
                    point = GeoPoint.from(position).toPoint(SpatialReference.wgs84()),
                )
            }.getOrNull()
        return result?.let {
            Offset(it.screenPoint.x.toFloat(), it.screenPoint.y.toFloat())
        }
    }

    override suspend fun fromScreenOffset(offset: Offset): GeoPoint? {
        val result =
            mapView.sceneView.screenToLocation(
                screenCoordinate =
                    DoubleXY(
                        x = offset.x.toDouble(),
                        y = offset.y.toDouble(),
                    ),
            )
        return result.getOrNull()?.toGeoPoint()
    }

    override fun fromScreenOffsetSync(offset: Offset): GeoPoint? =
        runBlocking {
            fromScreenOffset(offset)
        }
}

class ArcGISMapView2DHolder(
    override val mapView: WrapMapView,
    override val map: MapView,
) : ArcGISGeoViewHolder<WrapMapView, MapView> {
    override val rootView: FrameLayout = mapView
    override val geoView: GeoView = map

    // See ArcGISMapViewHolder: native accessors throw after view destroy.
    override val spatialReference: SpatialReference?
        get() = runCatching { map.map?.spatialReference }.getOrNull()
    override val operationalLayers: MutableList<Layer>?
        get() = runCatching { map.map?.operationalLayers }.getOrNull()

    /**
     * `GeoPoint.toPoint(spatialReference)` は経度緯度の値にその空間参照を「貼る」だけで座標変換は
     * しない。2D の `ArcGISMap` は既定で Web メルカトルなので、ビューの空間参照をそのまま渡すと
     * 経度・緯度がメートルとして解釈され、投影先は本来の位置から遥かに離れた点になる
     * （InfoBubble が画面外に置かれて出てこない、という形で表面化した）。
     * `SpatialReference.wgs84()` を明示して ArcGIS 側に再投影させる。
     * 逆方向（`fromScreenOffset`）は [Point.toGeoPoint] が WGS84 へ投影済み。
     */
    override fun toScreenOffset(position: GeoPointInterface): Offset? {
        val result =
            runCatching {
                map.locationToScreen(
                    mapPoint = GeoPoint.from(position).toPoint(SpatialReference.wgs84()),
                )
            }.getOrNull() ?: return null
        return Offset(result.x.toFloat(), result.y.toFloat())
    }

    override suspend fun fromScreenOffset(offset: Offset): GeoPoint? =
        map
            .screenToLocation(
                DoubleXY(
                    x = offset.x.toDouble(),
                    y = offset.y.toDouble(),
                ),
            )?.toGeoPoint()

    override fun fromScreenOffsetSync(offset: Offset): GeoPoint? =
        map
            .screenToLocation(
                DoubleXY(
                    x = offset.x.toDouble(),
                    y = offset.y.toDouble(),
                ),
            )?.toGeoPoint()
}

internal fun Context.getArcGisApiKey(): String? =
    packageManager
        .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        .metaData
        ?.getString("ARCGIS_API_KEY")
