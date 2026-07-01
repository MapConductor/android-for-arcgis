package com.mapconductor.arcgis

import android.graphics.Color

internal fun Int.toArcGISColor(): com.arcgismaps.Color =
    com.arcgismaps.Color.fromRgba(
        r = Color.red(this),
        g = Color.green(this),
        b = Color.blue(this),
        a = Color.alpha(this),
    )
