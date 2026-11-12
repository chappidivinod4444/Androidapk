package app.miccamguard

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/**
 * Thin wrapper around Shizuku. Shizuku lets this app run a few system commands
 * (the same ones "adb shell" can run) without root.
 */
object Shell {
    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4711

    private val _status = MutableStateFlow(Status.NOT_RUNNING)
    val status: StateFlow<Status> = _status

    @Volatile
    private var appContext: Context? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        } catch (_: Throwable) {
        }
        refresh()
    }

    fun refresh() {
        val ctx = appContext ?: return
        _status.value = try {
            if (!Shizuku.pingBinder()) {
                if (isInstalled(ctx)) Status.NOT_RUNNING else Status.NOT_INSTALLED
            } else if (Shizuku.isPreV11()) {
                Status.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Status.READY
            } else {
                Status.NO_PERMISSION
            }
        } catch (_: Throwable) {
            Status.NOT_RUNNING
        }
    }

    private fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
        refresh()
    }

    /** Runs a shell command with Shizuku's (adb-level) identity. Call from a background thread. */
    fun exec(cmd: String): Result {
        if (_status.value != Status.READY) return Result(-1, "", "Shizuku is not connected")
        var process: Process? = null
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val p = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            process = p
            var err = ""
            val errThread = Thread {
                err = try {
                    p.errorStream.bufferedReader().readText()
                } catch (_: Throwable) {
                    ""
                }
            }
            errThread.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            errThread.join(2000)
            Result(code, out, err)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Result(-1, "", cause.message ?: cause.javaClass.simpleName)
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
            }
        }
    }
}
