package com.example.evaluator_kotlin

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Trace
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.evaluator_kotlin.ui.theme.EvaluatorKotlinTheme
import com.google.android.gms.tasks.Tasks
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import java.io.File
import java.io.FileOutputStream

/**
 * Single, integrated file that supports BOTH image and video evaluation.
 * - Tap "Run (Image)" to load an image from assets and run inference.
 * - Tap "Run (Video)" to process frames from an asset video.
 * - Logs FPS/CPU/RAM + inference times using MetricsSampler, with best-effort GPU fields.
 * - Uses android.os.Trace sections so you can see spans in Perfetto/System Trace.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        System.loadLibrary("opencv_java4")
        setContent {
            EvaluatorKotlinTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
        instance = this
    }

    companion object {
        var instance: MainActivity? = null
        fun applicationContext(): Context = instance!!.applicationContext
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val resultImage = remember { mutableStateOf<Bitmap?>(null) }

    Column(modifier = modifier.padding(16.dp)) {
        Text(text = "Object Detection")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                runEvaluationFromImage("Sample Input.png") { bitmap ->
                    resultImage.value = bitmap
                }
            }) { Text("Run (Image)") }

            Button(onClick = {
                runEvaluationFromVideo("good posture.MOV") { bitmap ->
                    resultImage.value = bitmap
                }
            }) { Text("Run (Video)") }
        }

        resultImage.value?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Detection Results"
            )
        }
    }
}

// ------------------------------ Image path ------------------------------
fun runEvaluationFromImage(assetImageName: String, onComplete: (Bitmap) -> Unit) {
    val evaluator = Evaluator()
    val img = readImageFromPath(assetImageName)
    val ctx = MainActivity.applicationContext()

    evaluator.createInterpreter(ctx)

    Thread {
        try {
            Tasks.await(evaluator.initializeTask)
            Log.d("Interpreter", "TfLite.initialize() completed successfully")

            img?.let { mat ->
                val sampler = MetricsSampler(ctx).apply { start() }

                evaluator.modelReadyLatch.await()

                Trace.beginSection("inference-image")
                val t0 = System.nanoTime()
                val results = evaluator.runModel(mat)
                val t1 = System.nanoTime()
                Trace.endSection()
                val inferenceMs = (t1 - t0) / 1e6

                val yolo = evaluator.convertYolo(results)
                yolo.stringResults?.let { evaluator.drawDetections(mat, it, 1.0f, 1) }
                yolo.bowResults?.let { evaluator.drawDetections(mat, it, 1.0f, 0) }

                val rgbMat = Mat()
                Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGR2RGB)

                val bitmap = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rgbMat, bitmap)

                // Log one snapshot for this single inference
                val snap = sampler.sample(frameIndex = 0, inferenceMs = inferenceMs)
                Log.i(
                    "Metrics",
                    "frame=${snap.frameIndex} " +
                            "fps=${"%.1f".format(snap.fps)} " +
                            "cpu=${"%.1f".format(snap.appCpuPercent)}% " +
                            "gpu=${snap.gpuBusyPercent?.let { "%.1f".format(it) } ?: "-"}% " +
                            "gpuMHz=${snap.gpuFreqMHz ?: -1} " +
                            "pss=${snap.pssMB}MB " +
                            "java=${"%.1f".format(snap.javaHeapMB)}MB " +
                            "native=${"%.1f".format(snap.nativeHeapMB)}MB " +
                            "sysRamUsed=${"%.1f".format(snap.systemRamUsedPercent)}% " +
                            "appRamPct=${"%.1f".format(snap.appRamPercentOfTotal)}% " +
                            "inference=${"%.2f".format(snap.inferenceMs ?: 0.0)}ms"
                )

                MainActivity.instance?.runOnUiThread { onComplete(bitmap) }
                rgbMat.release()
            }
        } catch (e: Exception) {
            Log.e("Interpreter", "Error during evaluation", e)
        }
    }.start()
}

// ------------------------------ Video path ------------------------------
/**
 * Streams frames from a video asset. Rotation is normalized so drawing & inference share the same coordinates.
 * Logs metrics every 10 frames and adds Trace sections for Perfetto/System Trace.
 */
