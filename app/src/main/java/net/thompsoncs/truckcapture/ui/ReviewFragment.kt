package net.thompsoncs.truckcapture.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import net.thompsoncs.truckcapture.R
import net.thompsoncs.truckcapture.capture.CapturedPhoto
import net.thompsoncs.truckcapture.capture.formatSize
import net.thompsoncs.truckcapture.databinding.FragmentReviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Confirms what was just captured. Informational only — the upload was enqueued
 * the moment the file hit disk, so there is nothing to accept here and a tech
 * who walks away never loses a photo.
 *
 * The primary action is another angle of the same truck, since that is the
 * normal case; Done ends the session and returns to Home.
 */
class ReviewFragment : TcsFragment() {

    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAppBarVisible(true)
        setScreenLabel(getString(R.string.review_title))
        applyBottomInset(binding.actionBar)

        binding.anotherBtn.setOnClickListener {
            vm.resetCapture()
            findNavController().navigate(R.id.action_review_to_capture)
        }
        binding.doneBtn.setOnClickListener {
            vm.endSession()
            findNavController().navigate(R.id.action_review_to_home)
        }
        binding.viewQueueBtn.setOnClickListener {
            findNavController().navigate(R.id.action_review_to_queue)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.session.collect { session ->
                    val photo = session?.photos?.lastOrNull() ?: return@collect
                    render(session.truckNo, session.guid, session.photoCount, photo)
                }
            }
        }
    }

    private fun render(truckNo: String, guid: String, count: Int, photo: CapturedPhoto) {
        val banner = binding.truckBanner
        banner.bannerTruckNo.text = truckNo
        banner.bannerGuid.text =
            if (guid.isNotEmpty()) "GUID ${guid.take(8)}…" else "Manually entered"
        banner.bannerPhotoCount.visibility = View.VISIBLE
        banner.bannerPhotoCount.text =
            resources.getQuantityString(R.plurals.photo_count, count, count)

        binding.capturedHeadline.text =
            if (count == 1) "Photo captured" else "Photo $count captured"
        binding.fileNameText.text = photo.fileName

        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(photo.capturedAt))
        binding.metaText.text =
            "${formatSize(photo.sizeBytes)}  ·  $time  ·  queued for upload"

        loadThumbnail(photo.path)
    }

    /**
     * Decodes a downsampled preview off the main thread. A GoPro Max 360 still
     * is far too large to hand to an ImageView whole.
     */
    private fun loadThumbnail(path: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val f = File(path)
                    if (!f.exists()) return@withContext null

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)

                    // Target roughly the on-screen width; powers of two only.
                    val target = 1080
                    var scale = 1
                    while (bounds.outWidth / (scale * 2) >= target) scale *= 2

                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                        inSampleSize = scale
                    })
                } catch (e: Exception) {
                    null
                }
            }
            if (_binding == null) return@launch
            if (bmp != null) binding.thumbnail.setImageBitmap(bmp)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
