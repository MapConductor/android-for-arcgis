package com.mapconductor.arcgis.marker

import com.mapconductor.arcgis.ArcGISActualMarker
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapViewHolderInterface
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerEventHostInterface
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * ArcGIS 固有のドラッグの面のテスト。
 *
 * ## なぜこれを書いたか
 *
 * ArcGIS の長押しドラッグは `adb shell input`（motionevent / swipe のどちらでも）
 * では発火しない。`onMapLongPress` が `LongPressEvent` の `motionEvent.action` が
 * `ACTION_MOVE` であることを要求しており、ArcGIS 自身のジェスチャ検出器が
 * 実際の指のタッチストリームからしか作らないため。
 * 実機での自動確認ができないので、**移行で書き換えた部分だけ**をここで押さえる。
 *
 * ## updateDrag / endDrag がここに無い理由
 *
 * どちらも `com.arcgismaps.geometry.Point` を取る。この型はコンストラクタで
 * ArcGIS のネイティブランタイム（`runtimecore`）を読み込むため、素の JVM では
 * `UnsatisfiedLinkError` になり**作れない**。null も渡せない（Kotlin が
 * 非 null パラメータのチェックを差し込む）。
 * 位置の反映は実機で確かめること。ここでは対象の保持と解除だけを見る。
 *
 * ジェスチャ側の配線（`ArcGISMapViewControllerGestures` / `ArcGISMapView2DControllerGestures`）は
 * 今回まったく触っていない。変わったのは
 * `activeDragController` の型と、そこにぶら下がるクラスだけ。
 */
class ArcGISMarkerEventControllerTest {
    // ArcGIS の Point は android/ArcGIS のランタイムを要求するので、
    // ここでは「geometry に何が入ったか」を見ない。見るのは状態遷移と地理座標。
    private fun newController(): Pair<ArcGISMarkerEventController, FakeHost> {
        val host = FakeHost()
        return ArcGISMarkerEventController(host) to host
    }

    private fun entity(id: String): MarkerEntityInterface<ArcGISActualMarker> =
        MarkerEntity(
            marker = null,
            state = MarkerState(id = id, position = GeoPoint.fromLatLong(0.0, 0.0)),
            isRendered = true,
        )

    @Test
    fun `ドラッグ前は選択なし`() {
        val (controller, _) = newController()
        assertNull(controller.getSelectedMarker())
        assertNull(controller.getSelectedState())
    }

    @Test
    fun `startDrag で対象が保持される`() {
        val (controller, _) = newController()
        val target = entity("a")

        controller.startDrag(target)

        assertSame(target, controller.getSelectedMarker())
        assertSame(target.state, controller.getSelectedState())
    }

    @Test
    fun `setSelectedMarker(null) で対象が外れる`() {
        val (controller, _) = newController()
        controller.startDrag(entity("a"))

        // endDrag はこれと「最後の位置の反映」の 2 つでできている。
        // 位置の反映は ArcGIS の Point を要求するのでここでは見られない（下記）。
        controller.setSelectedMarker(null)

        assertNull(controller.getSelectedMarker())
        assertNull(controller.getSelectedState())
    }

    @Test
    fun `クリックの配送はコアの実装がそのまま使われる`() {
        val (controller, host) = newController()
        val state = MarkerState(id = "a", position = GeoPoint.fromLatLong(0.0, 0.0))

        controller.dispatchClick(state)

        assertEquals(listOf(state), host.clicked)
    }

    /** [MarkerEventHostInterface] の最小実装。 */
    private class FakeHost : MarkerEventHostInterface<ArcGISActualMarker> {
        val clicked = mutableListOf<MarkerState>()

        override val markerManager: MarkerManager<ArcGISActualMarker> = MarkerManager.defaultManager()
        override val renderer: MarkerOverlayRendererInterface<ArcGISActualMarker> = FakeRenderer()

        override var clickListener: OnMarkerEventHandler? = null
        override var dragStartListener: OnMarkerEventHandler? = null
        override var dragListener: OnMarkerEventHandler? = null
        override var dragEndListener: OnMarkerEventHandler? = null
        override var animateStartListener: OnMarkerEventHandler? = null
        override var animateEndListener: OnMarkerEventHandler? = null

        override fun find(position: GeoPointInterface): MarkerEntityInterface<ArcGISActualMarker>? = null

        override fun getEntity(id: String): MarkerEntityInterface<ArcGISActualMarker>? = null

        override fun dispatchClick(state: MarkerState) {
            clicked += state
        }

        override fun dispatchDragStart(state: MarkerState) = Unit

        override fun dispatchDrag(state: MarkerState) = Unit

        override fun dispatchDragEnd(state: MarkerState) = Unit
    }

    private class FakeRenderer : MarkerOverlayRendererInterface<ArcGISActualMarker> {
        override var animateStartListener: OnMarkerEventHandler? = null
        override var animateEndListener: OnMarkerEventHandler? = null
        override val holder: MapViewHolderInterface<*, *>
            get() = throw UnsupportedOperationException("not used in this test")

        override suspend fun onAdd(
            data: List<MarkerOverlayRendererInterface.AddParamsInterface>,
        ): List<ArcGISActualMarker?> = data.map { null }

        override suspend fun onChange(
            data: List<MarkerOverlayRendererInterface.ChangeParamsInterface<ArcGISActualMarker>>,
        ): List<ArcGISActualMarker?> = data.map { null }

        override suspend fun onRemove(data: List<MarkerEntityInterface<ArcGISActualMarker>>) = Unit

        override suspend fun onAnimate(entity: MarkerEntityInterface<ArcGISActualMarker>) = Unit

        override suspend fun onPostProcess() = Unit
    }
}
