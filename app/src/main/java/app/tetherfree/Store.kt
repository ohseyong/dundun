package app.tetherfree

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 앱이 기억하는 설정 결과. APN 표는 셸 권한 없이는 읽을 수 없어서 적용 결과를 여기 남긴다. */
class Store(private val context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("state", Context.MODE_PRIVATE)

    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        set(v) = prefs.edit().putBoolean("paired", v).apply()

    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(v) = prefs.edit().putBoolean("auto_update", v).apply()

    /** 마지막 적용 결과. ApnBroker 가 돌려준 값 그대로다. */
    val result: Map<String, String>
        get() = prefs.all.filterKeys { it.startsWith(PREFIX) }
            .mapKeys { it.key.removePrefix(PREFIX) }
            .mapValues { it.value.toString() }

    val appliedAt: Long get() = prefs.getLong("applied_at", 0L)

    internal val legacyResult: Map<String, String> get() = result.takeUnless { it.containsKey("format") }.orEmpty()
    val canRestore: Boolean get() = ApnJournalStore(context).hasEntries || legacyResult.isNotEmpty()

    /** 유심별 결과. 예전 기록(유심 하나, 접두사 없음)은 한 덩어리로 돌려준다. */
    val simResults: List<Map<String, String>>
        get() {
            val r = result
            val subs = r["subs"]?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            if (subs.isEmpty()) return if (r.isEmpty()) emptyList() else listOf(r)
            return subs.map { sub -> r.filterKeys { it.startsWith("$sub.") }.mapKeys { it.key.removePrefix("$sub.") } }
        }

    fun saveResult(values: Map<String, String>) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { remove(it) }
            remove("applied")
            putLong("applied_at", System.currentTimeMillis())
            values.filterKeys { it != "status" }.forEach { (k, v) -> putString(PREFIX + k, v) }
        }.apply()
    }

    fun clearResult() {
        check(prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { remove(it) }
            remove("applied")
            remove("applied_at")
        }.commit())
    }

    companion object {
        private const val PREFIX = "r_"
    }
}

/** 백그라운드 작업 상태. 화면과 알림이 같이 본다. */
sealed interface Work {
    data object Idle : Work
    /** 페어링 알림을 띄워 두고 코드를 기다리는 중. ready 는 페어링 화면을 찾았는지. */
    data class WaitingCode(val ready: Boolean, @param:StringRes val error: Int? = null) : Work
    data class Busy(@param:StringRes val text: Int) : Work
    data class Done(val restored: Boolean, val partial: Boolean = false) : Work
    data class Failed(val message: String) : Work
    data object Cancelled : Work
    data object Paired : Work
}

object WorkState {
    private val state = MutableStateFlow<Work>(Work.Idle)
    val flow: StateFlow<Work> = state
    fun set(work: Work) { state.value = work }
}
