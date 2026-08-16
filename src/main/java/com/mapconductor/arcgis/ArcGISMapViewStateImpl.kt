package com.mapconductor.arcgis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.mapconductor.compose.map.BaseMapViewSaver
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapPaddings
import com.mapconductor.core.map.MapPaddingsInterface
import com.mapconductor.core.map.MapViewState
import com.mapconductor.core.map.MapViewStateInterface
import java.util.UUID
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ArcGISMapViewStateInterface : MapViewStateInterface<ArcGISDesignTypeInterface>

class ArcGISMapViewState(
    override val id: String,
    mapDesignType: ArcGISDesignTypeInterface,
    cameraPosition: MapCameraPosition = MapCameraPosition.Default,
) : MapViewState<ArcGISDesignTypeInterface>(cameraPosition),
    ArcGISMapViewStateInterface {
    // Map padding
    private val _padding = MutableStateFlow(MapPaddings.Zeros)
    val padding: StateFlow<MapPaddingsInterface> = _padding.asStateFlow()

    private var controller: ArcGISMapViewControllerInterface? = null
    private var _mapDesignType: ArcGISDesignTypeInterface = mapDesignType

    override var mapDesignType: ArcGISDesignTypeInterface
        set(value) {
            _mapDesignType = value
            this.controller?.setMapDesignType(value)
        }
        get() = _mapDesignType

    internal fun setController(controller: ArcGISMapViewControllerInterface) {
        this.controller = controller
        attachController(controller, moveToInitialCamera = false)
        // setMapDesignType is intentionally omitted here: the Scene was already created
        // with the correct basemap style in holderProvider. Replacing the Basemap while
        // the Scene is loading causes viewpointChanged to fire with zoom~0, overwriting
        // the intended initial camera position.
    }

    internal fun clearController() {
        this.controller = null
        detachController()
    }

    internal fun onMapDesignTypeChange(value: ArcGISDesignTypeInterface) {
        _mapDesignType = value
    }

    override fun getMapViewHolder(): ArcGISGeoViewHolder<*, *>? = super.getMapViewHolder() as? ArcGISGeoViewHolder<*, *>

    /** Holder while the 3D [ArcGISMapView] (SceneView) is attached; null in 2D mode. */
    fun getSceneViewHolder(): ArcGISMapViewHolder? = controller?.holder as? ArcGISMapViewHolder

    /** Holder while the 2D [ArcGISMapView2D] (MapView) is attached; null in 3D mode. */
    fun getMapView2DHolder(): ArcGISMapView2DHolder? = controller?.holder as? ArcGISMapView2DHolder

    internal fun updateCameraPosition(cameraPosition: MapCameraPosition) {
        setCameraPositionInternal(cameraPosition)
    }
}

class ArcGISMapViewSaver : BaseMapViewSaver<ArcGISMapViewState>() {
    override fun saveMapDesign(
        state: ArcGISMapViewState,
        bundle: Bundle,
    ) {
        bundle.putString("id", state.mapDesignType.id)
    }

    override fun createState(
        stateId: String,
        mapDesignBundle: Bundle?,
        cameraPosition: MapCameraPosition,
    ): ArcGISMapViewState =
        ArcGISMapViewState(
            id = stateId,
            mapDesignType =
                ArcGISDesign.Create(
                    id = mapDesignBundle?.getString("id") ?: ArcGISDesign.Streets.id,
                ),
            cameraPosition = cameraPosition,
        )

    override fun getStateId(state: ArcGISMapViewState): String = state.id
}

@Composable
fun rememberArcGISMapViewState(
    mapDesign: ArcGISDesign = ArcGISDesign.Streets,
    cameraPosition: MapCameraPositionInterface = MapCameraPosition.Default,
): ArcGISMapViewState {
    val stateId by rememberSaveable {
        val uuid = UUID.randomUUID().toString()
        mutableStateOf(uuid)
    }
    val state =
        rememberSaveable(
            stateSaver = ArcGISMapViewSaver().createSaver(),
        ) {
            mutableStateOf(
                ArcGISMapViewState(
                    id = stateId,
                    mapDesignType = mapDesign,
                    cameraPosition = MapCameraPosition.from(cameraPosition),
                ),
            )
        }

    return state.value
}

internal fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
