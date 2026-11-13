package app.miccamguard

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/** Connects to the phone's own Wireless debugging service, with its own key kept inside this app. */
private class AdbManager(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(context.filesDir, "adb_key")
        val certFile = File(context.filesDir, "adb_cert")
        var loadedKey: PrivateKey? = null
        var loadedCert: Certificate? = null
        try {
            if (keyFile.exists() && certFile.exists()) {
                loadedKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                loadedCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
            }
        } catch (_: Throwable) {
            loadedKey = null
            loadedCert = null
        }
        if (loadedKey == null || loadedCert == null) {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            val name = X500Name("CN=MicCamGuard")
            val now = Date()
            val until = Date(now.time + 20L * 365 * 86_400_000L)
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now.time), now, until, name, pair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
            loadedKey = pair.private
            loadedCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            keyFile.writeBytes(loadedKey.encoded)
            certFile.writeBytes(loadedCert.encoded)
        }
        key = loadedKey!!
        cert = loadedCert!!
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Mic & Cam Guard"
}

/**
 * Runs a few system commands (the same ones a computer could run over USB debugging)
 * through Wireless debugging. No root and no other app needed.
 */
object Shell {
    enum class Status { NEED_PAIRING, OFF, CONNECTING, READY }

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    private val _status = MutableStateFlow(Status.NEED_PAIRING)
    val status: StateFlow<Status> = _status

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var manager: AdbManager? = null
    private val connecting = AtomicBoolean(false)
    private val managerLock = Any()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _status.value = if (Prefs.paired(context)) Status.OFF else Status.NEED_PAIRING
        connectAsync()
    }

    private fun manager(): AdbManager {
        manager?.let { return it }
        synchronized(managerLock) {
            manager?.let { return it }
            val m = AdbManager(appContext!!)
            manager = m
            return m
        }
    }

    fun paired(): Boolean = appContext?.let { Prefs.paired(it) } ?: false

    /** Starts a background connection attempt if paired and not already connected. */
    fun connectAsync() {
        if (_status.value == Status.READY || !paired()) return
        Thread { connectBlocking(10_000) }.start()
    }

    /** Kept for older callers. */
    fun refresh() = connectAsync()

    /**
     * Tries to connect, waiting at most [timeoutMs]. Returns true when ready.
     * Call from a background thread.
     */
    fun connectBlocking(timeoutMs: Long): Boolean {
        val ctx = appContext ?: return false
        if (isConnected()) {
            _status.value = Status.READY
            return true
        }
        if (!Prefs.paired(ctx)) {
            _status.value = Status.NEED_PAIRING
            return false
        }
        if (!connecting.compareAndSet(false, true)) return false
        _status.value = Status.CONNECTING
        try {
            val ok = manager().connectTls(ctx, timeoutMs)
            _status.value = if (ok) Status.READY else Status.OFF
            if (ok) afterConnect()
            return ok
        } catch (_: AdbPairingRequiredException) {
            Prefs.setPaired(ctx, false)
            _status.value = Status.NEED_PAIRING
        } catch (_: Throwable) {
            _status.value = Status.OFF
        } finally {
            connecting.set(false)
        }
        return false
    }

    private fun isConnected(): Boolean = try {
        manager?.isConnected == true
    } catch (_: Throwable) {
        false
    }

    private fun afterConnect() {
        // Stops Android from forgetting this pairing after 7 days without use.
        exec("settings put global adb_allowed_connection_time 0")
    }

    /** Pairs with the code shown in Settings > Wireless debugging. Call from a background thread. */
    fun pair(port: Int, code: String): Boolean {
        val ctx = appContext ?: return false
        val ok = try {
            manager().pair("127.0.0.1", port, code.trim())
        } catch (_: Throwable) {
            false
        }
        if (ok) {
            Prefs.setPaired(ctx, true)
            _status.value = Status.OFF
            connectBlocking(15_000)
        }
        return ok
    }

    /** Runs a shell command. Call from a background thread. */
    fun exec(cmd: String): Result {
        val m = manager
        if (m == null || !isConnected()) {
            if (_status.value == Status.READY) _status.value = Status.OFF
            return Result(-1, "", "Not connected")
        }
        return try {
            val stream = m.openStream("shell:($cmd) 2>&1; echo __RC__\$?")
            var text = ""
            val reader = Thread {
                text = try {
                    stream.openInputStream().bufferedReader().readText()
                } catch (_: Throwable) {
                    ""
                }
            }
            reader.start()
            reader.join(20_000)
            try {
                stream.close()
            } catch (_: Throwable) {
            }
            val marker = text.lastIndexOf("__RC__")
            if (marker < 0) return Result(-1, text, "No reply")
            val code = text.substring(marker + 6).trim().toIntOrNull() ?: -1
            Result(code, text.substring(0, marker), "")
        } catch (t: Throwable) {
            if (!isConnected()) _status.value = Status.OFF
            Result(-1, "", t.message ?: t.javaClass.simpleName)
        }
    }

    fun forget() {
        val ctx = appContext ?: return
        try {
            manager?.disconnect()
        } catch (_: Throwable) {
        }
        Prefs.setPaired(ctx, false)
        _status.value = Status.NEED_PAIRING
    }
}
