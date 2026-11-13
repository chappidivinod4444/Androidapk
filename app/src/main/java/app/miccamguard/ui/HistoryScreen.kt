package app.miccamguard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.miccamguard.Guard
import app.miccamguard.R
import app.miccamguard.Sensor
import app.miccamguard.UsageEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun openAppSettings(context: Context, pkg: String) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Throwable) {
    }
}

fun sensorIcon(sensor: Sensor): Int =
    if (sensor == Sensor.MIC) R.drawable.ic_mic else R.drawable.ic_camera

private fun formatTime(ms: Long): String =
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ms))

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 1 -> "under 1 sec"
        s < 60 -> "$s sec"
        s < 3600 -> "${s / 60} min ${s % 60} sec"
        else -> "${s / 3600} hr ${(s % 3600) / 60} min"
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun IconBubble(icon: Int, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(42.dp)
            .background(tint.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun EventRow(event: UsageEvent) {
    val live = event.end == null
    val tint = if (live) GuardColors.Danger else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(sensorIcon(event.sensor), tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    event.labels.isEmpty() -> "Unknown app"
                    event.guessed -> "Likely " + event.labels.joinToString()
                    else -> event.labels.joinToString()
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                event.sensor.label + " · " + formatTime(event.start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (live) "Now" else formatDuration((event.end ?: event.start) - event.start),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (live) GuardColors.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun HistoryScreen() {
    val history by Guard.history.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle(
                "History",
                "Every time the mic or camera was used",
                Modifier.weight(1f),
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = { Guard.clearHistory() }) { Text("Clear") }
            }
        }

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBubble(R.drawable.ic_history, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "Nothing recorded yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Turn on protection on the Guard tab.\nUses of your mic and camera will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(history, key = { it.id }) { event ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        EventRow(event)
                    }
                }
            }
        }
    }
}
