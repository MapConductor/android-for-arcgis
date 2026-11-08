package com.mapconductor.arcgis

import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import kotlin.math.log2
import kotlin.math.pow

/**
 * WebTiledLayer に渡すレベルの梯子の数字。ローカルのタイル（ラスター・GroundImage）で共有する。
 *
 * 梯子は**統一ズーム**（Google Maps 基準・256px タイル）から組む。
 * [AbstractZoomAltitudeConverter.WEB_MERCATOR_INITIAL_MPP_256] が統一ズーム 0 の
 * 1px あたりメートル数。512px のタイルは 1 枚で 256px タイル 2 枚ぶんを覆うので、
 * level `z` は統一ズーム `z + 1` に対応する。
 *
 * 1 枚のタイルが覆う地面は `resolution * tileSize` なので、解像度は**必ず tileSize 基準**で
 * 刻む。256 基準のまま tileSize に 512 を渡すと level L のタイルが L-1 の広さを覆い、
 * ArcGIS は番号だけ 1 段深い (z, x, y) を要求する（ArcGIS 300 で計測。200.x の
 * SceneView は自前の 256 格子で (col, row) を数えていたので、その読み替えで合っていた）。
 * ラスターは d0f4819 でこれに直したが、GroundImage は同じ読み替えを自前で持ったまま
 * 残っていて、地球の別の場所の（空の）タイルを引いて何も表示されなかった。
 * 二度と片方だけ直すことにならないよう、ここ 1 か所で持つ。
 */
internal object WebMercatorTileLevels {
    /** 統一ズームが基準にしているタイルの一辺（Google 準拠の 256px）。 */
    private const val UNIFIED_TILE_SIZE = 256.0
    const val DEFAULT_DPI = 96
    private const val INCHES_PER_METER = 39.37

    /** この level のタイルを引くべき統一ズーム。256px なら level そのもの。 */
    fun unifiedZoomForLevel(
        level: Int,
        tileSize: Int,
    ): Double = level + log2(tileSize.toDouble() / UNIFIED_TILE_SIZE)

    /** level のタイル 1px あたりのメートル数。`resolution * tileSize` が level のタイル 1 枚の地面の幅。 */
    fun resolution(
        level: Int,
        tileSize: Int,
    ): Double = AbstractZoomAltitudeConverter.WEB_MERCATOR_INITIAL_MPP_256 / 2.0.pow(unifiedZoomForLevel(level, tileSize))

    /**
     * 縮尺の分母。2D の MapView はこの梯子を見て level を選ぶので、解像度と同じ比率で
     * 刻んでいないと 1 段ずれる。3D の SceneView は見ていない（自前の 256px の梯子で選ぶ）。
     */
    fun scale(resolution: Double): Double = resolution * DEFAULT_DPI * INCHES_PER_METER
}
