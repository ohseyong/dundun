package app.tetherfree

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns

internal class AdbConnectionError(message: String) : Exception(message)

internal class AdbConnection(private val context: Context) {
    /**
     * 무선 디버깅에 붙는다. 막 켠 직전에는 지난번 포트가 먼저 잡혀 거절될 수 있어서 몇 번 다시 해 본다.
     */
    fun connect(adb: AdbManager, store: Store) {
        var last: Throwable? = null
        repeat(3) { attempt ->
            if (adb.isConnected) return
            // 막 다시 켠 직후에는 지난번 포트 기록이 먼저 잡힐 수 있어서, 찾은 포트를 모두 최근 것부터 시도한다.
            val endpoints = discoverConnectPorts(if (attempt == 0) 8_000 else 5_000)
            for ((host, port) in endpoints.reversed()) {
                try {
                    if (adb.connect(host, port) || adb.isConnected) return
                } catch (e: AdbPairingRequiredException) {
                    store.paired = false
                    throw AdbConnectionError(context.getString(R.string.err_repair))
                } catch (t: Throwable) {
                    Log.w("AdbConnection", "connect $port failed: ${t.message}")
                    last = t
                }
            }
            Thread.sleep(1_500)
        }
        Log.w("AdbConnection", "connect gave up", last)
        throw AdbConnectionError(context.getString(R.string.err_connect))
    }

    /** mDNS 로 무선 디버깅 연결 포트를 찾는다. 처음 찾은 뒤 잠깐 더 기다려 다른 기록도 모은다. */
    private fun discoverConnectPorts(timeoutMs: Long): List<Pair<String, Int>> {
        val found = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val first = java.util.concurrent.CountDownLatch(1)
        val finder = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { host, port ->
            val address = host?.hostAddress
            if (address != null && port > 0) {
                synchronized(found) {
                    found.remove(address to port)
                    found.add(address to port)
                }
                first.countDown()
            }
        }
        finder.start()
        try {
            if (first.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) Thread.sleep(1_500)
        } finally {
            finder.stop()
        }
        return synchronized(found) { found.toList() }
    }

    companion object {
        /** `am instrument -w` 출력에서 INSTRUMENTATION_RESULT 줄을 모은다. */
        fun parseInstrument(output: String): Map<String, String> = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("INSTRUMENTATION_RESULT: ") }
            .map { it.removePrefix("INSTRUMENTATION_RESULT: ") }
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            .toMap()
    }
}
