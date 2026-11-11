// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val GUIDE_STEPS = 6

private enum class TvBrand { SAMSUNG, LG }

/**
 * First-time setup, one step per screen: which TV, what you need, Developer Mode, restart,
 * connect, then the Samsung sign-in that creates the TV's certificate and the install itself.
 * Shown on first launch and reachable later from "Setup guide" on the main screen.
 */
@Composable
fun SetupGuide(vm: InstallerViewModel, copy: (String) -> Unit, onImport: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var brand by rememberSaveable { mutableStateOf<TvBrand?>(null) }
    val busy = vm.busy != null
    val connected = vm.tvState == TvState.ONLINE
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    BackHandler { if (step > 0) step-- else vm.closeGuide() }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Setup guide", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::closeGuide) { Text(if (step == 0) "Skip" else "Close", color = Palette.Muted) }
            }
            Text("Step ${step + 1} of $GUIDE_STEPS", color = Palette.Muted, fontSize = 13.sp)
        }
        LinearProgressIndicator(
            progress = { (step + 1f) / GUIDE_STEPS },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(CircleShape),
            color = Palette.Accent,
            trackColor = Palette.SurfaceHigh,
        )

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (step) {
                    0 -> ChooseTvStep(brand, onChoose = { brand = it })
                    1 -> NeedsStep(vm)
                    2 -> DeveloperModeStep(vm, copy)
                    3 -> PowerCycleStep()
                    4 -> TvAddressStep(vm)
                    else -> SamsungAccountStep(vm, onImport)
                }
            }
        }

        HorizontalDivider(color = Palette.Outline)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) {
                    SoftButton("Back", null, enabled = !busy, onClick = { step-- }, modifier = Modifier.width(96.dp).height(56.dp))
                }
                val next: () -> Unit = { step++ }
                when (step) {
                    0 -> GradientButton(
                        if (brand == TvBrand.LG) "LG is coming soon" else "Next",
                        enabled = brand == TvBrand.SAMSUNG, onClick = next, modifier = Modifier.weight(1f),
                    )
                    1 -> GradientButton("I have these", enabled = true, onClick = next, modifier = Modifier.weight(1f))
                    2 -> GradientButton("Done, next", enabled = true, onClick = next, modifier = Modifier.weight(1f))
                    3 -> GradientButton("The TV is back on", enabled = true, onClick = next, modifier = Modifier.weight(1f))
                    4 -> when {
                        connected -> GradientButton("Next", enabled = !busy, onClick = next, modifier = Modifier.weight(1f))
                        busy -> GradientButton("Checking…", enabled = false, onClick = {}, modifier = Modifier.weight(1f))
                        else -> GradientButton(
                            "Check connection", enabled = vm.tvIp.isNotBlank(),
                            onClick = { focus.clearFocus(); vm.checkTv() }, modifier = Modifier.weight(1f),
                        )
                    }
                    else -> if (vm.source != null && connected) {
                        GradientButton(vm.primaryLabel, enabled = !busy, onClick = { vm.closeGuide(); vm.install() }, modifier = Modifier.weight(1f))
                    } else {
                        GradientButton("Finish", enabled = true, onClick = vm::closeGuide, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- steps

@Composable
private fun ChooseTvStep(brand: TvBrand?, onChoose: (TvBrand) -> Unit) {
    GuideTitle("Which TV do you have?", "This app puts Nuvio on your TV. About 10 minutes the first time, then updates take one tap.")
    BrandOption("Samsung", "Smart TVs with Tizen", selected = brand == TvBrand.SAMSUNG, available = true) { onChoose(TvBrand.SAMSUNG) }
    BrandOption("LG", "Coming soon", selected = brand == TvBrand.LG, available = false) { onChoose(TvBrand.LG) }
    if (brand == TvBrand.LG) {
        Note("LG TVs are not supported yet. Support is planned; the next versions of this app will add it.")
    }
}

@Composable
private fun BrandOption(name: String, detail: String, selected: Boolean, available: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Palette.Accent.copy(alpha = 0.12f) else Palette.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, color = if (available) Palette.Text else Palette.Muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Palette.Muted, fontSize = 14.sp)
        }
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (selected) Palette.Accent else Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Palette.OnAccent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NeedsStep(vm: InstallerViewModel) {
    GuideTitle("What you need", "Get these ready before you start.")
    Panel {
        Bullet("Your Samsung TV and its remote")
        Bullet("This phone and the TV on the same Wi-Fi")
        Bullet("A Samsung account (free). You sign in once, near the end.")
    }
    Panel {
        SectionTitle("This phone")
        val onNetwork = vm.phoneIp != "unknown"
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (onNetwork) Palette.Success else Palette.Error)
            Spacer(Modifier.width(10.dp))
            Text(
                if (onNetwork) "On Wi-Fi · IP ${vm.phoneIp}" else "Not on Wi-Fi. Connect to the same Wi-Fi as the TV first.",
                color = Palette.Text, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun DeveloperModeStep(vm: InstallerViewModel, copy: (String) -> Unit) {
    GuideTitle("Turn on Developer Mode", "Do this on the TV, with the TV remote.")
    Panel {
        GuideStep(1, "Press Home and open Apps.")
        GuideStep(2, "Press 1 2 3 4 5 on the remote. No number keys? Press the 123 button and pick the numbers on screen.")
        GuideStep(3, "Switch Developer Mode to On.")
        GuideStep(4, "In Host PC IP, type this phone's IP:")
        Row(
            Modifier
                .padding(start = 36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.SurfaceHigh)
                .clickable { copy(vm.phoneIp) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(vm.phoneIp, color = Palette.Link, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(12.dp))
            Text("Copy", color = Palette.Muted, fontSize = 12.sp)
        }
        if (vm.phoneIp.startsWith("10.0.2.") || vm.phoneIp.startsWith("10.0.3.")) {
            Note("This looks like an emulator address. Enter your computer's Wi-Fi IP on the TV instead (run ipconfig on the PC).", warning = true)
        }
        if (vm.phoneIp == "unknown") {
            Note("This phone is not on Wi-Fi. Connect it to the same Wi-Fi as the TV, then come back to this step.", warning = true)
        }
        GuideStep(5, "Select OK.")
    }
    Note("If this phone's IP changes later, the app notices and tells you the new number to type.")
}

@Composable
private fun PowerCycleStep() {
    GuideTitle("Restart the TV at the plug", "Developer Mode starts only after the TV loses power.")
    Panel {
        GuideStep(1, "Unplug the TV from power.")
        GuideStep(2, "Wait 5 seconds.")
        GuideStep(3, "Plug it back in and turn the TV on.")
    }
    Note("Turning the TV off with the remote is not enough. That only puts it to sleep.")
}

@Composable
private fun TvAddressStep(vm: InstallerViewModel) {
    GuideTitle("Connect to the TV", "The app checks the phone's IP, the TV's IP and Developer Mode for you.")
    Panel {
        GuideStep(1, "Tap Find my TV and pick your TV from the list.")
        TvFinder(vm)
        GuideStep(2, "Not listed? On the TV open Settings › General (or Connection) › Network › Network Status › IP Settings, and type the IP shown there:")
        TvIpField(vm, enabled = vm.busy == null)
        when (vm.tvState) {
            TvState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Palette.Warning, pulsing = true)
                Spacer(Modifier.width(10.dp))
                Text("Checking…", color = Palette.Text, fontSize = 14.sp)
            }
            TvState.ONLINE, TvState.OFFLINE -> vm.ipReport?.let { IpReportView(it) }
            TvState.UNKNOWN -> Unit
        }
    }
}

@Composable
private fun SamsungAccountStep(vm: InstallerViewModel, onImport: () -> Unit) {
    val uri = LocalUriHandler.current
    val installed = vm.installedVersion
    if (vm.hasIdentityForTv) {
        GuideTitle("You're ready", "The certificate for this TV is already saved, so no Samsung sign-in is needed.")
    } else {
        GuideTitle(
            "Sign in with Samsung",
            "Samsung TVs only run apps that are signed for that exact TV. The app gets that signature for you. You just sign in once.",
        )
    }
    Panel {
        SectionTitle(if (installed != null) "What happens when you tap Update" else "What happens when you tap Install")
        GuideStep(1, "The app downloads Nuvio.")
        if (vm.hasIdentityForTv) {
            GuideStep(2, "The app signs Nuvio with your TV's saved certificate.")
        } else {
            GuideStep(2, "Your browser opens Samsung's sign-in page. Sign in, then come back to this app.")
            GuideStep(3, "Samsung creates a certificate just for your TV, and the app signs Nuvio with it.")
        }
        GuideStep(if (vm.hasIdentityForTv) 3 else 4, "Nuvio is installed on the TV. You can watch each step as it happens.")
        if (!vm.hasIdentityForTv) {
            GuideStep(5, "A backup of the certificate is saved to this phone's Downloads folder. Keep it: updates need it.")
        }
    }
    if (!vm.hasIdentityForTv) {
        Panel {
            SectionTitle("No Samsung account?")
            Text("Any Samsung account works, and it is free. Create one, then come back here.", color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp)
            SoftButton("Create a Samsung account", null, enabled = true, onClick = { uri.openUri("https://account.samsung.com/") })
        }
        Panel {
            SectionTitle("Used the desktop Nuvio installer before?")
            Text(
                "Import the certificate file it saved (samsung-tv-certificate-….json). Without it, the TV refuses updates to the Nuvio it installed.",
                color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            SoftButton("Import certificate", null, enabled = vm.busy == null, onClick = onImport)
        }
        Panel {
            SectionTitle("Rather not sign in on this phone?")
            Text(
                "Create a Samsung certificate in Tizen Studio's Certificate Manager on a computer, with your TV's DUID" +
                    (vm.tvInfo?.duid?.let { " ($it)" } ?: "") + ". Copy author.p12 and distributor.p12 from its profile folder " +
                    "to this phone, tap Import and select both files. The Samsung sign-in then happens in Tizen Studio, not here.",
                color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            SoftButton("Import Tizen Studio files", null, enabled = vm.busy == null, onClick = onImport)
        }
    }
    Note("You get a notification when a new Nuvio version is out. Tap it to update the TV.")
}

// ---------------------------------------------------------------- pieces

@Composable
private fun GuideTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.Text, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Palette.Muted, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun GuideStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
            Text("$number", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.Text, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(Palette.Accent))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.Text, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (warning) Palette.Warning.copy(alpha = 0.1f) else Palette.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (warning) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.Warning, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (warning) Palette.Warning else Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
