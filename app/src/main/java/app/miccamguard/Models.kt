package app.miccamguard

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap

enum class Sensor(
    val permission: String,
    val appOp: String,
    val privacyName: String,
    val label: String,
) {
    MIC(Manifest.permission.RECORD_AUDIO, "RECORD_AUDIO", "microphone", "Microphone"),
    CAMERA(Manifest.permission.CAMERA, "CAMERA", "camera", "Camera"),
}

/** One period during which the mic or camera was in use. */
data class UsageEvent(
    val id: Long,
    val sensor: Sensor,
    val packages: List<String>,
    val labels: List<String>,
    val start: Long,
    val end: Long?,
)

/** An installed app that has asked for mic and/or camera access. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val isSystem: Boolean,
    val icon: ImageBitmap?,
    val requestsMic: Boolean,
    val micGranted: Boolean,
    val requestsCam: Boolean,
    val camGranted: Boolean,
)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun monitoring(c: Context): Boolean = sp(c).getBoolean("monitoring", false)
    fun setMonitoring(c: Context, v: Boolean) = sp(c).edit().putBoolean("monitoring", v).apply()

    fun alerts(c: Context): Boolean = sp(c).getBoolean("alerts", true)
    fun setAlerts(c: Context, v: Boolean) = sp(c).edit().putBoolean("alerts", v).apply()

    fun trusted(c: Context): Set<String> =
        (sp(c).getStringSet("trusted", null) ?: emptySet()).toSet()

    fun addTrusted(c: Context, pkg: String) =
        sp(c).edit().putStringSet("trusted", trusted(c) + pkg).apply()

    fun sensorOff(c: Context, s: Sensor): Boolean = sp(c).getBoolean("off_" + s.name, false)
    fun setSensorOff(c: Context, s: Sensor, v: Boolean) =
        sp(c).edit().putBoolean("off_" + s.name, v).apply()
}

class GuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Guard.init(this)
        Shell.init(this)
    }
}
