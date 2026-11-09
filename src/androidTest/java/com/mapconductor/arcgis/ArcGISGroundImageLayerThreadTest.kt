package com.mapconductor.arcgis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.mapping.layers.Layer
import com.arcgismaps.mapping.view.MapView
import com.mapconductor.arcgis.groundimage.ArcGISGroundImageOverlayRenderer
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageEntity
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.tileserver.TileServerRegistry
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * GroundImage は operational layer の並びをメインスレッドで足し外しすること。
 *
 * ラスターのレンダラ（とマーカーのタイル）は同じ並びをメインスレッドで触る。GroundImage だけが
 * Default のまま触っていて、GroundImage とラスターを同時に外すと（ページを閉じる・
 * clearOverlays・両方の state を同時に外す）並びの反復と削除がぶつかり、
 * `ConcurrentModificationException` で落ちた（タブレットの 2D で再現）。
 */
@RunWith(AndroidJUnit4::class)
class ArcGISGroundImageLayerThreadTest {
    private lateinit var real: ArcGISMapView2DHolder

    /** 足し外しが走ったスレッドを記録する並び。 */
    private class ThreadRecordingList : java.util.AbstractList<Layer>() {
        val items = ArrayList<Layer>()
        val mutationsOffMain: MutableList<String> = Collections.synchronizedList(ArrayList())
        var mutations = 0

        private fun record(what: String) {
            mutations++
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mutationsOffMain.add("$what on ${Thread.currentThread().name}")
            }
        }

        override val size: Int get() = items.size

        override fun get(index: Int): Layer = items[index]

        override fun add(
            index: Int,
            element: Layer,
        ) {
            record("add")
            items.add(index, element)
        }

        override fun removeAt(index: Int): Layer {
            record("remove")
            return items.removeAt(index)
        }

        override fun set(
            index: Int,
            element: Layer,
        ): Layer {
            record("set")
            return items.set(index, element)
        }
    }

    private class RecordingHolder(
        real: ArcGISMapView2DHolder,
        override val operationalLayers: MutableList<Layer>,
    ) : ArcGISGeoViewHolder<WrapMapView, MapView> by real

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ArcGISEnvironment.applicationContext = context.applicationContext
            val map = MapView(context)
            val wrap = WrapMapView(context).also { it.arcGISMapView = map }
            real = ArcGISMapView2DHolder(wrap, map)
        }
    }

    @Test
    fun groundImageTouchesTheLayerListOnTheMainThread() =
        runBlocking {
            val layers = ThreadRecordingList()
            val renderer =
                ArcGISGroundImageOverlayRenderer(
                    holder = RecordingHolder(real, layers),
                    tileServer = TileServerRegistry.get(),
                )
            val state =
                GroundImageState(
                    id = "gi-thread",
                    bounds = GeoRectBounds(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
                    image = BitmapDrawable(null, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)),
                )
            val handle = renderer.createGroundImage(state)!!
            assertEquals(1, layers.size)

            renderer.removeGroundImage(GroundImageEntity(handle, state))
            // 削除は renderer 自身のスコープで走る。
            repeat(50) { if (layers.isNotEmpty()) delay(20) }
            assertEquals(0, layers.size)
            assertTrue("add and remove must both have happened", layers.mutations >= 2)
            assertTrue(
                "operational layers must be changed on the main thread: ${layers.mutationsOffMain}",
                layers.mutationsOffMain.isEmpty(),
            )
        }
}
