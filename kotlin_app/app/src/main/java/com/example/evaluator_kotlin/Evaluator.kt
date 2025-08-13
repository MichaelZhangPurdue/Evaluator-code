package com.example.evaluator_kotlin

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tflite.java.TfLite
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.support.common.FileUtil
import java.util.concurrent.CountDownLatch
import kotlin.math.*

/**
 * Single, integrated Evaluator
 * - Merges both versions you shared and removes duplication
 * - Keeps your public API the same
 * - Fixes a couple of subtle issues (see comments)
 */
class Evaluator {
    // ----- LiteRT init -----
    val initializeTask: Task<Void> by lazy {
        Log.d("InitDebug", "TfLite.initialize() is being called")
        TfLite.initialize(MainActivity.applicationContext())
    }
    val modelReadyLatch = CountDownLatch(1)
    private lateinit var model: InterpreterApi

    // ----- temporal smoothing / state -----
    private var bowRepeat = 0
    private var stringRepeat = 0
    private var bowPoints: MutableList<Point>? = null
    private var stringPoints: MutableList<Point>? = null
    private var yLocked = false
    private var yAvg: MutableList<Double>? = null
    private var frameCounter = 0
    private var stringYCoordHeights: MutableList<List<Int>> = mutableListOf()
    private val numWaitFrames = 10

    fun createInterpreter(context: Context) {
        initializeTask
            .addOnSuccessListener {
                Log.d("InitDebug", "SuccessListener is being called")
                val options = InterpreterApi.Options()
                    .setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
                model = InterpreterApi.create(
                    FileUtil.loadMappedFile(context, "nano_best_float32.tflite"),
                    options
                )
                modelReadyLatch.countDown()
            }
            .addOnFailureListener { e ->
                Log.e("Interpreter", "Cannot initialize interpreter", e)
            }
    }

    // ------------------------------ Pre/Post ------------------------------
    /**
     * Rescales while preserving AR, then pads to square. Returns padded image and pad-fractions.
     */
    fun letterbox(img: Mat, newShape: Size = Size(640.0, 640.0)): Pair<Mat, Pair<Double, Double>> {
        val shape = Size(img.width().toDouble(), img.height().toDouble())
        val r = min(newShape.width / shape.width, newShape.height / shape.height)
        val newUnpad = Size(round(shape.width * r), round(shape.height * r))
        val dw = (newShape.width - newUnpad.width) / 2
        val dh = (newShape.height - newUnpad.height) / 2

        val resized = Mat()
        Imgproc.resize(img, resized, newUnpad)

        val top = round(dh - 0.1).toInt()
        val bottom = round(dh + 0.1).toInt()
        val left = round(dw - 0.1).toInt()
        val right = round(dw + 0.1).toInt()

        val padded = Mat()
        Core.copyMakeBorder(
            resized, padded, top, bottom, left, right, Core.BORDER_CONSTANT,
            Scalar(114.0, 114.0, 114.0)
        )
        // return pad as fractions of the padded image dims (top/height, left/width)
        return Pair(padded, Pair(top / padded.height().toDouble(), left / padded.width().toDouble()))
    }

    /** Preprocess to RGB float32 [0,1] and return (image, padFractions). */
    fun preprocess(img: Mat, newShape: Size = Size(640.0, 640.0)): Pair<Mat, Pair<Double, Double>> {
        val (letterboxed, pad) = letterbox(img, newShape)
        val rgb = Mat()
        Imgproc.cvtColor(letterboxed, rgb, Imgproc.COLOR_BGR2RGB)
        val floatImg = Mat()
        rgb.convertTo(floatImg, CvType.CV_32FC3, 1.0 / 255.0)
        return Pair(floatImg, pad)
    }

