package app.tetherfree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.annotation.StringRes
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.Executors

/**
 * 페어링 코드를 알림으로 받고, 무선 디버깅에 붙어서 APN 작업을 실행한다.
 * 사용자는 설정의 페어링 창을 띄워 둔 채로 알림에 코드를 넣기만 하면 된다.
 */
class AdbService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var mdns: AdbMdns? = null
    @Volatile private var pairHost: String? = null
    @Volatile private var pairPort: Int = -1
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
        when (intent?.action) {
            ACTION_PAIR -> waitForCode(null)
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString().orEmpty().filter(Char::isDigit)
                when {
                    busy -> show(working(R.string.busy_working))
                    code.length != 6 -> waitForCode(R.string.err_code_digits)
                    else -> run(R.string.busy_connecting) { pairThenApply(code) }
                }
            }
            ACTION_APPLY -> run(R.string.busy_applying) { connectAndRun("apply") }
            ACTION_RESTORE -> run(R.string.busy_restoring) { connectAndRun("restore") }
            else -> {
                // startForegroundService 로 왔으면 한 번은 startForeground 를 불러야 한다.
                show(working(R.string.busy_cleaning))
                finish(null)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMdns()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- 흐름 ----

    private fun waitForCode(@StringRes error: Int?) {
        WorkState.set(Work.WaitingCode(ready = pairPort > 0, error = error))
        show(codeNotification(error))
        startMdns()
    }

    private fun run(@StringRes text: Int, block: () -> Unit) {
        if (busy) return
        busy = true
        WorkState.set(Work.Busy(text))
        show(working(text))
        worker.execute {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    private fun pairThenApply(code: String) {
        val endpoint = awaitPairingPort(20_000)
        if (endpoint == null) {
            waitForCode(R.string.err_pair_dialog_not_found)
            return
        }
        val paired = try {
            AdbManager.get(this).pair(endpoint.first, endpoint.second, code)
        } catch (t: Throwable) {
            Log.w(TAG, "pair failed", t)
            false
        }
        if (!paired) {
            waitForCode(R.string.err_code_wrong)
            return
        }
        Store(this).paired = true
        stopMdns()
        connectAndRun("apply")
    }

    private fun connectAndRun(op: String) {
        val store = Store(this)
        val adb = AdbManager.get(this)
        try {
            WorkState.set(Work.Busy(R.string.busy_connecting))
            connect(adb, store)

            val sub = Sys.dataSubId()
            if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) throw UserError(getString(R.string.err_no_data_sim))

            val busyText = if (op == "apply") R.string.busy_applying_apn else R.string.busy_restoring
            WorkState.set(Work.Busy(busyText))
            show(working(busyText))
            val args = buildMap {
                put("op", op)
                put("sub", sub.toString())
                if (op == "restore") putAll(store.restoreArgs)
            }
            val command = buildString {
                append("am instrument -w --no-restart")
                args.forEach { (k, v) -> append(" -e ").append(k).append(' ').append(quote(v)) }
                append(' ').append(packageName).append("/.ApnBroker")
            }
            val output = adb.exec(command)
            val result = parseInstrument(output)
            if (result["status"] != "ok") {
                Log.w(TAG, "broker output: $output")
                throw UserError(result["error"] ?: getString(R.string.err_apply_failed, output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()))
            }

            if (op == "apply") store.saveApplied(result) else store.clearApplied()

            // 할 일이 끝났으니 무선 디버깅은 끈다. 이 명령으로 연결도 끊긴다.
            runCatching { adb.exec("settings put global adb_wifi_enabled 0", 5_000) }
            finish(Work.Done(restored = op == "restore"))
        } catch (e: UserError) {
            finish(Work.Failed(e.message.orEmpty()))
        } catch (t: Throwable) {
            Log.e(TAG, "run failed", t)
            finish(Work.Failed(getString(R.string.err_failed, t.message ?: t.javaClass.simpleName)))
        } finally {
            runCatching { adb.disconnect() }
        }
    }

    /**
     * 무선 디버깅에 붙는다. 막 켠 직전에는 지난번 포트가 먼저 잡혀 거절될 수 있어서 몇 번 다시 해 본다.
     */
    private fun connect(adb: AdbManager, store: Store) {
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
                    throw UserError(getString(R.string.err_repair))
                } catch (t: Throwable) {
                    Log.w(TAG, "connect $port failed: ${t.message}")
                    last = t
                }
            }
            Thread.sleep(1_500)
        }
        Log.w(TAG, "connect gave up", last)
        throw UserError(getString(R.string.err_connect))
    }

    /** mDNS 로 무선 디버깅 연결 포트를 찾는다. 처음 찾은 뒤 잠깐 더 기다려 다른 기록도 모은다. */
    private fun discoverConnectPorts(timeoutMs: Long): List<Pair<String, Int>> {
        val found = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val first = java.util.concurrent.CountDownLatch(1)
        val finder = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { host, port ->
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

    private fun finish(result: Work?) {
        stopMdns()
        WorkState.set(result ?: Work.Idle)
        val nm = getSystemService(NotificationManager::class.java)
        // 진행 알림은 지우고, 결과는 다른 번호로 새로 띄운다. 같은 번호를 빠르게 고치면 마지막 갱신이 버려질 수 있다.
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(NOTIFY_ID)
        if (result != null) nm.notify(RESULT_ID, resultNotification(result))
        stopSelf()
    }

    // ---- 페어링 포트 찾기 ----

    private fun startMdns() {
        if (mdns != null) return
        mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (host != null && port > 0) {
                val changed = pairPort != port
                pairHost = host.hostAddress
                pairPort = port
                if (changed && !busy) {
                    WorkState.set(Work.WaitingCode(ready = true))
                    show(codeNotification(null))
                }
            } else {
                pairPort = -1
            }
        }.also { it.start() }
    }

    private fun stopMdns() {
        mdns?.stop()
        mdns = null
    }

    private fun awaitPairingPort(timeoutMs: Long): Pair<String, Int>? {
        startMdnsOnMain()
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val host = pairHost
            val port = pairPort
            if (host != null && port > 0) return host to port
            Thread.sleep(250)
        }
        return null
    }

    private fun startMdnsOnMain() {
        if (mdns == null) android.os.Handler(mainLooper).post { startMdns() }
    }

    // ---- 알림 ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_setup), NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    private fun show(notification: Notification) {
        startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun base(): Notification.Builder = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentIntent(openApp())
        .setOnlyAlertOnce(false)

    private fun codeNotification(@StringRes error: Int?): Notification {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.notif_code_label)).build()
        val reply = PendingIntent.getForegroundService(
            this, 1, Intent(this, AdbService::class.java).setAction(ACTION_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(null, getString(R.string.notif_code_action), reply).addRemoteInput(input).build()
        val text = getString(error ?: if (pairPort > 0) R.string.notif_code_ready else R.string.notif_code_open_dialog)
        return base()
            .also { builder ->
                // 전체 화면 알림으로 지정하면, 폰을 쓰는 중에는 위쪽 알림이 저절로 들어가지 않고 계속 떠 있는다.
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.canUseFullScreenIntent()) builder.setFullScreenIntent(openApp(), true)
            }
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentTitle(getString(if (error != null) R.string.notif_retry_title else R.string.notif_code_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .addAction(action)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_cancel), cancel()).build())
            .build()
    }

    private fun working(@StringRes text: Int): Notification = base()
        .setContentTitle(getString(text))
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .setOngoing(true)
        .build()

    private fun resultNotification(result: Work): Notification = when (result) {
        is Work.Done -> base()
            .setContentTitle(getString(if (result.restored) R.string.notif_restored_title else R.string.notif_done_title))
            .setContentText(doneText(result.restored, short = true))
            .setStyle(Notification.BigTextStyle().bigText(doneText(result.restored, short = false)))
            .setAutoCancel(true)
            .build()
        is Work.Failed -> base()
            .setContentTitle(getString(R.string.notif_failed_title))
            .setContentText(result.message)
            .setStyle(Notification.BigTextStyle().bigText(result.message))
            .setAutoCancel(true)
            .build()
        else -> base().setContentTitle(getString(R.string.app_name)).setAutoCancel(true).build()
    }

    private fun doneText(restored: Boolean, short: Boolean): String {
        if (restored) return getString(R.string.notif_restored_text)
        val r = Store(this).result
        val name = r["name"].orEmpty().ifBlank { r["apn"].orEmpty() }
        val apn = r["apn"].orEmpty()
        if (name.isBlank()) return getString(R.string.notif_hotspot)
        if (short) return getString(R.string.notif_done_short, name)
        val where = if (apn.isNotBlank() && apn != name) "$name($apn)" else name
        return getString(R.string.notif_done_long, where)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(): PendingIntent = PendingIntent.getService(
        this, 2, Intent(this, AdbService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private class UserError(message: String) : Exception(message)

    companion object {
        private const val TAG = "AdbService"
        private const val CHANNEL = "setup"
        private const val NOTIFY_ID = 7
        private const val RESULT_ID = 8
        private const val KEY_CODE = "code"
        const val ACTION_PAIR = "app.tetherfree.PAIR"
        const val ACTION_CODE = "app.tetherfree.CODE"
        const val ACTION_APPLY = "app.tetherfree.APPLY"
        const val ACTION_RESTORE = "app.tetherfree.RESTORE"
        const val ACTION_CANCEL = "app.tetherfree.CANCEL"

        fun start(context: Context, action: String) {
            context.startForegroundService(Intent(context, AdbService::class.java).setAction(action))
        }

        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

        /** `am instrument -w` 출력에서 INSTRUMENTATION_RESULT 줄을 모은다. */
        fun parseInstrument(output: String): Map<String, String> = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("INSTRUMENTATION_RESULT: ") }
            .map { it.removePrefix("INSTRUMENTATION_RESULT: ") }
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            .toMap()
    }
}
