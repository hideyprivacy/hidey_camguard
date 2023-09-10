package com.example.hideycamguard

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.os.postDelayed
import com.example.hideycamguard.ImageUtil.Companion.bitmapToByteBuffer
import com.example.hideycamguard.ImageUtil.Companion.bitmapToJpeg
import com.example.hideycamguard.ImageUtil.Companion.yuv420ToBitmap
import okhttp3.*
import okio.HashingSink
import okio.Okio
import okio.buffer
import okio.sink
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.InterpreterApi
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*


// ... other imports

class MainActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var webView: WebView
    private lateinit var playButton: Button
    private lateinit var playerLayout: LinearLayout
    private lateinit var quitButton: Button

    private lateinit var tflite: InterpreterApi
    private val handler = Handler()
    private lateinit var cameraManager: CameraManager
    private lateinit var frontCameraId: String

    //private val capturedMats: MutableList<Mat> = mutableListOf()
    private val capturedBitmap: MutableList<Bitmap> = mutableListOf()

    private var captureInterval = 15000 // 15 seconds, can be updated
    private val maxCaptures = 10
    private var continueCapturing = true
    private var modelInitialized = false

    private val MODEL_NAME = "hidey_camguard.tflite"
    private val TAG = "MainActivity"
    private val MODEL_URl = "https://github.com/hideyprivacy/hidey_camguard/raw/main/app/src/main/assets/hidey_camguard.tflite"
    private var isPlaying = false

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        playButton = findViewById(R.id.playVideoButton)
        playerLayout = findViewById(R.id.playerLayout)
        quitButton = findViewById(R.id.quitButton)

        initializeWebView()
        downloadAndInitializeModel(applicationContext, MODEL_URl, "hidey_camguard")
        initializeFrontCamera()
        setUpPlayerControls()
    }

    private fun initializeWebView() {
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()
    }

    private fun downloadAndInitializeModel(context: Context, modelUrl: String, modelName: String) {
        val modelFile = File(context.filesDir, modelName)

        if (modelFile.exists()) {
            // Model already downloaded, initialize it.
            initializeModel(modelFile)
        } else {
            // Model doesn't exist, download and then initialize it.
            val request = Request.Builder().url(modelUrl).build()
            val client = OkHttpClient()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Handle failure
                    handler.post {
                        Toast.makeText(context, "Failed to download modle", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body!!
                    val contentLength = responseBody.contentLength()

                    val sink = modelFile.sink().buffer()
                    val source = responseBody.source()

                    var totalBytesRead: Long = 0
                    val bufferSize: Long = 8 * 1024 // 8 KB
                    var readBytes: Long

                    while (source.read(sink.buffer(), bufferSize).also { readBytes = it } != -1L) {
                        sink.emit()
                        totalBytesRead += readBytes
                        val progress = (totalBytesRead.toFloat() / contentLength.toFloat()) * 100
                        Log.i("Model", "Download progress: $progress%")
                    }

                    sink.close()
                    Log.i("Model", "Model downloaded. File size: ${modelFile.length()}")

                    handler.post {
                        // Initialize the model
                        initializeModel(modelFile)
                    }
                }
            })
        }
    }

    private fun initializeModel(modelFile: File) {
        val tfliteOptions = Interpreter.Options()
        tflite = Interpreter(modelFile, tfliteOptions)
        Toast.makeText(applicationContext, "Model initialization completed", Toast.LENGTH_SHORT).show()
        modelInitialized = true
    }



    private fun initializeFrontCamera() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Find the front-facing camera
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val cameraOrientation = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cameraOrientation == CameraCharacteristics.LENS_FACING_FRONT) {
                frontCameraId = cameraId
                break
            }
        }
    }

    private fun setUpPlayerControls() {
        playButton.setOnClickListener {
            if (modelInitialized) {
                continueCapturing = true
                playButton.visibility = View.INVISIBLE
                playerLayout.visibility = View.VISIBLE
                playVideo()
                scheduleImageCapturingAndAnalysis(calculateCaptureInterval())
            } else {
                Toast.makeText(applicationContext, "Model is still downloading, wait a little bit...", Toast.LENGTH_LONG).show()
            }
        }

        quitButton.setOnClickListener {
            quitVideoPlayer()
        }
    }

    private fun scheduleImageCapturingAndAnalysis(captureInterval: Long) {
        handler.postDelayed(object : Runnable {
            @RequiresApi(Build.VERSION_CODES.P)
            override fun run() {
                if (continueCapturing) {
                    captureAndAnalyze()
                    handler.postDelayed(this, captureInterval)
                }
            }
        }, 1000)
    }

    private fun calculateCaptureInterval(): Long {
        //TODO: This will be determined by the duration of the video, we will work it out later
        //return hardcoded value for now
        return captureInterval.toLong()
    }

    private fun playVideo() {
        val videoLinks = arrayOf(
            "https://www.youtube.com/watch?v=AtrbswyzHAs",
            "https://www.youtube.com/watch?v=QWqrfuW0pxQ",
            "https://www.youtube.com/watch?v=ReywPnQLk4c",
        )
        // Load a random video
        val randomIndex = (videoLinks.indices).random()
        webView.loadUrl(videoLinks[randomIndex])
    }

    private fun quitVideoPlayer() {
        continueCapturing = false
        pause()
        playerLayout.visibility = View.INVISIBLE
        playButton.visibility = View.VISIBLE
        //testModelOnImages()
    }

    private fun play() {
        webView.evaluateJavascript("javascript:document.querySelector('video').play()", null)
    }

    private fun pause() {
        webView.evaluateJavascript("javascript:document.querySelector('video').pause()", null)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun captureAndAnalyze() {
        Log.i(TAG, "Starting capture And Analysis... ")

        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, maxCaptures)

        val cameraStateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequestBuilder.addTarget(imageReader.surface)
                val captureRequest = captureRequestBuilder.build()

                val outputs: MutableList<OutputConfiguration> = ArrayList()
                outputs.add(OutputConfiguration(imageReader.surface))
                val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, AsyncTask.THREAD_POOL_EXECUTOR, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        session.setRepeatingRequest(captureRequest, null, handler)

                        imageReader.setOnImageAvailableListener({ reader ->
                            Log.i(TAG, "Captured image: %s".format(capturedBitmap.size))
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)

                                // Save the image to a folder
                                saveImageToDevice(image)

                                //val mat = getScaledImageMatrix(image)
                                //capturedMats.add(mat)
                                capturedBitmap.add(getScaledBitmapFromImage(image))

                                image.close()
                            }

                            if (capturedBitmap.size >= maxCaptures) {
                                session.close() //close capture session
                                Log.i(TAG, "Max Capture reached: %s".format(capturedBitmap.size))

                                if (inspectImageForCamera(capturedBitmap)) {
                                    Log.i(TAG, "CAMERA DETECTED")
                                    handler.post {
                                        Toast.makeText(applicationContext, "Camera detected!", Toast.LENGTH_SHORT).show()
                                        // stop video playback here
                                        quitVideoPlayer()
                                        continueCapturing = false
                                    }
                                } else {
                                    Log.i(TAG, " --------------->>>>>>>>>>>> CAMERA NOT DETECTED")
                                }
                                capturedBitmap.clear()
                            }
                        }, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                })

                camera.createCaptureSession(sessionConfig)

            }

            override fun onDisconnected(camera: CameraDevice) {}
            override fun onError(camera: CameraDevice, error: Int) {}
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Request camera permission here
            return
        }
        cameraManager.openCamera(frontCameraId, cameraStateCallback, handler)
    }

    private fun getScaledBitmapFromImage(image: Image): Bitmap {
        val originalBitmap = yuv420ToBitmap(image)
        // Resize the Bitmap
        val matrix = Matrix()
        matrix.postScale(128f / originalBitmap.width, 128f / originalBitmap.height)
        return Bitmap.createBitmap(
            originalBitmap,
            0,
            0,
            originalBitmap.width,
            originalBitmap.height,
            matrix,
            true
        )
    }

    private fun saveImageToDevice(image: Image) {
        //val jpegBytes = toJpegImage(image, 100)
        val jpegBytes = bitmapToJpeg(yuv420ToBitmap(image))
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timestamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/MyCapturedImages")
        }

        val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            val outputStream = contentResolver.openOutputStream(it)

            outputStream?.use { os ->
                // Write your image bytes here
                os.write(jpegBytes)
                os.flush()
            }
        } ?: run {
            Log.e(TAG, "Failed to create new MediaStore record.")
        }
    }


    private fun inspectImageForCamera(capturedBitmap: MutableList<Bitmap>): Boolean {

        // Loop through each captured image for analysis
        for (originalBitmap in capturedBitmap) {
            // Assume isCameraDetected is determined from contours, edges, etc.
            //val isCameraDetected = analyzeMats(originalMat):
            val isCameraDetected = true

            if (isCameraDetected) {
                // Prepare the preprocessed image for ML model
                val inputBuffer: ByteBuffer = bitmapToByteBuffer(originalBitmap)// Convert your Mat to ByteBuffer

                // Perform ML inference
                val output = Array(1) { FloatArray(2) }  // Assume binary classification
                tflite.run(inputBuffer, output)

                // Post-process ML model output
                Log.i(TAG, output.contentDeepToString())
                Log.i(TAG, "Model prob for Camera Present is: %s".format(output[0][1]))
                if (output[0][1] > 0.8) {  // Just an example condition
                    // Confirmed, it is a camera
                    return true
                }
            }

        }
        return false
    }



    // Function to load test images and run the model on them
    private fun testModelOnImages() {
        val assetManager = assets
        try {
            val imagesList = assetManager.list("camera_present") // "test_images" is the folder where test images are stored
            if (imagesList != null) {
                for (image in imagesList) {
                    val inputStream = assetManager.open("camera_present/$image")
                    val bitmap = BitmapFactory.decodeStream(inputStream)

                    // Run the model
                    val matrix = Matrix()
                    matrix.postScale(128f / bitmap.width, 128f / bitmap.height)
                    val resizedBitMap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    val inputBuffer: ByteBuffer = bitmapToByteBuffer(resizedBitMap)

                    val output = Array(1) { FloatArray(2) }
                    tflite.run(inputBuffer, output)

                    Log.i(TAG, "For image: $image, model output is: ${output[0].contentToString()}")

                    inputStream.close()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error in reading assets", e)
        }
    }

}
