package app.miccamguard.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
                    else -> "Connect Shizuku to see which app"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun openShizuku(context: Context): Boolean = try {
    val intent = context.packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PACKAGE)
    if (intent != null) {
        context.startActivity(intent)
        true
    } else {
        false
    }
} catch (_: Throwable) {
    false
}

private fun installShizuku(context: Context) {
    val id = Shell.SHIZUKU_PACKAGE
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
    } catch (_: Throwable) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
            )
        } catch (_: Throwable) {
        }
    }
}

@Composable
private fun ShizukuCard(status: Shell.Status, onGuide: () -> Unit) {
    val context = LocalContext.current
    val ready = status == Shell.Status.READY
    SectionCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(R.drawable.ic_shield, if (ready) GuardColors.Safe else GuardColors.Warn)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (status) {
                            Shell.Status.READY -> "Shizuku connected"
                            Shell.Status.NO_PERMISSION -> "Shizuku is running"
                            Shell.Status.NOT_RUNNING -> "Shizuku is not started"
                            Shell.Status.NOT_INSTALLED -> "Set up Shizuku (one time)"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when (status) {
                            Shell.Status.READY -> "The guard can name the app using your mic or camera, and block it."
                            Shell.Status.NO_PERMISSION -> "Allow Mic & Cam Guard to use it."
                            Shell.Status.NOT_RUNNING -> "Open Shizuku and tap Start. Your blocks stay active meanwhile."
                            Shell.Status.NOT_INSTALLED -> "Needed to name the app and block it in one tap."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!ready) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (status) {
                        Shell.Status.NO_PERMISSION ->
                            Button(onClick = { Shell.requestPermission() }) { Text("Allow") }

                        Shell.Status.NOT_RUNNING ->
                            Button(onClick = { if (!openShizuku(context)) installShizuku(context) }) {
                                Text("Open Shizuku")
                            }

                        else ->
                            Button(onClick = { installShizuku(context) }) { Text("Install") }
                    }
                    OutlinedButton(onClick = onGuide) { Text("How to set up") }
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
        title = { Text("Set up Shizuku") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val steps = listOf(
                    "Install Shizuku from the Play Store.",
                    "Open Settings > About device > Version and tap Build number 7 times. This unlocks Developer options.",
                    "Connect to Wi-Fi. In Settings > System & updates > Developer options, turn on Wireless debugging.",
                    "Open Shizuku and tap Pairing. Follow its notification: in Wireless debugging tap \"Pair device with pairing code\" and type the code into the notification.",
                    "Back in Shizuku, tap Start.",
                    "Come back here and tap Allow.",
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
                    "After a phone restart, open Shizuku and tap Start again. Apps you blocked stay blocked even when Shizuku is stopped.",
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

        ShizukuCard(shell, onGuide = { showGuide = true })

        SectionLabel("Switch off for every app")
        SectionCard {
            SwitchRow(
                icon = R.drawable.ic_mic,
                tint = if (micOff) GuardColors.Danger else MaterialTheme.colorScheme.primary,
                title = "Turn off microphone",
                subtitle = if (shellReady) "No app can listen while this is on"
                else "Needs Shizuku",
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
                else "Needs Shizuku",
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
