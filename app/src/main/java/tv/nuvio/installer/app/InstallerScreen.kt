// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import java.time.Instant
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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
import tv.nuvio.installer.core.SavedTv
import tv.nuvio.installer.core.SavedTvs
import tv.nuvio.installer.core.TvBrand
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
import tv.nuvio.installer.core.NuvioApp
import tv.nuvio.installer.core.Fix
import tv.nuvio.installer.core.MainAction
import tv.nuvio.installer.core.ReadyCheck
import tv.nuvio.installer.core.ReadyResult
import tv.nuvio.installer.core.ReadyState

// ---------------------------------------------------------------- palette

/** One set of colours. Every text colour reaches 4.5:1 contrast on [background]. */
@Immutable
class PaletteColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val link: Color,
    val blue: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
)

val DarkColors = PaletteColors(
    background = Color(0xFF0A0A0F),
    surface = Color(0xFF14141B),
    surfaceHigh = Color(0xFF1C1C25),
    outline = Color(0xFF2A2A35),
    text = Color(0xFFF4F4F6),
    muted = Color(0xFF9A9AA8),
    faint = Color(0xFF8B8B9A),
    link = Color(0xFF7DD3FC),
    blue = Color(0xFF3B82F6),
    accent = Color(0xFF0EA5E9),
    onAccent = Color(0xFF082F49),
    success = Color(0xFF34D399),
    error = Color(0xFFF87171),
    warning = Color(0xFFFBBF24),
)

val LightColors = PaletteColors(
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFECEEF2),
    outline = Color(0xFFD5D8E0),
    text = Color(0xFF111318),
    muted = Color(0xFF4B5060),
    faint = Color(0xFF6B7080),
    link = Color(0xFF0369A1),
    blue = Color(0xFF2563EB),
    accent = Color(0xFF0369A1),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF047857),
    error = Color(0xFFB91C1C),
    warning = Color(0xFFB45309),
)

/** Auto follows the phone's dark/light setting; Light and Dark override it. */
enum class ThemeMode(val label: String) { AUTO("Auto"), LIGHT("Light"), DARK("Dark") }

/**
 * The app's colours. [MainActivity] sets [systemDark] on start (Android restarts the activity
 * when the phone's setting changes) and [mode] from the saved choice; the header button changes [mode].
 */
object Palette {
    var systemDark by mutableStateOf(true)
    var mode by mutableStateOf(ThemeMode.AUTO)

