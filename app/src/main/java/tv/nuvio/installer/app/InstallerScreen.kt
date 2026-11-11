// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import tv.nuvio.installer.core.TizenStudioCertificate
import tv.nuvio.installer.core.NoteLine
import tv.nuvio.installer.core.Release
import tv.nuvio.installer.core.ReleaseNotes
import tv.nuvio.installer.core.CertificateExpiry
import tv.nuvio.installer.core.CheckState
import tv.nuvio.installer.core.ExpiryState
import tv.nuvio.installer.core.FoundTv
import tv.nuvio.installer.core.InstallStage
import tv.nuvio.installer.core.IpReport
import tv.nuvio.installer.core.LogLevel

// ---------------------------------------------------------------- palette

object Palette {
    val Background = Color(0xFF0A0A0F)
    val Surface = Color(0xFF14141B)
    val SurfaceHigh = Color(0xFF1C1C25)
    val Outline = Color(0xFF2A2A35)
    val Text = Color(0xFFF4F4F6)
    val Muted = Color(0xFF9A9AA8)
    val Faint = Color(0xFF5C5C6A)
    /** Text buttons and links. */
    val Link = Color(0xFF7DD3FC)
    val Blue = Color(0xFF3B82F6)
    /** Progress, focus and selection. */
    val Accent = Color(0xFF0EA5E9)
    val Success = Color(0xFF34D399)
    val Error = Color(0xFFF87171)
    val Warning = Color(0xFFFBBF24)
    /** Same colours as the app icon. */
    val BrandGradient = Brush.horizontalGradient(listOf(Color(0xFF1D4ED8), Color(0xFF0E7490)))
}

// ---------------------------------------------------------------- screen

@Composable
fun InstallerScreen(vm: InstallerViewModel, copy: (String) -> Unit) {
    val busy = vm.busy != null
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    val pickWgt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::selectFile) }
    val importCert = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importIdentities(uris) }
    val exportCert = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(vm::exportIdentities)
    }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    if (aboutOpen) AboutDialog(vm, onDismiss = { aboutOpen = false })
    vm.importPasswordRequest?.let { request ->
        val (title, message) = when (request) {
            is ImportPasswordRequest.Backup -> "Backup password" to
                if (request.wrongPassword) "That password did not open the backup. Try again." else "This backup is protected. Type its password."
            is ImportPasswordRequest.TizenStudio -> when {
                request.part == TizenStudioCertificate.Part.DISTRIBUTOR -> "Distributor password" to
                    if (request.wrongPassword) "That password did not open distributor.p12. Try again."
                    else "The author password does not open distributor.p12. Type the distributor certificate's password."
                request.wrongPassword -> "Certificate password" to "That password did not open author.p12. Try again."
                else -> "Certificate password" to "Type the password you chose for this certificate profile in Tizen Studio."
            }
        }
        PasswordDialog(
            title = title,
            message = message,
            confirmLabel = "Import",
            needsConfirmation = false,
            onDone = vm::answerImportPassword,
        )
    }

    // The automatic certificate backup could not write to Downloads: open the save dialog instead.
    LaunchedEffect(vm.backupRequest) {
        if (vm.backupRequest) {
            vm.backupRequestHandled()
            exportCert.launch(vm.backupFileName)
        }
    }
    // Once Nuvio is on the TV, ask (once) for permission to announce new versions.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(vm.installedVersion, vm.busy) {
        if (vm.busy == null && vm.shouldAskForNotifications()) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (vm.showGuide) {
        SetupGuide(vm, copy, onImport = { importCert.launch(arrayOf("*/*")) })
        return
    }

    // Progress and results go to the top of the screen, and the screen scrolls there, so they are never out of sight.
    val mainScroll = rememberScrollState()
    val installing = vm.busy != null && vm.busy != "Checking TV"
    LaunchedEffect(installing, vm.outcome) { if (installing || vm.outcome != null) mainScroll.animateScrollTo(0) }

    // Mobile-first: one column sized for phones; on larger screens the same column is centred.
    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(mainScroll)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Header(onGuide = vm::openGuide, onAbout = { aboutOpen = true })
                vm.appUpdate?.let { update -> AppUpdateBanner(update, onDismiss = vm::dismissAppUpdate) }
                if (installing) ProgressCard(vm)
                vm.outcome?.let { outcome ->
                    OutcomeCard(
                        outcome = outcome,
                        fixBelow = outcome.kind == OutcomeKind.ERROR && vm.ipReport?.fix != null,
                        onDismiss = vm::dismissOutcome,
                        onShowLog = { logOpen = true },
                        onImport = { importCert.launch(arrayOf("*/*")) },
                        onUninstall = vm::uninstall,
                    )
                }
                TvCard(vm, onPickFile = { pickWgt.launch(arrayOf("*/*")) })
                CertificateCard(
                    vm,
                    enabled = !busy,
                    onImport = { importCert.launch(arrayOf("*/*")) },
                    onBackup = { exportCert.launch(vm.backupFileName) },
                    onSignIn = vm::createCertificate,
                )
                LogCard(vm, open = logOpen, onToggle = { logOpen = !logOpen }, copy = copy)
                Spacer(Modifier.height(8.dp))
            }
        }
        BottomBar(vm)
    }
}

