package com.mapconductor.arcgis

import com.arcgismaps.mapping.layers.Layer
import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.controller.OverlayStacking
import java.lang.ref.WeakReference

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
 * ポリゴンのタイルは operational layer の中のポリゴンの帯（GroundImage の上）。GraphicsOverlay の
 * ポリゴン・サークル・ポリラインはその上に来るので、帯の順どおりになる。
 */
internal object ArcGISOverlayStacking {
    private val keys = IdentityWeakTable<Layer, OverlayStacking.Key>()

    /** マーカーのタイル: operational layer の中で一番上。 */
    val MARKER_TILE = OverlayStacking.Key(OverlayKind.Marker, Int.MIN_VALUE)

    /** ポリゴンのタイル: GroundImage の上、マーカーのタイルの下。 */
    val POLYGON_TILE = OverlayStacking.Key(OverlayKind.Polygon, Int.MIN_VALUE)

    /**
     * ポリラインのタイル: ポリゴンのタイルの上、マーカーのタイルの下。operational layer なので
     * GraphicsOverlay（ネイティブのポリゴン・サークル・ポリライン）より下（ポリゴンのタイルと同じ制約）。
     */
    val POLYLINE_TILE = OverlayStacking.Key(OverlayKind.Polyline, Int.MIN_VALUE)

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
                keys.put(layer, key)
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
        synchronized(keys) { keys[old]?.let { keys.put(new, it) } }
    }
}

/**
 * 層 → 値の表。**同一性（===）で引き**、層は弱参照で持つ。
 *
 * 以前は `WeakHashMap<Layer, …>` だったが、ArcGIS の `Layer` は `equals` / `hashCode` を
 * 上書きしていて、載せた後に同じ層を引いても見つからないことがあった（3D で、GroundImage を
 * 先に載せてからラスターを足すと、GroundImage の鍵が引けずにラスターが一番上に積まれた）。
 * 層の数は高々数十なので線形探索でよい。呼び出し側で同期する。
 */
internal class IdentityWeakTable<K : Any, V : Any> {
    private val entries = ArrayList<Pair<WeakReference<K>, V>>()

    operator fun get(key: K): V? = entries.firstOrNull { it.first.get() === key }?.second

    fun put(
        key: K,
        value: V,
    ) {
        entries.removeAll { it.first.get().let { k -> k == null || k === key } }
        entries.add(WeakReference(key) to value)
    }

    val size: Int get() = entries.count { it.first.get() != null }
}
