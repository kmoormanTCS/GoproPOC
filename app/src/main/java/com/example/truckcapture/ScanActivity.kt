package com.example.truckcapture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Stage 1 (diagnostic) QR/barcode scanner.
 *
 * Opens a camera preview, scans for barcodes with ML Kit, and on the first
 * successful read returns the RAW value to MainActivity via setResult. It does
 * NOT parse or auto-capture yet — the point is to see exactly what the truck
 * codes contain before we build the auto-capture flow on top of it.
 *
 * Returns to caller:
 *   RESULT_OK  + extra "raw_value" (String) and "format" (String)
 *   RESULT_CANCELED if the user backs out or camera permission is denied.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RAW = "raw_value"
        const val EXTRA_FORMAT = "format"
        private const val CAMERA_PERMISSION_REQUEST = 101
    }

    private lateinit var previewView: PreviewView
    private lateinit var hintView: TextView
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient()

    // Guard so we only return one result even if several frames decode.
    @Volatile private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        previewView = findViewById(R.id.previewView)
        hintView = findViewById(R.id.scanHint)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission needed to scan.", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) } }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Log.d("TRUCKCAP", "Camera bind failed: ${e.message}")
                Toast.makeText(this, "Could not open camera.", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyze(proxy: ImageProxy) {
        if (handled) { proxy.close(); return }
        val media = proxy.image
        if (media == null) { proxy.close(); return }

        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val first = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                if (first != null && !handled) {
                    handled = true
                    val raw = first.rawValue ?: ""
                    val fmt = formatName(first.format)
                    // Log the raw contents so we can see exactly what's encoded.
                    Log.d("TRUCKCAP", "SCAN RESULT: format=$fmt rawValue=[$raw]")
                    val data = android.content.Intent().apply {
                        putExtra(EXTRA_RAW, raw)
                        putExtra(EXTRA_FORMAT, fmt)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                }
            }
            .addOnFailureListener { e ->
                Log.d("TRUCKCAP", "Scan error: ${e.message}")
            }
            .addOnCompleteListener {
                proxy.close()
            }
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        else -> "OTHER($format)"
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        scanner.close()
    }
}