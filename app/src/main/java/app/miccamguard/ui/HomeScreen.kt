package app.miccamguard.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.miccamguard.Guard
import app.miccamguard.MonitorService
import app.miccamguard.PairingService
import app.miccamguard.Prefs
import app.miccamguard.R
import app.miccamguard.Sensor
import app.miccamguard.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun SwitchRow(
    icon: Int,
    tint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SensorCard(
    sensor: Sensor,
    monitoring: Boolean,
    active: Boolean,
    apps: List<String>,
    shellReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = when {
        active -> GuardColors.Danger
        monitoring -> GuardColors.Safe
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val pulse = if (active) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        value
    } else {
        1f
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(sensorIcon(sensor), color, Modifier.alpha(pulse))
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(10.dp)
                        .alpha(pulse)
                        .background(color, CircleShape)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                sensor.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    active -> "In use now"
                    monitoring -> "Not in use"
                    else -> "Not watching"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (active) GuardColors.Danger else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    !active -> " "
                    apps.isNotEmpty() -> apps.joinToString()
                    shellReady -> "Finding the app…"
                    else -> "Connect the guard to see which app"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Opens Developer options, scrolled to Wireless debugging where the phone supports it. */
fun openWirelessDebugging(context: Context) {
    val key = ":settings:fragment_args_key"
    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(key, "toggle_adb_wireless")
        .putExtra(":settings:show_fragment_args", Bundle().apply { putString(key, "toggle_adb_wireless") })
    try {
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }
}

private fun openUsageAccess(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Throwable) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }
}

private fun developerOptionsOn(context: Context): Boolean = try {
    Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
} catch (_: Throwable) {
    false
}

@Composable
private fun ConnectCard(
    status: Shell.Status,
    pairing: PairingService.State,
    onStartPairing: () -> Unit,
    onGuide: () -> Unit,
) {
    val context = LocalContext.current
    val ready = status == Shell.Status.READY
    val waitingForCode = pairing == PairingService.State.SEARCHING ||
        pairing == PairingService.State.FOUND || pairing == PairingService.State.FAILED ||
        pairing == PairingService.State.PAIRING
    SectionCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(R.drawable.ic_shield, if (ready) GuardColors.Safe else GuardColors.Warn)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            ready -> "Guard connected"
                            status == Shell.Status.CONNECTING -> "Connecting…"
                            waitingForCode -> "Waiting for the pairing code"
                            status == Shell.Status.OFF -> "Wireless debugging is off"
                            else -> "Connect the guard (one time)"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when {
                            ready -> "The guard can name the app using your mic or camera, and block it."
                            status == Shell.Status.CONNECTING -> "Looking for Wireless debugging on this phone."
                            waitingForCode -> "In Wireless debugging, tap \"Pair device with pairing code\", then type the code into this app's notification."
                            status == Shell.Status.OFF -> "Turn on Wireless debugging and the guard reconnects by itself. Apps you blocked stay blocked."
                            else -> "Lets the guard name the app and block it in one tap. Uses Android's built-in Wireless debugging. No root, no other app."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!ready) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        status == Shell.Status.CONNECTING -> Unit
                        waitingForCode ->
                            Button(onClick = { openWirelessDebugging(context) }) { Text("Open settings") }
                        status == Shell.Status.OFF -> {
                            Button(onClick = { openWirelessDebugging(context) }) { Text("Turn on") }
                            OutlinedButton(onClick = { Shell.connectAsync() }) { Text("Retry") }
                        }
                        else -> {
                            Button(onClick = onStartPairing) { Text("Start") }
                            OutlinedButton(onClick = onGuide) { Text("How it works") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Got it") } },
        title = { Text("Connect the guard") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Android only lets an app see which app uses the mic, or block other apps, through its built-in Wireless debugging. This app connects to it directly. Nothing else to install, no root.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val steps = listOf(
                    "Connect to Wi-Fi.",
                    "Turn on Developer options: Settings > About device > Version, tap Build number 7 times.",
                    "Tap Start in this app. It opens Developer options.",
                    "Turn on Wireless debugging, open it and tap \"Pair device with pairing code\".",
                    "Pull down the notification from Mic & Cam Guard, tap Enter code and type the 6 digits.",
                )
                steps.forEachIndexed { index, step ->
                    Row {
                        Text(
                            "${index + 1}.",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "You pair only once. After a restart or a Wi-Fi change Android turns Wireless debugging off. Turn it back on and the guard reconnects by itself. Apps you blocked stay blocked either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
@Composable
fun HomeScreen(say: (String) -> Unit, onSeeHistory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val monitoring by Guard.monitoring.collectAsStateWithLifecycle()
    val micActive by Guard.micActive.collectAsStateWithLifecycle()
    val camActive by Guard.camActive.collectAsStateWithLifecycle()
    val micApps by Guard.micApps.collectAsStateWithLifecycle()
    val camApps by Guard.camApps.collectAsStateWithLifecycle()
    val micOff by Guard.micOff.collectAsStateWithLifecycle()
    val camOff by Guard.camOff.collectAsStateWithLifecycle()
    val history by Guard.history.collectAsStateWithLifecycle()
    val shell by Shell.status.collectAsStateWithLifecycle()
    val shellReady = shell == Shell.Status.READY
    val pairing by PairingService.state.collectAsStateWithLifecycle()
    var usageAccess by remember { mutableStateOf(Guard.hasUsageAccess()) }
    var devOptions by remember { mutableStateOf(developerOptionsOn(context)) }
    LifecycleResumeEffect(Unit) {
        usageAccess = Guard.hasUsageAccess()
        devOptions = developerOptionsOn(context)
        onPauseOrDispose { }
    }

    var alerts by remember { mutableStateOf(Prefs.alerts(context)) }
    var showGuide by remember { mutableStateOf(false) }
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun setProtection(on: Boolean) {
        Prefs.setMonitoring(context, on)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MonitorService.start(context)
        } else {
            MonitorService.stop(context)
        }
    }

    fun startPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            say("Allow notifications. The pairing code is typed into a notification.")
            return
        }
        if (!devOptions) {
            showGuide = true
            say("First turn on Developer options: tap Build number 7 times in About device > Version.")
            return
        }
        PairingService.start(context)
        openWirelessDebugging(context)
    }

    fun setSensorOff(sensor: Sensor, off: Boolean) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { Guard.setSensorOff(sensor, off) }
            say(
                error ?: if (off) "${sensor.label} is now off for every app"
                else "${sensor.label} is available to apps again"
            )
        }
    }

    if (showGuide) GuideDialog(onClose = { showGuide = false })

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Mic & Cam Guard", "See and stop apps that use your mic or camera")

        // Main on/off
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.linearGradient(
                        if (monitoring) listOf(GuardColors.HeroOnA, GuardColors.HeroOnB)
                        else listOf(GuardColors.HeroOffA, GuardColors.HeroOffB)
                    )
                )
                .padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(Color.White.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (monitoring) "Protection is on" else "Protection is off",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        if (monitoring) "Watching your mic and camera"
                        else "Turn on to get alerts when any app uses your mic or camera",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.88f),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = monitoring,
                    onCheckedChange = { setProtection(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color.White.copy(alpha = 0.38f),
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.Black.copy(alpha = 0.25f),
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }

        // Live status
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SensorCard(Sensor.MIC, monitoring, micActive, micApps, shellReady, Modifier.weight(1f))
            SensorCard(Sensor.CAMERA, monitoring, camActive, camApps, shellReady, Modifier.weight(1f))
        }

        ConnectCard(
            status = shell,
            pairing = pairing,
            onStartPairing = { startPairing() },
            onGuide = { showGuide = true },
        )

        SectionLabel("Switch off for every app")
        SectionCard {
            SwitchRow(
                icon = R.drawable.ic_mic,
                tint = if (micOff) GuardColors.Danger else MaterialTheme.colorScheme.primary,
                title = "Turn off microphone",
                subtitle = if (shellReady) "No app can listen while this is on"
                else "Connect the guard first",
                checked = micOff,
                enabled = shellReady,
                onChange = { setSensorOff(Sensor.MIC, it) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(
                icon = R.drawable.ic_camera,
                tint = if (camOff) GuardColors.Danger else MaterialTheme.colorScheme.primary,
                title = "Turn off camera",
                subtitle = if (shellReady) "No app can use the camera while this is on"
                else "Connect the guard first",
                checked = camOff,
                enabled = shellReady,
                onChange = { setSensorOff(Sensor.CAMERA, it) },
            )
        }

        SectionLabel("Settings")
        SectionCard {
            SwitchRow(
                icon = R.drawable.ic_history,
                tint = MaterialTheme.colorScheme.primary,
                title = "Alert me",
                subtitle = "Show a notification each time the mic or camera is used",
                checked = alerts,
                enabled = true,
                onChange = {
                    alerts = it
                    Prefs.setAlerts(context, it)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(R.drawable.ic_apps, if (usageAccess) GuardColors.Safe else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Guess the app when unsure",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (usageAccess) "On. Shows the app that was on screen when the guard isn't connected"
                        else "Give Usage access so it can name the likely app even without connecting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { openUsageAccess(context) }) { Text(if (usageAccess) "Change" else "Allow") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(R.drawable.ic_shield, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Keep the guard running",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Set Battery usage to \"Allow background activity\" so the phone doesn't stop it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { openAppSettings(context, context.packageName) }) { Text("Open") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("Recent activity") }
            if (history.isNotEmpty()) {
                TextButton(onClick = onSeeHistory) { Text("See all") }
            }
        }
        SectionCard {
            if (history.isEmpty()) {
                Text(
                    "Nothing yet. Uses of your mic and camera will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                val recent = history.take(3)
                recent.forEachIndexed { index, event ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EventRow(event)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
