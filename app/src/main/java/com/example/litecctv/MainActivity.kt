package com.example.litecctv

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.example.litecctv.machine.DBHelper
import com.example.litecctv.machine.MotionDetector
import com.example.litecctv.machine.MySingleton
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread


typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private val db = DBHelper(this, null)
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    var token = ""
    private val motionDetector: MotionDetector = MotionDetector()
    private var cctvStatus = false
    private lateinit var cameraHandler: Handler
    private var lensFacing: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    @SuppressLint("Range")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val db = DBHelper(this, null)
        val cursor = db.getToken()
        cursor!!.moveToFirst()
        if(cursor.count != 0 ){
            token = cursor.getString(cursor.getColumnIndex(DBHelper.TOKEN_COL))
            Log.e("Sending", token )
        }
        else
            generateToken()
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listener for take photo button
        camera_capture_button.setOnClickListener { switchOnOffCCTV() }
        // Show token string on the screen
        token_text_view.text = token

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Camera switching button
        switch_camera_button.setOnClickListener { switchCamera() }

        // Start capture timer (capture every 900ms)
        thread {
            cameraHandler = Handler(Looper.getMainLooper())
            cameraHandler.post(object: Runnable {
                override fun run() {
                    if (cctvStatus) {
                        takePhoto()
                    }
                    cameraHandler.postDelayed(this, 900)
                }
            })
        }
    }

    fun playSound() {
        try {
            val notification: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun switchCamera() {
        Toast.makeText(this, "Camera Switch", Toast.LENGTH_LONG).show()
        if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA)
            lensFacing = CameraSelector.DEFAULT_FRONT_CAMERA
        else if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
            lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
        startCamera()
    }

    @SuppressLint("SetTextI18n")
    private fun switchOnOffCCTV() {
        if (cctvStatus) {
            cctvStatus = false
            camera_capture_button.text = "Start"
        }
        else {
            cctvStatus = true
            camera_capture_button.text = "Stop"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return // Elvis operator

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg")

        // Create output options object which contains file + metadata
        ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {

            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                super.onCaptureSuccess(imageProxy)
                var bitmap: Bitmap = imageProxyToBitmap(imageProxy)
                imageProxy.close()
                if (motionDetector.hasMotion(bitmap)) {
                    Toast.makeText(baseContext, "Capture OK - MOTION DETECTED - Sending Picture To Server Now", Toast.LENGTH_SHORT).show()
                    thread {
                        // Play sound
                        playSound()

                        // Resize bitmap
                        bitmap = Bitmap.createScaledBitmap(bitmap, IMAGE_CAPTURE_WIDTH, IMAGE_CAPTURE_HEIGHT, false)

                        val base64String = getBase64OfPhoto(bitmap)
                        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
                        sendImageToCloud(timestamp, base64String, token)
                    }
                }
            }
        })
    }
    private fun generateToken() {
        val queue = Volley.newRequestQueue(this)
        val stringRequest = StringRequest(Request.Method.GET, URL_TOKEN_POST,
            { response ->
                // Display the first 500 characters of the response string.
                Log.e("AAA", "generateToken: $response")
                db.addToken(response)
                db.addToken(response)
                token = response
                Toast.makeText(this, token, Toast.LENGTH_SHORT).show()
            },
            { Log.e("AAA", "generateToken:") })

        queue.add(stringRequest)
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->  
                    Log.d(TAG, "Average luminosity = $luma")
                })
            }

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, lensFacing, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun getBase64OfPhoto(image: Bitmap): String? {
        val baos = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val byteArray: ByteArray = baos.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    private fun sendImageToCloud(fileName: String, imageData: String?, token: String) {
//        val progressDialog = ProgressDialog.show(this@MainActivity, "", "Uploading...", true)
        MySingleton.getInstance(this.applicationContext).requestQueue
        val map = mutableMapOf<String, Any?>()
        map["filename"] = fileName
        map["token"] = token
        map["imagedata"] = imageData

        val json = JSONObject(map)
        Log.e("sendImageToCloud", json.toString() )
        val jsonReq = object: JsonObjectRequest(
            Method.POST,URL_IMAGE_POST,json,
        Response.Listener { response ->
            Log.i(TAG, "Response from server: $response")
        }, Response.ErrorListener{
            // Error in request
                Toast.makeText(this,
                    "Volley error: $it",
                    Toast.LENGTH_SHORT).show()
        })
        {
            override fun getHeaders(): MutableMap<String, String> {
                val headers = HashMap<String, String>()
                headers["Content-Type"] = "application/json; charset=utf-8"
                return headers
            }
        }

//        Log.e("Sending", "sendImageToCloud: " )

        jsonReq.retryPolicy = DefaultRetryPolicy(
            0,
            DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        )
        MySingleton.getInstance(this).addToRequestQueue(jsonReq)
    }
    private class LuminosityAnalyzer(private val listener: LumaListener): ImageAnalysis.Analyzer {
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()
            val data = ByteArray(remaining())
            get(data)
            return data
        }

        override fun analyze(image: ImageProxy) {
            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map {
                it.toInt() and 0xFF
            }
            val luma = pixels.average()
            listener(luma)
            image.close()
        }
    }



    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val IMAGE_CAPTURE_WIDTH = 300
        private const val IMAGE_CAPTURE_HEIGHT = 300
        private const val URL_IMAGE_POST = "http://128.199.123.139:8080/image/"
        private const val URL_TOKEN_POST = "http://128.199.123.139:8080/tokenCheck/"
    }
}
