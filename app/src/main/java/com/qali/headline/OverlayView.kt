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
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.qali.headline.util.FaceMeshConstants
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: FaceLandmarkerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()
    private var maskPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private var maskBitmap: Bitmap? = null
    private var sourceLandmarks: List<NormalizedLandmark>? = null
    private var textureCoords: FloatArray? = null
    private var vertexArray: FloatArray? = null

    init {
        initPaints()
    }

    fun clear() {
        results = null
        linePaint.reset()
        pointPaint.reset()
        maskPaint.reset()
        maskBitmap = null
        sourceLandmarks = null
        textureCoords = null
        invalidate()
        initPaints()
    }

    fun setMaskImage(bitmap: Bitmap, landmarks: List<NormalizedLandmark>) {
        this.maskBitmap = bitmap
        this.sourceLandmarks = landmarks

        // Pre-calculate texture coordinates (pixel coordinates in the bitmap)
        val coords = FloatArray(landmarks.size * 2)
        for (i in landmarks.indices) {
            coords[i * 2] = landmarks[i].x() * bitmap.width
            coords[i * 2 + 1] = landmarks[i].y() * bitmap.height
        }
        this.textureCoords = coords

        maskPaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        maskPaint.isAntiAlias = true
        invalidate()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Clear previous drawings if results exist but have no face landmarks
        if (results?.faceLandmarks().isNullOrEmpty()) {
            return
        }

        results?.let { faceLandmarkerResult ->

            // Calculate scaled image dimensions
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor

            // Calculate offsets to center the image on the canvas
            val offsetX = (width - scaledImageWidth) / 2f
            val offsetY = (height - scaledImageHeight) / 2f

            // Iterate through each detected face
            faceLandmarkerResult.faceLandmarks().forEach { faceLandmarks ->
                if (maskBitmap != null && textureCoords != null && sourceLandmarks != null) {
                    drawMask(canvas, faceLandmarks, offsetX, offsetY)
                } else {
                    // Draw all landmarks for the current face
                    drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)

                    // Draw all connectors for the current face
                    drawFaceConnectors(canvas, faceLandmarks, offsetX, offsetY)
                }
            }
        }
    }

    private fun drawMask(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        val texCoords = textureCoords ?: return
        val indices = FaceMeshConstants.TRIANGULATION_INDICES

        // Vertices for the current face (scaled to screen)
        if (vertexArray == null || vertexArray?.size != faceLandmarks.size * 2) {
            vertexArray = FloatArray(faceLandmarks.size * 2)
        }
        val verts = vertexArray!!
        for (i in faceLandmarks.indices) {
            verts[i * 2] = faceLandmarks[i].x() * imageWidth * scaleFactor + offsetX
            verts[i * 2 + 1] = faceLandmarks[i].y() * imageHeight * scaleFactor + offsetY
        }

        // Draw the warped mesh
        // We use the first 468 landmarks as that's what's typically in the canonical model
        // indices refer to these landmarks.

        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            faceLandmarks.size,
            verts,
            0,
            texCoords,
            0,
            null,
            0,
            indices,
            0,
            indices.size,
            maskPaint
        )
    }

    /**
     * Draws all landmarks for a single face on the canvas.
     */
    private fun drawFaceLandmarks(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        faceLandmarks.forEach { landmark ->
            val x = landmark.x() * imageWidth * scaleFactor + offsetX
            val y = landmark.y() * imageHeight * scaleFactor + offsetY
            canvas.drawPoint(x, y, pointPaint)
        }
    }

    /**
     * Draws all the connectors between landmarks for a single face on the canvas.
     */
    private fun drawFaceConnectors(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        FaceLandmarker.FACE_LANDMARKS_CONNECTORS.filterNotNull().forEach { connector ->
            val startLandmark = faceLandmarks.getOrNull(connector.start())
            val endLandmark = faceLandmarks.getOrNull(connector.end())

            if (startLandmark != null && endLandmark != null) {
                val startX = startLandmark.x() * imageWidth * scaleFactor + offsetX
                val startY = startLandmark.y() * imageHeight * scaleFactor + offsetY
                val endX = endLandmark.x() * imageWidth * scaleFactor + offsetX
                val endY = endLandmark.y() * imageHeight * scaleFactor + offsetY

                canvas.drawLine(startX, startY, endX, endY, linePaint)
            }
        }
    }

    fun setResults(
        faceLandmarkerResults: FaceLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = faceLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // PreviewView is in FILL_START mode. So we need to scale up the
                // landmarks to match with the size that the captured images will be
                // displayed.
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
        private const val TAG = "Face Landmarker Overlay"
    }
}