    /**
     * Postprocess YOLO-OBB style output back to 4-corner polygons in original image space.
     * outputs: [N, 7] => (cx, cy, h, w, conf, cls, angleRad)
     */
    fun postprocess(
        origImg: Mat,
        outputs: Array<FloatArray>,
        resizedShape: Size,
        pad: Pair<Double, Double>
    ): List<List<Float>> {
        val results = mutableListOf<List<Float>>()

        val targetH = origImg.height().toFloat()
        val targetW = origImg.width().toFloat()
        val targetScale = max(targetH, targetW) // matches your earlier convention

        for (out in outputs) {
            val cx = targetScale * (out[0] - pad.second.toFloat())
            val cy = targetScale * (out[1] - pad.first.toFloat())
            val w = targetScale * out[3]
            val h = targetScale * out[2]
            val conf = out[4]
            val cls = out[5]
            val angleRad = out[6]

            val points = rotatedRectToPoints(cx, cy, w, h, angleRad)
            val flat = points.flatMap { listOf(it.first, it.second) }.toMutableList()
            flat.add(conf)
            flat.add(cls)
            results.add(flat)
        }
        return results
    }

    // Convert rotated rect (cx,cy,w,h,angle) to 4 corner points (x,y) in image coords
    fun rotatedRectToPoints(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        angleRad: Float
    ): List<Pair<Float, Float>> {
        val halfW = w / 2
        val halfH = h / 2
        val cosA = cos(angleRad - (Math.PI.toFloat() / 2))
        val sinA = sin(angleRad - (Math.PI.toFloat() / 2))
        val corners = listOf(
            -halfW to -halfH,
            halfW to -halfH,
            halfW to halfH,
            -halfW to halfH
        )
        return corners.map { (x, y) ->
            val xRot = x * cosA - y * sinA + cx
            val yRot = x * sinA + y * cosA + cy
            xRot to yRot
        }
    }

