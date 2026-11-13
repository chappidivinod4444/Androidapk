@file:OptIn(ExperimentalMaterial3Api::class)

package app.miccamguard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.miccamguard.AppEntry
import app.miccamguard.Guard
import app.miccamguard.R
import app.miccamguard.Sensor
import app.miccamguard.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun AccessToggle(sensor: Sensor, granted: Boolean, onClick: () -> Unit) {
    val color = if (granted) GuardColors.Safe else GuardColors.Danger
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(color.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(sensorIcon(sensor)),
                contentDescription = sensor.label,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            if (granted) "Allowed" else "Blocked",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun AppRow(
    entry: AppEntry,
    onOpen: () -> Unit,
    onToggle: (Sensor, Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = entry.icon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(11.dp)),
                )
            } else {
                IconBubble(R.drawable.ic_apps, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (entry.isSystem) "System app" else "Installed app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.requestsMic) {
                AccessToggle(Sensor.MIC, entry.micGranted) { onToggle(Sensor.MIC, entry.micGranted) }
            }
            if (entry.requestsCam) {
                AccessToggle(Sensor.CAMERA, entry.camGranted) { onToggle(Sensor.CAMERA, entry.camGranted) }
            }
        }
    }
}

@Composable
fun AppsScreen(say: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps by Guard.apps.collectAsStateWithLifecycle()
    val loading by Guard.appsLoading.collectAsStateWithLifecycle()
    val shell by Shell.status.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 = all, 1 = mic, 2 = camera
    var showSystem by rememberSaveable { mutableStateOf(false) }

    // Reload whenever the screen comes back (for example after changing something in Settings).
    LifecycleResumeEffect(Unit) {
        scope.launch { Guard.loadApps() }
        onPauseOrDispose { }
    }

    val shown = remember(apps, query, filter, showSystem) {
        val q = query.trim().lowercase()
        apps.filter { app ->
            (showSystem || !app.isSystem) &&
                (filter != 1 || app.requestsMic) &&
                (filter != 2 || app.requestsCam) &&
                (q.isEmpty() || app.label.lowercase().contains(q) || app.pkg.lowercase().contains(q))
        }
    }
    val allowedCount = remember(apps) { apps.count { it.micGranted || it.camGranted } }

    fun toggle(entry: AppEntry, sensor: Sensor, granted: Boolean) {
        if (shell != Shell.Status.READY) {
            say("Connect the guard on the Guard tab to block in one tap. Opening this app's settings instead.")
            openAppSettings(context, entry.pkg)
            return
        }
        scope.launch {
            val error = withContext(Dispatchers.IO) { Guard.setBlocked(entry.pkg, sensor, block = granted) }
            Guard.refreshApp(entry.pkg)
            say(
                error ?: if (granted) "${sensor.label} blocked for ${entry.label}"
                else "${sensor.label} allowed for ${entry.label}"
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        ScreenTitle(
            "Apps",
            if (apps.isEmpty()) "Apps that can use your mic or camera"
            else "$allowedCount apps can use your mic or camera right now",
            Modifier.padding(top = 12.dp, bottom = 10.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("All") })
            FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Microphone") })
            FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("Camera") })
            FilterChip(
                selected = showSystem,
                onClick = { showSystem = !showSystem },
                label = { Text("System apps") },
            )
        }

        when {
            loading && apps.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            shown.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No apps match.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                ) {
                    item {
                        Text(
                            "Tap a mic or camera icon to block or allow it. Tap the app name to open its settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    items(shown, key = { it.pkg }) { entry ->
                        AppRow(
                            entry = entry,
                            onOpen = { openAppSettings(context, entry.pkg) },
                            onToggle = { sensor, granted -> toggle(entry, sensor, granted) },
                        )
                    }
                }
            }
        }
    }
}
