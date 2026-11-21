package app.tetherfree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * GitHub 릴리스(ohseyong/tether-free)에서 새 버전을 받아 설치한다.
 * 받은 APK는 지금 설치된 앱과 같은 키로 서명됐는지, 버전이 더 높은지 확인한 뒤에만 설치한다.
 */
object Updater {
    const val REPO = "ohseyong/dundun"
    private const val TAG = "Updater"
    private const val CHANNEL = "update"
    private const val NOTIFY_ID = 21

    data class Release(val version: String, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object Latest : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class NeedPermission(val release: Release) : State
        data object Installing : State
        data object Failed : State
    }

    private val state = MutableStateFlow<State>(State.Idle)
    val flow: StateFlow<State> = state
    private val lock = Mutex()

    /** 화면이 떠 있는지. 설치 확인 창을 바로 띄울지, 알림으로 보낼지 정한다. */
    @Volatile var foreground = false

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** "1.10.0" > "1.9.2" 처럼 숫자 단위로 비교한다. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.trim().removePrefix("v").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.trim().removePrefix("v").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 최신 릴리스를 묻는다. 새 버전이 아니면 null. */
    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        state.value = State.Checking
        try {
            val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DunDun-Updater")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")
            var apk: Release? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apk = Release(version, a.getString("browser_download_url"), a.optLong("size"))
                    break
                }
            }
            val newer = apk?.takeIf { isNewer(version, currentVersion(context)) }
            state.value = if (newer != null) State.Available(newer) else State.Latest
            newer
        } catch (t: Throwable) {
            Log.w(TAG, "check failed", t)
            state.value = State.Failed
            null
        }
    }

    /** 받아서 설치까지. 설치 허용이 없으면 NeedPermission 으로 멈춘다. */
    suspend fun downloadAndInstall(context: Context, release: Release) = lock.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (!app.packageManager.canRequestPackageInstalls()) {
                state.value = State.NeedPermission(release)
                return@withContext
            }
            try {
                val file = download(app, release)
                verify(app, file)
                state.value = State.Installing
                install(app, file)
            } catch (t: Throwable) {
                Log.w(TAG, "update failed", t)
                state.value = State.Failed
            }
        }
    }

    private fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "dundun-${release.version}.apk")
        state.value = State.Downloading(release, 0)
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DunDun-Updater")
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) {
                        last = pct
                        state.value = State.Downloading(release, pct)
                    }
                }
            }
        }
        return file
    }

    /** 같은 앱, 같은 서명, 더 높은 버전인지 본다. */
    private fun verify(context: Context, file: File) {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive: PackageInfo = pm.getPackageArchiveInfo(file.path, flags) ?: error("not an APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName) { "other package" }
        check(archive.longVersionCode > installed.longVersionCode) { "not newer" }
        val mine = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        check(mine.isNotEmpty() && mine == theirs) { "signature mismatch" }
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // 이 앱이 마지막으로 설치한 앱이면 확인 창 없이 바로 바뀔 수 있다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    fun markFailed() { state.value = State.Failed }

    // ---- 자동 업데이트 ----

    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork("update")
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork("update", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 확인 창이 필요한데 화면이 없을 때 알림으로 띄운다. */
    fun notifyConfirm(context: Context, confirm: Intent, version: String?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_update), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pending = PendingIntent.getActivity(context, 3, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (version != null) context.getString(R.string.update_notif_title, version)
        else context.getString(R.string.update_button)
        nm.notify(NOTIFY_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.update_notif_body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build())
    }
}

/** 설치 세션 결과. 확인이 필요하면 확인 창을 띄운다. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val version = (Updater.flow.value as? Updater.State.Available)?.release?.version
                if (Updater.foreground) {
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Updater.notifyConfirm(context, confirm, version) }
                } else {
                    Updater.notifyConfirm(context, confirm, version)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                Log.w("InstallReceiver", "install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                Updater.markFailed()
            }
        }
    }
}

/** 하루에 한 번 새 버전을 확인하고, 있으면 받아서 설치한다. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Store(applicationContext).autoUpdate) return Result.success()
        val release = Updater.check(applicationContext) ?: return Result.success()
        Updater.downloadAndInstall(applicationContext, release)
        return Result.success()
    }
}
