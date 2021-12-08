package com.example.litecctv

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    val IMAGE_CAPTURE_WIDTH = 300
    val IMAGE_CAPTURE_HEIGHT = 300
    val URL_IMAGE_POST = "http://128.199.123.139:8080/image/"
    val URL_TOKEN_POST = "http://128.199.123.139:8080/tokenCheck/"
    val STRING_LENGTH = 6
    private var imageCapture: ImageCapture? = null
    val db = DBHelper(this, null)
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    var token = ""
    private var motionDetector: MotionDetector? = null
    private var cctvStatus = false
    private lateinit var cameraHandler: Handler

    @SuppressLint("Range")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val db = DBHelper(this, null)
        val cursor = db.getToken()
        cursor!!.moveToFirst()
        if(cursor.getCount() != 0 ){
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
        token_text_view.setText(token)

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    fun switchOnOffCCTV() {
        if (cctvStatus) {
            cctvStatus = false
            camera_capture_button.setText("Start")
        }
        else {
            cctvStatus = true
            camera_capture_button.setText("Stop")
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
        val motionDetector = motionDetector ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg")

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onError(exception: ImageCaptureException) {
                super.onError(exception)
            }

            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                super.onCaptureSuccess(imageProxy)
                var bitmap: Bitmap = imageProxyToBitmap(imageProxy)
                imageProxy.close()
                if (motionDetector.hasMotion(bitmap)) {
                    Toast.makeText(baseContext, "Capture OK - MOTION DETECTED - Sending Picture To Server Now", Toast.LENGTH_LONG).show()
                    thread() {
                        // Resize bitmap
                        bitmap = Bitmap.createScaledBitmap(bitmap, IMAGE_CAPTURE_WIDTH, IMAGE_CAPTURE_HEIGHT, false)

                        val base64String = getBase64OfPhoto(bitmap)
                        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
                        sendImageToCloud(timestamp, base64String, token)
                    }
                }
                else {
//                    Toast.makeText(baseContext, "Capture OK - NO MOTION DETECTED", Toast.LENGTH_LONG).show()
                }
            }
        })
    }
    private fun generateToken() {
        val queue = Volley.newRequestQueue(this)
        val stringRequest = StringRequest(Request.Method.GET, URL_TOKEN_POST,
            Response.Listener<String> { response ->
                // Display the first 500 characters of the response string.
                Log.e("AAA", "generateToken: $response", )
                db.addToken(response)
                db.addToken(response)
                token = response
                Toast.makeText(this, token, Toast.LENGTH_SHORT).show()
            },
            Response.ErrorListener { Log.e("AAA", "generateToken:", ) })

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

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->  
                    Log.d(TAG, "Average luminosity = $luma")
                })
            }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            // Instantiate motion detector
            motionDetector = MotionDetector()

            cameraHandler = Handler(Looper.getMainLooper())
            cameraHandler.post(object: Runnable {
                override fun run() {
                    if (cctvStatus) {
                        takePhoto()
                    }
                    cameraHandler.postDelayed(this, 2000)
                }
            })

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
        val queue = MySingleton.getInstance(this.applicationContext).requestQueue
        val map = mutableMapOf<String, Any?>()
        map["filename"] = fileName
        map["token"] = token
        map["imagedata"] = imageData

        val json = JSONObject(map)
        Log.e("sendImageToCloud", json.toString() )
        val jsonReq = object: JsonObjectRequest(Request.Method.POST,URL_IMAGE_POST,json,
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
    }
}
