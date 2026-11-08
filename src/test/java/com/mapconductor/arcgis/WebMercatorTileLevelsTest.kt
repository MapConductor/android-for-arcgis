package com.mapconductor.arcgis

import com.mapconductor.core.projection.WEB_MERCATOR_MAX_EXTENT_METERS
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * ArcGIS がレベルの梯子から数える (level, col, row) が、ローカルのタイルサーバ
 * （GroundImageTileProvider など、素直な XYZ で z を読む）の同じ番号のタイルを指すこと。
 *
 * 512px のときだけ 256 基準へ読み替えていた頃は、level L に L-1 の格子の col/row が付き
 * （Eswatini の z=15 で x=9617 になる。正しくは 19235）、GroundImage が何も描かれなかった。
 */
class WebMercatorTileLevelsTest {
    // GroundImage サンプルの画像の中心（University of Eswatini）
    private val lon = 31.306239
    private val lat = -26.479235

    private fun mercatorX(lon: Double) = lon / 180.0 * WEB_MERCATOR_MAX_EXTENT_METERS

    private fun mercatorY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2)) / PI * WEB_MERCATOR_MAX_EXTENT_METERS

    /** WebTiledLayer の TileInfo（原点は左上）から ArcGIS が出す col / row。 */
    private fun arcgisColRow(
        level: Int,
        tileSize: Int,
    ): Pair<Int, Int> {
        val footprint = WebMercatorTileLevels.resolution(level, tileSize) * tileSize
        val col = floor((mercatorX(lon) + WEB_MERCATOR_MAX_EXTENT_METERS) / footprint).toInt()
        val row = floor((WEB_MERCATOR_MAX_EXTENT_METERS - mercatorY(lat)) / footprint).toInt()
        return col to row
    }

    /** 素直な XYZ で z=level のときの x / y（タイルの px 数に依らない）。 */
    private fun xyz(level: Int): Pair<Int, Int> {
        val n = 2.0.pow(level)
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        val y = floor((1.0 - ln(tan(Math.toRadians(lat)) + 1.0 / kotlin.math.cos(Math.toRadians(lat))) / PI) / 2.0 * n).toInt()
        return x to y
    }

    @Test
    fun levelNamesTheSameTileAsXyz_for512() {
        for (level in listOf(0, 5, 13, 15, 18)) {
            assertEquals("level $level", xyz(level), arcgisColRow(level, 512))
        }
    }

    @Test
    fun levelNamesTheSameTileAsXyz_for256() {
        for (level in listOf(0, 5, 13, 15, 18)) {
            assertEquals("level $level", xyz(level), arcgisColRow(level, 256))
        }
    }

    @Test
    fun aLevelCoversTheGroundOfThatZoom_whateverTheTileSize() {
        val circumference = 2 * WEB_MERCATOR_MAX_EXTENT_METERS
        for (tileSize in listOf(256, 512)) {
            for (level in 0..22) {
                val footprint = WebMercatorTileLevels.resolution(level, tileSize) * tileSize
                assertEquals("tileSize $tileSize level $level", circumference / 2.0.pow(level), footprint, footprint * 1e-6)
            }
        }
    }
}
