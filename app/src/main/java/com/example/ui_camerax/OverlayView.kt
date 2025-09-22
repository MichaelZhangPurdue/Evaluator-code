/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var handLandmarkerResult: HandLandmarkerResult? = null
    private var poseLandmarkerResult: PoseLandmarkerResult? = null

    private var linePaint = Paint()
    private var pointPaint = Paint()
    private var poseLinePaint = Paint()
    private var posePointPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    // New properties to store the offsets
    private var xOffset: Float = 0f
    private var yOffset: Float = 0f


    init {
        initPaints()
    }

    fun clear() {
        handLandmarkerResult = null
        poseLandmarkerResult = null
        linePaint.reset()
        pointPaint.reset()
        poseLinePaint.reset()
        posePointPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL

        poseLinePaint.color = Color.WHITE
        poseLinePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        poseLinePaint.style = Paint.Style.STROKE

        posePointPaint.color = Color.BLUE
        posePointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        posePointPaint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Pose Landmarks, using the exact syntax from your example
        poseLandmarkerResult?.let { poseResult ->
            if (poseResult.landmarks().isNotEmpty()) {
                val landmarks = poseResult.landmarks()[0]

                // Draw connections
                PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                    canvas.drawLine(
                        // Apply the x and y offsets to all coordinates
                        landmarks[connection.start()].x() * imageWidth * scaleFactor + xOffset,
                        landmarks[connection.start()].y() * imageHeight * scaleFactor + yOffset,
                        landmarks[connection.end()].x() * imageWidth * scaleFactor + xOffset,
                        landmarks[connection.end()].y() * imageHeight * scaleFactor + yOffset,
                        poseLinePaint
                    )
                }

                // Draw points
                for (normalizedLandmark in landmarks) {
                    canvas.drawPoint(
                        // Apply the x and y offsets
                        normalizedLandmark.x() * imageWidth * scaleFactor + xOffset,
                        normalizedLandmark.y() * imageHeight * scaleFactor + yOffset,
                        posePointPaint
                    )
                }
            }
        }

        // Draw Hand Landmarks
        handLandmarkerResult?.let { handResult ->
            for (landmarks in handResult.landmarks()) {
                // Draw hand connections
                HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
                    canvas.drawLine(
                        // Apply the x and y offsets
                        landmarks.get(connection!!.start()).x() * imageWidth * scaleFactor + xOffset,
                        landmarks.get(connection.start()).y() * imageHeight * scaleFactor + yOffset,
                        landmarks.get(connection.end()).x() * imageWidth * scaleFactor + xOffset,
                        landmarks.get(connection.end()).y() * imageHeight * scaleFactor + yOffset,
                        linePaint
                    )
                }

                // Draw hand points
                for (normalizedLandmark in landmarks) {
                    canvas.drawPoint(
                        // Apply the x and y offsets
                        normalizedLandmark.x() * imageWidth * scaleFactor + xOffset,
                        normalizedLandmark.y() * imageHeight * scaleFactor + yOffset,
                        pointPaint
                    )
                }
            }
        }
    }

    fun setResults(
        handLandmarkerResult: HandLandmarkerResult?,
        poseLandmarkerResult: PoseLandmarkerResult?,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        this.handLandmarkerResult = handLandmarkerResult
        this.poseLandmarkerResult = poseLandmarkerResult

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // The LIVE_STREAM case often needs to be adjusted based on the camera preview's scale type.
                // For a 'fitCenter' scale type, min() is usually correct.
                // For a 'fillCenter' scale type, max() is usually correct.
                // Your issue suggests 'fitCenter', so we'll use min() for both.
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }

        // Calculate the offsets to center the drawings
        val scaledImageWidth = imageWidth * scaleFactor
        val scaledImageHeight = imageHeight * scaleFactor
        xOffset = (width - scaledImageWidth) / 2
        yOffset = (height - scaledImageHeight) / 2

        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
    }
}