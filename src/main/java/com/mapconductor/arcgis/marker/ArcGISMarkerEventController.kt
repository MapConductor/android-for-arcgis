package com.mapconductor.arcgis.marker

import com.arcgismaps.geometry.Point
import com.mapconductor.arcgis.ArcGISActualMarker
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.DefaultMarkerEventController
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerEventHostInterface
import com.mapconductor.core.marker.MarkerState

/**
 * ArcGIS のマーカーイベント。
 *
 * クリックの引き当て・配送・リスナーの転送・ドラッグ対象の保持は
 * [DefaultMarkerEventController] がすべて持つ。ここに残すのは**ArcGIS 固有の
 * ドラッグの面**だけ。
 *
 * ## なぜ ArcGIS だけ差分が要るのか
 *
 * 他プロバイダのドラッグは「対象を選ぶ」「地理座標を更新する」の 2 つで表せるが、
 * ArcGIS は [Graphic][com.arcgismaps.mapping.view.Graphic] の `geometry` に
 * **ArcGIS の投影座標系の [Point]** を直接入れる。地理座標だけでは足りず、
 * ジェスチャ側が `screenToBaseSurface` などで得た [Point] をそのまま渡す必要がある
 * （3D では `screenToLocation` の非同期待ちを避けるためにこの形になっている）。
 *
 * そのため `updateDrag(point, position)` は地理座標と投影座標の**両方**を受け取る。
 * この形はコアの共通の面には収まらないので寄せない。
 */
internal class ArcGISMarkerEventController(
    host: MarkerEventHostInterface<ArcGISActualMarker>,
) : DefaultMarkerEventController<ArcGISActualMarker>(host) {
    /** ドラッグ中のマーカーの状態。無ければ null。 */
    fun getSelectedState(): MarkerState? = getSelectedMarker()?.state

    /**
     * ドラッグ対象を決める。
     *
     * 保持はコアがやる（ArcGIS はドラッグ層を持たないので、レンダラのフックは
     * 既定の no-op のまま＝従来と同じ挙動）。
     */
    fun startDrag(entity: MarkerEntityInterface<ArcGISActualMarker>) {
        setSelectedMarker(entity)
    }

    /**
     * ドラッグ中の位置更新。
     *
     * @param point ArcGIS の投影座標。`Graphic.geometry` へ入れる。
     * @param position 同じ位置の地理座標。`MarkerState.position` へ入れる
     *   （これがコアの再描画とアプリへの通知を駆動する）。
     */
    fun updateDrag(
        point: Point,
        position: GeoPoint,
    ) {
        getSelectedMarker()?.also { entity ->
            entity.marker?.geometry = point
            entity.state.position = position
        }
    }

    /** ドラッグの確定。最後の位置を反映してから対象を外す。 */
    fun endDrag(
        point: Point,
        position: GeoPoint,
    ) {
        updateDrag(point, position)
        setSelectedMarker(null)
    }
}
