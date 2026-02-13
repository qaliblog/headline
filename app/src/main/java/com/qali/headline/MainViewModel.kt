package com.qali.headline
/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Mesh(
    val vertices: FloatArray,
    val indices: ShortArray,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float
)

/**
 *  This ViewModel is used to store face landmarker helper settings
 */
class MainViewModel : ViewModel() {

    private var _maskBitmap: Bitmap? = null
    private var _maskLandmarks: List<NormalizedLandmark>? = null

    val maskBitmap: Bitmap? get() = _maskBitmap
    val maskLandmarks: List<NormalizedLandmark>? get() = _maskLandmarks

    fun setMask(bitmap: Bitmap, landmarks: List<NormalizedLandmark>) {
        _maskBitmap = bitmap
        _maskLandmarks = landmarks
    }

    private var _modelMesh: Mesh? = null
    private var _modelLandmarks: List<NormalizedLandmark>? = null
    private var _modelBaseRotationY: Float = 0f
    private val _modelLoaded = MutableLiveData<Boolean>()

    val modelMesh: Mesh? get() = _modelMesh
    val modelLandmarks: List<NormalizedLandmark>? get() = _modelLandmarks
    val modelBaseRotationY: Float get() = _modelBaseRotationY
    val modelLoaded: LiveData<Boolean> get() = _modelLoaded

    fun setModel(mesh: Mesh?, landmarks: List<NormalizedLandmark>?, rotationY: Float) {
        _modelMesh = mesh
        _modelLandmarks = landmarks
        _modelBaseRotationY = rotationY
        _modelLoaded.value = mesh != null
    }

    private var _sphereScale: Float = 1.0f
    private var _offsetZ: Float = 0.0f
    private var _stretchX: Float = 1.0f
    private var _stretchY: Float = 1.0f
    private var _stretchZ: Float = 1.0f

    val sphereScale: Float get() = _sphereScale
    val offsetZ: Float get() = _offsetZ
    val stretchX: Float get() = _stretchX
    val stretchY: Float get() = _stretchY
    val stretchZ: Float get() = _stretchZ

    fun setSphereScale(scale: Float) { _sphereScale = scale }
    fun setOffsetZ(offset: Float) { _offsetZ = offset }
    fun setStretchX(stretch: Float) { _stretchX = stretch }
    fun setStretchY(stretch: Float) { _stretchY = stretch }
    fun setStretchZ(stretch: Float) { _stretchZ = stretch }

    private var _delegate: Int = FaceLandmarkerHelper.DELEGATE_CPU
    private var _minFaceDetectionConfidence: Float =
        FaceLandmarkerHelper.DEFAULT_FACE_DETECTION_CONFIDENCE
    private var _minFaceTrackingConfidence: Float = FaceLandmarkerHelper
        .DEFAULT_FACE_TRACKING_CONFIDENCE
    private var _minFacePresenceConfidence: Float = FaceLandmarkerHelper
        .DEFAULT_FACE_PRESENCE_CONFIDENCE
    private var _maxFaces: Int = FaceLandmarkerHelper.DEFAULT_NUM_FACES

    val currentDelegate: Int get() = _delegate
    val currentMinFaceDetectionConfidence: Float
        get() =
            _minFaceDetectionConfidence
    val currentMinFaceTrackingConfidence: Float
        get() =
            _minFaceTrackingConfidence
    val currentMinFacePresenceConfidence: Float
        get() =
            _minFacePresenceConfidence
    val currentMaxFaces: Int get() = _maxFaces

    fun setDelegate(delegate: Int) {
        _delegate = delegate
    }

    fun setMinFaceDetectionConfidence(confidence: Float) {
        _minFaceDetectionConfidence = confidence
    }
    fun setMinFaceTrackingConfidence(confidence: Float) {
        _minFaceTrackingConfidence = confidence
    }
    fun setMinFacePresenceConfidence(confidence: Float) {
        _minFacePresenceConfidence = confidence
    }

    fun setMaxFaces(maxResults: Int) {
        _maxFaces = maxResults
    }

    fun processModel(context: Context, mesh: Mesh) {
        viewModelScope.launch(Dispatchers.Default) {
            var bestRotation = 0f
            var bestLandmarks: List<NormalizedLandmark>? = null
            var bestScore = -1f

            val tempHelper = FaceLandmarkerHelper(
                context = context,
                runningMode = RunningMode.IMAGE,
                minFaceDetectionConfidence = 0.3f,
                maxNumFaces = 1
            )

            for (angle in listOf(0f, 90f, 180f, 270f)) {
                val bitmap = renderMeshToBitmap(mesh, angle)
                val result = tempHelper.detectImage(bitmap)
                if (result != null && result.result.faceLandmarks().isNotEmpty()) {
                    val landmarks = result.result.faceLandmarks()[0]
                    // Heuristic: best face is the one most centered and largest in the bitmap
                    // For now, just take the first one that works, or simple score
                    val score = 1.0f
                    if (score > bestScore) {
                        bestScore = score
                        bestRotation = angle
                        bestLandmarks = landmarks
                    }
                }
            }
            tempHelper.clearFaceLandmarker()

            withContext(Dispatchers.Main) {
                setModel(mesh, bestLandmarks, bestRotation)
            }
        }
    }

    private fun renderMeshToBitmap(mesh: Mesh, rotationY: Float): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.LTGRAY)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.GRAY
        paint.style = Paint.Style.FILL

        val vertices = mesh.vertices
        val indices = mesh.indices
        val numVertices = vertices.size / 3
        val projected = FloatArray(numVertices * 2)

        val rad = Math.toRadians(rotationY.toDouble()).toFloat()
        val cosA = Math.cos(rad.toDouble()).toFloat()
        val sinA = Math.sin(rad.toDouble()).toFloat()

        var rotMinX = Float.MAX_VALUE; var rotMaxX = -Float.MAX_VALUE
        var rotMinY = Float.MAX_VALUE; var rotMaxY = -Float.MAX_VALUE

        for (i in 0 until numVertices) {
            val x = vertices[i * 3]
            val y = vertices[i * 3 + 1]
            val z = vertices[i * 3 + 2]

            val rx = x * cosA + z * sinA
            val ry = y

            projected[i * 2] = rx
            projected[i * 2 + 1] = ry

            if (rx < rotMinX) rotMinX = rx; if (rx > rotMaxX) rotMaxX = rx
            if (ry < rotMinY) rotMinY = ry; if (ry > rotMaxY) rotMaxY = ry
        }

        val width = rotMaxX - rotMinX
        val height = rotMaxY - rotMinY
        val scale = (size * 0.8f) / Math.max(width, height)
        val offsetX = size / 2f - (rotMinX + rotMaxX) / 2f * scale
        val offsetY = size / 2f - (rotMinY + rotMaxY) / 2f * scale

        for (i in 0 until numVertices) {
            projected[i * 2] = projected[i * 2] * scale + offsetX
            projected[i * 2 + 1] = projected[i * 2 + 1] * scale + offsetY
        }

        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, numVertices, projected, 0, null, 0, null, 0, indices, 0, indices.size, paint)

        return bitmap
    }
}