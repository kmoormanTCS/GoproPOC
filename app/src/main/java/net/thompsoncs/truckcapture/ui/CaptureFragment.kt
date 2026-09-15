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
import net.thompsoncs.truckcapture.capture.CaptureState
import net.thompsoncs.truckcapture.capture.StageRow
import net.thompsoncs.truckcapture.capture.StageState
import net.thompsoncs.truckcapture.capture.TruckSession
import net.thompsoncs.truckcapture.databinding.FragmentCaptureBinding
import net.thompsoncs.truckcapture.databinding.ItemStageBinding
import kotlinx.coroutines.launch

/**
 * The Capturing screen. Shows the six capture stages as a checklist that ticks
 * through live, so a slow or stuck capture is legible at a glance — the old
 * build collapsed all of this into a single status line.
 *
 * The capture runs in the activity-scoped ViewModel, so leaving and returning
 * to this screen (or rotating) picks the same capture back up.
 */
class CaptureFragment : TcsFragment() {

    private var _binding: FragmentCaptureBinding? = null
    private val binding get() = _binding!!

    /** One row view per stage, reused as state changes rather than re-inflated. */
    private val stageViews = mutableListOf<ItemStageBinding>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAppBarVisible(true)
        applyBottomInset(binding.actionBar)

        binding.captureBtn.setOnClickListener { vm.startCapture() }
        binding.stopBtn.setOnClickListener { vm.stopCapture() }
        binding.doneBtn.setOnClickListener { finishTruck() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.session.collect { renderSession(it) } }
                launch { vm.captureState.collect { renderState(it) } }
            }
        }
    }

    private fun renderSession(session: TruckSession?) {
        // The session is cleared on Done; the nav pop is already on its way.
        if (session == null) return
        val banner = binding.truckBanner
        banner.bannerTruckNo.text = session.truckNo
        banner.bannerGuid.text =
            if (session.wasScanned) "GUID ${session.guid.take(8)}…" else "Manually entered"

        if (session.photoCount > 0) {
            banner.bannerPhotoCount.visibility = View.VISIBLE
            banner.bannerPhotoCount.text = resources.getQuantityString(
                R.plurals.photo_count, session.photoCount, session.photoCount
            )
        } else {
            banner.bannerPhotoCount.visibility = View.GONE
        }

        // Second and later shots are "another angle", not a first capture.
        val next = session.photoCount + 1
        binding.captureBtn.text =
            if (session.photoCount == 0) getString(R.string.action_capture)
            else getString(R.string.action_capture_another)
        binding.idleHeadline.text =
            if (session.photoCount == 0) "Ready to shoot"
            else "Ready for photo $next"
    }

    private fun renderState(state: CaptureState) {
        when (state) {
            is CaptureState.Idle -> {
                showIdle()
                binding.errorCard.visibility = View.GONE
            }

            is CaptureState.Running -> {
                setScreenLabel(getString(R.string.capture_title))
                binding.idleCard.visibility = View.GONE
                binding.errorCard.visibility = View.GONE
                binding.stagesCard.visibility = View.VISIBLE
                binding.captureBtn.visibility = View.GONE
                binding.stopBtn.visibility = View.VISIBLE
                binding.doneBtn.visibility = View.GONE
                renderStages(state.stages)
            }

            is CaptureState.Succeeded -> {
                // Hand off to Review; reset so returning here is a clean idle.
                vm.resetCapture()
                if (isAdded) findNavController().navigate(R.id.action_capture_to_review)
            }

            is CaptureState.Failed -> {
                setScreenLabel(getString(R.string.capture_failed_label))
                binding.idleCard.visibility = View.GONE
                binding.stagesCard.visibility = View.VISIBLE
                renderStages(state.stages)
                binding.errorCard.visibility = View.VISIBLE
                binding.errorText.text = state.message
                binding.errorDetail.text = state.detail.orEmpty()
                binding.errorDetail.visibility =
                    if (state.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
                binding.captureBtn.visibility = View.VISIBLE
                binding.captureBtn.text = getString(R.string.action_retry)
                binding.stopBtn.visibility = View.GONE
                binding.doneBtn.visibility = View.VISIBLE
            }

            is CaptureState.Stopped -> {
                // Stopping is a deliberate act, not a failure — go back to armed.
                showIdle()
                binding.errorCard.visibility = View.GONE
            }
        }
    }

    private fun showIdle() {
        setScreenLabel(getString(R.string.capture_ready_label))
        binding.idleCard.visibility = View.VISIBLE
        binding.stagesCard.visibility = View.GONE
        binding.captureBtn.visibility = View.VISIBLE
        binding.stopBtn.visibility = View.GONE
        binding.doneBtn.visibility = View.VISIBLE
        // renderSession sets the right label; this covers a stop before any shot.
        vm.session.value?.let { renderSession(it) }
    }

    private fun renderStages(stages: List<StageRow>) {
        // Inflate once, then only update.
        if (stageViews.size != stages.size) {
            binding.stagesContainer.removeAllViews()
            stageViews.clear()
            stages.forEach { row ->
                val item = ItemStageBinding.inflate(
                    layoutInflater, binding.stagesContainer, true
                )
                item.stageLabel.text = row.stage.label
                stageViews.add(item)
            }
        }

        stages.forEachIndexed { i, row ->
            val item = stageViews.getOrNull(i) ?: return@forEachIndexed
            item.stageLabel.text = row.stage.label

            when (row.state) {
                StageState.PENDING -> {
                    item.stageSpinner.visibility = View.GONE
                    item.stageIcon.visibility = View.VISIBLE
                    item.stageIcon.setImageResource(R.drawable.ic_pending_circle)
                    item.stageLabel.setTextColor(color(R.color.tcs_muted))
                }
                StageState.ACTIVE -> {
                    item.stageIcon.visibility = View.GONE
                    item.stageSpinner.visibility = View.VISIBLE
                    item.stageLabel.setTextColor(color(R.color.tcs_ink))
                }
                StageState.DONE -> {
                    item.stageSpinner.visibility = View.GONE
                    item.stageIcon.visibility = View.VISIBLE
                    item.stageIcon.setImageResource(R.drawable.ic_check_circle)
                    item.stageLabel.setTextColor(color(R.color.tcs_ink2))
                }
                StageState.FAILED -> {
                    item.stageSpinner.visibility = View.GONE
                    item.stageIcon.visibility = View.VISIBLE
                    item.stageIcon.setImageResource(R.drawable.ic_error_circle)
                    item.stageLabel.setTextColor(color(R.color.tcs_red))
                }
            }

            val detail = row.detail
            if (detail.isNullOrEmpty()) {
                item.stageDetail.visibility = View.GONE
            } else {
                item.stageDetail.visibility = View.VISIBLE
                item.stageDetail.text = detail
                item.stageDetail.setTextColor(
                    if (row.state == StageState.FAILED) color(R.color.tcs_red)
                    else color(R.color.tcs_muted)
                )
            }
        }
    }

    private fun color(id: Int) = requireContext().getColor(id)

    private fun finishTruck() {
        vm.endSession()
        findNavController().popBackStack(R.id.homeFragment, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stageViews.clear()
        _binding = null
    }
}
