package net.thompsoncs.truckcapture

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import net.thompsoncs.truckcapture.databinding.ActivityMainBinding

/**
 * Single-activity host for the capture flow (Home → Scan → Capture → Review,
 * plus Uploads). Owns the TCS app bar so every screen carries the branding
 * without repeating the markup; fragments adjust it through the helpers below.
 *
 * The capture itself lives in CaptureViewModel, scoped to this activity, so a
 * running capture and the current truck session both survive rotation and
 * fragment swaps.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35 forces edge-to-edge, so the app bar would otherwise sit
        // under the clock. Inset only the bar — the nav host stays full-height
        // so ScanFragment can run the camera behind the status bar. Each
        // fragment handles its own bottom inset (see TcsFragment).
        val barPaddingTop = binding.appBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = barPaddingTop + top)
            insets
        }

        val host = supportFragmentManager.findFragmentById(R.id.navHost) as NavHostFragment
        navController = host.navController

        binding.backBtn.setOnClickListener { navController.navigateUp() }

        // Home is the root, so it gets no Back arrow; everything else does.
        // This is the only reliable way out of Uploads on a tablet locked down
        // by MDM, where the system Back button may not be available.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val atRoot = destination.id == R.id.homeFragment
            binding.backBtn.visibility = if (atRoot) View.GONE else View.VISIBLE
        }
    }

    /** The small grey label on the right of the app bar. */
    fun setScreenLabel(label: String) {
        binding.screenLabel.text = label
    }

    /** ScanFragment hides the bar so the camera preview can go full-bleed. */
    fun setAppBarVisible(visible: Boolean) {
        binding.appBar.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
