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
/*
import androidx.lifecycle.ViewModel

/**
 * This ViewModel is used to store hand and pose landmarker helper settings
 */
class MainViewModel : ViewModel() {

    // Hand Landmarker Settings
    private var _delegate: Int = HandLandmarkerHelper.DELEGATE_CPU
    private var _minHandDetectionConfidence: Float =
        HandLandmarkerHelper.DEFAULT_HAND_DETECTION_CONFIDENCE
    private var _minHandTrackingConfidence: Float = HandLandmarkerHelper
        .DEFAULT_HAND_TRACKING_CONFIDENCE
    private var _minHandPresenceConfidence: Float = HandLandmarkerHelper
        .DEFAULT_HAND_PRESENCE_CONFIDENCE
    private var _maxHands: Int = HandLandmarkerHelper.DEFAULT_NUM_HANDS

    // Use the constants from HandLandmarkerHelper directly
    private var _minPoseDetectionConfidence: Float =
        HandLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE
    private var _minPoseTrackingConfidence: Float =
        HandLandmarkerHelper.DEFAULT_POSE_TRACKING_CONFIDENCE
    private var _minPosePresenceConfidence: Float =
        HandLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE

    // Public getters for Hand settings
    val currentDelegate: Int get() = _delegate
    val currentMinHandDetectionConfidence: Float
        get() = _minHandDetectionConfidence
    val currentMinHandTrackingConfidence: Float
        get() = _minHandTrackingConfidence
    val currentMinHandPresenceConfidence: Float
        get() = _minHandPresenceConfidence
    val currentMaxHands: Int get() = _maxHands

    // Public getters for Pose settings (New)
    val currentMinPoseDetectionConfidence: Float
        get() = _minPoseDetectionConfidence
    val currentMinPoseTrackingConfidence: Float
        get() = _minPoseTrackingConfidence
    val currentMinPosePresenceConfidence: Float
        get() = _minPosePresenceConfidence

    // Setter methods for Hand settings
    fun setDelegate(delegate: Int) {
        _delegate = delegate
    }

    fun setMinHandDetectionConfidence(confidence: Float) {
        _minHandDetectionConfidence = confidence
    }

    fun setMinHandTrackingConfidence(confidence: Float) {
        _minHandTrackingConfidence = confidence
    }

    fun setMinHandPresenceConfidence(confidence: Float) {
        _minHandPresenceConfidence = confidence
    }

    fun setMaxHands(maxResults: Int) {
        _maxHands = maxResults
    }

    // Setter methods for Pose settings (New)
    fun setMinPoseDetectionConfidence(confidence: Float) {
        _minPoseDetectionConfidence = confidence
    }

    fun setMinPoseTrackingConfidence(confidence: Float) {
        _minPoseTrackingConfidence = confidence
    }

    fun setMinPosePresenceConfidence(confidence: Float) {
        _minPosePresenceConfidence = confidence
    }
}

 */