internal val MaxContentWidth = 560.dp

/** Primary action pinned to the bottom, within thumb reach. */
@Composable
private fun BottomBar(vm: InstallerViewModel) {
    val busy = vm.busy != null
    val installing = vm.busy == "Install"
    HorizontalDivider(color = Palette.Outline)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (installing) {
                val stage = vm.stage
                val step = (stage?.ordinal ?: 0) + 1
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (vm.waitingForLogin) "Waiting for Samsung login…" else "Step $step of ${InstallStage.entries.size} · ${(stage ?: InstallStage.PACKAGE).title}",
                        color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    vm.progress?.let { Text("${(it * 100).toInt()}%", color = Palette.Muted, fontSize = 13.sp) }
                }
                val fraction = ((step - 1) + (vm.progress ?: 0.5f)) / InstallStage.entries.size
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
            }
            GradientButton(
                text = when {
                    installing -> "Installing…"
                    busy -> "${vm.busy}…"
                    else -> vm.primaryLabel
                },
                enabled = !busy && vm.source != null && vm.tvIp.isNotBlank(),
                onClick = vm::install,
            )
        }
    }
}

@Composable
private fun Header(onGuide: () -> Unit, onAbout: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("TV Installer", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("for Nuvio", color = Palette.Muted, fontSize = 12.sp)
            }
            HeaderAction(Icons.Filled.List, "Guide", onGuide)
            HeaderAction(Icons.Filled.Info, "About", onAbout)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("for Samsung TV", Palette.Muted)
            Chip("LG TV · coming soon", Palette.Faint)
        }
    }
}

/** Small icon with a label underneath, for the header. */
@Composable
private fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.Link, modifier = Modifier.size(22.dp))
        Text(label, color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun Chip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, RoundedCornerShape(20.dp))
            .padding(18.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun SectionTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun StatusDot(color: Color, pulsing: Boolean = false) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "dot")
        transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha").value
    } else 1f
    Box(Modifier.size(9.dp).alpha(alpha).clip(CircleShape).background(color))
}

@Composable
internal fun GradientButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Palette.BrandGradient else Brush.horizontalGradient(listOf(Palette.SurfaceHigh, Palette.SurfaceHigh)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else Palette.Faint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SoftButton(
    text: String,
    icon: ImageVector?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.Text,
) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoTile(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), color = Palette.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        content()
    }
}

// ---------------------------------------------------------------- TV card (main)

