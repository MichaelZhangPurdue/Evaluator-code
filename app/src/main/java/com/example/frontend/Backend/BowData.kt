package com.example.frontend.Backend

import android.graphics.Bitmap
import android.graphics.PointF

data class BowData(
    val bowBox: List<PointF>? = null,
    val stringBox: List<PointF>? = null,
    val bowClass: Int? = null,
    val bowAngle: Float? = null
)