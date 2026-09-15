package net.thompsoncs.truckcapture.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import net.thompsoncs.truckcapture.R
import net.thompsoncs.truckcapture.UploadQueue
import net.thompsoncs.truckcapture.databinding.FragmentQueueBinding
import kotlinx.coroutines.launch

/**
 * Uploads, plus the diagnostics log.
 *
 * The log stays on-screen for now because the GoPro link is still being shaken
 * out in the field and logcat isn't available to a tech on a truck lot. It sits
 * below the queue, out of the main flow but one scroll away.
 */
class QueueFragment : TcsFragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!

    private val adapter = QueueAdapter()

    /** Clearing is destructive, so it takes a second confirming tap. */
    private var clearArmed = false

    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            vm.refreshQueue()
            refreshHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAppBarVisible(true)
        setScreenLabel(getString(R.string.queue_title))
        applyBottomInset(binding.root)

        binding.queueList.layoutManager = LinearLayoutManager(requireContext())
        binding.queueList.adapter = adapter

        binding.clearQueueBtn.setOnClickListener { onClearTapped() }
        binding.clearLogBtn.setOnClickListener { vm.clearLog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.queueEntries.collect { render(it) } }
                launch { vm.log.collect { renderLog(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Poll while visible so UPLOADING settles to DONE live.
        vm.refreshQueue()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 1000L)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun render(entries: List<UploadQueue.Entry>) {
        adapter.submitList(entries)
        val empty = entries.isEmpty()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.queueList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.clearQueueBtn.isEnabled = !empty

        // When there is nothing queued, the empty state says so on its own —
        // the roll-up would just repeat it.
        binding.summaryText.visibility = if (empty) View.GONE else View.VISIBLE
        binding.summaryDivider.visibility = if (empty) View.GONE else View.VISIBLE

        if (!empty) {
            val counts = entries.groupingBy { it.status }.eachCount()
            binding.summaryText.text = listOf("PENDING", "UPLOADING", "FAILED", "DONE")
                .mapNotNull { s -> counts[s]?.let { "$it ${s.lowercase()}" } }
                .joinToString("  ·  ")
        }
    }

    private fun renderLog(lines: List<String>) {
        binding.logText.text = lines.joinToString("\n")
        // Keep the newest line in view.
        binding.logScroll.post {
            _binding?.logScroll?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun onClearTapped() {
        if (!clearArmed) {
            clearArmed = true
            binding.clearQueueBtn.text = getString(R.string.action_clear_queue_confirm)
            // Auto-disarm so it can't sit armed and be hit by accident later.
            binding.clearQueueBtn.postDelayed({
                if (clearArmed && _binding != null) {
                    clearArmed = false
                    binding.clearQueueBtn.text = getString(R.string.action_clear_queue)
                }
            }, 4000)
            return
        }
        clearArmed = false
        binding.clearQueueBtn.text = getString(R.string.action_clear_queue)
        vm.clearQueue()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshHandler.removeCallbacks(refreshRunnable)
        _binding = null
    }
}
