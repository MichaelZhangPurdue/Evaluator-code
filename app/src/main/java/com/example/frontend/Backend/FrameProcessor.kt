package com.example.frontend.Backend

import ClassificationResult
import com.example.frontend.Backend.CoordResult

import android.graphics.Bitmap
import android.graphics.Matrix

import com.example.frontend.Backend.BowClassifier
import com.example.frontend.Backend.HandsClassifier

import org.opencv.core.*
import org.opencv.imgproc.Imgproc



//need imports from model

class FrameProcessor (private val bowClassifier: BowClassifier, private val //BowClassifier
// must be imported for this to work
handsClassifier: HandsClassifier) { //HandsClassifier must be imported for this to work

    fun processFrame(frameBitmap: Bitmap): Pair<CoordResult, ClassificationResult> {
        val bowData = bowClassifier.process(frameBitmap)
        val handData = handsClassifier.process(frameBitmap)

        val coordResult = CoordResult()
        val classificationResult = ClassificationResult()

        //Parse bow data
        bowData.bowBox?.let {
            coordResult.boxBowTopLeft = it[0]
            coordResult.boxBowTopRight = it[1]
            coordResult.boxBowBottomLeft = it[2]
            coordResult.boxBowBottomRight = it[3]
        }

        bowData.stringBox?.let {
            coordResult.boxStringTopLeft = it[0]
            coordResult.boxStringTopRight = it[1]
            coordResult.boxStringBottomLeft = it[2]
            coordResult.boxStringBottomRight = it[3]
        }

        bowData.bowClass?.let {
            classificationResult.bowVertical = mapBowVertical(it)
        }

        bowData.bowAngle?.let {
            classificationResult.bowAngle = it
        }

        // Parse hands data
        classificationResult.wristPosture = handData.wristPosture
        classificationResult.elbowPosture = handData.elbowPosture
        coordResult.handCoordinates = handData.handCoordinates

        return Pair(coordResult, classificationResult)

    }






    fun bowStringAnnotations (
        frame: Mat,
        coordList: Map <String, Pair<Int, Int>>,
        classificationList: Map<String, String>
    ) {
        val radius = 5
        val thickness = -1
        val textOffset = 35

        val bowKeys = listOf(
            "box bow top left",
            "box bow top right",
            "box bow bottom left",
            "box bow bottom right"
        )

        if (bowKeys.all { coordList.containsKey(it) }) {
            val color = Scalar(0.0, 255.0, 0.0)
            val textColor = Scalar(211.0, 100.0, 100.0)
            for (key in bowKeys) {
                val point = coordList[key]!!
                Imgproc.circle(
                    frame,
                    Point(point.first.toDouble(), point.second.toDouble()),
                    radius,
                    color,
                    thickness
                )
            }

            // Put bow coordinates text
            var y = 35 * 6 + 20
            Imgproc.putText(
                frame, "Bow OBB Coords:", Point(frame.cols() - 370.0, y.toDouble()),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 2
            )

            for ((i, key) in bowKeys.withIndex()) {
                y += 35
                val point = coordList[key]!!
                Imgproc.putText(
                    frame,
                    "Coord ${i + 1}: (${point.first}, ${point.second})",
                    Point(frame.cols() - 370.0, y.toDouble()),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 2
                )
            }


        }
    }

    private fun mapBowVertical(value: Int): String {
        return when(value) {
            -3 -> "Calibrating. Keep bow away from strings"
            -2 -> "Invalid, no points"
            -1 -> "Invalid, some points"
            0 -> "Correct"
            1 -> "Outside Bow Zone"
            2 -> "Too Low"
            3 -> "Too High"
            else -> "Unknown"
        }
    }

}