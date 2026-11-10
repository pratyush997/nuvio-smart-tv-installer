// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    private val viewModel: InstallerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loginOpener = { url -> openBrowser(url) }
        UpdateCheck.schedule(applicationContext)
        if (savedInstanceState == null) handleUpdateIntent(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Palette.Accent,
                    onPrimary = androidx.compose.ui.graphics.Color.White,
                    background = Palette.Background,
                    surface = Palette.SurfaceHigh,
                    surfaceContainer = Palette.SurfaceHigh,
                    onSurface = Palette.Text,
                    outlineVariant = Palette.Outline,
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
        viewModel.refreshPhoneIp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    /** Tapping the "new version" notification starts the update straight away. */
    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.action != UpdateCheck.ACTION_UPDATE) return
        UpdateCheck.dismiss(this)
        viewModel.updateNow()
    }

    private fun openBrowser(url: String) {
        val uri = Uri.parse(url)
        try {
            val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(Palette.Background.toArgb()).build()
            CustomTabsIntent.Builder().setShowTitle(true).setDefaultColorSchemeParams(colors).build().launchUrl(this, uri)
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                .onFailure { Toast.makeText(this, "No browser found to open the Samsung login.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Nuvio installer", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