@Composable
private fun TvCard(vm: InstallerViewModel, onPickFile: () -> Unit) {
    val busy = vm.busy != null
    Panel {
        SectionTitle("Your TV")

        TvIpField(vm, enabled = !busy)
        TvFinder(vm)

        val (dotColor, statusText) = when (vm.tvState) {
            TvState.UNKNOWN -> Palette.Faint to "Not checked yet"
            TvState.CHECKING -> Palette.Warning to "Connecting…"
            TvState.ONLINE -> Palette.Success to buildString {
                append("Connected")
                vm.tvName.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                vm.tvInfo?.platformVersion?.takeIf { it.isNotBlank() }?.let { append(" · Tizen $it") }
            }
            TvState.OFFLINE -> Palette.Error to "Not reachable"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(dotColor, pulsing = vm.tvState == TvState.CHECKING)
            Spacer(Modifier.width(10.dp))
            Text(statusText, color = Palette.Text, fontSize = 14.sp)
        }
        if (vm.tvState == TvState.OFFLINE) {
            val report = vm.ipReport
            if (report != null) IpReportView(report)
            else vm.tvError?.let { Text(it, color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp) }
            TextButton(onClick = vm::openGuide) { Text("Open the setup guide", color = Palette.Link) }
        } else if (vm.tvIp.isBlank()) {
            Text("Tap Find my TV, or type the TV's IP address.", color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoTile("Nuvio on TV", Modifier.weight(1f)) {
                val installed = vm.installedVersion
                Text(
                    when {
                        vm.tvState != TvState.ONLINE -> "—"
                        installed != null -> installed
                        else -> "Not installed"
                    },
                    color = if (installed != null) Palette.Text else Palette.Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                )
            }
            VersionTile(vm, Modifier.weight(1f), enabled = !busy, onPickFile = onPickFile)
        }
        (vm.source as? PackageSource.FromRelease)?.release?.let { ReleaseNotesView(it) }

        val installed = vm.tvState == TvState.ONLINE && vm.installedVersion != null
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Open on TV", Icons.Filled.PlayArrow, enabled = !busy && installed, onClick = vm::launchOnTv, modifier = Modifier.weight(1f))
            SoftButton("Uninstall", Icons.Filled.Delete, enabled = !busy && installed, onClick = vm::uninstall, modifier = Modifier.weight(1f), tint = Palette.Muted)
        }
        SoftButton(
            "Type on TV", Icons.Filled.Edit, enabled = vm.tvState == TvState.ONLINE, onClick = vm::openTypeOnTv,
            modifier = Modifier.fillMaxWidth(), tint = Palette.Link,
        )
        if (vm.installedVersion != null) AutoUpdateRow(vm)
    }
    if (vm.typeOnTvOpen) TypeOnTvDialog(vm)
}

/** Types into the TV's on-screen keyboard from the phone (search, sign-in fields). */
@Composable
private fun TypeOnTvDialog(vm: InstallerViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = vm::closeTypeOnTv,
        title = { Text("Type on TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "On the TV, open a search box or text field so its keyboard shows. Then type here and tap Send.",
                    fontSize = 13.sp, lineHeight = 18.sp,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("Text") }, singleLine = true,
                    enabled = !vm.typeOnTvSending, modifier = Modifier.fillMaxWidth(),
                )
                vm.typeOnTvStatus?.let { Text(it, color = Palette.Muted, fontSize = 12.sp, lineHeight = 16.sp) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.sendTextToTv(text, enter = false) }, enabled = !vm.typeOnTvSending && text.isNotEmpty()) { Text("Send") }
                TextButton(onClick = { vm.sendTextToTv(text, enter = true) }, enabled = !vm.typeOnTvSending) { Text("Send + Enter") }
            }
        },
        dismissButton = { TextButton(onClick = vm::closeTypeOnTv) { Text("Close") } },
    )
}

@Composable
private fun AutoUpdateRow(vm: InstallerViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Update Nuvio automatically", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                if (vm.autoUpdate) "New versions are installed on the TV when the TV is on and the phone is on the same Wi-Fi. You get a notification after each update."
                else "Off: you get a notification when a new version is out, and tap it to update.",
                color = Palette.Muted, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.Switch(checked = vm.autoUpdate, onCheckedChange = vm::updateAutoUpdate)
    }
}

