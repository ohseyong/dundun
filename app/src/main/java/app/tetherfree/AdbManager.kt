package app.tetherfree

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** 이 폰의 무선 디버깅(adbd)에 앱이 직접 붙는 연결. 키는 한 번 만들어 계속 쓴다. */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val dir = context.filesDir
        val keyFile = File(dir, "adb.key")
        val certFile = File(dir, "adb.crt")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
            val pair = generator.generateKeyPair()
            val name = X500Name("CN=DunDun")
            val now = Date()
            val until = Date(now.time + 20L * 365 * 24 * 60 * 60 * 1000)
            val serial = BigInteger.valueOf(SecureRandom().nextLong() and Long.MAX_VALUE)
            val holder = JcaX509v3CertificateBuilder(name, serial, now, until, name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            key = pair.private
            cert = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "DunDun"

    /** 셸 명령을 실행하고 출력 전체를 돌려준다. 오래 걸리면 끊는다. */
    fun exec(command: String, timeoutMs: Long = 30_000): String {
        val stream = openStream("shell:$command")
        val out = StringBuilder()
        var error: Throwable? = null
        val reader = Thread {
            try {
                stream.openInputStream().bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) { out.append(buf, 0, n) }
                    }
                }
            } catch (t: Throwable) {
                error = t
            }
        }
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            runCatching { stream.close() }
            throw IOException("Command timed out")
        }
        runCatching { stream.close() }
        val text = synchronized(out) { out.toString() }
        if (text.isEmpty() && error != null) throw IOException(error)
        return text
    }

    companion object {
        @Volatile private var instance: AdbManager? = null

        fun get(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }
    }
}
