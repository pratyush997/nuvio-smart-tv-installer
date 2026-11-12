// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.app

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tv.nuvio.installer.core.NuvioApp
import tv.nuvio.installer.core.TvBrand

private const val GUIDE_STEPS = 6

/** Steps that can't be done without Wi-Fi: what you need, Developer Mode (the phone's address), find your TV. */
private val WIFI_STEPS = setOf(1, 2, 4)

/**
 * First-time setup, one step per screen: which TV, what you need, Developer Mode, restart,
 * connect, then the Samsung sign-in that creates the TV's certificate and the install itself.
 * Shown on first launch and reachable later from "Setup guide" on the main screen.
 */
@Composable
fun SetupGuide(vm: InstallerViewModel, copy: (String) -> Unit, onImport: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(vm.guideStartStep) }
    var brand by rememberSaveable { mutableStateOf<TvBrand?>(null) }
    val busy = vm.busy != null
    val connected = vm.tvState == TvState.ONLINE
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val scroll = rememberScrollState()
    LaunchedEffect(step) {
        scroll.scrollTo(0)
        // The "find your TV" step looks for TVs as soon as it opens. Without Wi-Fi, joining it starts the search.
        if (step == 4 && vm.onWifi && vm.tvIp.isBlank() && vm.foundTvs == null) vm.scanForTvs()
    }
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
                if (!vm.onWifi && step in WIFI_STEPS) {
                    GradientButton("Open Wi-Fi settings", enabled = true, onClick = { openWifiSettings(context) }, modifier = Modifier.weight(1f))
                } else when (step) {
                    0 -> GradientButton(
                        if (brand == TvBrand.LG) "LG is coming soon" else "Next",
                        enabled = brand == TvBrand.SAMSUNG, onClick = next, modifier = Modifier.weight(1f),
                    )
                    1 -> GradientButton("I have these", enabled = true, onClick = next, modifier = Modifier.weight(1f))
                    2 -> GradientButton("Done, next", enabled = true, onClick = next, modifier = Modifier.weight(1f))
                    3 -> GradientButton("The TV is back on", enabled = true, onClick = next, modifier = Modifier.weight(1f))
                    4 -> when {
                        connected -> GradientButton("Next", enabled = !busy, onClick = next, modifier = Modifier.weight(1f))
                        vm.scanning -> GradientButton("Looking for your TV…", enabled = false, onClick = {}, modifier = Modifier.weight(1f))
                        busy -> GradientButton("Checking…", enabled = false, onClick = {}, modifier = Modifier.weight(1f))
                        vm.tvIp.isBlank() -> GradientButton(
                            if (vm.foundTvs == null) "Find my TV" else "Look again",
                            enabled = true, onClick = { vm.scanForTvs() }, modifier = Modifier.weight(1f),
                        )
                        else -> GradientButton(
                            "Try again", enabled = true,
                            onClick = { focus.clearFocus(); vm.checkTv() }, modifier = Modifier.weight(1f),
                        )
                    }
                    else -> when {
                        vm.source(NuvioApp.TV) == null || !connected -> GradientButton("Finish", enabled = true, onClick = vm::closeGuide, modifier = Modifier.weight(1f))
                        vm.hasIdentityForTv -> GradientButton(
                            vm.buttonLabel(NuvioApp.TV), enabled = !busy, onClick = { vm.closeGuide(); vm.install(NuvioApp.TV) }, modifier = Modifier.weight(1f),
                        )
                        else -> GradientButton(
                            "Sign in with Samsung", enabled = !busy, onClick = { vm.closeGuide(); vm.install(NuvioApp.TV) }, modifier = Modifier.weight(1f),
                        )
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
        Bullet("A Samsung account. It's free, and you sign in only once.")
    }
    if (!vm.onWifi) {
        NoWifiNotice(button = false)
        return
    }
    Panel {
        SectionTitle("This phone")
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Palette.Success)
            Spacer(Modifier.width(10.dp))
            Text("On Wi-Fi", color = Palette.Text, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun DeveloperModeStep(vm: InstallerViewModel, copy: (String) -> Unit) {
    GuideTitle("Turn on Developer Mode", "On the TV, with the TV remote. This lets the TV accept Nuvio from your phone.")
    // The IP address to type on the TV is the phone's Wi-Fi address.
    if (!vm.onWifi) {
        NoWifiNotice(button = false)
        return
    }
    Panel {
        GuideStep(1, "Press Home and open Apps.")
        GuideStep(2, "Press 1 2 3 4 5 on the remote. No number keys? Press the 123 button and pick the numbers on screen.")
        GuideStep(3, "Switch Developer Mode to On.")
        GuideStep(4, "In Host IP, type this IP address:")
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
            Note("This looks like an emulator IP. Enter your computer's Wi-Fi IP on the TV instead (run ipconfig on the PC).", warning = true)
        }
        GuideStep(5, "Select OK.")
    }
    if (vm.phoneIp.startsWith("10.0.2.") || vm.phoneIp.startsWith("10.0.3.")) return
    StaticIpTip(vm, copy)
}

/**
 * The TV only accepts the IP address typed in Host IP, and routers can give the phone a new one.
 * Setting it as a static IP in the phone's Wi-Fi settings keeps it, so Host IP never has to be
 * typed again. Short here; the steps, filled in with this phone's values, are in a popup.
 */
@Composable
private fun StaticIpTip(vm: InstallerViewModel, copy: (String) -> Unit) {
    val settings = vm.staticIp ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    Panel {
        SectionTitle("Stop this IP address from changing")
        Text(
            "Your router can give this phone a new IP address some day. Then you'd have to type the new one on the TV again. " +
                "A quick change in this phone's Wi-Fi settings stops that.",
            color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp,
        )
        SubtleLink("Show me how") { open = true }
    }
    if (open) StaticIpDialog(settings, vm.routerPage, copy, onDismiss = { open = false })
}

/** Primary DNS servers suggested for the static IP: Cloudflare, then Google. */
private const val DNS_1 = "1.1.1.1"
private const val DNS_2 = "8.8.8.8"

/**
 * The steps for a static IP, in the app's own style: title and buttons stay put, the steps
 * scroll between them, and the values sit in one compact table, each row tap to copy.
 */
@Composable
private fun StaticIpDialog(settings: StaticIpSettings, routerPage: String?, copy: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 24.dp).widthIn(max = 480.dp).fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)).background(Palette.Background),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Set a static IP", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("So this phone always keeps ${settings.ip}.", color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            HorizontalDivider(color = Palette.Outline)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GuideStep(1, "Open Wi-Fi settings and tap the gear next to the Wi-Fi you're on.")
                GuideStep(2, "Tap the pencil at the top. On Samsung, tap View more at the bottom.")
                GuideStep(3, "Change IP settings from DHCP to Static.")
                GuideStep(4, "Fill in these. Tap a row to copy it.")
                Column(
                    Modifier.padding(start = 36.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Palette.Surface).border(1.dp, Palette.Outline, RoundedCornerShape(14.dp)),
                ) {
                    SettingRow("IP address", settings.ip, copy)
                    HorizontalDivider(color = Palette.Outline)
                    SettingRow("Gateway", settings.gateway, copy)
                    HorizontalDivider(color = Palette.Outline)
                    SettingRow("Prefix length", settings.prefixLength.toString(), copy)
                    HorizontalDivider(color = Palette.Outline)
                    SettingRow("DNS 1", DNS_1, copy)
                    HorizontalDivider(color = Palette.Outline)
                    SettingRow("DNS 2", DNS_2, copy)
                }
                GuideStep(5, "Tap Save.")
                Note("If the Wi-Fi stops working, set IP settings back to DHCP.")
                if (routerPage != null) SubtleLink("Or reserve it on your router's page instead") { runCatching { uri.openUri(routerPage) } }
            }
            HorizontalDivider(color = Palette.Outline)
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Close", null, enabled = true, onClick = onDismiss, modifier = Modifier.width(96.dp).height(56.dp))
                GradientButton(
                    "Open Wi-Fi settings", enabled = true,
                    onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One row of the table: what the phone's setting is called, and the value to type. [value] null: keep what's there. */
@Composable
private fun SettingRow(label: String, value: String?, copy: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = value != null) { value?.let(copy) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, color = Palette.Link, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            Text("Copy", color = Palette.Muted, fontSize = 12.sp)
        } else {
            Text("Leave as is", color = Palette.Muted, fontSize = 13.sp)
        }
    }
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
    var typeAddress by rememberSaveable { mutableStateOf(false) }
    val nothingFound = vm.foundTvs?.isEmpty() == true
    GuideTitle("Find your TV", "Make sure the TV is on, then tap it in the list.")
    if (!vm.onWifi) {
        NoWifiNotice(button = false)
        return
    }
    Panel {
        TvFinder(vm, showButton = false)
        when (vm.tvState) {
            TvState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Palette.Warning, pulsing = true)
                Spacer(Modifier.width(10.dp))
                Text("Checking…", color = Palette.Text, fontSize = 14.sp)
            }
            TvState.ONLINE -> Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Palette.Success)
                Spacer(Modifier.width(10.dp))
                Text("Connected" + vm.tvName.takeIf { it.isNotBlank() }?.let { " to $it" }.orEmpty(), color = Palette.Text, fontSize = 14.sp)
            }
            TvState.OFFLINE -> vm.ipReport?.let { IpReportView(it) }
            TvState.UNKNOWN -> Unit
        }
    }
    // Typing the address is the fallback: hidden until asked for, or until the search finds nothing.
    if (typeAddress || nothingFound) {
        Panel {
            Text(
                "On the TV, open Settings › General (or Connection) › Network › Network Status › IP Settings. Type the IP address shown there:",
                color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            TvIpField(vm, enabled = vm.busy == null)
        }
    } else {
        SubtleLink("TV not in the list? Type its IP") { typeAddress = true }
    }
    Note("If the router gives your TV a new IP one day, the app finds it again by itself.")
}