/** TV address input with a "check" button; shared by the main screen and the setup guide. */
@Composable
internal fun TvIpField(vm: InstallerViewModel, enabled: Boolean) {
    val focus = LocalFocusManager.current
    val busy = !enabled
    OutlinedTextField(
        value = vm.tvIp,
        onValueChange = vm::updateTvIp,
        label = { Text("TV IP address") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.checkTv() }),
        trailingIcon = {
            if (vm.tvState == TvState.CHECKING) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Muted)
            } else {
                IconButton(onClick = { focus.clearFocus(); vm.checkTv() }, enabled = !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Check TV", tint = Palette.Muted)
                }
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline,
            focusedLabelColor = Palette.Accent, unfocusedLabelColor = Palette.Muted,
            focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text, disabledTextColor = Palette.Muted,
            disabledBorderColor = Palette.Outline, cursorColor = Palette.Accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Find my TV": scans the Wi-Fi and lists the Samsung TVs that answered; tapping one uses it. */
@Composable
internal fun TvFinder(vm: InstallerViewModel) {
    val found = vm.foundTvs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.scanning) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Muted)
                Spacer(Modifier.width(10.dp))
                Text("Looking for TVs on this Wi-Fi…", color = Palette.Muted, fontSize = 13.sp)
            } else {
                TextButton(onClick = { vm.scanForTvs() }, enabled = vm.busy == null) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.Link, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (found == null) "Find my TV" else "Search again", color = Palette.Link)
                }
            }
        }
        if (!vm.scanning && found != null) {
            when {
                vm.phoneIp == "unknown" -> Text(
                    "This phone is not on Wi-Fi. Connect it to the same Wi-Fi as the TV.",
                    color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                found.isEmpty() -> Text(
                    "No Samsung TV answered. Check that the TV is on and on the same Wi-Fi (not a guest network), or type its IP.",
                    color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                else -> found.forEach { tv -> FoundTvRow(tv, vm.phoneIp, selected = tv.ip == vm.tvIp, enabled = vm.busy == null) { vm.selectTv(tv) } }
            }
        }
    }
}