    val isDark: Boolean
        get() = when (mode) {
            ThemeMode.AUTO -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    private val colors get() = if (isDark) DarkColors else LightColors

    val Background get() = colors.background
    val Surface get() = colors.surface
    val SurfaceHigh get() = colors.surfaceHigh
    val Outline get() = colors.outline
    val Text get() = colors.text
    val Muted get() = colors.muted
    val Faint get() = colors.faint
    /** Text buttons and links. */
    val Link get() = colors.link
    val Blue get() = colors.blue
    /** Progress, focus and selection. */
    val Accent get() = colors.accent
    /** Text and icons on [Accent]. */
    val OnAccent get() = colors.onAccent
    val Success get() = colors.success
    val Error get() = colors.error
    val Warning get() = colors.warning
    /** Same colours as the app icon; white text on it reads in both modes. */
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

    val importCert = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importIdentities(uris) }
    val exportCert = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(vm::exportIdentities)
    }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var bugReportOpen by rememberSaveable { mutableStateOf(false) }
    if (aboutOpen) AboutDialog(vm, onDismiss = { aboutOpen = false }, onReport = { aboutOpen = false; bugReportOpen = true })
    if (bugReportOpen) BugReportDialog(vm, copy, onDismiss = { bugReportOpen = false })
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

    // Checks, progress and results go to the top of the screen, and the screen scrolls there, so they are never out of sight.
    val mainScroll = rememberScrollState()
    val working = vm.busy != null && vm.busy != "Checking TV"
    LaunchedEffect(working, vm.outcome) { if (working || vm.outcome != null) mainScroll.animateScrollTo(0) }
    var certOpen by rememberSaveable { mutableStateOf(false) }
    var pickingFor by rememberSaveable { mutableStateOf<NuvioApp?>(null) }
    val pickPackage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val app = pickingFor
        if (uri != null && app != null) vm.selectFile(app, uri)
        pickingFor = null
    }

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
                Header(onTheme = vm::nextThemeMode, onGuide = vm::openGuide, onAbout = { aboutOpen = true })
                vm.appUpdate?.let { update -> AppUpdateBanner(update, onDismiss = vm::dismissAppUpdate) }
                vm.checksApp?.let { app -> ChecklistCard(vm, app) }
                if (working && !vm.checking) ProgressCard(vm)
                vm.outcome?.let { outcome ->
                    OutcomeCard(
                        outcome = outcome,
                        fixBelow = outcome.kind == OutcomeKind.ERROR && vm.ipReport?.fix != null,
                        onDismiss = vm::dismissOutcome,
                        onShowLog = { logOpen = true },
                        onReport = { bugReportOpen = true },
                        onImport = { importCert.launch(arrayOf("*/*")) },
                        onUninstall = { vm.uninstall(outcome.app ?: NuvioApp.TV) },
                    )
                }
                TvCard(vm)
                NuvioApp.entries.forEach { app ->
                    AppCard(vm, app, onPickFile = { pickingFor = app; pickPackage.launch(arrayOf("*/*")) })
                }
                MoreRow(
                    vm,
                    certOpen = certOpen,
                    logOpen = logOpen,
                    onCertificate = { certOpen = !certOpen },
                    onActivity = { logOpen = !logOpen },
                )
                if (certOpen) {
                    CertificateCard(
                        vm,
                        enabled = !busy,
                        onImport = { importCert.launch(arrayOf("*/*")) },
                        onBackup = { exportCert.launch(vm.backupFileName) },
                        onSignIn = { vm.createCertificate() },
                    )
                }
                if (logOpen) LogCard(vm, open = true, onToggle = { logOpen = false }, copy = copy)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (vm.checking || vm.busy == "Install") BottomBar(vm)
    }
    if (vm.typeOnTvOpen) TypeOnTvDialog(vm)
}

internal val MaxContentWidth = 560.dp

/** Pinned to the bottom only while checks or an install run: where they are, in one line. */
@Composable
private fun BottomBar(vm: InstallerViewModel) {
    HorizontalDivider(color = Palette.Outline)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val total: Int
            val done: Float
            val text: String
            if (vm.checking) {
                val app = vm.checksApp ?: NuvioApp.TV
                val current = vm.checkRunning ?: ReadyCheck.entries[vm.checks.size.coerceAtMost(ReadyCheck.entries.size - 1)]
                total = ReadyCheck.entries.size
                done = vm.checks.size + 0.5f
                text = "Check ${current.ordinal + 1} of $total · ${current.title(app)}"
            } else {
                val stage = vm.stage
                val step = (stage?.ordinal ?: 0) + 1
                total = InstallStage.entries.size
                done = (step - 1) + (vm.progress ?: 0.5f)
                text = if (vm.waitingForLogin) "Waiting for Samsung sign-in…" else "Step $step of $total · ${(stage ?: InstallStage.PACKAGE).title}"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (!vm.checking) vm.progress?.let { Text("${(it * 100).toInt()}%", color = Palette.Muted, fontSize = 13.sp) }
            }
            LinearProgressIndicator(
                progress = { (done / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                color = Palette.Accent,
                trackColor = Palette.SurfaceHigh,
            )
        }
    }
}

@Composable
private fun Header(onTheme: () -> Unit, onGuide: () -> Unit, onAbout: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("TV Installer", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("for Nuvio", color = Palette.Muted, fontSize = 12.sp)
        }
        val themeIcon = when (Palette.mode) {
            ThemeMode.AUTO -> R.drawable.ic_theme_auto
            ThemeMode.LIGHT -> R.drawable.ic_theme_light
            ThemeMode.DARK -> R.drawable.ic_theme_dark
        }
        HeaderAction(painterResource(themeIcon), Palette.mode.label, onTheme)
        HeaderAction(rememberVectorPainter(Icons.Filled.List), "Guide", onGuide)
        HeaderAction(rememberVectorPainter(Icons.Filled.Info), "About", onAbout)
    }
}

