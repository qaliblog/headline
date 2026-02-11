package com.google.mediapipe.examples.facelandmarker.util

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import android.opengl.Matrix
import kotlin.math.sqrt

object PoseUtils {
    // Landmark indices
    private const val NOSE_TIP = 1
    private const val LEFT_EYE = 33
    private const val RIGHT_EYE = 263

    /**
     * Calculates a 4x4 transform matrix for the 3D model using normalized landmarks.
     *
     * @param landmarks Normalized landmarks from MediaPipe
     * @param aspect Aspect ratio of the view (width / height)
     */
    fun calculateTransformMatrix(
        landmarks: List<NormalizedLandmark>,
        aspect: Float
    ): FloatArray {
        val leftEye = landmarks[LEFT_EYE]
        val rightEye = landmarks[RIGHT_EYE]
        val noseTip = landmarks[NOSE_TIP]

        // 1. Calculate Basis Vectors for Rotation
        // We adjust for aspect ratio to get correct angles in screen space
        val dx = (rightEye.x() - leftEye.x()) * aspect
        val dy = rightEye.y() - leftEye.y()
        val dz = rightEye.z() - leftEye.z()
        val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

        val rx = dx / dist
        val ry = dy / dist
        val rz = dz / dist

        val midNormX = (leftEye.x() + rightEye.x()) / 2f
        val midNormY = (leftEye.y() + rightEye.y()) / 2f
        val midNormZ = (leftEye.z() + rightEye.z()) / 2f

        val nx = (noseTip.x() - midNormX) * aspect
        val ny = noseTip.y() - midNormY
        val nz = noseTip.z() - midNormZ

        // Z-axis (Forward): Cross product of X and Nose vector
        var zx = ry * nz - rz * ny
        var zy = rz * nx - rx * nz
        var zz = rx * ny - ry * nx
        val zDist = sqrt((zx * zx + zy * zy + zz * zz).toDouble()).toFloat()
        zx /= zDist
        zy /= zDist
        zz /= zDist

        // Y-axis (Up): Cross product of Z and X
        val yx = zy * rz - zz * ry
        val yy = zz * rx - zx * rz
        val yz = zx * ry - zy * rx

        // 2. Scale
        // Use eye distance. Since everything is normalized [0, 1],
        // we scale the model to match the head size.
        // A typical head is about 3x the eye distance.
        val scale = dist * 2.0f

        // 3. Position
        // Map [0, 1] to Filament space [-aspect, aspect] x [-1, 1]
        // Assuming camera is set up with height 2.0 at Z=0.
        val tx = (midNormX - 0.5f) * 2f * aspect
        val ty = (0.5f - midNormY) * 2f

        // 4. Build Matrix (Column-major)
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)

        // Column 0 (Right)
        matrix[0] = rx * scale
        matrix[1] = -ry * scale // Filament Y is up
        matrix[2] = rz * scale

        // Column 1 (Up)
        matrix[4] = yx * scale
        matrix[5] = -yy * scale
        matrix[6] = yz * scale

        // Column 2 (Forward)
        matrix[8] = zx * scale
        matrix[9] = -zy * scale
        matrix[10] = zz * scale

        // Column 3 (Translation)
        matrix[12] = tx
        matrix[13] = ty
        matrix[14] = -0.5f // Slightly in front of origin

        return matrix
    }
}