@Composable
private fun FoundTvRow(tv: FoundTv, phoneIp: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val (color, state) = when {
        tv.isReadyFor(phoneIp) -> Palette.Success to "Ready"
        tv.status.developerMode == false -> Palette.Warning to "Developer Mode off"
        tv.status.developerIp != null && tv.status.developerIp != phoneIp -> Palette.Warning to "Host PC IP is ${tv.status.developerIp}"
        else -> Palette.Faint to "Tap to check"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh)
            .border(1.dp, if (selected) Palette.Accent else Palette.SurfaceHigh, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOf(tv.status.name, tv.status.model).filter { it.isNotBlank() }.joinToString(" · "),
                color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(tv.ip, color = Palette.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(10.dp))
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(state, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Phone and TV address checks, one row each, then what to do about the first problem. */
@Composable
internal fun IpReportView(report: IpReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        report.rows.forEach { row ->
            val (icon, tint) = when (row.state) {
                CheckState.OK -> Icons.Filled.Check to Palette.Success
                CheckState.FAIL -> Icons.Filled.Close to Palette.Error
                CheckState.UNKNOWN -> Icons.Filled.Info to Palette.Faint
            }
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = row.state.name.lowercase(), tint = tint, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(row.text, color = if (row.state == CheckState.FAIL) Palette.Text else Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        report.fix?.let { fix ->
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp))
                    .background(Palette.Warning.copy(alpha = 0.08f))
                    .border(1.dp, Palette.Warning.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.Warning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("How to fix it", color = Palette.Warning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                fix.reason?.let { Text(highlightIps(it), color = Palette.Text, fontSize = 14.sp, lineHeight = 20.sp) }
                fix.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(Palette.Warning.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${index + 1}", color = Palette.Warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(highlightIps(step), color = Palette.Text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 1.dp))
                    }
                }
                fix.tip?.let { tip ->
                    HorizontalDivider(color = Palette.Warning.copy(alpha = 0.2f))
                    Text(highlightIps(tip), color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    fix.tipLink?.let { link ->
                        val uri = LocalUriHandler.current
                        TextButton(onClick = { runCatching { uri.openUri(link) } }) { Text("Open the router's page", color = Palette.Link) }
                    }
                }
            }
        }
    }
}

private val Ipv4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

/** Makes IP addresses stand out, since they are what the user has to read or type. */
internal fun highlightIps(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in Ipv4.findAll(text)) {
        append(text.substring(last, match.range.first))
        withStyle(SpanStyle(color = Palette.Link, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)) { append(match.value) }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

/** "A new version of this app is out", with a direct APK download. */
@Composable
private fun AppUpdateBanner(update: AppUpdate, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Accent.copy(alpha = 0.1f))
            .border(1.dp, Palette.Accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TV Installer ${update.version} is available", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("You have ${BuildConfig.VERSION_NAME}. Download it, then open the file to update.", color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Palette.Muted, modifier = Modifier.size(18.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Download", null, enabled = true, onClick = { uri.openUri(update.downloadUrl) }, modifier = Modifier.weight(1f), tint = Palette.Link)
            SoftButton("What's new", null, enabled = true, onClick = { uri.openUri(update.pageUrl) }, modifier = Modifier.weight(1f), tint = Palette.Muted)
        }
    }
}

@Composable
private fun VersionTile(vm: InstallerViewModel, modifier: Modifier, enabled: Boolean, onPickFile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        InfoTile("Install", Modifier.fillMaxWidth(), onClick = if (enabled) ({ open = true }) else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val text = when (val s = vm.source) {
                    is PackageSource.FromRelease -> s.release.tag_name
                    is PackageSource.FromFile -> s.name
                    null -> if (vm.releasesError != null) "Unavailable" else "Loading…"
                }
                Text(text, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose version", tint = Palette.Muted)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val latest = vm.latestRelease
            vm.releases.forEach { release ->
                DropdownMenuItem(
                    text = { Text("Nuvio ${release.label}" + if (release == latest) "  (latest)" else "") },
                    onClick = { vm.source = PackageSource.FromRelease(release); open = false },
                )
            }
            if (vm.releasesError != null) {
                DropdownMenuItem(text = { Text("Retry loading versions") }, onClick = { vm.loadReleases(); open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Beta versions")
                        Text(
                            if (vm.betaChannel) "On: pre-releases are offered and installed" else "Off: only full releases",
                            color = Palette.Muted, fontSize = 12.sp,
                        )
                    }
                },
                trailingIcon = { androidx.compose.material3.Switch(checked = vm.betaChannel, onCheckedChange = null) },
                onClick = { vm.updateBetaChannel(!vm.betaChannel) },
            )
            DropdownMenuItem(text = { Text("Choose a .wgt file…") }, onClick = { open = false; onPickFile() })
        }
    }
}

/** "What's new" for the chosen Nuvio version: the first points, expandable to all. */
@Composable
private fun ReleaseNotesView(release: Release) {
    val lines = remember(release.id) { ReleaseNotes.parse(release.body) }
    if (lines.isEmpty()) return
    var expanded by remember(release.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val shown = if (expanded) lines else lines.take(4)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("What's new in ${release.tag_name}", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        for (line in shown) {
            when (line.kind) {
                NoteLine.Kind.HEADING -> Text(line.text, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                NoteLine.Kind.BULLET -> Row {
                    Text("•", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.width(14.dp))
                    Text(line.text, color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                }
                NoteLine.Kind.TEXT -> Text(line.text, color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Row {
            if (lines.size > shown.size || expanded) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all", color = Palette.Link) }
            }
            if (release.html_url.isNotBlank()) {
                TextButton(onClick = { uri.openUri(release.html_url) }) { Text("Open on GitHub", color = Palette.Link) }
            }
        }
    }
}

// ---------------------------------------------------------------- progress

@Composable
private fun ProgressCard(vm: InstallerViewModel) {
    Panel {
        SectionTitle(if (vm.busy == "Install") "Installing Nuvio…" else vm.busy.orEmpty() + "…")
        if (vm.busy == "Install") {
            val current = vm.stage
            InstallStage.entries.forEach { step ->
                val state = when {
                    current == null -> if (step == InstallStage.PACKAGE) 1 else 0
                    step.ordinal < current.ordinal -> 2
                    step == current -> 1
                    else -> 0
                }
                StepRow(step.title, state, if (state == 1) vm.progressLabel else "", if (state == 1) vm.progress else null)
            }
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
        }
        LiveActivity(vm)
        AnimatedVisibility(vm.waitingForLogin) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.Link, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in to Samsung", color = Palette.Text, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "A one-time Samsung login creates the certificate your TV needs. Finish it in the browser, then come back here.",
                    color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                TextButton(onClick = vm::reopenLogin) { Text("Open login again", color = Palette.Link) }
            }
        }
    }
}

/**
 * What the installer is doing right now, in plain words, newest at the bottom. "Show details"
 * adds the raw TV commands and replies.
 */
@Composable
private fun LiveActivity(vm: InstallerViewModel) {
    var details by rememberSaveable { mutableStateOf(false) }
    val start = vm.log.indexOfLast { it.text.startsWith("── ") }.coerceAtLeast(0)
    val lines = vm.log.drop(start + 1).filter { details || (it.level != LogLevel.COMMAND && it.level != LogLevel.OUTPUT) }
    if (lines.isEmpty()) return
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size, details) { scroll.animateScrollTo(scroll.maxValue) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { details = !details }) {
                Text(if (details) "Hide details" else "Show details", color = Palette.Link, fontSize = 13.sp)
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = if (details) 260.dp else 150.dp).clip(RoundedCornerShape(12.dp))
                .background(Palette.Background).verticalScroll(scroll).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            lines.forEach { line ->
                val (mark, color) = when (line.level) {
                    LogLevel.SUCCESS -> "✓" to Palette.Success
                    LogLevel.ERROR -> "✕" to Palette.Error
                    LogLevel.COMMAND -> "›" to Palette.Blue
                    LogLevel.OUTPUT -> " " to Palette.Faint
                    LogLevel.INFO -> "•" to Palette.Muted
                }
                Row {
                    Text(mark, color = color, fontSize = 12.sp, modifier = Modifier.width(16.dp))
                    Text(
                        line.text, color = if (line.level == LogLevel.OUTPUT) Palette.Faint else Palette.Text,
                        fontSize = if (details) 11.sp else 13.sp, lineHeight = if (details) 15.sp else 18.sp,
                        fontFamily = if (details) FontFamily.Monospace else FontFamily.Default,
                    )
                }
            }
        }
    }
}

/** state: 0 = pending, 1 = active, 2 = done */
@Composable
private fun StepRow(title: String, state: Int, detail: String, progress: Float?) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                2 -> Box(Modifier.size(22.dp).clip(CircleShape).background(Palette.Success.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Success, modifier = Modifier.size(15.dp))
                }
                1 -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Accent)
                else -> Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, Palette.Faint, CircleShape))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                color = when (state) { 0 -> Palette.Faint; else -> Palette.Text },
                fontSize = 15.sp,
                fontWeight = if (state == 1) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (state == 1 && progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).clip(CircleShape),
                        color = Palette.Accent,
                        trackColor = Palette.SurfaceHigh,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("${(progress * 100).toInt()}%", color = Palette.Muted, fontSize = 12.sp)
                }
            } else if (state == 1 && detail.isNotBlank()) {
                Text(detail, color = Palette.Muted, fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- outcome

@Composable
private fun OutcomeCard(outcome: Outcome, fixBelow: Boolean, onDismiss: () -> Unit, onShowLog: () -> Unit, onImport: () -> Unit, onUninstall: () -> Unit) {
    val (color, icon) = when (outcome.kind) {
        OutcomeKind.SUCCESS -> Palette.Success to Icons.Filled.Check
        OutcomeKind.ERROR -> Palette.Error to Icons.Filled.Close
        OutcomeKind.NEEDS_CERTIFICATE -> Palette.Warning to Icons.Filled.Warning
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(outcome.title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Palette.Muted, modifier = Modifier.size(18.dp))
            }
        }
        Text(outcome.message, color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp)
        if (fixBelow) {
            Text("Follow the steps under \"How to fix it\" below, then try again.", color = Palette.Text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        }
        when (outcome.kind) {
            OutcomeKind.NEEDS_CERTIFICATE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Import certificate", null, enabled = true, onClick = onImport, modifier = Modifier.weight(1f), tint = Palette.Warning)
                SoftButton("Uninstall from TV", null, enabled = true, onClick = onUninstall, modifier = Modifier.weight(1f), tint = Palette.Muted)
            }
            OutcomeKind.ERROR -> TextButton(onClick = onShowLog) { Text("Show details", color = color) }
            OutcomeKind.SUCCESS -> Unit
        }
    }
}

