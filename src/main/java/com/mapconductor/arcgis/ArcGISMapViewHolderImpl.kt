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
import kotlin.math.abs
import kotlin.math.max
import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import kotlinx.coroutines.runBlocking

interface ArcGISGeoViewHolder<ActualMapView : FrameLayout, ActualMap : GeoView> :
    MapViewHolderInterface<ActualMapView, ActualMap> {
    val rootView: FrameLayout
    val geoView: GeoView
    val spatialReference: SpatialReference?
    val operationalLayers: MutableList<Layer>?

    /** 3D（SceneView）なら true。ラスターの LOD の組み方が 2D と 3D で逆になる
     * （`ArcGISRasterLayerOverlayRenderer.buildWebMercatorTileInfo` を参照）。 */
    val usesSceneView: Boolean
        get() = geoView is SceneView

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

    /**
     * 2D `MapView` を「見た目だけ」傾ける角度（論理 tilt、度）。
     *
     * 2D はカメラピッチを持てないため、ビューそのものを X 軸まわりに回して遠近感を作る
     * （react-for-leaflet が CSS `rotateX` で行っているのと同じ方式。ios-for-arcgis の
     * `ArcGIS2DTiltModifier` と対応する）。地図側の中心・縮尺は [ArcGIS2DTiltEmulation] が
     * 受け持ち、ここは描画だけを扱う。
     *
     * 負の tilt は中心の前進で表現されるので、描画角度は常に `abs(tilt)` を使う。
     *
     * 注意: `GraphicsOverlay` の内容（マーカー・ポリゴン等）は `MapView` の内側にあるため、
     * この変換で一緒に寝る。
     */
    var visualTilt: Double = 0.0
        set(value) {
            if (field == value) return
            field = value
            // 平面の拡大率が変わるので測り直す（反映は onMeasure）。
            requestLayout()
            // 回転そのものはレイアウトを待たずに当てる。
            applyVisualTilt()
        }

    /**
     * 内側の `MapView` の大きさは**この測定パスの中で**決める。
     *
     * 以前は `onLayout` の中で `layoutParams` を差し替えていたが、それが効くには
     * 「次のレイアウトパス」が要る。Compose ホストなら来るが、**React Native は
     * ビューのフレームを直接書き換えるだけでパスを回さない**ため来ない。結果、
     * 画面回転で内側の `MapView` が回転前のピクセルサイズのまま残り、
     * `Gravity.CENTER` で中央に寄った横長の地図が画面からはみ出していた。
     */
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        updateVisualTiltLayoutParams(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        // `changed` で絞らない。React Native では自分のフレームが変わらないまま
        // 内側の `MapView` だけ測り直されることがあり、そこで回転を当て直す必要がある。
        applyVisualTilt()
    }

    /**
     * 回した平面が元のフレームを覆うよう [PLANE_SCALE] 倍に広げる。親（この FrameLayout）で
     * クリップする。拡大しても縮尺は変わらない（縮尺は解像度で決まる）ので、単に地図が
     * 広く映る＝傾いたカメラがより広い地表を見るのと同じになる。
     */
    private fun updateVisualTiltLayoutParams(
        frameWidth: Int,
        frameHeight: Int,
    ) {
        if (!this::arcGISMapView.isInitialized) return
        if (frameWidth <= 0 || frameHeight <= 0) return

        val angle = abs(visualTilt).coerceIn(0.0, MAX_TILT_DEGREES).toFloat()
        val scale = if (angle > 0f) PLANE_SCALE else 1.0f
        val targetWidth = (frameWidth * scale).toInt()
        val targetHeight = (frameHeight * scale).toInt()

        val params = arcGISMapView.layoutParams as LayoutParams
        if (params.width != targetWidth || params.height != targetHeight || params.gravity != Gravity.CENTER) {
            params.width = targetWidth
            params.height = targetHeight
            params.gravity = Gravity.CENTER
            arcGISMapView.layoutParams = params
        }
    }

    /** 回転そのもの。大きさは [updateVisualTiltLayoutParams] が測定時に決めている。 */
    private fun applyVisualTilt() {
        if (!this::arcGISMapView.isInitialized) return
        if (width <= 0 || height <= 0) return

        val angle = abs(visualTilt).coerceIn(0.0, MAX_TILT_DEGREES).toFloat()

        // 遠近は掛けない（正射影）。react-for-leaflet / react-for-openlayers の CSS も
        // `perspective` を置いておらず、[PLANE_SCALE] = 1 / cos(60°) がちょうど効く前提。
        // 遠近を入れると遠方が縮んで平面が上辺を覆えなくなる。Android は正射影を直接
        // 指定できないため、視点距離を十分大きく取って近似する。
        arcGISMapView.cameraDistance =
            max(arcGISMapView.width, arcGISMapView.height) * ORTHOGRAPHIC_DISTANCE_FACTOR
        arcGISMapView.rotationX = angle
    }

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

    companion object {
        /**
         * 回した平面が元のフレームを覆うための拡大率。
         * 正射影なら回した後の高さは `PLANE_SCALE * cos(tilt)` なので、最大 60° で
         * ちょうど 1.0 になる 2.0 が最小値。react-for-leaflet / react-for-openlayers の
         * 200% と同じ。
         */
        private const val PLANE_SCALE = 2.0f

        /** 正射影に近づけるための視点距離 ÷ ビューサイズ。大きいほど遠近が弱い。 */
        private const val ORTHOGRAPHIC_DISTANCE_FACTOR = 200.0f

        private const val MAX_TILT_DEGREES = 60.0
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
