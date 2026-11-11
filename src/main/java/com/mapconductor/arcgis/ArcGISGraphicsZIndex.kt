package com.mapconductor.arcgis

import com.arcgismaps.mapping.view.Graphic
import java.util.IdentityHashMap

/**
 * 帯の中の重なり順。ポリゴン・サークル・ポリライン・マーカーの GraphicsOverlay ごとに 1 つ持つ。
 *
 * 帯（ポリゴン < サークル < ポリライン < マーカー）は GraphicsOverlay 自体の並びで決まる
 * （[ArcGISOverlayStacking]）。帯の**中**は (state の zIndex, 渡された順) で決め、その順位を
 * 各 Graphic の `zIndex` プロパティに書く。
 *
 * state の zIndex をそのまま書かないのは、ArcGIS が同じ `zIndex` の Graphic を足した順に
 * 描くとは限らないから（API リファレンスも「usually」「in rare cases ... may change the display
 * order」とし、順序が要るなら zIndex を明示せよとしている）。タブレットの 2D で、zIndex=0 の
 * ポリゴン 2 枚の間に zIndex=1 のものが挟まると、後から足した方が下に描かれた。マーカーは
 * zIndex が全部 0 でも足した順にならなかった。順位はどれも違う値なので、同値の扱いに頼らない。
 *
 * 順位は狭義単調増加であればよく、連番である必要はない。外したときは詰めない。足すときは
 * 前後の間に空きがあればそこへ、無ければ後ろの順位を空きが出るところまで 1 ずつずらす。
 * いちばん上に足す（よくある場合）は 1 つ書くだけ。
 *
 * メインスレッドから使う（各レンダラは Graphic をメインスレッドで触る）。念のため同期する。
 */
internal class ArcGISGraphicsZIndex {
    private class Entry(
        val graphic: Graphic,
        var zIndex: Double,
        val seq: Long,
        var rank: Int,
    )

    private val entries = ArrayList<Entry>()
    private val byGraphic = IdentityHashMap<Graphic, Entry>()
    private var nextSeq = 0L

    /**
     * [graphic] を [zIndex]（`null` は 0）の位置に置く。初めての Graphic は同じ zIndex の中で
     * 一番上（渡された順）。既に置いた Graphic の zIndex が変わったときは、最初に渡された順を
     * 保ったまま移す。
     */
    @Synchronized
    fun place(
        graphic: Graphic,
        zIndex: Int?,
    ) = placeAt(graphic, (zIndex ?: 0).toDouble())

    /**
     * [graphic] を描く順の値 [order]（大きいほど上）の位置に置く。マーカーが使う
     * （`MarkerDrawOrder` の値: zIndex が第 1 キー、同じなら南・西が上）。値が同じものの
     * 間だけ、最初に渡された順になる。
     */
    @Synchronized
    fun placeAt(
        graphic: Graphic,
        order: Double,
    ) {
        val z = order
        val existing = byGraphic[graphic]
        val entry =
            if (existing != null) {
                if (existing.zIndex == z) return
                entries.removeAt(indexOf(existing))
                existing.zIndex = z
                existing
            } else {
                Entry(graphic, z, nextSeq++, 0).also { byGraphic[graphic] = it }
            }
        val at = insertionPoint(entry)
        entries.add(at, entry)
        assignRanksFrom(at)
    }

    /** [graphic] を外す。順位は詰めない（残りの並びはそのまま正しい）。 */
    @Synchronized
    fun forget(graphic: Graphic) {
        val entry = byGraphic.remove(graphic) ?: return
        entries.removeAt(indexOf(entry))
    }

    /** 置いている Graphic の数（テスト用）。 */
    @get:Synchronized
    val size: Int get() = entries.size

    private fun compare(
        a: Entry,
        b: Entry,
    ): Int = if (a.zIndex != b.zIndex) a.zIndex.compareTo(b.zIndex) else a.seq.compareTo(b.seq)

    private fun insertionPoint(entry: Entry): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(entries[mid], entry) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun indexOf(entry: Entry): Int {
        val i = insertionPoint(entry)
        check(i < entries.size && entries[i] === entry) { "stack order lost track of a graphic" }
        return i
    }

    /** [from] の順位を前の 1 つ上にし、後ろが狭義単調増加に戻るところまでずらす。 */
    private fun assignRanksFrom(from: Int) {
        var previous = if (from > 0) entries[from - 1].rank else -1
        for (i in from until entries.size) {
            val entry = entries[i]
            if (i > from && entry.rank > previous) break
            entry.rank = previous + 1
            if (entry.graphic.zIndex != entry.rank) entry.graphic.zIndex = entry.rank
            previous = entry.rank
        }
    }
}