/** Small icon with a label underneath, for the header. */
@Composable
private fun HeaderAction(icon: Painter, label: String, onClick: () -> Unit) {
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

// ---------------------------------------------------------------- TV card (main)

/**
 * The selected TV in one line: name, status, Switch (the saved TVs) and +. The address field and
 * Find my TV show only when there is no TV, or after "Change address". When the TV can't be
 * reached: the fix steps, Check again and the setup guide.
 */
@Composable
private fun TvCard(vm: InstallerViewModel) {
    val busy = vm.busy != null
    val noTv = vm.tvIp.isBlank()
    var switching by rememberSaveable { mutableStateOf(false) }
    var editingAddress by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.tvState) { if (vm.tvState == TvState.ONLINE) editingAddress = false }
    val action = vm.mainAction
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_tv), contentDescription = null, tint = Palette.Link, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tvTitle(vm), color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (dotColor, statusText) = tvStatus(vm)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(dotColor, pulsing = vm.tvState == TvState.CHECKING)
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, color = Palette.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (vm.savedTvs.isNotEmpty()) {
                TextButton(onClick = { switching = !switching }, enabled = !busy) {
                    Text("Switch", color = Palette.Link)
                    Icon(
                        if (switching) Icons.Filled.KeyboardArrowUp else Icons.Filled.ArrowDropDown,
                        contentDescription = null, tint = Palette.Link, modifier = Modifier.size(20.dp),
                    )
                }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Palette.Accent.copy(alpha = 0.12f))
                    .clickable(enabled = !busy, onClickLabel = "Add a TV", role = Role.Button, onClick = vm::addTv)
                    .alpha(if (busy) 0.4f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add a TV", tint = Palette.Link)
            }
        }
        if (switching) TvList(vm, enabled = !busy, onPicked = { switching = false })

        // Without Wi-Fi nothing below can work: the notice and its button take their place.
        if (action == MainAction.ConnectWifi) {
            NoWifiNotice()
            return@Panel
        }

        // The field stays while an address is typed or checked, until it belongs to a saved TV.
        val newAddress = vm.currentTv == null && vm.tvState != TvState.ONLINE
        if (noTv || newAddress || editingAddress) {
            TvIpField(vm, enabled = !busy)
            TvFinder(vm, showButton = action != MainAction.FindTv)
        } else if (vm.tvState != TvState.CHECKING) {
            TextButton(onClick = { editingAddress = true }, enabled = !busy, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("Change IP · ${vm.tvIp}", color = Palette.Muted, fontSize = 13.sp)
            }
        }
        vm.tvMovedFrom?.let {
            Text(
                "Your TV got a new IP from the router. The app found it again, nothing to do.",
                color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }

        // When the install checks stopped at a TV row, the checklist above shows the fix instead.
        val fixInChecklist = vm.failedCheck?.check in TvChecks
        if (vm.tvState == TvState.OFFLINE && !fixInChecklist) {
            val report = vm.ipReport
            if (report != null) IpReportView(report)
            else vm.tvError?.let { Text(it, color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp) }
        } else if (noTv && vm.foundTvs == null) {
            Text("Tap Find my TV, or type the TV's IP address.", color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        when {
            vm.checking -> Unit
            action == MainAction.FindTv -> GradientButton(
                if (vm.scanning) "Looking for TVs…" else if (vm.foundTvs == null) "Find my TV" else "Look again",
                enabled = !busy && !vm.scanning, onClick = { vm.scanForTvs() },
            )
            action is MainAction.CheckAgain && action.app == null -> GradientButton(
                when {
                    vm.tvState == TvState.CHECKING -> "Checking…"
                    vm.tvState == TvState.OFFLINE -> "Check again"
                    else -> "Check TV"
                },
                enabled = !busy, onClick = vm::checkTv,
            )
        }
        if (vm.tvState == TvState.OFFLINE && !fixInChecklist) {
            TextButton(onClick = vm::openGuide) { Text("Setup guide", color = Palette.Link) }
        }
    }
}

/** The checks about the TV itself (rows 2–5), whose fix is the TV card's address report. */
private val TvChecks = setOf(ReadyCheck.TV, ReadyCheck.DEVELOPER_MODE, ReadyCheck.HOST_IP, ReadyCheck.CONNECTION)

private fun tvTitle(vm: InstallerViewModel): String =
    vm.currentTv?.label?.takeIf { it.isNotBlank() }
        ?: vm.tvName.takeIf { it.isNotBlank() }
        ?: "No TV yet"

private fun tvStatus(vm: InstallerViewModel): Pair<Color, String> =
    if (!vm.onWifi) Palette.Error to "Phone not on Wi-Fi" else tvStateStatus(vm)

private fun tvStateStatus(vm: InstallerViewModel): Pair<Color, String> = when (vm.tvState) {
    TvState.UNKNOWN -> Palette.Faint to if (vm.tvIp.isBlank()) "Not picked yet" else "Not checked yet"
    TvState.CHECKING -> Palette.Warning to "Connecting…"
    TvState.ONLINE -> Palette.Success to buildString {
        append("Connected")
        vm.tvInfo?.platformVersion?.takeIf { it.isNotBlank() }?.let { append(" · Tizen $it") }
    }
    TvState.OFFLINE -> Palette.Error to "Not reachable"
}

// ---------------------------------------------------------------- app cards

/**
 * One app: what is on the TV, one button, links (Open on TV, What's new, Version) and a ⋯ menu
 * (Reinstall, Uninstall, Choose a file). The button is the highlighted one when
 * [InstallerViewModel.mainAction] points at this app; otherwise it is a soft button.
 */
@Composable
private fun AppCard(vm: InstallerViewModel, app: NuvioApp, onPickFile: () -> Unit) {
    val busy = vm.busy != null
    val online = vm.tvState == TvState.ONLINE
    val tizen = vm.tvInfo?.platformVersion
    val supported = !online || app.supports(tizen)
    val installed = online && vm.isInstalled(app)
    val opens = vm.opensOnTv(app)
    val source = vm.source(app)
    val release = (source as? PackageSource.FromRelease)?.release
    var notice by rememberSaveable { mutableStateOf(false) }
    var notesOpen by rememberSaveable(app.name) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val highlighted = when (val action = vm.mainAction) {
        is MainAction.Update -> action.app == app
        is MainAction.Open -> action.app == app
        is MainAction.Install -> action.app == app
        else -> false
    }
    // The first Legacy install shows who makes it.
    val install = { if (app == NuvioApp.LEGACY && !vm.legacyNoticeSeen) notice = true else vm.install(app) }

    Panel(Modifier.alpha(if (online) 1f else 0.6f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(app.label, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (app == NuvioApp.TV) Text("Recommended", color = Palette.Success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            else Text("Community app", color = Palette.Muted, fontSize = 12.sp)
        }
        Text(
            when {
                !online -> "Connect your TV first"
                !supported -> "Needs a 2018 or newer TV"
                app == NuvioApp.TV && installed -> buildString {
                    append("${vm.installedLabel(app)} on TV")
                    if (vm.updateAvailable) vm.latestRelease(app)?.let { append(" · ${it.tag_name} is out") }
                }
                installed -> "On this TV · updates itself"
                else -> "Not on this TV"
            },
            color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
        )
        if (app == NuvioApp.LEGACY && online && supported && !installed && NuvioApp.isOlderTv(tizen)) {
            Text("Your TV is from 2018 to 2020. Nuvio Legacy may run smoother on it.", color = Palette.Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (online && supported) {
            val label = if (opens) "Open on TV" else vm.buttonLabel(app)
            val onClick: () -> Unit = if (opens) ({ vm.launchOnTv(app) }) else install
            val enabled = !busy && (opens || source != null)
            if (highlighted) GradientButton(label, enabled = enabled, onClick = onClick)
            else SoftButton(label, null, enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth(), tint = Palette.Link)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (installed && !opens) LinkButton("Open on TV", enabled = !busy) { vm.launchOnTv(app) }
                if (release != null && !release.body.isNullOrBlank()) {
                    LinkButton(if (notesOpen) "Hide what's new" else "What's new") { notesOpen = !notesOpen }
                }
                if (supported) VersionMenu(vm, app, enabled = !busy)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More for ${app.label}", tint = Palette.Muted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (installed && opens) DropdownMenuItem(text = { Text("Reinstall") }, onClick = { menuOpen = false; install() })
                    if (installed) DropdownMenuItem(text = { Text("Uninstall") }, onClick = { menuOpen = false; vm.uninstall(app) })
                    val fileKind = if (app == NuvioApp.TV) ".wgt" else ".tpk or .wgt"
                    DropdownMenuItem(text = { Text("Choose a $fileKind file…") }, onClick = { menuOpen = false; onPickFile() })
                }
            }
        }
        if (notesOpen && release != null) ReleaseNotesView(release)
        if (app == NuvioApp.TV && installed && vm.installedVersion != null) AutoUpdateRow(vm)
    }
    if (notice) {
        LegacyNoticeDialog(
            onContinue = { notice = false; vm.acknowledgeLegacyNotice(); vm.install(NuvioApp.LEGACY) },
            onDismiss = { notice = false },
        )
    }
}

/** A small text button for the links under an app's main button. */
@Composable
private fun LinkButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(text, color = if (enabled) Palette.Link else Palette.Faint, fontSize = 13.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- install checks

/** The install checks for [app], one row each; on a failure, how to fix it and Check again. */
@Composable
private fun ChecklistCard(vm: InstallerViewModel, app: NuvioApp) {
    val failed = vm.failedCheck.takeIf { !vm.checking }
    Panel {
        SectionTitle(if (failed != null) "Can't install ${app.label} yet" else "Getting ready…") {
            if (!vm.checking) {
                IconButton(onClick = vm::dismissChecks, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Palette.Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        ReadyCheck.entries.forEach { check ->
            val result = vm.checks.firstOrNull { it.check == check }
            ReadyRow(check.title(app), result, running = result == null && vm.checkRunning == check)
        }
        if (failed != null) {
            val tvRow = failed.check in TvChecks
            val fix = vm.ipReport?.fix
            if (tvRow && fix != null) FixBox(fix)
            // Joining Wi-Fi checks the TV again by itself, so the settings are the one thing to open.
            if (failed.check == ReadyCheck.WIFI && !vm.onWifi) NoWifiNotice()
            else GradientButton("Check again", enabled = vm.busy == null, onClick = { vm.install(app) })
            if (tvRow || failed.check == ReadyCheck.WIFI) {
                TextButton(onClick = vm::openGuide) { Text("Setup guide", color = Palette.Link) }
            }
        }
    }
}

@Composable
private fun ReadyRow(title: String, result: ReadyResult?, running: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Accent)
                result == null -> Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, Palette.Faint, CircleShape))
                else -> {
                    val (icon, tint) = when (result.state) {
                        ReadyState.PASS -> Icons.Filled.Check to Palette.Success
                        ReadyState.FAIL -> Icons.Filled.Close to Palette.Error
                        ReadyState.UNSURE -> Icons.Filled.Info to Palette.Faint
                        ReadyState.LATER -> Icons.Filled.Info to Palette.Link
                    }
                    Box(Modifier.size(22.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = result.state.name.lowercase(), tint = tint, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = if (result == null && !running) Palette.Faint else Palette.Text,
                fontSize = 15.sp,
                fontWeight = if (running || result?.state == ReadyState.FAIL) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (result != null && result.detail.isNotBlank()) {
                Text(
                    highlightIps(result.detail),
                    color = if (result.state == ReadyState.FAIL) Palette.Text else Palette.Muted,
                    fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- more

/** Less-used things, one tap away: Type on TV, Certificate & backup, Activity. */
@Composable
private fun MoreRow(vm: InstallerViewModel, certOpen: Boolean, logOpen: Boolean, onCertificate: () -> Unit, onActivity: () -> Unit) {
    val certWarning = vm.certExpiresAt?.let { CertificateExpiry.state(it) != ExpiryState.VALID } == true
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.tvState == TvState.ONLINE) MoreChip("Type on TV", open = null, onClick = vm::openTypeOnTv)
        MoreChip("Certificate & backup", open = certOpen, onClick = onCertificate, warning = certWarning)
        MoreChip("Activity", open = logOpen, onClick = onActivity)
    }
}

/** [open] null: opens a dialog, no arrow. */
@Composable
private fun MoreChip(text: String, open: Boolean?, onClick: () -> Unit, warning: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (open == true) Palette.Accent.copy(alpha = 0.12f) else Palette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (open == null) 14.dp else 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (warning) {
            Icon(Icons.Filled.Warning, contentDescription = "Needs attention", tint = Palette.Warning, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (open != null) {
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Shown once, the first time Nuvio Legacy is installed: who makes it, that nobody here checks it, and that it sits next to Nuvio TV. */
@Composable
private fun LegacyNoticeDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Nuvio Legacy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Nuvio Legacy is made by a community developer, not by the Nuvio team or by TV Installer.",
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
                Text(
                    "TV Installer downloads it straight from the developer's GitHub page. It does not check or test those versions. " +
                        "After the install, Nuvio Legacy updates itself on the TV.",
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
                Text(
                    "It uses your same Nuvio account and add-ons, and installs next to Nuvio TV without replacing it.",
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
                TextButton(onClick = { uri.openUri(NuvioApp.LEGACY.repoUrl) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("See its GitHub page", color = Palette.Muted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Install Nuvio Legacy", fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The saved TVs, one row each: tap to switch, the bin asks before taking the TV off the list. */
@Composable
private fun TvList(vm: InstallerViewModel, enabled: Boolean, onPicked: () -> Unit = {}) {
    val selected = vm.currentTv
    var removingKey by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.savedTvs.forEach { tv ->
            TvRow(
                tv = tv,
                selected = selected != null && SavedTvs.sameTv(tv, selected),
                enabled = enabled,
                onSelect = { vm.selectSavedTv(tv); onPicked() },
                onRemove = { removingKey = tv.key },
            )
        }
    }
    removingKey?.let { key ->
        val tv = SavedTvs.withKey(vm.savedTvs, key)
        if (tv == null) {
            LaunchedEffect(key) { removingKey = null }
            return@let
        }
        RemoveTvDialog(
            tv = tv,
            onConfirm = { removingKey = null; vm.removeTv(tv) },
            onDismiss = { removingKey = null },
        )
    }
}

@Composable
private fun TvRow(tv: SavedTv, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Palette.Accent.copy(alpha = 0.12f) else Palette.SurfaceHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline, shape)
            .clickable(enabled = enabled && !selected, onClick = onSelect)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(if (selected) Palette.Accent.copy(alpha = 0.18f) else Palette.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_tv), contentDescription = null,
                tint = if (selected) Palette.Link else Palette.Muted, modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tv.label, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandTag(tv.brand)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(tv.installedLabel?.let { "Nuvio $it" }, "Nuvio Legacy".takeIf { tv.hasLegacy })
                        .joinToString(" · ").ifEmpty { "No Nuvio yet" },
                    color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove ${tv.label}", tint = Palette.Faint, modifier = Modifier.size(20.dp))
        }
    }
}

/** "Samsung" or "LG", so a mixed household can tell the TVs apart. */
@Composable
internal fun BrandTag(brand: TvBrand) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.Outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(brand.label, color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RemoveTvDialog(tv: SavedTv, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = Palette.Error) },
        title = { Text("Remove ${tv.label}?") },
        text = {
            Text(
                "The app forgets this TV and stops updating Nuvio on it. Nuvio stays on the TV. " +
                    "Its certificate stays on this phone, so adding the TV again needs no new sign-in.",
                fontSize = 14.sp, lineHeight = 20.sp,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove", color = Palette.Error, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
internal fun TvFinder(vm: InstallerViewModel, showButton: Boolean = true) {
    val found = vm.foundTvs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.scanning) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Muted)
                Spacer(Modifier.width(10.dp))
                Text("Looking for TVs on this Wi-Fi…", color = Palette.Muted, fontSize = 13.sp)
            } else if (showButton) {
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
        tv.status.developerIp != null && tv.status.developerIp != phoneIp -> Palette.Warning to "Host IP is ${tv.status.developerIp}"
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
        report.fix?.let { FixBox(it, Modifier.padding(top = 6.dp)) }
    }
}

/** "How to fix it": the reason, numbered steps and a tip. Shared by the TV card and the install checks. */
@Composable
internal fun FixBox(fix: Fix, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
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
    var notesOpen by remember { mutableStateOf(false) }
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
            SoftButton("What's new", null, enabled = true, onClick = { notesOpen = true }, modifier = Modifier.weight(1f), tint = Palette.Muted)
        }
    }
    if (notesOpen) AppWhatsNewDialog(update, onDismiss = { notesOpen = false })
}

/** Popup: what changed in the new version of this app, with the download as the one main action. */
@Composable
private fun AppWhatsNewDialog(update: AppUpdate, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val lines = remember(update.tag) { ReleaseNotes.changes(update.notes) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.Outline, RoundedCornerShape(24.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("What's new in ${update.version}", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("You have ${BuildConfig.VERSION_NAME}.", color = Palette.Muted, fontSize = 13.sp)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Palette.Muted, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigh)
                    .verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (lines.isEmpty()) Text("No notes for this version.", color = Palette.Muted, fontSize = 13.sp)
                else NoteLinesView(lines)
            }
            Spacer(Modifier.height(16.dp))
            GradientButton("Download ${update.version}", enabled = true, onClick = { uri.openUri(update.downloadUrl) })
            TextButton(onClick = { uri.openUri(update.pageUrl) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Open on GitHub", color = Palette.Muted, fontSize = 13.sp)
            }
        }
    }
}

/** Release-note lines: headings, bullets and paragraphs. */
@Composable
private fun NoteLinesView(lines: List<NoteLine>) {
    for (line in lines) {
        when (line.kind) {
            NoteLine.Kind.HEADING -> Text(line.text, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            NoteLine.Kind.BULLET -> Row {
                Text("•", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.width(14.dp))
                Text(line.text, color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            NoteLine.Kind.TEXT -> Text(line.text, color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** Newest versions listed in the version menu before "Older versions". */
private const val VERSIONS_SHOWN = 5

/** "Version 1.4.0 ▾" under an app's button: picks which release (or picked file) the button installs. */
@Composable
private fun VersionMenu(vm: InstallerViewModel, app: NuvioApp, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, contentPadding = PaddingValues(start = 8.dp, end = 2.dp)) {
            val text = when (val s = vm.source(app)) {
                is PackageSource.FromRelease -> "Version ${s.release.tag_name}"
                is PackageSource.FromFile -> s.name
                null -> if (vm.releasesError(app) != null) "Versions unavailable" else "Loading versions…"
            }
            Text(
                text, color = if (enabled) Palette.Link else Palette.Faint, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose version", tint = if (enabled) Palette.Link else Palette.Faint)
        }
        // Fixed width and at most VERSIONS_SHOWN rows, so the menu fits on screen without scrolling.
        var showAll by remember { mutableStateOf(false) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; showAll = false }, modifier = Modifier.width(280.dp)) {
            val latest = vm.latestRelease(app)
            val selected = (vm.source(app) as? PackageSource.FromRelease)?.release
            val all = vm.releases(app)
            val shown = if (showAll) all else all.take(VERSIONS_SHOWN)
            shown.forEach { release ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(releaseTitle(app, release), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            val tag = listOfNotNull("latest".takeIf { release == latest }, "beta".takeIf { release.prerelease }).joinToString(" · ")
                            if (tag.isNotEmpty()) Text("  $tag", color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
                        }
                    },
                    trailingIcon = {
                        if (release == selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Palette.Text, modifier = Modifier.size(20.dp))
                    },
                    onClick = { vm.chooseSource(app, PackageSource.FromRelease(release)); open = false; showAll = false },
                )
            }
            if (!showAll && all.size > VERSIONS_SHOWN) {
                DropdownMenuItem(
                    text = { Text("Older versions", color = Palette.Muted) },
                    trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Palette.Muted) },
                    onClick = { showAll = true },
                )
            }
            if (vm.releasesError(app) != null) {
                DropdownMenuItem(text = { Text("Retry loading versions") }, onClick = { vm.loadReleases(app); open = false })
            }
        }
    }
}

/** "Nuvio 1.3.11" for Nuvio TV; Legacy's releases are already named "Nuvio Legacy 2.0.8". */
private fun releaseTitle(app: NuvioApp, release: Release): String {
    val name = release.name?.takeIf { it.isNotBlank() } ?: release.tag_name
    return when {
        app == NuvioApp.TV -> "Nuvio $name"
        name.startsWith(app.label, ignoreCase = true) -> name
        else -> "${app.label} ${release.tag_name}"
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
        NoteLinesView(shown)
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
                    "A one-time Samsung sign-in creates the certificate your TV needs. Finish it in the browser, then come back here.",
                    color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                TextButton(onClick = vm::reopenLogin) { Text("Open sign-in again", color = Palette.Link) }
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
private fun OutcomeCard(
    outcome: Outcome,
    fixBelow: Boolean,
    onDismiss: () -> Unit,
    onShowLog: () -> Unit,
    onReport: () -> Unit,
    onImport: () -> Unit,
    onUninstall: () -> Unit,
) {
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
            OutcomeKind.ERROR -> Row {
                TextButton(onClick = onShowLog) { Text("Show details", color = color) }
                TextButton(onClick = onReport) { Text("Report this problem", color = Palette.Muted) }
            }
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
        vm.signInExpiresAt?.let { SignInKept(it, onForget = vm::forgetSignIn, onExpired = vm::refreshSignIn) }
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
            // Soft, not gradient: the screen has one highlighted button, and this step is optional.
            SoftButton("Sign in with Samsung", null, enabled = enabled && connected, onClick = onSignIn, modifier = Modifier.fillMaxWidth(), tint = Palette.Link)
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

/**
 * Shows that a Samsung sign-in is saved (for certificates for more TVs) and counts down to when
 * it is dropped.
 */
@Composable
private fun SignInKept(expiresAt: Instant, onForget: () -> Unit, onExpired: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(expiresAt) {
        while (now.isBefore(expiresAt)) {
            kotlinx.coroutines.delay(15_000)
            now = Instant.now()
        }
        onExpired()
    }
    val minutes = ((java.time.Duration.between(now, expiresAt).seconds + 59) / 60).coerceAtLeast(1)
    val left = when {
        minutes >= 48 * 60 -> "${minutes / (24 * 60)} days"
        minutes >= 120 -> "${minutes / 60} h"
        else -> "$minutes min"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.Link, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Samsung sign-in saved · expires in $left", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "Certificates for other TVs need no sign-in until then, even after closing the app. Saved encrypted on this phone only.",
                color = Palette.Muted, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        TextButton(onClick = onForget) { Text("Forget", color = Palette.Link) }
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

/**
 * Shown wherever the phone needs Wi-Fi. With [button], it carries the main action;
 * the setup guide puts that button in its bottom bar instead.
 */
@Composable
internal fun NoWifiNotice(button: Boolean = true) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WarningBox("This phone is not on Wi-Fi", "Connect it to the same Wi-Fi as your TV. The app can't find or reach the TV without it.")
        if (button) GradientButton("Open Wi-Fi settings", enabled = true, onClick = { openWifiSettings(context) })
    }
}

/** The Wi-Fi panel over the app on Android 10 and newer; the Wi-Fi settings page before that. */
internal fun openWifiSettings(context: Context) {
    val page = Intent(Settings.ACTION_WIFI_SETTINGS)
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else page
    runCatching { context.startActivity(intent) }
        .recoverCatching { context.startActivity(page) }
}

internal fun formatDate(instant: java.time.Instant): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault()).format(instant)

/** Popup: the app, who made it, its licence and update status, and links to the code and to report a problem. */
@Composable
private fun AboutDialog(vm: InstallerViewModel, onDismiss: () -> Unit, onReport: () -> Unit) {
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
            SoftButton("Report a problem", null, enabled = true, onClick = onReport, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Shows the report before it goes anywhere: it ends up on a public GitHub page. */
@Composable
private fun BugReportDialog(vm: InstallerViewModel, copy: (String) -> Unit, onDismiss: () -> Unit) {
    val (text, url) = remember { vm.bugReport() }
    val uri = LocalUriHandler.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a problem") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This opens a GitHub issue with the details below filled in. Issues are public: TV IDs, tokens and e-mail " +
                        "addresses are already taken out, but check it first. You need a GitHub account to send it.",
                    fontSize = 13.sp, lineHeight = 18.sp,
                )
                Text(
                    text,
                    color = Palette.Muted, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp))
                        .background(Palette.SurfaceHigh).verticalScroll(rememberScrollState()).padding(10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { uri.openUri(url); onDismiss() }) { Text("Open GitHub issue") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { copy(text) }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
