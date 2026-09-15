package net.thompsoncs.truckcapture.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import net.thompsoncs.truckcapture.R
import net.thompsoncs.truckcapture.databinding.FragmentHelpBinding
import net.thompsoncs.truckcapture.databinding.ViewHelpStepBinding

/**
 * The field guide: what to do, in the order a tech does it.
 *
 * Exists because the workflow has one genuinely confusing property — the
 * GoPro's Wi-Fi has no internet, so the device looks "connected" while uploads
 * can't run. Left unexplained, that reads as a broken app.
 */
class HelpFragment : TcsFragment() {

    private var _binding: FragmentHelpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAppBarVisible(true)
        setScreenLabel(getString(R.string.help_title))
        applyBottomInset(binding.root)

        val steps = listOf(
            binding.step1 to (R.string.help_step_1_title to R.string.help_step_1_body),
            binding.step2 to (R.string.help_step_2_title to R.string.help_step_2_body),
            binding.step3 to (R.string.help_step_3_title to R.string.help_step_3_body),
            binding.step4 to (R.string.help_step_4_title to R.string.help_step_4_body)
        )
        steps.forEachIndexed { i, (included, text) ->
            val step = ViewHelpStepBinding.bind(included.root)
            step.stepNum.text = (i + 1).toString()
            step.stepTitle.setText(text.first)
            step.stepBody.setText(text.second)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
