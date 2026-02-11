package com.qali.headline.util

import android.opengl.Matrix
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

object PoseUtils {
    // Tuning Constants
    var OFFSET_X = 0.0f
    var OFFSET_Y = 0.0f
    var OFFSET_Z = 0.0f
    var SCALE_FACTOR = 1.0f

    /**
     * Combines MediaPipe face pose with correction, scale and offset.
     * Order: Pose * Correction * Scale * Offset
     */
    fun getFinalMatrix(
        facePoseMatrix: FloatArray, // 4x4 column-major from MediaPipe
        landmarks: List<NormalizedLandmark>,
        aspect: Float
    ): FloatArray {
        val finalMatrix = FloatArray(16)
        val temp1 = FloatArray(16)
        val temp2 = FloatArray(16)

        // 1. Offset Translation Matrix (to center model)
        val offsetMatrix = FloatArray(16)
        Matrix.setIdentityM(offsetMatrix, 0)
        Matrix.translateM(offsetMatrix, 0, OFFSET_X, OFFSET_Y, OFFSET_Z)

        // 2. Scale Matrix
        // Compute scale from 3D distance between eyes (landmarks 33 and 263)
        val eyeDist = calculate3DDistance(landmarks[33], landmarks[263], aspect)
        // Reference eye distance (normalized) is roughly 0.06 in canonical space?
        // We'll use a relative scale.
        val baseScale = eyeDist * 10.0f * SCALE_FACTOR
        val scaleMatrix = FloatArray(16)
        Matrix.setIdentityM(scaleMatrix, 0)
        Matrix.scaleM(scaleMatrix, 0, baseScale, baseScale, baseScale)

        // 3. Correction Matrix (Coordinate System Mismatch)
        // MediaPipe facialTransformationMatrix usually has:
        // X: right, Y: up, Z: forward (towards camera)
        // Filament: X: right, Y: up, Z: back (out of screen)
        // We might need to flip Z.
        val correctionMatrix = FloatArray(16)
        Matrix.setIdentityM(correctionMatrix, 0)
        // Matrix.scaleM(correctionMatrix, 0, 1f, 1f, -1f) // Example flip

        // 4. Combine: Final = Pose * Correction * Scale * Offset
        // Multiply: temp1 = Scale * Offset
        Matrix.multiplyMM(temp1, 0, scaleMatrix, 0, offsetMatrix, 0)
        // Multiply: temp2 = Correction * temp1
        Matrix.multiplyMM(temp2, 0, correctionMatrix, 0, temp1, 0)
        // Multiply: final = Pose * temp2
        Matrix.multiplyMM(finalMatrix, 0, facePoseMatrix, 0, temp2, 0)

        return finalMatrix
    }

    private fun calculate3DDistance(p1: NormalizedLandmark, p2: NormalizedLandmark, aspect: Float): Float {
        val dx = (p1.x() - p2.x()) * aspect
        val dy = p1.y() - p2.y()
        val dz = p1.z() - p2.z()
        return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }
}
