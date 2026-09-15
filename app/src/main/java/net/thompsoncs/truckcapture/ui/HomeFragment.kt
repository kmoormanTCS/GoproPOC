package net.thompsoncs.truckcapture.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import net.thompsoncs.truckcapture.R
import net.thompsoncs.truckcapture.UploadQueue
import net.thompsoncs.truckcapture.capture.UploadReadiness
import net.thompsoncs.truckcapture.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

/**
 * The starting screen: one big Scan button, a manual-entry escape hatch, a
 * glanceable upload roll-up, and — when it matters — a line telling the tech
 * what to do to get photos moving.
 */
class HomeFragment : TcsFragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Cellular coming and going does not always fire a default-network
     * callback while the device is joined to the GoPro's AP, so re-read
     * readiness on a slow tick while this screen is visible.
     */
    private val readinessHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val readinessTick = object : Runnable {
        override fun run() {
            vm.refreshReadiness()
            readinessHandler.postDelayed(this, 3000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAppBarVisible(true)
        setScreenLabel(getString(R.string.home_title))
        applyBottomInset(binding.root)

        binding.scanBtn.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_scan)
        }
        binding.manualBtn.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_manual)
        }
        binding.queueCard.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_queue)
        }
        binding.helpBtn.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_help)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.queueEntries.collect { render() } }
                launch { vm.readiness.collect { render() } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Background uploads may have progressed while we were away.
        vm.refreshQueue()
        vm.refreshReadiness()
        readinessHandler.removeCallbacks(readinessTick)
        readinessHandler.postDelayed(readinessTick, 3000L)
    }

    override fun onPause() {
        super.onPause()
        readinessHandler.removeCallbacks(readinessTick)
    }

    private fun render() {
        if (_binding == null) return
        val entries = vm.queueEntries.value
        renderQueueSummary(entries)
        renderReadiness(entries)
    }

    private fun renderQueueSummary(entries: List<UploadQueue.Entry>) {
        if (entries.isEmpty()) {
            binding.queueSummary.text = getString(R.string.queue_empty)
            return
        }
        val counts = entries.groupingBy { it.status }.eachCount()
        // Fixed order so the line doesn't reshuffle as statuses change.
        val parts = listOf("PENDING", "UPLOADING", "FAILED", "DONE").mapNotNull { status ->
            counts[status]?.let { "$it ${status.lowercase()}" }
        }
        binding.queueSummary.text = parts.joinToString("  ·  ")
    }

    /**
     * The advice line. Only shown when photos are actually waiting — with an
     * empty queue there is nothing to advise, and a permanent connectivity
     * badge would just be noise.
     */
    private fun renderReadiness(entries: List<UploadQueue.Entry>) {
        val waiting = entries.count { it.status != "DONE" }
        if (waiting == 0) {
            binding.readinessPanel.visibility = View.GONE
            return
        }
        binding.readinessPanel.visibility = View.VISIBLE

        val (textRes, iconRes, tintRes) = when (vm.readiness.value) {
            UploadReadiness.READY_WIFI ->
                Triple(R.string.upload_running_wifi, R.drawable.ic_wifi, R.color.tcs_green)
            UploadReadiness.READY_CELLULAR ->
                Triple(R.string.upload_running_cell, R.drawable.ic_signal_cellular, R.color.tcs_green)
            UploadReadiness.NO_INTERNET ->
                Triple(R.string.upload_blocked_gopro, R.drawable.ic_wifi_off, R.color.tcs_amber)
            UploadReadiness.OFFLINE ->
                Triple(R.string.upload_blocked_offline, R.drawable.ic_wifi_off, R.color.tcs_amber)
        }

        val photos = resources.getQuantityString(R.plurals.photo_count, waiting, waiting)
        binding.readinessText.text = "$photos waiting · ${getString(textRes)}"
        binding.readinessIcon.setImageResource(iconRes)
        binding.readinessIcon.imageTintList =
            android.content.res.ColorStateList.valueOf(requireContext().getColor(tintRes))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        readinessHandler.removeCallbacks(readinessTick)
        _binding = null
    }
}
