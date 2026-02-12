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
package com.qali.headline.fragment

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING
import com.qali.headline.FaceLandmarkerHelper
import com.qali.headline.MainViewModel
import com.qali.headline.R
import com.qali.headline.RecordingService
import com.qali.headline.databinding.FragmentCameraBinding
import com.qali.headline.renderer.FaceMaskRenderer
import com.qali.headline.util.PoseUtils
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Face Landmarker"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private val faceBlendshapesResultAdapter by lazy {
        FaceBlendshapesResultAdapter()
    }

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private var isRecording = false
    private lateinit var mediaProjectionManager: MediaProjectionManager

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    private lateinit var faceMaskRenderer: FaceMaskRenderer
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            faceMaskRenderer.render()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadModelFromUri(it) }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            startRecordingWithPermission(data)
        } else {
            Toast.makeText(requireContext(), "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            isRecording = false
            updateRecordButtonUi()
        }
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(frameCallback)
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // Start the FaceLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (faceLandmarkerHelper.isClose()) {
                faceLandmarkerHelper.setupFaceLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if(this::faceLandmarkerHelper.isInitialized) {
            viewModel.setMaxFaces(faceLandmarkerHelper.maxNumFaces)
            viewModel.setMinFaceDetectionConfidence(faceLandmarkerHelper.minFaceDetectionConfidence)
            viewModel.setMinFaceTrackingConfidence(faceLandmarkerHelper.minFaceTrackingConfidence)
            viewModel.setMinFacePresenceConfidence(faceLandmarkerHelper.minFacePresenceConfidence)
            viewModel.setDelegate(faceLandmarkerHelper.currentDelegate)

            // Close the FaceLandmarkerHelper and release resources
            backgroundExecutor.execute { faceLandmarkerHelper.clearFaceLandmarker() }
        }
    }

    override fun onDestroyView() {
        if (isRecording) {
            stopRecording()
        }
        _fragmentCameraBinding = null
        faceMaskRenderer.onDestroy()
        super.onDestroyView()

        // Shut down our background executor
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

        faceMaskRenderer = FaceMaskRenderer(requireContext())
        faceMaskRenderer.setDebugMode(false) // Set to true to see debug cube and logs
        fragmentCameraBinding.filamentSurface.apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            faceMaskRenderer.init(this)
        }

        loadDefaultModel()

        fragmentCameraBinding.fabModelPicker.setOnClickListener {
            showModelPicker()
        }

        mediaProjectionManager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        fragmentCameraBinding.fabRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        with(fragmentCameraBinding.recyclerviewResults) {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = faceBlendshapesResultAdapter
        }

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Create the FaceLandmarkerHelper that will handle the inference
        backgroundExecutor.execute {
            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minFaceDetectionConfidence = viewModel.currentMinFaceDetectionConfidence,
                minFaceTrackingConfidence = viewModel.currentMinFaceTrackingConfidence,
                minFacePresenceConfidence = viewModel.currentMinFacePresenceConfidence,
                maxNumFaces = viewModel.currentMaxFaces,
                currentDelegate = viewModel.currentDelegate,
                faceLandmarkerHelperListener = this
            )
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()
        updateModelAdjusterUi()
    }

    private fun loadDefaultModel() {
        try {
            val buffer = readAsset("models/face_mask.glb")
            faceMaskRenderer.loadModel(buffer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load default model", e)
        }
    }

    private fun readAsset(path: String): ByteBuffer {
        val inputStream = requireContext().assets.open(path)
        val bytes = inputStream.readBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.flip()
        return buffer
    }

    private fun showModelPicker() {
        val assets = requireContext().assets.list("models")?.toList() ?: emptyList()
        val bottomSheet = ModelPickerBottomSheet(
            models = assets,
            onModelSelected = { modelPath ->
                val buffer = readAsset("models/$modelPath")
                faceMaskRenderer.loadModel(buffer)
            },
            onFilePickerClicked = {
                filePickerLauncher.launch("*/*")
            }
        )
        bottomSheet.show(childFragmentManager, "ModelPicker")
    }

    private fun loadModelFromUri(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.flip()
            faceMaskRenderer.loadModel(buffer)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to load model", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initBottomSheetControls() {
        // init bottom sheet settings
        fragmentCameraBinding.bottomSheetLayout.maxFacesValue.text =
            viewModel.currentMaxFaces.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinFaceDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinFaceTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinFacePresenceConfidence
            )

        // When clicked, lower face detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceDetectionConfidence >= 0.2) {
                faceLandmarkerHelper.minFaceDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise face detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceDetectionConfidence <= 0.8) {
                faceLandmarkerHelper.minFaceDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower face tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceTrackingConfidence >= 0.2) {
                faceLandmarkerHelper.minFaceTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise face tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceTrackingConfidence <= 0.8) {
                faceLandmarkerHelper.minFaceTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower face presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (faceLandmarkerHelper.minFacePresenceConfidence >= 0.2) {
                faceLandmarkerHelper.minFacePresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise face presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (faceLandmarkerHelper.minFacePresenceConfidence <= 0.8) {
                faceLandmarkerHelper.minFacePresenceConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of faces that can be detected at a
        // time
        fragmentCameraBinding.bottomSheetLayout.maxFacesMinus.setOnClickListener {
            if (faceLandmarkerHelper.maxNumFaces > 1) {
                faceLandmarkerHelper.maxNumFaces--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of faces that can be detected
        // at a time
        fragmentCameraBinding.bottomSheetLayout.maxFacesPlus.setOnClickListener {
            if (faceLandmarkerHelper.maxNumFaces < 2) {
                faceLandmarkerHelper.maxNumFaces++
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        faceLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch(e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "FaceLandmarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }

        // Model Scale
        fragmentCameraBinding.bottomSheetLayout.scaleMinus.setOnClickListener {
            if (viewModel.currentScaleFactor > 0.1f) {
                viewModel.setScaleFactor(viewModel.currentScaleFactor - 0.1f)
                updateModelAdjusterUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.scalePlus.setOnClickListener {
            if (viewModel.currentScaleFactor < 5.0f) {
                viewModel.setScaleFactor(viewModel.currentScaleFactor + 0.1f)
                updateModelAdjusterUi()
            }
        }

        // Offset X
        fragmentCameraBinding.bottomSheetLayout.offsetXMinus.setOnClickListener {
            viewModel.setOffsetX(viewModel.currentOffsetX - 0.01f)
            updateModelAdjusterUi()
        }
        fragmentCameraBinding.bottomSheetLayout.offsetXPlus.setOnClickListener {
            viewModel.setOffsetX(viewModel.currentOffsetX + 0.01f)
            updateModelAdjusterUi()
        }

        // Offset Y
        fragmentCameraBinding.bottomSheetLayout.offsetYMinus.setOnClickListener {
            viewModel.setOffsetY(viewModel.currentOffsetY - 0.01f)
            updateModelAdjusterUi()
        }
        fragmentCameraBinding.bottomSheetLayout.offsetYPlus.setOnClickListener {
            viewModel.setOffsetY(viewModel.currentOffsetY + 0.01f)
            updateModelAdjusterUi()
        }

        // Offset Z
        fragmentCameraBinding.bottomSheetLayout.offsetZMinus.setOnClickListener {
            viewModel.setOffsetZ(viewModel.currentOffsetZ - 0.01f)
            updateModelAdjusterUi()
        }
        fragmentCameraBinding.bottomSheetLayout.offsetZPlus.setOnClickListener {
            viewModel.setOffsetZ(viewModel.currentOffsetZ + 0.01f)
            updateModelAdjusterUi()
        }
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        updateRecordButtonUi()

        val intent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun startRecordingWithPermission(data: Intent) {
        try {
            // Start Foreground Service with projection data
            val serviceIntent = Intent(requireContext(), RecordingService::class.java).apply {
                putExtra("RESULT_CODE", Activity.RESULT_OK)
                putExtra("DATA", data)
            }
            ContextCompat.startForegroundService(requireContext(), serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording service", e)
            Toast.makeText(requireContext(), "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
            updateRecordButtonUi()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        updateRecordButtonUi()

        val serviceIntent = Intent(requireContext(), RecordingService::class.java)
        requireContext().stopService(serviceIntent)

        Toast.makeText(requireContext(), "Video saved to External Files", Toast.LENGTH_LONG).show()
    }

    private fun updateRecordButtonUi() {
        if (isRecording) {
            fragmentCameraBinding.fabRecord.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            fragmentCameraBinding.fabRecord.setImageResource(R.drawable.ic_baseline_photo_camera_24)
        }
    }

    private fun updateModelAdjusterUi() {
        fragmentCameraBinding.bottomSheetLayout.scaleValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentScaleFactor)
        fragmentCameraBinding.bottomSheetLayout.offsetXValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentOffsetX)
        fragmentCameraBinding.bottomSheetLayout.offsetYValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentOffsetY)
        fragmentCameraBinding.bottomSheetLayout.offsetZValue.text =
            String.format(Locale.US, "%.2f", viewModel.currentOffsetZ)

        PoseUtils.SCALE_FACTOR = viewModel.currentScaleFactor
        PoseUtils.OFFSET_X = viewModel.currentOffsetX
        PoseUtils.OFFSET_Y = viewModel.currentOffsetY
        PoseUtils.OFFSET_Z = viewModel.currentOffsetZ
    }


    // Update the values displayed in the bottom sheet. Reset Facelandmarker
    // helper.
    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxFacesValue.text =
            faceLandmarkerHelper.maxNumFaces.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                faceLandmarkerHelper.minFaceDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                faceLandmarkerHelper.minFaceTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                faceLandmarkerHelper.minFacePresenceConfidence
            )

        // Needs to be cleared instead of reinitialized because the GPU
        // delegate needs to be initialized on the thread using it when applicable
        backgroundExecutor.execute {
            faceLandmarkerHelper.clearFaceLandmarker()
            faceLandmarkerHelper.setupFaceLandmarker()
        }
        fragmentCameraBinding.overlay.clear()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectFace(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectFace(imageProxy: ImageProxy) {
        faceLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after face have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(
        resultBundle: FaceLandmarkerHelper.ResultBundle
    ) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                if (fragmentCameraBinding.recyclerviewResults.scrollState != SCROLL_STATE_DRAGGING) {
                    faceBlendshapesResultAdapter.updateResults(resultBundle.result)
                    faceBlendshapesResultAdapter.notifyDataSetChanged()
                }


                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)

                // Pass necessary information to OverlayView for drawing on the canvas
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                // Update 3D model transform
                if (resultBundle.result.faceLandmarks().isNotEmpty() &&
                    resultBundle.result.facialTransformationMatrixes().isPresent) {
                    val landmarks = resultBundle.result.faceLandmarks()[0]
                    val poseMatrix = resultBundle.result.facialTransformationMatrixes().get()[0]
                    val aspect = fragmentCameraBinding.filamentSurface.width.toFloat() /
                                 fragmentCameraBinding.filamentSurface.height.toFloat()

                    val matrix = PoseUtils.getFinalMatrix(
                        poseMatrix,
                        landmarks,
                        aspect
                    )
                    faceMaskRenderer.updateModelTransform(matrix)
                }

                // Force a redraw
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    override fun onEmpty() {
        fragmentCameraBinding.overlay.clear()
        activity?.runOnUiThread {
            faceBlendshapesResultAdapter.updateResults(null)
            faceBlendshapesResultAdapter.notifyDataSetChanged()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            faceBlendshapesResultAdapter.updateResults(null)
            faceBlendshapesResultAdapter.notifyDataSetChanged()

            if (errorCode == FaceLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    FaceLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }
}
