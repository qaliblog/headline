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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.qali.headline.util.FaceMeshConstants
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.Optional
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
        private const val TAG = "Face Landmarker Overlay"

        // Use all 468 facial landmarks for the mesh
        private val MESH_LANDMARK_INDICES = IntArray(468) { it }

        // Triangle indices from canonical face model
        private val MESH_TRIANGLES = FaceMeshConstants.TRIANGULATION
    }

    private var results: FaceLandmarkerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()
    private var maskPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private var maskBitmap: Bitmap? = null
    private var maskShaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var srcTexCoords = FloatArray(MESH_LANDMARK_INDICES.size * 2)
    private var dstVertices = FloatArray(MESH_LANDMARK_INDICES.size * 2)

    private var modelMesh: Mesh? = null
    private var modelLandmarks: List<NormalizedLandmark>? = null
    private var modelBaseRotationY: Float = 0f

    private var sphereScale: Float = 1f
    private var offsetZ: Float = 0f
    private var stretchX: Float = 1f
    private var stretchY: Float = 1f
    private var stretchZ: Float = 1f

    init {
        initPaints()
    }

    fun clear() {
        results = null
        invalidate()
    }

    fun setMaskImage(bitmap: Bitmap, landmarks: List<NormalizedLandmark>) {
        this.maskBitmap = bitmap
        this.modelMesh = null // Clear 3D model if 2D mask is set

        // Store source coordinates for mesh landmarks
        for (i in MESH_LANDMARK_INDICES.indices) {
            val idx = MESH_LANDMARK_INDICES[i]
            if (idx < landmarks.size) {
                srcTexCoords[i * 2] = landmarks[idx].x() * bitmap.width
                srcTexCoords[i * 2 + 1] = landmarks[idx].y() * bitmap.height
            }
        }

        maskShaderPaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        invalidate()
    }

    fun setModelData(mesh: Mesh?, landmarks: List<NormalizedLandmark>?, rotationY: Float) {
        this.modelMesh = mesh
        this.modelLandmarks = landmarks
        this.modelBaseRotationY = rotationY
        if (mesh != null) this.maskBitmap = null // Clear 2D mask if 3D model is set
        invalidate()
    }

    fun setAdjusters(scale: Float, offsetZ: Float, sx: Float, sy: Float, sz: Float) {
        this.sphereScale = scale
        this.offsetZ = offsetZ
        this.stretchX = sx
        this.stretchY = sy
        this.stretchZ = sz
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
            faceLandmarkerResult.faceLandmarks().forEachIndexed { index, faceLandmarks ->
                if (modelMesh != null && modelLandmarks != null) {
                    val matricesOptional = faceLandmarkerResult.facialTransformationMatrixes()
                    val matrices = if (matricesOptional.isPresent) matricesOptional.get() else null
                    val matrixObj = matrices?.getOrNull(index)
                    draw3DModel(canvas, faceLandmarks, matrixObj, offsetX, offsetY)
                } else if (maskBitmap != null) {
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
        if (maskBitmap == null) return

        for (i in MESH_LANDMARK_INDICES.indices) {
            val idx = MESH_LANDMARK_INDICES[i]
            if (idx < faceLandmarks.size) {
                dstVertices[i * 2] = faceLandmarks[idx].x() * imageWidth * scaleFactor + offsetX
                dstVertices[i * 2 + 1] = faceLandmarks[idx].y() * imageHeight * scaleFactor + offsetY
            } else {
                return // Missing key landmarks
            }
        }

        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            dstVertices.size,
            dstVertices,
            0,
            srcTexCoords,
            0,
            null,
            0,
            MESH_TRIANGLES,
            0,
            MESH_TRIANGLES.size,
            maskShaderPaint
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

    private fun getMatrixData(matrix: Any?): FloatArray? {
        if (matrix == null) return null
        return try {
            val method = matrix.javaClass.methods.firstOrNull { it.returnType == FloatArray::class.java }
            method?.invoke(matrix) as? FloatArray
        } catch (e: Exception) {
            null
        }
    }

    private fun draw3DModel(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        transformationMatrixObj: Any?,
        offsetX: Float,
        offsetY: Float
    ) {
        val transformationMatrix = getMatrixData(transformationMatrixObj)
        val mesh = modelMesh ?: return
        val modLandmarks = modelLandmarks ?: return

        val liveSphere = getBoundingSphere(faceLandmarks, (imageWidth * scaleFactor).toInt(), (imageHeight * scaleFactor).toInt())
        val modelSphere = getBoundingSphere(modLandmarks, 512, 512)

        val baseScale = liveSphere.radius / modelSphere.radius
        val finalScale = baseScale * sphereScale

        val vertices = mesh.vertices
        val indices = mesh.indices
        val numVertices = vertices.size / 3
        val projected = FloatArray(numVertices * 2)
        val zCoords = FloatArray(numVertices)

        // Pre-calculate base rotation
        val baseRad = Math.toRadians(modelBaseRotationY.toDouble()).toFloat()
        val cosB = Math.cos(baseRad.toDouble()).toFloat()
        val sinB = Math.sin(baseRad.toDouble()).toFloat()

        // We can use the transformationMatrix for rotation if it exists
        // MediaPipe transformation matrix is 4x4
        // If not, we'll just do a simple centered draw

        // Let's re-center the mesh based on its own bounding box first.
        val mCX = (mesh.maxX + mesh.minX) / 2f
        val mCY = (mesh.maxY + mesh.minY) / 2f
        val mCZ = (mesh.maxZ + mesh.minZ) / 2f

        for (i in 0 until numVertices) {
            val x = vertices[i * 3]
            val y = vertices[i * 3 + 1]
            val z = vertices[i * 3 + 2]

            var rx = x - mCX
            var ry = y - mCY
            var rz = z - mCZ

            // 2. Apply base rotation
            val rrx = rx * cosB + rz * sinB
            val rry = ry
            val rrz = -rx * sinB + rz * cosB

            rx = rrx; ry = rry; rz = rrz

            // 3. Apply user stretch
            rx *= stretchX
            ry *= stretchY
            rz *= stretchZ

            // 4. Apply transformation matrix if available (for live rotation)
            if (transformationMatrix != null && transformationMatrix.size == 16) {
                val tx = transformationMatrix[0] * rx + transformationMatrix[4] * ry + transformationMatrix[8] * rz
                val ty = transformationMatrix[1] * rx + transformationMatrix[5] * ry + transformationMatrix[9] * rz
                val tz = transformationMatrix[2] * rx + transformationMatrix[6] * ry + transformationMatrix[10] * rz
                rx = tx; ry = ty; rz = tz
            }

            // 5. Scale and translate to live sphere
            val fx = rx * finalScale + liveSphere.centerX + offsetX
            val fy = ry * finalScale + liveSphere.centerY + offsetY
            val fz = rz * finalScale + liveSphere.centerZ + offsetZ * liveSphere.radius

            projected[i * 2] = fx
            projected[i * 2 + 1] = fy
            zCoords[i] = fz
        }

        // Sort triangles by average Z for basic depth buffering
        val numTriangles = indices.size / 3
        val triIndices = (0 until numTriangles).sortedByDescending { t ->
            val v1 = indices[t * 3].toInt() and 0xFFFF
            val v2 = indices[t * 3 + 1].toInt() and 0xFFFF
            val v3 = indices[t * 3 + 2].toInt() and 0xFFFF
            (zCoords[v1] + zCoords[v2] + zCoords[v3]) / 3f
        }

        val sortedIndices = ShortArray(indices.size)
        for (i in 0 until numTriangles) {
            val t = triIndices[i]
            sortedIndices[i * 3] = indices[t * 3]
            sortedIndices[i * 3 + 1] = indices[t * 3 + 1]
            sortedIndices[i * 3 + 2] = indices[t * 3 + 2]
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.GRAY
        paint.style = Paint.Style.FILL

        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            numVertices,
            projected,
            0,
            null,
            0,
            null,
            0,
            sortedIndices,
            0,
            sortedIndices.size,
            paint
        )
    }


    private data class Sphere(val centerX: Float, val centerY: Float, val centerZ: Float, val radius: Float)

    private fun getBoundingSphere(landmarks: List<NormalizedLandmark>, width: Int, height: Int): Sphere {
        var sumX = 0f; var sumY = 0f; var sumZ = 0f
        for (l in landmarks) {
            sumX += l.x() * width
            sumY += l.y() * height
            sumZ += l.z() * width
        }
        val cx = sumX / landmarks.size
        val cy = sumY / landmarks.size
        val cz = sumZ / landmarks.size

        var maxDistSq = 0f
        for (l in landmarks) {
            val dx = l.x() * width - cx
            val dy = l.y() * height - cy
            val dz = l.z() * width - cz
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq > maxDistSq) maxDistSq = distSq
        }
        return Sphere(cx, cy, cz, Math.sqrt(maxDistSq.toDouble()).toFloat())
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

}
