package net.thompsoncs.truckcapture.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import net.thompsoncs.truckcapture.MainActivity
import net.thompsoncs.truckcapture.capture.CaptureViewModel

/**
 * Shared plumbing for the flow's screens: the activity-scoped ViewModel, the
 * app-bar hooks, and the window-inset helpers.
 *
 * Insets are handled per-fragment rather than with a blanket
 * fitsSystemWindows on the activity, because the scanner needs to draw behind
 * the system bars while every other screen needs to stay clear of them.
 */
abstract class TcsFragment : Fragment() {

    protected val vm: CaptureViewModel by activityViewModels()

    private val host: MainActivity? get() = activity as? MainActivity

    protected fun setScreenLabel(label: String) { host?.setScreenLabel(label) }

    protected fun setAppBarVisible(visible: Boolean) { host?.setAppBarVisible(visible) }

    /**
     * Adds the navigation-bar inset to [view]'s bottom padding, keeping its
     * own padding intact. Apply to whatever sits at the bottom of the screen —
     * the action bar on Capture/Review, the scroll container elsewhere.
     */
    protected fun applyBottomInset(view: View) {
        val base = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
            ).bottom
            v.updatePadding(bottom = base + bottom)
            insets
        }
    }

    /** As [applyBottomInset], but for the status bar — used by the scanner. */
    protected fun applyTopInset(view: View) {
        val base = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = base + top)
            insets
        }
    }
}
