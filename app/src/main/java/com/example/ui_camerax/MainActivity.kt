package com.example.ui_camerax

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.example.evaluator_kotlin.Detector
import com.example.ui_camerax.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max


typealias LumaListener = (luma: Double) -> Unit



class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null
    private var detector: Detector? = null


    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var frontCamera = false
    private var foundDims = false

    private lateinit var overlayView: OverlayView

    private lateinit var cameraExecutor: ExecutorService



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        overlayView = viewBinding.overlayView

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }
        enableEdgeToEdge()

        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }


    private fun takePhoto() {}

    private fun captureVideo() {}

    private fun startCamera() {
        detector = Detector(this, this)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            //IMage analyzer stuff
            val imageAnalyzer = ImageAnalysis.Builder()

                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                //.setTargetRotation(viewBinding.viewFinder.display.rotation)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                val bitmapBuffer =
                    createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
                //println("PHONE ORIENTATION ${imageProxy.imageInfo.rotationDegrees}")
                val matrix = Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                }

                val rotatedBitmap = Bitmap.createBitmap(
                    bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                    matrix, true
                )
                detector?.detect(rotatedBitmap)

            }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))

    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun noDetect() {
        println("no detect")
        //HANDLE THIS CASE BY TELLING USER
        runOnUiThread {
            overlayView.updateResults(Detector.returnBow(-2, null, null, 0))
        }
    }

    override fun detected(results: Detector.YoloResults, sourceWidth: Int, sourceHeight: Int) {
        /*
        if (!foundDims) {
            var dims: Pair<Int, Int> ?= null
            dims = overlayView.returnDims()
            if (dims.first != 0) {
                foundDims = true
                detector?.setDimensions(dims)
            }
        }

         */
        val bowPoints = detector?.classify(results)

        val overlayWidth = overlayView.width
        val overlayHeight = overlayView.height
        val scaleFactor = max(
            overlayWidth.toFloat() / sourceWidth,
            overlayHeight.toFloat() / sourceHeight
        )
        val scaledImageWidth = sourceWidth * scaleFactor
        val scaledImageHeight = sourceHeight * scaleFactor
        val offsetX = (scaledImageWidth - overlayWidth) / 2f
        val offsetY = (scaledImageHeight - overlayHeight) / 2f
        bowPoints?.bow?.forEach { point ->
            point.x = (point.x * scaleFactor) - offsetX
            point.y = (point.y * scaleFactor) - offsetY
        }
        bowPoints?.string?.forEach { point ->
            point.x = (point.x * scaleFactor) - offsetX
            point.y = (point.y * scaleFactor) - offsetY
        }
        println("DETECTED")
        runOnUiThread {
            if (bowPoints != null) {
                overlayView.updateResults(bowPoints)
            }
        }
        println(bowPoints)
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }




    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()

    }
}
