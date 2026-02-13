package com.qali.headline

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "RecordingChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoUri: Uri? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Video")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            startRecording(resultCode, data)
        }

        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics
            setupMediaRecorder(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

            mediaRecorder?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording in service", e)
        }
    }

    private fun setupMediaRecorder(width: Int, height: Int, density: Int) {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val filename = "recording_${System.currentTimeMillis()}.mp4"
        val evenWidth = if (width % 2 == 0) width else width - 1
        val evenHeight = if (height % 2 == 0) height else height - 1

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Headline")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        val fileDescriptor = videoUri?.let { contentResolver.openFileDescriptor(it, "rw") }?.fileDescriptor

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (fileDescriptor != null) {
                setOutputFile(fileDescriptor)
            } else {
                val videoFile = File(getExternalFilesDir(null), filename)
                setOutputFile(videoFile.absolutePath)
            }
            setVideoSize(evenWidth, evenHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(5 * 1024 * 1024)
            setVideoFrameRate(30)
            prepare()

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RecordingDisplay", evenWidth, evenHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaRecorder", e)
        }
        mediaRecorder?.release()
        virtualDisplay?.release()
        mediaProjection?.stop()

        videoUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, contentValues, null, null)
            }
        }

        super.onDestroy()
    }
}