fun runEvaluationFromVideo(assetVideoName: String, onFrameEvaluated: (Bitmap) -> Unit) {
    val evaluator = Evaluator()
    val ctx = MainActivity.applicationContext()
    val videoPath = copyAssetVideoToInternalStorage(ctx, assetVideoName)
    evaluator.createInterpreter(ctx)

    Thread {
        try {
            Tasks.await(evaluator.initializeTask)
            evaluator.modelReadyLatch.await()

            val sampler = MetricsSampler(ctx).apply { start() }

            val capture = VideoCapture(videoPath)
            if (!capture.isOpened) {
                Log.e("VideoCapture", "Failed to open video at $videoPath")
                return@Thread
            }

            // Read rotation metadata once
            val retriever = android.media.MediaMetadataRetriever().apply { setDataSource(videoPath) }
            val rotationDeg = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            retriever.release()

            val frame = Mat()
            val work = Mat()     // oriented frame we use for inference & drawing
            val rgbMat = Mat()
            var bitmap: Bitmap? = null
            var frameIndex = 0

            while (capture.read(frame)) {
                // Normalize orientation up front
                when (rotationDeg) {
                    90  -> org.opencv.core.Core.rotate(frame, work, org.opencv.core.Core.ROTATE_90_CLOCKWISE)
                    180 -> org.opencv.core.Core.rotate(frame, work, org.opencv.core.Core.ROTATE_180)
                    270 -> org.opencv.core.Core.rotate(frame, work, org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE)
                    else -> {
                        work.release()
                        frame.copyTo(work)
                    }
                }

                // Inference with timing + trace span
                Trace.beginSection("inference-video")
                val t0 = System.nanoTime()
                val results = evaluator.runModel(work)
                val t1 = System.nanoTime()
                Trace.endSection()
                val inferenceMs = (t1 - t0) / 1e6

                // Draw detections
                val yolo = evaluator.convertYolo(results)
                yolo.stringResults?.let { evaluator.drawDetections(work, it, 1.0f, 1) }
                yolo.bowResults?.let { evaluator.drawDetections(work, it, 1.0f, 0) }

                // Display
                Imgproc.cvtColor(work, rgbMat, Imgproc.COLOR_BGR2RGB)
                if (bitmap == null || bitmap!!.width != rgbMat.cols() || bitmap!!.height != rgbMat.rows()) {
                    bitmap?.recycle()
                    bitmap = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
                }
                Utils.matToBitmap(rgbMat, bitmap!!)
                MainActivity.instance?.runOnUiThread { onFrameEvaluated(bitmap!!) }

                // Metrics every 10 frames
                if (frameIndex % 10 == 0) {
                    val snap = sampler.sample(frameIndex, inferenceMs)
                    Log.i(
                        "Metrics",
                        "frame=${snap.frameIndex} " +
                                "fps=${"%.1f".format(snap.fps)} " +
                                "cpu=${"%.1f".format(snap.appCpuPercent)}% " +
                                "gpu=${snap.gpuBusyPercent?.let { "%.1f".format(it) } ?: "-"}% " +
                                "gpuMHz=${snap.gpuFreqMHz ?: -1} " +
                                "pss=${snap.pssMB}MB " +
                                "java=${"%.1f".format(snap.javaHeapMB)}MB " +
                                "native=${"%.1f".format(snap.nativeHeapMB)}MB " +
                                "sysRamUsed=${"%.1f".format(snap.systemRamUsedPercent)}% " +
                                "appRamPct=${"%.1f".format(snap.appRamPercentOfTotal)}% " +
                                "inference=${"%.2f".format(snap.inferenceMs ?: 0.0)}ms"
                    )
                }

                frameIndex++
            }

            capture.release()
            frame.release()
            work.release()
            rgbMat.release()
            // don't recycle bitmap; UI holds it

        } catch (oom: OutOfMemoryError) {
            Log.e("VideoEvaluation", "OOM while processing video", oom)
        } catch (e: Exception) {
            Log.e("VideoEvaluation", "Failed to evaluate video frames", e)
        }
    }.start()
}

// ------------------------------ Helpers ------------------------------
fun readImageFromAssets(context: Context, filename: String): Mat? {
    return try {
        val assetManager = context.assets
        val inputStream = assetManager.open(filename)

        val suffix = if (filename.contains('.')) filename.substringAfterLast('.') else "png"
        val tempFile = File.createTempFile("inference_asset_", ".${suffix}", context.cacheDir)
        tempFile.outputStream().use { output -> inputStream.copyTo(output) }

        Imgcodecs.imread(tempFile.absolutePath)
    } catch (e: Exception) {
        Log.e("OpenCV", "Failed to read asset: ${e.message}")
        null
    }
}

fun readImageFromPath(assetImageName: String): Mat? {
    val image: Mat? = readImageFromAssets(MainActivity.applicationContext(), assetImageName)
    if (image == null || image.empty()) {
        Log.e("OpenCV", "Error: Could not read image from assets: $assetImageName")
        return null
    }
    return image
}

/** Copies a video asset into internal storage (if needed) and returns an absolute path. */
fun copyAssetVideoToInternalStorage(context: Context, assetFileName: String): String {
    val dest = File(context.filesDir, assetFileName)
    if (!dest.exists()) {
        context.assets.open(assetFileName).use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }
    return dest.absolutePath
}
