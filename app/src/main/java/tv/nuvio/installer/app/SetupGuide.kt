package tv.nuvio.installer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val GUIDE_STEPS = 5

/**
 * First-time setup, one step per screen: what you need, Developer Mode, power-cycle, TV address, install.
 * Shown on first launch and reachable later from "Setup guide" on the main screen.
 */
@Composable
fun SetupGuide(vm: InstallerViewModel, copy: (String) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
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
            color = Palette.Violet,
            trackColor = Palette.SurfaceHigh,
        )

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (step) {
                    0 -> WelcomeStep(vm)
                    1 -> DeveloperModeStep(vm, copy)
                    2 -> PowerCycleStep()
                    3 -> TvAddressStep(vm)
                    else -> ReadyStep(vm)
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
                    0 -> GradientButton("Start", enabled = true, onClick = next, modifier = Modifier.weight(1f))
                    1 -> GradientButton("Done, next", enabled = true, onClick = next, modifier = Modifier.weight(1f))
                    2 -> GradientButton("The TV is back on", enabled = true, onClick = next, modifier = Modifier.weight(1f))
                    3 -> when {
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
private fun WelcomeStep(vm: InstallerViewModel) {
    GuideTitle("Put Nuvio on your Samsung TV", "About 10 minutes, once. After that, updates take one tap.")
    Panel {
        SectionTitle("You need")
        Bullet("A Samsung smart TV and its remote")
        Bullet("This phone and the TV on the same Wi-Fi")
        Bullet("A Samsung account (free). You sign in once.")
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("LG TV · coming soon", Palette.Faint)
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
            Text(vm.phoneIp, color = Palette.Cyan, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
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
        GuideStep(1, "On the TV open Settings › General (or Connection) › Network › Network Status › IP Settings.")
        GuideStep(2, "Type the IP address shown there:")
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
private fun ReadyStep(vm: InstallerViewModel) {
    val installed = vm.installedVersion
    GuideTitle(
        "Your TV is connected",
        if (installed != null) "Nuvio $installed is on the TV now." else "Setup is done. One tap installs Nuvio.",
    )
    Panel {
        SectionTitle("What happens next")
        GuideStep(1, "The app downloads Nuvio.")
        GuideStep(2, "A Samsung sign-in opens in the browser, the first time only. It creates the certificate your TV needs.")
        GuideStep(3, "Nuvio is installed on the TV. Keep the TV on.")
        GuideStep(4, "The certificate is backed up to this phone's Downloads folder automatically.")
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
        Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(Palette.Violet))
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
