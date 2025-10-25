package com.example.wirelesswebcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewFinder: PreviewView
    private var httpServer: MyWebServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)

        if (allPermissionsGranted()) {
            startCamera()
            startServer()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val jpeg = imageProxyToJpeg(imageProxy)
                            if (jpeg != null) {
                                MyWebServer.latestJpeg.set(jpeg)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Analyzer error", t)
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unexpected image format: ${image.format}. Expected YUV_420_888.")
            return null
        }

        val width = image.width
        val height = image.height

        val nv21 = yuv420ToNv21(image)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        val jpeg = out.toByteArray()
        out.reset()
        out.close()
        return jpeg
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val nv21 = ByteArray(ySize + 2 * chromaWidth * chromaHeight)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        copyPlane(
            src = yPlane.buffer,
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride,
            width = width,
            height = height,
            out = nv21,
            outOffset = 0,
            outPixelStride = 1
        )

        val uBuf = uPlane.buffer.duplicate().apply { position(0) }
        val vBuf = vPlane.buffer.duplicate().apply { position(0) }

        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var outPos = ySize
        for (row in 0 until chromaHeight) {
            var uIdx = row * uRowStride
            var vIdx = row * vRowStride
            for (col in 0 until chromaWidth) {
                nv21[outPos++] = vBuf.get(vIdx) // V
                nv21[outPos++] = uBuf.get(uIdx) // U
                uIdx += uPixelStride
                vIdx += vPixelStride
            }
        }
        return nv21
    }

    private fun copyPlane(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        outOffset: Int,
        outPixelStride: Int
    ) {
        val srcBuf = src.duplicate()
        var outPos = outOffset
        val rowBuffer = ByteArray(rowStride)

        for (row in 0 until height) {
            val length = min(rowStride, srcBuf.remaining())
            srcBuf.get(rowBuffer, 0, length)

            if (pixelStride == 1 && outPixelStride == 1) {
                System.arraycopy(rowBuffer, 0, out, outPos, width)
                outPos += width
            } else {
                var inPos = 0
                var outColPos = outPos
                for (col in 0 until width) {
                    out[outColPos] = rowBuffer[inPos]
                    outColPos += outPixelStride
                    inPos += pixelStride
                }
                outPos += width * outPixelStride
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
                startServer()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startServer() {
        httpServer = MyWebServer(SERVER_PORT)
        try {
            httpServer?.start()
            val ipAddress = getIpAddress()
            Toast.makeText(
                this,
                "Server at http://$ipAddress:$SERVER_PORT/stream.mjpeg",
                Toast.LENGTH_LONG
            ).show()
            Log.d(TAG, "Server started at http://$ipAddress:$SERVER_PORT/stream.mjpeg")
        } catch (e: IOException) {
            Log.e(TAG, "Error starting server: ${e.message}", e)
            Toast.makeText(this, "Error starting server: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        httpServer?.stop()
        Log.d(TAG, "Server stopped.")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraExecutor.shutdown()
        } catch (_: Throwable) {}
        stopServer()
    }

    private fun getIpAddress(): String {
        return try {
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs: List<java.net.InetAddress> = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.isSiteLocalAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
            "Unknown"
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP address", ex)
            "Unknown"
        }
    }

    companion object {
        private const val TAG = "WirelessWebcam"
        private const val SERVER_PORT = 8080
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.INTERNET)
    }
}