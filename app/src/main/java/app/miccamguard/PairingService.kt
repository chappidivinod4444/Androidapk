package app.miccamguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-time pairing with Wireless debugging. While the user has the
 * "Pair device with pairing code" box open in Settings, this shows a
 * notification where they type the code, so they never have to leave Settings.
 */
class PairingService : Service() {

    enum class State { IDLE, SEARCHING, FOUND, PAIRING, DONE, FAILED }

    companion object {
        const val CH_PAIR = "pairing"
        const val NID_PAIR = 30
        const val ACTION_REPLY = "app.miccamguard.PAIR_REPLY"
        const val ACTION_STOP = "app.miccamguard.PAIR_STOP"
        const val KEY_CODE = "code"
        private const val TIMEOUT_MS = 10 * 60_000L

        val state = MutableStateFlow(State.IDLE)

        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, PairingService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, PairingService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifications: NotificationManager
    private var mdns: AdbMdns? = null

    @Volatile
    private var port = -1

    private val timeout = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CH_PAIR, "Pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Where you type the Wireless debugging pairing code"
                setShowBadge(false)
            }
        )
        try {
            ServiceCompat.startForeground(
                this,
                NID_PAIR,
                searching("Open Wireless debugging and tap \"Pair device with pairing code\"."),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (_: Throwable) {
            stopSelf()
            return
        }
        state.value = State.SEARCHING
        startSearch()
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun startSearch() {
        try {
            mdns?.stop()
        } catch (_: Throwable) {
        }
        mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, p ->
            handler.post { onPort(p) }
        }.also { it.start() }
    }

    private fun onPort(p: Int) {
        if (state.value == State.PAIRING || state.value == State.DONE) return
        if (p > 0) {
            port = p
            state.value = State.FOUND
            show(askForCode("Pairing code found. Tap Enter code and type the 6 digits shown in Settings."))
        } else if (port > 0) {
            port = -1
            state.value = State.SEARCHING
            show(searching("The pairing box closed. Open \"Pair device with pairing code\" again."))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString()?.filter { it.isDigit() } ?: ""
                onCode(code)
            }
        }
        return START_NOT_STICKY
    }

    private fun onCode(code: String) {
        val p = port
        if (code.length != 6) {
            show(askForCode("The code has 6 digits. Please try again."))
            return
        }
        if (p <= 0) {
            show(searching("Open \"Pair device with pairing code\" in Wireless debugging first."))
            return
        }
        state.value = State.PAIRING
        show(progress("Pairing…"))
        Thread {
            val ok = Shell.pair(p, code)
            handler.post {
                if (ok) {
                    state.value = State.DONE
                    try {
                        mdns?.stop()
                    } catch (_: Throwable) {
                    }
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    notifications.notify(NID_PAIR + 1, done())
                    stopSelf()
                } else {
                    state.value = State.FAILED
                    show(askForCode("That didn't work. Check the code in Settings and try again."))
                }
            }
        }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        try {
            mdns?.stop()
        } catch (_: Throwable) {
        }
        if (state.value != State.DONE) state.value = State.IDLE
        super.onDestroy()
    }

    // ---------------------------------------------------------- notifications

    private fun show(n: Notification) {
        try {
            notifications.notify(NID_PAIR, n)
        } catch (_: Throwable) {
        }
    }

    private fun base(text: String) = NotificationCompat.Builder(this, CH_PAIR)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle("Connect Mic & Cam Guard")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setColor(0xFF2DD4BF.toInt())
        .setOngoing(true)
        .setOnlyAlertOnce(false)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    private fun stopAction(): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, 2,
            Intent(this, PairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(0, "Cancel", pi)
    }

    private fun searching(text: String): Notification =
        base(text).addAction(stopAction()).build()

    private fun progress(text: String): Notification =
        base(text).setProgress(0, 0, true).build()

    private fun askForCode(text: String): Notification {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        val pi = PendingIntent.getService(
            this, 1,
            Intent(this, PairingService::class.java).setAction(ACTION_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val enter = NotificationCompat.Action.Builder(0, "Enter code", pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
        return base(text).addAction(enter).addAction(stopAction()).build()
    }

    private fun done(): Notification {
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_PAIR)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Mic & Cam Guard is connected")
            .setContentText("Done! You only need to do this once.")
            .setColor(0xFF2DD4BF.toInt())
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }
}
