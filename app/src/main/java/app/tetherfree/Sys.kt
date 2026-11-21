package app.tetherfree

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** 시스템 상태 읽기와 설정 화면 이동. */
object Sys {
    private const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"

    fun devOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0

    /** null 은 읽을 수 없다는 뜻이다. */
    fun wirelessDebuggingOn(context: Context): Boolean? = try {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) != 0
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    fun wifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    fun dataSubId(): Int = SubscriptionManager.getDefaultDataSubscriptionId()

    fun carrierName(context: Context): String {
        val sub = dataSubId()
        if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return ""
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return ""
        return runCatching { tm.createForSubscriptionId(sub).simOperatorName.orEmpty() }.getOrDefault("")
    }

    /** 휴대전화 정보 화면을 열고 '소프트웨어 정보'를 강조한다. */
    fun openSoftwareInfo(context: Context) = launch(context,
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).putExtra(FRAGMENT_ARG_KEY, "software_info"))

    /** 개발자 옵션을 열고 '무선 디버깅' 줄을 강조한다. */
    fun openWirelessDebugging(context: Context) = launch(context,
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(FRAGMENT_ARG_KEY, "toggle_adb_wireless"))

    fun openWifi(context: Context) = launch(context, Intent(Settings.ACTION_WIFI_SETTINGS))

    fun openNotificationSettings(context: Context) = launch(context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))

    /** 핫스팟 설정. 삼성, AOSP 순서로 시도하고 안 되면 연결 설정을 연다. */
    fun openHotspot(context: Context) {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in candidates) if (launch(context, intent)) return
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