// ---------------------------------------------------------------- side cards

@Composable
private fun Collapsible(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Panel {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            badge?.invoke()
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Palette.Muted,
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun CertificateCard(vm: InstallerViewModel, enabled: Boolean, onImport: () -> Unit, onBackup: () -> Unit, onSignIn: () -> Unit) {
    val connected = vm.tvState == TvState.ONLINE
    Panel {
        SectionTitle("Signing certificate")
        val (color, text) = when {
            vm.hasIdentityForTv -> Palette.Success to "Saved for this TV"
            vm.identityCount > 0 && !connected -> Palette.Muted to "${vm.identityCount} saved · connect to your TV to check it"
            else -> Palette.Faint to "Not created yet"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.width(10.dp))
            Text(text, color = Palette.Text, fontSize = 14.sp)
        }
        CertificateExpiryNote(vm.certExpiresAt, forThisTv = vm.hasIdentityForTv)
        if (vm.hasIdentityForTv) {
            Text(
                "Installs and updates use this certificate, so no sign-in is needed. Keep the backup: without it, the TV refuses updates.",
                color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
        } else {
            Text(
                "Samsung TVs only run apps signed for that TV. Sign in with your Samsung account once and Samsung creates the certificate. " +
                    "(If you skip this, the sign-in happens during the first install.)",
                color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
            GradientButton("Sign in with Samsung", enabled = enabled && connected, onClick = onSignIn)
            if (!connected) {
                Text(
                    "Connect to your TV above first: the certificate is made for that TV.",
                    color = Palette.Faint, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            Text(
                "Used the desktop Nuvio installer before? Import its certificate (.json) instead, so updates keep working.",
                color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
        vm.lastBackup?.let { Text("Last backup: $it", color = Palette.Faint, fontSize = 12.sp, lineHeight = 17.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Import", null, enabled = enabled, onClick = onImport, modifier = Modifier.weight(1f))
            SoftButton("Back up", null, enabled = enabled && vm.identityCount > 0, onClick = onBackup, modifier = Modifier.weight(1f))
        }
        BackupProtection(vm, enabled)
    }
}

/** Password protection for backups: the backup file holds the certificate's private keys. */
@Composable
private fun BackupProtection(vm: InstallerViewModel, enabled: Boolean) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        PasswordDialog(
            title = "Protect backups",
            message = "Backups hold your TV certificate's private keys. With a password, the file is useless to anyone without it. " +
                "Write the password down: if you forget it, the backup cannot be opened.",
            confirmLabel = "Protect",
            needsConfirmation = true,
            onDone = { password -> editing = false; if (password != null) vm.setBackupPassword(password) },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = if (vm.backupProtected) Palette.Success else Palette.Faint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (vm.backupProtected) "Backups are password-protected" else "Backups are not password-protected",
            color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        if (vm.backupProtected) {
            TextButton(onClick = { vm.setBackupPassword(null) }, enabled = enabled) { Text("Remove", color = Palette.Muted) }
        } else {
            TextButton(onClick = { editing = true }, enabled = enabled) { Text("Add password", color = Palette.Link) }
        }
    }
}

/** Asks for a password (twice when [needsConfirmation]). [onDone] gets null on cancel. */
@Composable
internal fun PasswordDialog(title: String, message: String, confirmLabel: String, needsConfirmation: Boolean, onDone: (String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val tooShort = needsConfirmation && password.length < 6
    val mismatch = needsConfirmation && again != password
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, fontSize = 13.sp, lineHeight = 18.sp)
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (needsConfirmation) {
                    OutlinedTextField(
                        value = again, onValueChange = { again = it }, label = { Text("Password again") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = again.isNotEmpty() && mismatch,
                    )
                    Text(
                        when {
                            tooShort -> "At least 6 characters."
                            again.isNotEmpty() && mismatch -> "The passwords do not match."
                            else -> " "
                        },
                        color = Palette.Muted, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(password) }, enabled = password.isNotEmpty() && !tooShort && !mismatch) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

/** "Valid until …", or a warning when the certificate expires within 30 days or has expired. */
@Composable
private fun CertificateExpiryNote(expiresAt: java.time.Instant?, forThisTv: Boolean) {
    expiresAt ?: return
    val date = formatDate(expiresAt)
    when (CertificateExpiry.state(expiresAt)) {
        ExpiryState.VALID -> if (forThisTv) Text("Valid until $date", color = Palette.Faint, fontSize = 12.sp)
        ExpiryState.EXPIRES_SOON -> WarningBox(
            "Certificate expires on $date",
            "After that date the TV may refuse Nuvio updates signed with it. Updating then means uninstalling Nuvio from the TV " +
                "and installing it again, which creates a new certificate. Nuvio's settings on the TV are lost when it is uninstalled.",
        )
        ExpiryState.EXPIRED -> WarningBox(
            "Certificate expired on $date",
            "The TV refuses Nuvio updates signed with an expired certificate. To update, uninstall Nuvio from the TV, then install it " +
                "again: a new certificate is created during the install. Nuvio's settings on the TV are lost when it is uninstalled.",
        )
    }
}

@Composable
internal fun WarningBox(title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Palette.Warning.copy(alpha = 0.08f))
            .border(1.dp, Palette.Warning.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.Warning, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Palette.Warning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(text, color = Palette.Text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

internal fun formatDate(instant: java.time.Instant): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault()).format(instant)

/** Popup: the app, who made it, its licence and update status, and links to the code and to report a problem. */
@Composable
private fun AboutDialog(vm: InstallerViewModel, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val repo = "https://github.com/${UpdateCheck.APP_REPO}"
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.Outline, RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Palette.Muted, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(76.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("TV Installer for Nuvio", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Install and update Nuvio on your TV from your phone.", color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Chip("Version ${BuildConfig.VERSION_NAME}", Palette.Text)
                }
            }
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigh),
            ) {
                AboutRow("Made by", "Pratyush", Palette.Link) { uri.openUri("https://github.com/pratyush997") }
                HorizontalDivider(color = Palette.Outline)
                AboutRow("Licence", "Open source · GPL-3.0", Palette.Link) { uri.openUri("$repo/blob/main/LICENSE") }
                HorizontalDivider(color = Palette.Outline)
                when (val status = vm.appUpdateStatus) {
                    AppUpdateStatus.Checking -> AboutRow("Updates", "Checking…", Palette.Muted)
                    AppUpdateStatus.UpToDate -> AboutRow("Updates", "Up to date", Palette.Success)
                    AppUpdateStatus.Failed -> AboutRow("Updates", "Couldn't check · Retry", Palette.Warning, onClick = vm::checkAppUpdate)
                    is AppUpdateStatus.Available -> AboutRow("Updates", "${status.update.version} available · Download", Palette.Link) {
                        uri.openUri(status.update.downloadUrl)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            GradientButton("View on GitHub", enabled = true, onClick = { uri.openUri(repo) })
            Spacer(Modifier.height(10.dp))
            SoftButton("Report a problem", null, enabled = true, onClick = { uri.openUri("$repo/issues/new/choose") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text(
                "Nuvio is made by NuvioMedia.",
                color = Palette.Faint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String, valueColor: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun LogCard(vm: InstallerViewModel, open: Boolean, onToggle: () -> Unit, copy: (String) -> Unit) {
    Collapsible("Activity log", open, onToggle, badge = {
        if (vm.log.isNotEmpty()) Text("${vm.log.size}", color = Palette.Faint, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { copy(vm.logText()) }, enabled = vm.log.isNotEmpty()) { Text("Copy", color = Palette.Link) }
            TextButton(onClick = vm::clearLog, enabled = vm.log.isNotEmpty()) { Text("Clear", color = Palette.Muted) }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(vm.log.size) { scroll.animateScrollTo(scroll.maxValue) }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Background)
                .verticalScroll(scroll)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (vm.log.isEmpty()) Text("Nothing yet.", color = Palette.Faint, fontSize = 12.sp)
            vm.log.forEach { line ->
                Text(
                    line.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = when (line.level) {
                        LogLevel.ERROR -> Palette.Error
                        LogLevel.SUCCESS -> Palette.Success
                        LogLevel.COMMAND -> Palette.Blue
                        LogLevel.OUTPUT -> Palette.Faint
                        LogLevel.INFO -> Palette.Muted
                    },
                )
            }
        }
    }
}
