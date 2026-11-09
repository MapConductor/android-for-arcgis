package com.mapconductor.arcgis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.arcgismaps.mapping.view.MapView
import com.mapconductor.arcgis.circle.ArcGISCircleOverlayController
import com.mapconductor.arcgis.circle.ArcGISCircleOverlayRenderer
import com.mapconductor.arcgis.marker.ArcGISMarkerController
import com.mapconductor.arcgis.marker.ArcGISMarkerRenderer
import com.mapconductor.arcgis.polygon.ArcGISPolygonOverlayController
import com.mapconductor.arcgis.polygon.ArcGISPolygonOverlayRenderer
import com.mapconductor.arcgis.polyline.ArcGISPolylineOverlayController
import com.mapconductor.arcgis.polyline.ArcGISPolylineOverlayRenderer
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.PolylineState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 同期（compose の LaunchedEffect から来る add）が途中で取り消されても、地図に載せた Graphic を
 * 見失わないこと。
 *
 * compose は overlay の state が変わるたびに前の LaunchedEffect を取り消す。レンダラは Graphic を
 * メインスレッドで載せる（`withContext(Dispatchers.Main)`）が、呼び出し側は compose の
 * ディスパッチャにいるので、載せ終わって戻る前に取り消されると withContext は結果を捨てる。
 * Graphic は地図に載ったまま entity に登録されず、次の同期が同じ id をもう一度載せるので
 * 2 つになる。残った方はどこからも外されない（clearOverlays でも消えない）。
 * タブレットの 2D で、起動直後にマーカー・ポリゴン・ポリラインが同じ id で 2 つずつ入った。
 */
@RunWith(AndroidJUnit4::class)
class ArcGISOverlaySyncCancellationTest {
    private lateinit var holder: ArcGISMapView2DHolder
    private val main = Handler(Looper.getMainLooper())

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ArcGISEnvironment.applicationContext = context.applicationContext
            val map = MapView(context)
            val wrap = WrapMapView(context).also { it.arcGISMapView = map }
            holder = ArcGISMapView2DHolder(wrap, map)
        }
    }

    /**
     * メインスレッドを止めている間に、別のディスパッチャから [sync] を始めて取り消す。
     * レンダラの載せる処理はメインスレッドが空いてから走り、呼び出し側は取り消し済みで戻る。
     */
    private fun cancelWhileTheMainThreadIsBusy(sync: suspend () -> Unit) {
        val hold = CountDownLatch(1)
        val held = CountDownLatch(1)
        main.post {
            held.countDown()
            hold.await(5, TimeUnit.SECONDS)
        }
        held.await(5, TimeUnit.SECONDS)
        val job = CoroutineScope(Dispatchers.Default).launch { sync() }
        Thread.sleep(200)
        job.cancel()
        hold.countDown()
        runBlocking { job.join() }
        // 取り消された呼び出しの後片付け（レンダラ自身のスコープで走るもの）を待つ。
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        runBlocking { delay(200) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun count(overlay: GraphicsOverlay): Int {
        var n = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync { n = overlay.graphics.size }
        return n
    }

    private val square =
        listOf(GeoPoint(35.0, 139.0), GeoPoint(35.0, 139.1), GeoPoint(35.1, 139.1), GeoPoint(35.1, 139.0))

    @Test
    fun aCancelledPolygonSyncDoesNotOrphanItsGraphic() {
        val overlay = GraphicsOverlay()
        val controller = ArcGISPolygonOverlayController(renderer = ArcGISPolygonOverlayRenderer(overlay, holder))
        val state = PolygonState(points = square, id = "p")
        cancelWhileTheMainThreadIsBusy { controller.add(listOf(state)) }
        // 次の同期（compose が新しい state で走らせるもの）。
        runBlocking { controller.add(listOf(state)) }
        assertEquals("one graphic per polygon id", 1, count(overlay))
        runBlocking { controller.clear() }
        runBlocking { delay(200) }
        assertEquals("clear must take every graphic away", 0, count(overlay))
    }

    @Test
    fun aCancelledPolylineSyncDoesNotOrphanItsGraphic() {
        val overlay = GraphicsOverlay()
        val controller = ArcGISPolylineOverlayController(renderer = ArcGISPolylineOverlayRenderer(overlay, holder))
        val state = PolylineState(points = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)), id = "l")
        cancelWhileTheMainThreadIsBusy { controller.add(listOf(state)) }
        runBlocking { controller.add(listOf(state)) }
        assertEquals("one graphic per polyline id", 1, count(overlay))
    }

    @Test
    fun aCancelledCircleSyncDoesNotOrphanItsGraphic() {
        val overlay = GraphicsOverlay()
        val controller = ArcGISCircleOverlayController(renderer = ArcGISCircleOverlayRenderer(overlay, holder))
        val state = CircleState(center = GeoPoint(35.0, 139.0), radiusMeters = 500.0, id = "c")
        cancelWhileTheMainThreadIsBusy { controller.add(listOf(state)) }
        runBlocking { controller.add(listOf(state)) }
        assertEquals("one graphic per circle id", 1, count(overlay))
    }

    @Test
    fun aCancelledMarkerSyncDoesNotOrphanItsGraphic() {
        val overlay = GraphicsOverlay()
        val controller =
            ArcGISMarkerController(
                markerManager = MarkerManager.defaultManager(),
                renderer = ArcGISMarkerRenderer(overlay, holder),
                markerTiling = MarkerTilingOptions.Default,
            )
        val state = MarkerState(id = "m", position = GeoPoint(35.0, 139.0))
        cancelWhileTheMainThreadIsBusy { controller.add(listOf(state)) }
        runBlocking { controller.add(listOf(state)) }
        assertEquals("one graphic per marker id", 1, count(overlay))
    }
}
