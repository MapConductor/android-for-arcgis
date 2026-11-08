package com.mapconductor.arcgis

import com.arcgismaps.mapping.view.Graphic

/**
 * 帯の中の重なり順（zIndex）。ポリゴン・サークル・ポリライン・マーカーの Graphic に使う。
 *
 * 帯（ポリゴン < サークル < ポリライン < マーカー）は GraphicsOverlay 自体の並びで決まる
 * （[ArcGISOverlayStacking]）。帯の**中**は、各 Graphic の `zIndex` プロパティで決める。
 * 3D（SceneView）・2D（MapView）とも、同じ GraphicsOverlay の中はこの値の順に描かれ、
 * 同じ値なら足した順になる。
 *
 * 以前はポリゴンだけ、zIndex を属性（`attributes["zIndex"]`）に書いて `onPostProcess` で
 * コレクションを `clear()` → `addAll()` で並べ替えていた。2D はそれでは描く順が変わらず、
 * zIndex=1 を先に、zIndex=0 を後に足すと zIndex=1 が下に残った。サークルとポリラインは
 * （マーカーも）zIndex を見ておらず、3D・2D とも足した順のままだった。`zIndex` プロパティだけで
 * 3D・2D とも正しく描かれるので、コレクションの並べ替えはやめた（`clear()` → `addAll()` は
 * 非同期の削除と重なると、消したはずの Graphic を戻しうる）。
 */
internal object ArcGISGraphicsZIndex {
    /** [graphic] に [zIndex] を載せる。`null`（サークルの既定）は 0。 */
    fun apply(
        graphic: Graphic,
        zIndex: Int?,
    ) {
        val value = zIndex ?: 0
        if (graphic.zIndex != value) graphic.zIndex = value
    }
}
