package app.miccamguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** App-wide state: live sensor status, history, app list, and the block/unblock actions. */
object Guard {
    private const val MAX_HISTORY = 500

    private lateinit var app: Context
    private val lock = Any()
    private var initialized = false
    private var counter = 0L
    private val io = Executors.newSingleThreadExecutor()

    val monitoring = MutableStateFlow(false)
    val micActive = MutableStateFlow(false)
    val camActive = MutableStateFlow(false)
    val micApps = MutableStateFlow<List<String>>(emptyList())
    val camApps = MutableStateFlow<List<String>>(emptyList())
    val micOff = MutableStateFlow(false)
    val camOff = MutableStateFlow(false)
    val history = MutableStateFlow<List<UsageEvent>>(emptyList())
    val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val appsLoading = MutableStateFlow(false)

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            app = context.applicationContext
            initialized = true
            // Anything still "open" from a previous run is closed at its start time.
            history.value = readHistory().map { if (it.end == null) it.copy(end = it.start) else it }
            micOff.value = Prefs.sensorOff(app, Sensor.MIC)
            camOff.value = Prefs.sensorOff(app, Sensor.CAMERA)
        }
    }

    private val userId: Int get() = Process.myUid() / 100000

    // ---------------------------------------------------------------- history

    private fun historyFile() = File(app.filesDir, "history.json")

    private fun readHistory(): List<UsageEvent> = try {
        val arr = JSONArray(historyFile().readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            UsageEvent(
                id = o.getLong("id"),
                sensor = Sensor.valueOf(o.getString("sensor")),
                packages = o.optJSONArray("pkgs").toStrings(),
                labels = o.optJSONArray("labels").toStrings(),
                start = o.getLong("start"),
                end = if (o.has("end")) o.getLong("end") else null,
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun JSONArray?.toStrings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun persist() {
        val snapshot = history.value
        io.execute {
            try {
                val arr = JSONArray()
                for (e in snapshot) {
                    val o = JSONObject()
                    o.put("id", e.id)
                    o.put("sensor", e.sensor.name)
                    o.put("pkgs", JSONArray(e.packages))
                    o.put("labels", JSONArray(e.labels))
                    o.put("start", e.start)
                    if (e.end != null) o.put("end", e.end)
                    arr.put(o)
                }
                historyFile().writeText(arr.toString())
            } catch (_: Throwable) {
            }
        }
    }

    fun startEvent(sensor: Sensor): Long = synchronized(lock) {
        val now = System.currentTimeMillis()
        val id = now * 100 + (counter++ % 100)
        val event = UsageEvent(id, sensor, emptyList(), emptyList(), now, null)
        history.value = (listOf(event) + history.value).take(MAX_HISTORY)
        persist()
        id
    }

    fun updateEvent(id: Long, packages: List<String>, labels: List<String>) =
        mutate(id) { it.copy(packages = packages, labels = labels) }

    fun endEvent(id: Long) = mutate(id) { it.copy(end = System.currentTimeMillis()) }

    private fun mutate(id: Long, change: (UsageEvent) -> UsageEvent) {
        synchronized(lock) {
            history.value = history.value.map { if (it.id == id) change(it) else it }
            persist()
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            history.value = history.value.filter { it.end == null }
            persist()
        }
    }

    // ------------------------------------------------------------ identifying

    fun labelOf(pkg: String): String = try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    private val packageLine = Regex("""^\s*Package\s+(\S+):\s*$""")
    private val opLine = Regex("""^\s*([A-Z][A-Z0-9_]+)\s+\(""")
    private val agoPart = Regex("""\(-([0-9a-z]+)\)""")
    private val durationUnit = Regex("""(\d+)(ms|d|h|m|s)""")

    private fun parseAgoMs(text: String): Long? {
        var total = 0L
        var any = false
        for (m in durationUnit.findAll(text)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            any = true
            total += when (m.groupValues[2]) {
                "d" -> n * 86_400_000L
                "h" -> n * 3_600_000L
                "m" -> n * 60_000L
                "s" -> n * 1_000L
                else -> n
            }
        }
        return if (any) total else null
    }

    /**
     * Asks the system which app is using the sensor right now.
     * Needs Shizuku. Returns package names, or an empty list when unknown.
     * Call from a background thread.
     */
    fun identify(sensor: Sensor): List<String> {
        val running = LinkedHashSet<String>()
        var recentPkg: String? = null
        var recentAgo = Long.MAX_VALUE

        val ops = Shell.exec("dumpsys appops --op ${sensor.appOp}")
        if (ops.ok) {
            var pkg: String? = null
            var op: String? = null
            for (line in ops.out.lineSequence()) {
                val pm = packageLine.find(line)
                if (pm != null) {
                    pkg = pm.groupValues[1]
                    op = null
                    continue
                }
                val om = opLine.find(line)
                if (om != null) {
                    op = om.groupValues[1]
                    continue
                }
                val current = pkg ?: continue
                if (op != sensor.appOp) continue
                if (line.contains("Running start at")) {
                    running.add(current)
                } else if (line.contains("Access:")) {
                    val ago = agoPart.find(line)?.let { parseAgoMs(it.groupValues[1]) }
                    if (ago != null && ago < recentAgo) {
                        recentAgo = ago
                        recentPkg = current
                    }
                }
            }
        }

        if (running.isEmpty()) {
            if (sensor == Sensor.MIC) {
                val audio = Shell.exec("dumpsys audio")
                if (audio.ok) {
                    var active = false
                    for (line in audio.out.lineSequence()) {
                        val t = line.trim()
                        if (t.startsWith("riid ")) {
                            active = t.contains("active? true")
                        } else if (active && t.startsWith("session:")) {
                            Regex("""pack:([A-Za-z0-9._]+)""").find(t)?.let { running.add(it.groupValues[1]) }
                            active = false
                        }
                    }
                }
            } else {
                val cam = Shell.exec("dumpsys media.camera")
                if (cam.ok) {
                    for (m in Regex("""Client package:\s*([A-Za-z0-9._]+)""").findAll(cam.out)) {
                        running.add(m.groupValues[1])
                    }
                }
            }
        }

        // Very short uses may already be over: fall back to whoever used it in the last few seconds.
        val recent = recentPkg
        if (running.isEmpty() && recent != null && recentAgo < 15_000L) running.add(recent)

        running.remove(app.packageName)
        return running.toList()
    }

    // ------------------------------------------------------------------- apps

    suspend fun loadApps() = withContext(Dispatchers.IO) {
        appsLoading.value = true
        try {
            val pm = app.packageManager
            val out = ArrayList<AppEntry>()
            for (pi in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (pi.packageName == app.packageName) continue
                val requested = pi.requestedPermissions ?: continue
                val flags = pi.requestedPermissionsFlags
                var mic = false
                var micGranted = false
                var cam = false
                var camGranted = false
                for (i in requested.indices) {
                    val granted = flags != null && i < flags.size &&
                        (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    when (requested[i]) {
                        Sensor.MIC.permission -> {
                            mic = true
                            micGranted = granted
                        }
                        Sensor.CAMERA.permission -> {
                            cam = true
                            camGranted = granted
                        }
                    }
                }
                if (!mic && !cam) continue
                val info = pi.applicationInfo ?: continue
                val icon = try {
                    info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
                out.add(
                    AppEntry(
                        pkg = pi.packageName,
                        label = info.loadLabel(pm).toString(),
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        icon = icon,
                        requestsMic = mic,
                        micGranted = micGranted,
                        requestsCam = cam,
                        camGranted = camGranted,
                    )
                )
            }
            out.sortWith(compareBy<AppEntry> { it.isSystem }.thenBy { it.label.lowercase() })
            apps.value = out
        } catch (_: Throwable) {
        } finally {
            appsLoading.value = false
        }
    }

    private fun isGranted(pkg: String, sensor: Sensor): Boolean =
        app.packageManager.checkPermission(sensor.permission, pkg) == PackageManager.PERMISSION_GRANTED

    /** Re-reads the real permission state of one app after a change. */
    fun refreshApp(pkg: String) {
        apps.value = apps.value.map {
            if (it.pkg != pkg) it
            else it.copy(
                micGranted = it.requestsMic && isGranted(pkg, Sensor.MIC),
                camGranted = it.requestsCam && isGranted(pkg, Sensor.CAMERA),
            )
        }
    }

    private val safePackage = Regex("""^[A-Za-z0-9._]+$""")

    /**
     * Blocks or allows one app's mic/camera permission through Shizuku.
     * Returns null on success, or a message to show the user.
     * Call from a background thread.
     */
    fun setBlocked(pkg: String, sensor: Sensor, block: Boolean): String? {
        if (!safePackage.matches(pkg)) return "Unexpected app name"
        if (Shell.status.value != Shell.Status.READY) return "Shizuku is not connected"
        val perm = sensor.permission
        val u = "--user $userId"
        val cmd = if (block) {
            "pm revoke $u $pkg $perm; r=\$?; " +
                "pm set-permission-flags $u $pkg $perm user-fixed >/dev/null 2>&1; exit \$r"
        } else {
            "pm clear-permission-flags $u $pkg $perm user-fixed user-set >/dev/null 2>&1; " +
                "pm grant $u $pkg $perm"
        }
        val result = Shell.exec(cmd)
        val nowGranted = isGranted(pkg, sensor)
        if (nowGranted == !block) return null
        val text = result.err + result.out
        return if (text.contains("SecurityException") || text.contains("fixed")) {
            "Android protects this app, so its ${sensor.label.lowercase()} access can't be changed here."
        } else {
            "Couldn't change it. Try from the app's settings page."
        }
    }

    /**
     * Turns the system-wide mic or camera switch off/on for every app
     * (the same switch as the Quick Settings tile). Call from a background thread.
     */
    fun setSensorOff(sensor: Sensor, off: Boolean): String? {
        if (Shell.status.value != Shell.Status.READY) return "Shizuku is not connected"
        val verb = if (off) "enable" else "disable"
        val result = Shell.exec("cmd sensor_privacy $verb $userId ${sensor.privacyName}")
        if (!result.ok) return "Your phone didn't accept this switch."
        Prefs.setSensorOff(app, sensor, off)
        (if (sensor == Sensor.MIC) micOff else camOff).value = off
        return null
    }
}
