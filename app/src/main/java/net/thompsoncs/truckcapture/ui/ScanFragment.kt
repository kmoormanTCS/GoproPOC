package net.thompsoncs.truckcapture.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import net.thompsoncs.truckcapture.R
import net.thompsoncs.truckcapture.databinding.FragmentScanBinding
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Full-bleed QR scanner. On the first successful read it starts the truck
 * session and advances to Capture — the tech never has to confirm the scan.
 *
 * The reticle flashes green and the truck number appears for a beat before the
 * transition, so a misread is obvious immediately rather than three screens later.
 */
class ScanFragment : TcsFragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient()

    /** Guard so we only act on one result even if several frames decode. */
    @Volatile private var handled = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCamera() else showPermissionPanel()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAppBarVisible(false)
        // The bar is hidden here, so the scanner owns the status-bar area.
        applyTopInset(binding.statusSpacer)
        applyBottomInset(binding.root)

        binding.closeBtn.setOnClickListener { findNavController().popBackStack() }
        binding.grantBtn.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (hasCameraPermission()) showCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onStop() {
        super.onStop()
        // Restore the bar for whichever screen comes next.
        setAppBarVisible(true)
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel() {
        _binding ?: return
        binding.scanHint.visibility = View.GONE
        binding.permissionPanel.visibility = View.VISIBLE
    }

    private fun showCamera() {
        _binding ?: return
        binding.permissionPanel.visibility = View.GONE
        binding.scanHint.visibility = View.VISIBLE
        startCamera()
    }

    private fun startCamera() {
        val ctx = requireContext()
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            // The fragment may be gone by the time the provider is ready.
            if (_binding == null || !isAdded) return@addListener
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) } }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Log.d("TRUCKCAP", "Camera bind failed: ${e.message}")
                binding.scanHint.text = "Could not open the camera."
            }
        }, ContextCompat.getMainExecutor(ctx))
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
                    Log.d("TRUCKCAP", "SCAN RESULT: format=$fmt raw=[$raw]")
                    binding.root.post { onScanned(raw, fmt) }
                }
            }
            .addOnFailureListener { e -> Log.d("TRUCKCAP", "Scan error: ${e.message}") }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onScanned(raw: String, format: String) {
        if (_binding == null || !isAdded) return

        // Log the payload before parsing, so an unreadable code is still
        // recorded and visible on the Uploads screen.
        vm.logRawScan(raw, format)

        if (!vm.startSession(raw)) {
            // Unreadable — let them try again rather than dead-ending.
            handled = false
            binding.scanHint.text = "Couldn't read that code. Try again, or enter the ID by hand."
            return
        }

        val truckNo = vm.session.value?.truckNo.orEmpty()
        binding.reticle.setBackgroundResource(R.drawable.bg_reticle_success)
        binding.scannedTruck.text = truckNo
        binding.scannedTruck.visibility = View.VISIBLE

        // Hold the confirmation briefly so a misread is caught here, not later.
        binding.root.postDelayed({
            if (isAdded) findNavController().navigate(R.id.action_scan_to_capture)
        }, 450)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        scanner.close()
    }
}