@Composable
private fun SamsungAccountStep(vm: InstallerViewModel, onImport: () -> Unit) {
    val uri = LocalUriHandler.current
    var otherWays by rememberSaveable { mutableStateOf(false) }
    if (vm.hasIdentityForTv) {
        GuideTitle("You're ready", "This TV is already set up on this phone. No sign-in needed.")
        Panel {
            GuideStep(1, "Tap the button below.")
            GuideStep(2, "Nuvio installs on your TV. It takes about a minute.")
        }
        AnotherTvNote(vm)
        return
    }
    GuideTitle(
        "Sign in with Samsung",
        "Samsung TVs only open apps that are made for that TV. Signing in lets this app do that for you. You do it once per TV.",
    )
    Panel {
        GuideStep(1, "Tap Sign in with Samsung below.")
        GuideStep(2, "Samsung's page opens. Sign in, then come back to this app.")
        GuideStep(3, "Nuvio installs on your TV by itself. It takes about a minute.")
    }
    WarningBox(
        "Keep the backup file",
        "The app saves a file to your phone's Downloads folder. Keep it: if you change or reset your phone, you need it to update Nuvio. " +
            "Without it, Nuvio has to be removed from the TV and installed again.",
    )
    SubtleLink("No Samsung account? Create one, it's free") { uri.openUri("https://account.samsung.com/") }
    if (!otherWays) {
        SubtleLink("Other ways to set up this TV") { otherWays = true }
    } else {
        Panel {
            SectionTitle("Used the desktop Nuvio installer before?")
            Text(
                "Import the file it saved (samsung-tv-certificate-….json). Otherwise the TV refuses updates.",
                color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            SoftButton("Import file", null, enabled = vm.busy == null, onClick = onImport)
        }
        Panel {
            SectionTitle("Made a certificate in Tizen Studio?")
            Text(
                "Copy author.p12 and distributor.p12 to this phone, then import both files together." +
                    (vm.tvInfo?.duid?.let { " The certificate must include this TV's DUID: $it." } ?: ""),
                color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            SoftButton("Import files", null, enabled = vm.busy == null, onClick = onImport)
        }
    }
    AnotherTvNote(vm)
}

@Composable
private fun AnotherTvNote(vm: InstallerViewModel) {
    Note(
        if (vm.savedTvs.size > 1) "Nuvio updates for each of your TVs show up as notifications. Tap one to update that TV."
        else "Have another TV? When this one is done, tap Add another TV on the main screen.",
    )
}

/** A small text link for optional paths, so the main button stays the obvious choice. */
@Composable
private fun SubtleLink(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
        Text(text, color = Palette.Link, fontSize = 14.sp)
    }
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
