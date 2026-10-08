package app.waypoint.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.waypoint.R
import app.waypoint.core.util.Motion
import app.waypoint.core.util.enterRise
import app.waypoint.core.util.floatLoop
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.databinding.ActivitySplashBinding
import app.waypoint.ui.map.MapActivity
import app.waypoint.ui.setup.SetupActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** 2.8 s animated splash: radar sweep, blinking satellites, cycling status line. */
class SplashActivity : AppCompatActivity() {

    private val settings: SettingsStore by inject()
    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        binding.satellite.floatLoop()
        if (!Motion.reduced(this)) {
            binding.wordmark.enterRise(200)
            binding.subtitle.enterRise(350)
        }

        val lines = listOf(
            getString(R.string.splash_line1),
            getString(R.string.splash_line2),
            getString(R.string.splash_line3)
        )
        lifecycleScope.launch {
            lines.forEachIndexed { i, line ->
                binding.statusLine.text = line
                if (!Motion.reduced(this@SplashActivity)) binding.statusLine.enterRise()
                if (i < lines.lastIndex) delay(800)
            }
        }
        lifecycleScope.launch {
            delay(2800)
            val next = if (settings.value.setupDone) MapActivity::class.java else SetupActivity::class.java
            startActivity(Intent(this@SplashActivity, next))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
