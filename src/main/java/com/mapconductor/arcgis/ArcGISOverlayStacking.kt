package com.mapconductor.arcgis

import com.arcgismaps.mapping.layers.Layer
import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.controller.OverlayStacking
import java.util.WeakHashMap

/**
 * 重なり順（[OverlayStacking]）。ArcGIS では:
 *
 * - ラスター（RasterLayerState）と GroundImage は地図の operational layer。
 *   **GraphicsOverlay（ポリゴン・サークル・ポリライン・マーカー）は常にその上**に描かれる。
 *   operational layer の並びの中で、ラスター < GroundImage の順に入れる。
 * - GraphicsOverlay は下からポリゴン < サークル < ポリライン < マーカーの順に積む
 *   （コントローラの init）。
 *
 * マーカーのタイル（ラスター）も operational layer なので、ポリゴン等の GraphicsOverlay より
 * 下になる（SDK の制約。マーカーの帯には置けない）。operational layer の中では一番上に置く。
 */
internal object ArcGISOverlayStacking {
    private val keys = WeakHashMap<Layer, OverlayStacking.Key>()

    /** マーカーのタイル: operational layer の中で一番上。 */
    val MARKER_TILE = OverlayStacking.Key(OverlayKind.Marker, Int.MIN_VALUE)

    fun key(
        kind: OverlayKind,
        zIndex: Int = 0,
    ) = OverlayStacking.Key(kind, zIndex)

    /** [layer] を [key] の位置へ足す（ベースマップや他所が足した層は無視して位置を決める）。 */
    fun add(
        layers: MutableList<Layer>,
        layer: Layer,
        key: OverlayStacking.Key,
    ) {
        val before =
            synchronized(keys) {
                keys[layer] = key
                OverlayStacking.insertBefore(layers.toList(), key) { keys[it] }
            }
        val index = before?.let { layers.indexOf(it) } ?: -1
        if (index >= 0) layers.add(index, layer) else layers.add(layer)
    }

    /** 作り直した層を、元の層と同じ鍵で登録する（元の層の直上に置く呼び出し側向け）。 */
    fun inherit(
        old: Layer,
        new: Layer,
    ) {
        synchronized(keys) { keys[old]?.let { keys[new] = it } }
    }
}
