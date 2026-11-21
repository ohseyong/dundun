package app.tetherfree

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 앱이 기억하는 설정 결과. APN 표는 셸 권한 없이는 읽을 수 없어서 적용 결과를 여기 남긴다. */
class Store(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("state", Context.MODE_PRIVATE)

    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        set(v) = prefs.edit().putBoolean("paired", v).apply()

    val applied: Boolean get() = prefs.getBoolean("applied", false)

    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(v) = prefs.edit().putBoolean("auto_update", v).apply()

    /** 마지막 적용 결과. ApnBroker 가 돌려준 값 그대로다. */
    val result: Map<String, String>
        get() = prefs.all.filterKeys { it.startsWith(PREFIX) }
            .mapKeys { it.key.removePrefix(PREFIX) }
            .mapValues { it.value.toString() }

    val appliedAt: Long get() = prefs.getLong("applied_at", 0L)

    /** 되돌리기에 필요한 값. 유심마다 "<subId>.<키>" 이고, 1.4.0 까지의 기록은 접두사가 없다. */
    val restoreArgs: Map<String, String>
        get() = result.filterKeys { key ->
            key == "subs" || key in RESTORE_KEYS || key.substringAfter('.', "") in RESTORE_KEYS + "status"
        }

    /** 유심별 결과. 예전 기록(유심 하나, 접두사 없음)은 한 덩어리로 돌려준다. */
    val simResults: List<Map<String, String>>
        get() {
            val r = result
            val subs = r["subs"]?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            if (subs.isEmpty()) return if (r.isEmpty()) emptyList() else listOf(r)
            return subs.map { sub -> r.filterKeys { it.startsWith("$sub.") }.mapKeys { it.key.removePrefix("$sub.") } }
        }

    fun saveApplied(values: Map<String, String>) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { remove(it) }
            putBoolean("applied", true)
            putLong("applied_at", System.currentTimeMillis())
            values.filterKeys { it != "status" }.forEach { (k, v) -> putString(PREFIX + k, v) }
        }.apply()
    }

    fun clearApplied() {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { remove(it) }
            putBoolean("applied", false)
            remove("applied_at")
        }.apply()
    }

    companion object {
        private const val PREFIX = "r_"
        val RESTORE_KEYS = listOf("mode", "source", "target", "orig", "prev")
    }
}

/** 백그라운드 작업 상태. 화면과 알림이 같이 본다. */
sealed interface Work {
    data object Idle : Work
    /** 페어링 알림을 띄워 두고 코드를 기다리는 중. ready 는 페어링 화면을 찾았는지. */
    data class WaitingCode(val ready: Boolean, @StringRes val error: Int? = null) : Work
    data class Busy(@StringRes val text: Int) : Work
    data class Done(val restored: Boolean) : Work
    data class Failed(val message: String) : Work
}

object WorkState {
    private val state = MutableStateFlow<Work>(Work.Idle)
    val flow: StateFlow<Work> = state
    fun set(work: Work) { state.value = work }
}
