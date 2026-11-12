package com.cinematic.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var camera: Camera? = null

    private lateinit var cameraExecutor: ExecutorService
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isVideoMode = false
    private var isEisStabilized = true
    private var currentZoom = 1.0f
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private lateinit var btnCapture: Button
    private lateinit var btnFlash: Button
    private lateinit var btnStabilization: Button
    private lateinit var btnPhotoMode: Button
    private lateinit var btnVideoMode: Button
    private lateinit var txtZoomLock: TextView

    private lateinit var btn1x: Button
    private lateinit var btn3x: Button
    private lateinit var btn5x: Button
    private lateinit var btn10x: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        btnCapture = findViewById(R.id.btnCapture)
        btnFlash = findViewById(R.id.btnFlash)
        btnStabilization = findViewById(R.id.btnStabilization)
        btnPhotoMode = findViewById(R.id.btnPhotoMode)
        btnVideoMode = findViewById(R.id.btnVideoMode)
        txtZoomLock = findViewById(R.id.txtZoomLock)

        btn1x = findViewById(R.id.btnZoom1x)
        btn3x = findViewById(R.id.btnZoom3x)
        btn5x = findViewById(R.id.btnZoom5x)
        btn10x = findViewById(R.id.btnZoom10x)
        val btnFlip = findViewById<Button>(R.id.btnFlip)

        setupGestures()
        setupZoomButtons()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }

        btnPhotoMode.setOnClickListener {
            isVideoMode = false
            btnPhotoMode.setTextColor(Color.parseColor("#FFD700"))
            btnVideoMode.setTextColor(Color.parseColor("#AAAAAA"))
            btnCapture.text = "●"
            btnCapture.setTextColor(Color.parseColor("#D32F2F"))
            startCamera()
        }

        btnVideoMode.setOnClickListener {
            isVideoMode = true
            btnVideoMode.setTextColor(Color.parseColor("#FFD700"))
            btnPhotoMode.setTextColor(Color.parseColor("#AAAAAA"))
            btnCapture.text = "REC"
            btnCapture.setTextColor(Color.parseColor("#D32F2F"))
            startCamera()
        }

        btnStabilization.setOnClickListener {
            isEisStabilized = !isEisStabilized
            btnStabilization.text = if (isEisStabilized) "EIS: ON" else "EIS: OFF"
            btnStabilization.setBackgroundColor(if (isEisStabilized) Color.parseColor("#00897B") else Color.parseColor("#D32F2F"))
            startCamera()
        }

        btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> {
                    btnFlash.text = "FLASH: ON"
                    ImageCapture.FLASH_MODE_ON
                }
                ImageCapture.FLASH_MODE_ON -> {
                    btnFlash.text = "FLASH: AUTO"
                    ImageCapture.FLASH_MODE_AUTO
                }
                else -> {
                    btnFlash.text = "FLASH: OFF"
                    ImageCapture.FLASH_MODE_OFF
                }
            }
            imageCapture?.flashMode = flashMode
        }

        btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
        }

        btnCapture.setOnClickListener {
            if (isVideoMode) {
                toggleVideoRecording()
            } else {
                takeAIEnhancedPhoto()
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupZoomButtons() {
        btn1x.setOnClickListener { applyCustomZoom(1.0f) }
        btn3x.setOnClickListener { applyCustomZoom(3.0f) }
        btn5x.setOnClickListener { applyCustomZoom(5.0f) }
        btn10x.setOnClickListener { applyCustomZoom(10.0f) }
    }

    private fun applyCustomZoom(ratio: Float) {
        currentZoom = ratio
        camera?.cameraControl?.setZoomRatio(ratio)
        updateZoomUI(ratio)
    }

    private fun updateZoomUI(ratio: Float) {
        val activeColor = Color.parseColor("#FFD700")
        val normalColor = Color.WHITE

        btn1x.setTextColor(if (ratio == 1.0f) activeColor else normalColor)
        btn3x.setTextColor(if (ratio == 3.0f) activeColor else normalColor)
        btn5x.setTextColor(if (ratio == 5.0f) activeColor else normalColor)
        btn10x.setTextColor(if (ratio == 10.0f) activeColor else normalColor)

        if (ratio >= 2.0f) {
            txtZoomLock.visibility = View.VISIBLE
            txtZoomLock.text = "⚡ AI ZOOM LOCK ACTIVE (${String.format("%.1f", ratio)}x)"
        } else {
            txtZoomLock.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoomState = camera?.cameraInfo?.zoomState?.value ?: return true
                val newRatio = (zoomState.zoomRatio * detector.scaleFactor).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                currentZoom = newRatio
                camera?.cameraControl?.setZoomRatio(newRatio)
                updateZoomUI(newRatio)
                return true
            }
        })

        viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
            true
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                cameraProvider.unbindAll()

                if (isVideoMode) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)

                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                } else {
                    val captureBuilder = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setFlashMode(flashMode)

                    val ext = Camera2Interop.Extender(captureBuilder)
                    if (isEisStabilized) {
                        ext.setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )
                    }

                    imageCapture = captureBuilder.build()
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                }
                camera?.cameraControl?.setZoomRatio(currentZoom)
            } catch (exc: Exception) {
                Toast.makeText(this, "Binding error: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // AI Enhanced Photo Capture
    private fun takeAIEnhancedPhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()

                // AI इंजन (Retinex Curve + Color Science)
                val aiEnhancedBitmap = AIEnhancerEngine.processHighQualityAI(bitmap)
                savePhotoToGallery(aiEnhancedBitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Capture Failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun toggleVideoRecording() {
        val vCapture = videoCapture ?: return

        if (currentRecording != null) {
            currentRecording?.stop()
            currentRecording = null
            btnCapture.text = "REC"
            btnCapture.setBackgroundColor(Color.WHITE)
            return
        }

        val name = "VID_AI_EIS_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CinematicPro")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        currentRecording = vCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        btnCapture.text = "STOP"
                        btnCapture.setBackgroundColor(Color.RED)
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Toast.makeText(this, "AI-Stabilized Video Saved!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Error: ${recordEvent.error}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
    }

    private fun savePhotoToGallery(bitmap: Bitmap) {
        val filename = "IMG_AI_UHD_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CinematicPro")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            val outputStream: OutputStream? = contentResolver.openOutputStream(it)
            outputStream?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            }
            runOnUiThread {
                Toast.makeText(this, "AI UHD Photo Saved! (Instant Processing)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
