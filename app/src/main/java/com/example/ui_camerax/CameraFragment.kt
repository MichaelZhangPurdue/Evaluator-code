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
package com.example.ui_camerax.fragment
/*
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.example.ui_camerax.HandLandmarkerHelper
import com.example.ui_camerax.MainViewModel
import com.example.ui_camerax.R
import com.example.ui_camerax.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// You must change the listener interface to the new CombinedLandmarkerListener
class CameraFragment : Fragment(), HandLandmarkerHelper.CombinedLandmarkerListener {

    companion object {
        private const val TAG = "Hand Landmarker"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    // The HandLandmarkerHelper now acts as a combined helper, so the name is fine
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        backgroundExecutor.execute {
            if (handLandmarkerHelper.isClose()) {
                // Now you will call setupLandmarkers which sets up both models
                handLandmarkerHelper.setupHandLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if(this::handLandmarkerHelper.isInitialized) {
            viewModel.setMaxHands(handLandmarkerHelper.maxNumHands)
            viewModel.setMinHandDetectionConfidence(handLandmarkerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(handLandmarkerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(handLandmarkerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(handLandmarkerHelper.currentDelegate)

            // Now you will call clearLandmarkers() which closes both models
            backgroundExecutor.execute { handLandmarkerHelper.clearLandmarkers() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backgroundExecutor = Executors.newSingleThreadExecutor()

        fragmentCameraBinding.viewFinder.post {
            setUpCamera()
        }

        // Initialize the helper with the new combined listener
        backgroundExecutor.execute {
            handLandmarkerHelper = HandLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                maxNumHands = viewModel.currentMaxHands,
                currentDelegate = viewModel.currentDelegate,
                combinedLandmarkerHelperListener = this // Change to the new listener name
            )
        }

        initBottomSheetControls()
    }

    private fun initBottomSheetControls() {
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            viewModel.currentMaxHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandPresenceConfidence
            )

        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence >= 0.2) {
                handLandmarkerHelper.minHandDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence <= 0.8) {
                handLandmarkerHelper.minHandDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence >= 0.2) {
                handLandmarkerHelper.minHandTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence <= 0.8) {
                handLandmarkerHelper.minHandTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence >= 0.2) {
                handLandmarkerHelper.minHandPresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence <= 0.8) {
                handLandmarkerHelper.minHandPresenceConfidence += 0.1f
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.maxHandsMinus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands > 1) {
                handLandmarkerHelper.maxNumHands--
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.maxHandsPlus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands < 2) {
                handLandmarkerHelper.maxNumHands++
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        handLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch(e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "HandLandmarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            handLandmarkerHelper.maxNumHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandPresenceConfidence
            )

        backgroundExecutor.execute {
            // Call the combined clear and setup methods
            handLandmarkerHelper.clearLandmarkers()
            handLandmarkerHelper.setupHandLandmarker()
            handLandmarkerHelper.setupPoseLandmarker()
        }
        fragmentCameraBinding.overlay.clear()
    }

    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        // The method name can be changed to something more generic like detect()
                        detectLandmarks(image)
                    }
                }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // You can rename this to something like detectLandmarks()
    private fun detectLandmarks(imageProxy: ImageProxy) {
        handLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Now, the onResults method receives a CombinedResultBundle
// In CameraFragment.kt, change the onResults method to this

    override fun onResults(resultBundle: HandLandmarkerHelper.CombinedResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)

                // Pass the HandLandmarkerResult and PoseLandmarkerResult objects
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.handResults.firstOrNull(),
                    resultBundle.poseResults.firstOrNull(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth
                )

                // Force a redraw
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == HandLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    HandLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }
}

 */