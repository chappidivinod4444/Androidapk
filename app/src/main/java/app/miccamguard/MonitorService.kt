package app.miccamguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs in the background and listens for the system telling us that the
 * microphone or a camera has been opened by any app. It never opens them itself.
 */
class MonitorService : Service() {

    companion object {
        const val CH_STATUS = "status"
        const val CH_ALERT = "alerts"
        const val NID_STATUS = 1
        const val NID_MIC = 10
        const val NID_CAM = 11

        const val ACTION_BLOCK = "app.miccamguard.BLOCK"
        const val ACTION_TRUST = "app.miccamguard.TRUST"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_SENSOR = "sensor"
        const val EXTRA_NID = "nid"

        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, MonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var cameras: CameraManager
    private lateinit var notifications: NotificationManager
    private val busyCameras = HashSet<String>()
    private val eventIds = HashMap<Sensor, Long>()
    private var registered = false

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            onSensor(Sensor.MIC, !configs.isNullOrEmpty())
        }
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            busyCameras.remove(cameraId)
            onSensor(Sensor.CAMERA, busyCameras.isNotEmpty())
        }

        override fun onCameraUnavailable(cameraId: String) {
            busyCameras.add(cameraId)
            onSensor(Sensor.CAMERA, true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        cameras = getSystemService(CameraManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        createChannels()

        try {
            ServiceCompat.startForeground(
                this,
                NID_STATUS,
                statusNotification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (_: Throwable) {
            stopSelf()
            return
        }

        Guard.micActive.value = false
        Guard.camActive.value = false
        Guard.micApps.value = emptyList()
        Guard.camApps.value = emptyList()
        Guard.monitoring.value = true

        audio.registerAudioRecordingCallback(recordingCallback, handler)
        cameras.registerAvailabilityCallback(cameraCallback, handler)
        registered = true
        onSensor(Sensor.MIC, audio.activeRecordingConfigurations.isNotEmpty())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (registered) {
            try {
                audio.unregisterAudioRecordingCallback(recordingCallback)
                cameras.unregisterAvailabilityCallback(cameraCallback)
            } catch (_: Throwable) {
            }
        }
        for (id in eventIds.values) Guard.endEvent(id)
        eventIds.clear()
        Guard.micActive.value = false
        Guard.camActive.value = false
        Guard.micApps.value = emptyList()
        Guard.camApps.value = emptyList()
        Guard.monitoring.value = false
        scope.cancel()
        super.onDestroy()
    }

    private fun activeFlow(sensor: Sensor) = if (sensor == Sensor.MIC) Guard.micActive else Guard.camActive
    private fun appsFlow(sensor: Sensor) = if (sensor == Sensor.MIC) Guard.micApps else Guard.camApps

    private fun onSensor(sensor: Sensor, active: Boolean) {
        val flow = activeFlow(sensor)
        if (flow.value == active) return
        flow.value = active
        if (active) {
            val id = Guard.startEvent(sensor)
            eventIds[sensor] = id
            scope.launch { resolve(sensor, id) }
        } else {
            eventIds.remove(sensor)?.let { Guard.endEvent(it) }
            appsFlow(sensor).value = emptyList()
        }
        refreshStatus()
    }

    /** Works out which app it is (needs Shizuku), records it, and alerts the user. */
    private suspend fun resolve(sensor: Sensor, id: Long) {
        var pkgs: List<String> = emptyList()
        if (Shell.status.value == Shell.Status.READY) {
            for (attempt in 0 until 3) {
                pkgs = withContext(Dispatchers.IO) { Guard.identify(sensor) }
                if (pkgs.isNotEmpty()) break
                delay(350)
            }
        }
        val labels = pkgs.map { Guard.labelOf(it) }
        Guard.updateEvent(id, pkgs, labels)
        if (eventIds[sensor] == id) {
            appsFlow(sensor).value = labels.ifEmpty { listOf("Unknown app") }
            refreshStatus()
        }
        if (!Prefs.alerts(this)) return
        val trusted = Prefs.trusted(this)
        if (pkgs.isNotEmpty() && pkgs.all { it in trusted }) return
        postAlert(sensor, pkgs, labels)
    }

    // ---------------------------------------------------------- notifications

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Protection status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that Mic & Cam Guard is watching"
                setShowBadge(false)
            }
        )
        notifications.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Mic and camera alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when an app starts using the microphone or camera"
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statusNotification(): Notification {
        val mic = Guard.micActive.value
        val cam = Guard.camActive.value
        val text = when {
            mic && cam -> "Microphone and camera are in use"
            mic -> "Microphone in use" + by(Guard.micApps.value)
            cam -> "Camera in use" + by(Guard.camApps.value)
            else -> "Watching your microphone and camera"
        }
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Mic & Cam Guard is on")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF2DD4BF.toInt())
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun by(labels: List<String>): String =
        if (labels.isEmpty()) "" else " by " + labels.joinToString()

    private fun refreshStatus() {
        try {
            notifications.notify(NID_STATUS, statusNotification())
        } catch (_: Throwable) {
        }
    }

    private fun postAlert(sensor: Sensor, pkgs: List<String>, labels: List<String>) {
        val nid = if (sensor == Sensor.MIC) NID_MIC else NID_CAM
        val what = sensor.label.lowercase()
        val text = if (labels.isEmpty()) "An app started using your $what."
        else "${labels.joinToString()} started using your $what."

        val builder = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(if (sensor == Sensor.MIC) R.drawable.ic_mic else R.drawable.ic_camera)
            .setContentTitle("${sensor.label} in use")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setColor(0xFFFF6B6B.toInt())
            .setContentIntent(openAppIntent())

        if (pkgs.size == 1) {
            val pkg = pkgs[0]
            val base = sensor.ordinal * 10
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            if (Shell.status.value == Shell.Status.READY) {
                val block = Intent(this, ActionReceiver::class.java)
                    .setAction(ACTION_BLOCK)
                    .putExtra(EXTRA_PKG, pkg)
                    .putExtra(EXTRA_SENSOR, sensor.name)
                    .putExtra(EXTRA_NID, nid)
                builder.addAction(0, "Block", PendingIntent.getBroadcast(this, base + 1, block, flags))
            } else {
                val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(0, "App settings", PendingIntent.getActivity(this, base + 3, settings, flags))
            }
            val trust = Intent(this, ActionReceiver::class.java)
                .setAction(ACTION_TRUST)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_NID, nid)
            builder.addAction(0, "Don't alert for this app", PendingIntent.getBroadcast(this, base + 2, trust, flags))
        }

        try {
            notifications.notify(nid, builder.build())
        } catch (_: Throwable) {
        }
    }
}

/** Handles the buttons on alert notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pkg = intent.getStringExtra(MonitorService.EXTRA_PKG) ?: return
        val nid = intent.getIntExtra(MonitorService.EXTRA_NID, 0)
        val nm = app.getSystemService(NotificationManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val name = Guard.labelOf(pkg)

        when (intent.action) {
            MonitorService.ACTION_TRUST -> {
                Prefs.addTrusted(app, pkg)
                nm.cancel(nid)
                Toast.makeText(app, "No more alerts for $name", Toast.LENGTH_SHORT).show()
            }

            MonitorService.ACTION_BLOCK -> {
                val sensor = try {
                    Sensor.valueOf(intent.getStringExtra(MonitorService.EXTRA_SENSOR) ?: "")
                } catch (_: Throwable) {
                    return
                }
                val pending = goAsync()
                Thread {
                    try {
                        Shell.refresh()
                        val error = Guard.setBlocked(pkg, sensor, block = true)
                        Guard.refreshApp(pkg)
                        nm.cancel(nid)
                        main.post {
                            Toast.makeText(
                                app,
                                error ?: "${sensor.label} blocked for $name",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}

/** Restarts the monitor after the phone reboots, if protection was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.monitoring(context)) {
            MonitorService.start(context)
        }
    }
}
