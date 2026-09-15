package net.thompsoncs.truckcapture.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.navigation.fragment.findNavController
import net.thompsoncs.truckcapture.R
import net.thompsoncs.truckcapture.databinding.FragmentManualEntryBinding

/** Fallback entry when a truck's QR code won't scan. */
class ManualEntryFragment : TcsFragment() {

    private var _binding: FragmentManualEntryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManualEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAppBarVisible(true)
        setScreenLabel(getString(R.string.manual_title))
        applyBottomInset(binding.root)

        binding.startBtn.setOnClickListener { start() }
        binding.cancelBtn.setOnClickListener { findNavController().popBackStack() }

        // Go on the keyboard is the same as tapping Start.
        binding.truckInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { start(); true } else false
        }
        binding.truckInput.requestFocus()
    }

    private fun start() {
        val raw = binding.truckInput.text?.toString().orEmpty()
        if (!vm.startSession(raw)) {
            binding.truckInputLayout.error = "Enter a truck number"
            return
        }
        binding.truckInputLayout.error = null
        findNavController().navigate(R.id.action_manual_to_capture)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
