// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    private val viewModel: InstallerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loginOpener = { url -> openBrowser(url) }
        UpdateCheck.schedule(applicationContext, viewModel.autoUpdate)
        if (savedInstanceState == null) handleUpdateIntent(intent)
        // Android restarts the activity when the phone switches dark/light, so reading it here is enough.
        Palette.systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        Palette.mode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_THEME_MODE, null)
            ?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } } ?: ThemeMode.AUTO
        setContent {
            val dark = Palette.isDark
            SideEffect {
                val background = Palette.Background.toArgb()
                window.setBackgroundDrawable(ColorDrawable(background))
                // Ignored from Android 15 (the app draws behind the bars there); needed before it.
                @Suppress("DEPRECATION")
                run {
                    window.statusBarColor = background
                    window.navigationBarColor = background
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(
                colorScheme = scheme.copy(
                    primary = Palette.Accent,
                    onPrimary = Palette.OnAccent,
                    background = Palette.Background,
                    onBackground = Palette.Text,
                    surface = Palette.SurfaceHigh,
                    surfaceContainer = Palette.SurfaceHigh,
                    surfaceContainerHigh = Palette.SurfaceHigh,
                    onSurface = Palette.Text,
                    onSurfaceVariant = Palette.Muted,
                    outline = Palette.Outline,
                    outlineVariant = Palette.Outline,
                    error = Palette.Error,
                )
            ) {
                Box(Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding()) {
                    InstallerScreen(viewModel, ::copyText)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SelfUpdate.appVisible = true
        viewModel.phoneNetworkChanged()
        // Android's "Update this app?" screen, if it came while the app was in the background.
        SelfUpdate.pendingConfirm?.let { confirm ->
            SelfUpdate.pendingConfirm = null
            runCatching { startActivity(confirm) }
        }
    }

    override fun onPause() {
        SelfUpdate.appVisible = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    /** Tapping a "new version" notification starts that update straight away. */
    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.action == UpdateCheck.ACTION_APP_UPDATE) {
            UpdateCheck.dismissApp(this)
            viewModel.updateAppNow()
            return
        }
        if (intent?.action != UpdateCheck.ACTION_UPDATE) return
        val tvKey = intent.getStringExtra(UpdateCheck.EXTRA_TV_KEY)
        UpdateCheck.dismiss(this, tvKey)
        viewModel.updateNow(tvKey)
    }

    private fun openBrowser(url: String) {
        val uri = Uri.parse(url)
        try {
            val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(Palette.Background.toArgb()).build()
            CustomTabsIntent.Builder().setShowTitle(true).setDefaultColorSchemeParams(colors).build().launchUrl(this, uri)
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                .onFailure { Toast.makeText(this, "No browser found to open the Samsung sign-in.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TV Installer", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
