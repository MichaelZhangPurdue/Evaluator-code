package com.example.frontend.Backend

import android.graphics.PointF
import org.opencv.core.Point


data class CoordResult(
    var boxBowTopLeft: PointF? = null,
    var boxBowTopRight: PointF? = null,
    var boxBowBottomLeft: PointF? = null,
    var boxBowBottomRight: PointF? = null,

    var boxStringTopLeft: PointF? = null,
    var boxStringTopRight: PointF? = null,
    var boxStringBottomLeft: PointF? = null,
    var boxStringBottomRight: PointF? = null,

    var handCoordinates: List<PointF>? = null
)
