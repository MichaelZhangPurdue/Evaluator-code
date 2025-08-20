package com.example.frontend.Backend

import ClassificationResult
import com.example.frontend.Backend.CoordResult

import android.graphics.Bitmap
import android.graphics.Matrix

import com.example.frontend.Backend.BowClassifier
import com.example.frontend.Backend.HandsClassifier

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.android.Utils




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






    fun videoFeed(videoPath: String, outputPath: String): String {
        val cap = VideoCapture(videoPath)
        if (!cap.isOpened) {
            throw Exception("Failed to open video file")
        }

        val fps = cap.get(Videoio.CAP_PROP_FPS).toInt()
        var frameCount = 0
        val outputFrameLength = 960
        val outputFrameWidth = 720
        val frameSize = Size(outputFrameLength.toDouble(), outputFrameWidth.toDouble())

        val fourcc = VideoWriter.fourcc('a'.code, 'v'.code, 'c'.code, '1'.code)
        val writer = VideoWriter(outputPath, fourcc, fps.toDouble(), frameSize)
        if (!writer.isOpened) {
            throw Exception("Failed to create output video file")
        }

        val frame = Mat()
        val radius = 5
        val thickness = -1
        val textOffset = 35

        while (cap.read(frame)) {
            if (frame.empty()) break

            val bitmap = Bitmap.createBitmap(frame.cols(), frame.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(frame, bitmap)

            val (coordResult, classResult) = processFrame(bitmap)

            val annotatedFrame = Mat()
            Utils.bitmapToMat(bitmap, annotatedFrame)

            val coordList = coordResult.toMap()
            val classificationList = classResult.toMap()

            // ---------------- Bow box points ----------------
            if (coordList.containsKey("box bow top left")) {
                val color = Scalar(0.0, 255.0, 0.0)
                val textColor = Scalar(211.0, 100.0, 100.0)

                Imgproc.circle(annotatedFrame, coordList["box bow top left"]!!.toPoint(), radius, color, thickness)
                Imgproc.circle(annotatedFrame, coordList["box bow top right"]!!.toPoint(), radius, color, thickness)
                Imgproc.circle(annotatedFrame, coordList["box bow bottom left"]!!.toPoint(), radius, color, thickness)
                Imgproc.circle(annotatedFrame, coordList["box bow bottom right"]!!.toPoint(), radius, color, thickness)

                val x = annotatedFrame.cols() - 370.0
                var y = 35 * 6 + 20
                Imgproc.putText(annotatedFrame, "Bow OBB Coords:", Point(x, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 2)

                val bowKeys = listOf("box bow top left", "box bow top right", "box bow bottom left", "box bow bottom right")
                for ((i, key) in bowKeys.withIndex()) {
                    y += 35
                    val p = coordList[key]!!
                    Imgproc.putText(annotatedFrame, "Coord ${i + 1}: (${p.first}, ${p.second})", Point(x, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 2)
                }
            }

            // string box pts
            if (coordList.containsKey("box string top left")) {
                val color = Scalar(0.0, 255.0, 0.0)
                val textColor = Scalar(0.0, 255.0, 0.0)

                Imgproc.circle(annotatedFrame, coordList["box string top left"]!!.toPoint(), radius, color, thickness)
                Imgproc.circle(annotatedFrame, coordList["box string top right"]!!.toPoint(), radius, color, thickness)
                Imgproc.circle(annotatedFrame, coordList["box string bottom left"]!!.toPoint(), radius, color, thickness)
                Imgproc.circle(annotatedFrame, coordList["box string bottom right"]!!.toPoint(), radius, color, thickness)

                val x = annotatedFrame.cols() - 370.0
                var y = textOffset + 20
                Imgproc.putText(annotatedFrame, "String OBB Coords:", Point(x, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 2)

                val stringKeys = listOf("box string top left", "box string top right", "box string bottom left", "box string bottom right")
                for ((i, key) in stringKeys.withIndex()) {
                    y += textOffset
                    val p = coordList[key]!!
                    Imgproc.putText(annotatedFrame, "Coord ${i + 1}: (${p.first}, ${p.second})", Point(x, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 2)
                }
            }

            // bow vertical classification
            classificationList["bow vertical"]?.let {
                val textColor = Scalar(255.0, 0.0, 0.0)
                val bowTextCoord = Point(annotatedFrame.cols() - 370.0, (textOffset * 11).toDouble())
                Imgproc.putText(annotatedFrame, "Bow: $it", bowTextCoord, Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 4)
            }

            // bow angle classification
            classificationList["bow angle"]?.let {
                val textColor = Scalar(255.0, 0.0, 0.0)
                val bowTextCoord = Point(annotatedFrame.cols() - 370.0, (textOffset * 13).toDouble())
                Imgproc.putText(annotatedFrame, "Bow angle: $it", bowTextCoord, Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 4)
            }

            // ---------------- Wrist posture ----------------
            classificationList["wrist posture"]?.let {
                val textColor = Scalar(255.0, 0.0, 0.0)
                val wristTextCoord = Point(annotatedFrame.cols() - 370.0, (textOffset * 15).toDouble())
                Imgproc.putText(annotatedFrame, "Wrist: $it", wristTextCoord, Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 4)
            }

            // ---------------- Elbow posture ----------------
            classificationList["elbow posture"]?.let {
                val textColor = Scalar(255.0, 0.0, 0.0)
                val elbowTextCoord = Point(annotatedFrame.cols() - 370.0, (textOffset * 17).toDouble())
                Imgproc.putText(annotatedFrame, "Elbow: $it", elbowTextCoord, Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, textColor, 4)
            }

            // ---------------- Frame counter ----------------
            frameCount++
            Imgproc.putText(
                annotatedFrame, "Frame $frameCount",
                Point(10.0, 50.0),
                Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(0.0, 0.0, 255.0), 1
            )

            // Resize before writing
            val resizedFrame = Mat()
            Imgproc.resize(annotatedFrame, resizedFrame, frameSize)

            writer.write(resizedFrame)
        }

        cap.release()
        writer.release()

        val outFile = java.io.File(outputPath)
        if (outFile.exists() && outFile.length() > 0) {
            return outputPath
        } else {
            throw Exception("Failed to create output video file or file is empty")
        }
    }

    private fun Pair<Int, Int>.toPoint(): Point {
        return Point(this.first.toDouble(), this.second.toDouble())
    }


    private fun ClassificationResult.toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()

        bowVertical?.let { map["bow vertical"] = it }
        bowAngle?.let { map["bow angle"] = it }
        wristPosture?.let { map["wrist posture"] = it }
        elbowPosture?.let { map["elbow posture"] = it }

        return map
    }


    private fun CoordResult.toMap(): Map<String, Pair<Int, Int>> {
        val map = mutableMapOf<String, Pair<Int, Int>>()

        boxBowTopLeft?.let { map["box bow top left"] = it }
        boxBowTopRight?.let { map["box bow top right"] = it }
        boxBowBottomLeft?.let { map["box bow bottom left"] = it }
        boxBowBottomRight?.let { map["box bow bottom right"] = it }

        boxStringTopLeft?.let { map["box string top left"] = it }
        boxStringTopRight?.let { map["box string top right"] = it }
        boxStringBottomLeft?.let { map["box string bottom left"] = it }
        boxStringBottomRight?.let { map["box string bottom right"] = it }

        handCoordinates?.let { map["hand coordinates"] = it }

        return map
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