    // ------------------------------ Inference ------------------------------
    fun runModel(frame: Mat): List<List<Float>> {
        // NOTE: allocateTensors() is generally implicit, but harmless here
        model.allocateTensors()

        val inputTensor = model.getInputTensor(0)
        val inputShape = inputTensor.shape() // [1, H, W, 3]
        val targetH = inputShape[1]
        val targetW = inputShape[2]

        // IMPORTANT: OpenCV Size takes (width, height)
        val (preImg, pad) = preprocess(frame, Size(targetW.toDouble(), targetH.toDouble()))

        val inputArray = Array(1) { Array(targetH) { Array(targetW) { FloatArray(3) } } }
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val px = preImg.get(y, x) // [R,G,B]
                inputArray[0][y][x][0] = px[0].toFloat()
                inputArray[0][y][x][1] = px[1].toFloat()
                inputArray[0][y][x][2] = px[2].toFloat()
            }
        }

        val outTensor = model.getOutputTensor(0)
        val outShape = outTensor.shape() // e.g. [1, N, 7]
        val outputArray = Array(outShape[0]) { Array(outShape[1]) { FloatArray(outShape[2]) } }

        model.run(inputArray, outputArray)
        val outputs = outputArray[0] // [N,7]

        val results = postprocess(
            origImg = frame,
            outputs = outputs,
            resizedShape = Size(targetW.toDouble(), targetH.toDouble()),
            pad = pad
        )
        Log.d("Evaluator", "Detections: ${convertYolo(results)}")
        return results
    }

    // ------------------------------ Drawing ------------------------------
    fun drawDetections(img: Mat, box: List<Point>, score: Float, classId: Int) {
        val colorPalette = mapOf(
            1 to Scalar(255.0, 255.0, 100.0), // bow  (BGR)
            0 to Scalar(100.0, 255.0, 255.0)  // string
        )
        val color = colorPalette[classId] ?: Scalar(255.0, 255.0, 255.0)
        if (box.size == 4) {
            val pointsArray = MatOfPoint(*box.map { Point(it.x, it.y) }.toTypedArray())
            Imgproc.polylines(img, listOf(pointsArray), true, color, 2)
        }
    }

    // ------------------------------ Boxing helpers ------------------------------
    data class BoxResults(var classification: Int?, var box: MutableList<MutableList<Int>>?, var angle: Int?)

    data class YoloResults(var bowResults: MutableList<Point>?, var stringResults: MutableList<Point>?)

    fun convertYolo(results: List<List<Float>>): YoloResults {
        // results: [[x1,y1,x2,y2,x3,y3,x4,y4, conf, cls], ...]
        val yoloResults = YoloResults(null, null)
        if (results.isEmpty()) return yoloResults

        fun toPoints(row: List<Float>): MutableList<Point> = MutableList(4) { i ->
            Point(row[2 * i].toDouble(), row[2 * i + 1].toDouble())
        }

        val first = results[0]
        val firstPts = toPoints(first)
        if (first[9] == 1.0f) yoloResults.stringResults = firstPts else yoloResults.bowResults = firstPts

        if (results.size > 1 && results[1][9] != results[0][9]) {
            val second = results[1]
            val secondPts = toPoints(second)
            if (second[9] == 1.0f) yoloResults.stringResults = secondPts else yoloResults.bowResults = secondPts
        }
        return yoloResults
    }

    fun updatePoints(stringBox: MutableList<Point>, bowBox: MutableList<Point>) {
        bowPoints = bowBox
        if (!yLocked) {
            stringPoints = stringBox
        } else if (yAvg != null) {
            val sortedString = sortStringPoints(stringBox)
            stringPoints = sortedString
            stringPoints!![0].y = yAvg!![0]
            stringPoints!![1].y = yAvg!![1]
        }
    }

    fun sortStringPoints(pts: MutableList<Point>): MutableList<Point> {
        val sorted = pts.sortedBy { it.y }
        val top = sorted.take(2).sortedBy { it.x }
        val bottom = sorted.drop(2).sortedByDescending { it.x }
        return (top + bottom).toMutableList()
    }

    fun getMidline(): MutableList<Int> {
        fun d(a: Point, b: Point) = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
        val b = bowPoints ?: return mutableListOf(0, 0)
        val dists = listOf(d(b[0], b[1]), d(b[1], b[2]), d(b[2], b[3]), d(b[3], b[0]))
        val minIdx = dists.indexOf(dists.minOrNull())
        val (p1, p2) = when (minIdx) {
            0 -> (b[0] to b[1]) to (b[2] to b[3])
            1 -> (b[1] to b[2]) to (b[3] to b[0])
            2 -> (b[2] to b[3]) to (b[0] to b[1])
            else -> (b[3] to b[0]) to (b[1] to b[2])
        }
        val m1 = listOf((p1.first.x + p1.second.x) / 2, (p1.first.y + p1.second.y) / 2)
        val m2 = listOf((p2.first.x + p2.second.x) / 2, (p2.first.y + p2.second.y) / 2)
        val dy = m1[1] - m2[1]
        val dx = m1[0] - m2[0]
        return if (dx == 0.0) mutableListOf(Float.POSITIVE_INFINITY.toInt(), m1[0].toInt())
        else {
            val slope = dy / dx
            val intercept = m1[1] - slope * m1[0]
            mutableListOf(slope.toInt(), intercept.toInt())
        }
    }

    private fun getVerticalLines(): MutableList<MutableList<Double>> {
        val sp = stringPoints ?: return mutableListOf(mutableListOf(), mutableListOf())
        val topLeft = sp[0]; val topRight = sp[1]; val botRight = sp[2]; val botLeft = sp[3]

        fun sideSlopeAndIntercept(top: Point, bot: Point): Pair<Double, Double> {
            val dx = top.x - bot.x
            return if (dx == 0.0) Double.POSITIVE_INFINITY to -1.0
            else {
                val m = (top.y - bot.y) / dx
                val b = top.y - m * top.y
                m to b
            }
        }
        val (mL, bL) = sideSlopeAndIntercept(topLeft, botLeft)
        val (mR, bR) = sideSlopeAndIntercept(topRight, botRight)
        val leftLine = mutableListOf(mL, bL, topLeft.y, botLeft.y)
        val rightLine = mutableListOf(mR, bR, topRight.y, botRight.y)
        return mutableListOf(leftLine, rightLine)
    }

    private fun intersectsVertical(linearLine: MutableList<Int>, verticalLines: MutableList<MutableList<Double>>): Int {
        val m = linearLine[0].toDouble()
        val b = linearLine[1].toDouble()
        val sp = stringPoints ?: return 1
        val xLeft = sp[0].x
        val xRight = sp[1].x

        fun intersect(v: List<Double>, xRef: Double): Point? {
            val vm = v[0]; val vb = v[1]; val topY = v[2]; val botY = v[3]
            val (x, y) = when {
                vm == Double.POSITIVE_INFINITY || vb == -1.0 -> {
                    if (m == Double.POSITIVE_INFINITY) return null
                    xRef to (m * xRef + b)
                }
                m == Double.POSITIVE_INFINITY -> {
                    val x0 = b; x0 to (vm * x0 + vb)
                }
                abs(m - vm) < 1e-6 -> return null // parallel
                else -> {
                    val x0 = (vb - b) / (m - vm)
                    x0 to (m * x0 + b)
                }
            }
            val yMin = min(topY, botY)
            val yMax = max(topY, botY)
            return if (y in yMin..yMax) Point(x, y) else null
        }

        val p1 = intersect(verticalLines[0], xLeft)
        val p2 = intersect(verticalLines[1], xRight)
        if (p1 == null || p2 == null) return 1
        return bowHeightIntersection(mutableListOf(p1, p2), verticalLines)
    }

    private fun bowHeightIntersection(intersections: MutableList<Point>, verticalLines: List<List<Double>>): Int {
        val topScale = .15
        val v1 = verticalLines[0]; val v2 = verticalLines[1]
        val botY1 = v1[3]; val botY2 = v2[3]
        val topY1 = v1[2]; val topY2 = v2[2]
        val height = ((botY1 - topY1) + (botY2 - topY2)) / 2.0
        val minY = ((topY1 + topY2) / 2) + height * topScale
        return if (intersections[0].y >= minY || intersections[1].y >= minY) 2 else 0
    }

    private fun averageYCoordinate(stringBoxCoords: MutableList<Point>) {
        val sorted = sortStringPoints(stringBoxCoords)
        frameCounter += 1
        val yCoords = sorted.map { it.y.toInt() }
        stringYCoordHeights.add(yCoords)
        if (frameCounter % numWaitFrames == 0) {
            val tl = median(stringYCoordHeights.map { it[0] })
            val tr = median(stringYCoordHeights.map { it[1] })
            val br = median(stringYCoordHeights.map { it[2] })
            val bl = median(stringYCoordHeights.map { it[3] })
            if (stringPoints == null) stringPoints = mutableListOf(Point(), Point(), Point(), Point())
            stringPoints!![0].y = tl
            stringPoints!![1].y = tr
            stringPoints!![2].y = br
            stringPoints!![3].y = bl
            yAvg = mutableListOf(tl, tr)
            yLocked = true
            stringYCoordHeights = mutableListOf()
        }
    }

    private fun median(values: List<Int>): Double {
        require(values.isNotEmpty()) { "Empty list has no median." }
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2.0 else s[m].toDouble()
    }

    private fun degrees(radians: Double): Double = radians * (180.0 / Math.PI)

    private fun bowAngle(bowLine: MutableList<Int>, verticalLines: MutableList<MutableList<Double>>): Int {
        val maxAngle = 15
        val mBow = bowLine[0].toDouble()
        val m1 = verticalLines[0][0]
        val m2 = verticalLines[1][0]
        val a1 = abs(degrees(atan(abs(mBow - m2) / (1 + mBow * m2))))
        val a2 = abs(degrees(atan(abs(m1 - mBow) / (1 + m1 * mBow))))
        val minA = min(a1, a2)
        return if (minA > maxAngle) 1 else 0
    }

    data class ReturnBow(var classification: Int?, var bow: List<Point>?, var string: List<Point>?, var angle: Int?)

    fun classify(results: YoloResults): ReturnBow {
        val out = ReturnBow(null, null, null, null)
        stringPoints = results.stringResults
        bowPoints = results.bowResults

        if (stringPoints == null && bowPoints == null) {
            out.classification = -2
            return out
        }

        if (results.stringResults != null) {
            out.string = results.stringResults
            averageYCoordinate(results.stringResults!!)

            if (results.bowResults == null && bowRepeat < 5 && bowPoints != null) {
                out.classification = -1
                bowRepeat++
                out.bow = bowPoints
            }
            if (results.bowResults != null) {
                out.bow = results.bowResults
                if (results.stringResults == null && stringRepeat < 5 && stringPoints != null) {
                    out.classification = -1
                    stringRepeat++
                    out.string = stringPoints
                }
            }
            if (results.bowResults != null && results.stringResults != null) {
                updatePoints(results.stringResults!!, results.bowResults!!)
                val mid = getMidline()
                val verts = getVerticalLines()
                val inter = intersectsVertical(mid, verts)
                out.angle = bowAngle(mid, verts)
                out.classification = inter
                return out
            } else {
                out.classification = -1
                return out
            }
        }
        return out
    }
}
