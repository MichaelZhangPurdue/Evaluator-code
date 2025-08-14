package com.example.frontend.Backend

import android.graphics.Bitmap
import android.graphics.PointF

data class HandData(
    val wristPosture: String? = null,
    val elbowPosture: String? = null,
    val handCoordinates: List<PointF>? = null
)
