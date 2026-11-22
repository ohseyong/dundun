package app.tetherfree

import android.Manifest
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log

/** 사용자가 승인한 무선 디버깅 세션에서 APN 작업을 실행한다. */
class ApnBroker : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val out = Bundle()
        var automation: UiAutomation? = null
        try {
            automation = getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
            automation.adoptShellPermissionIdentity(
                Manifest.permission.WRITE_APN_SETTINGS,
                "android.permission.READ_PRIVILEGED_PHONE_STATE",
            )
            val rows = TelephonyApns(targetContext)
            val journal = ApnJournalStore(targetContext)
            val store = Store(targetContext)
            val recovery = ApnRecovery(rows, journal)
            when (arguments?.getString("op")) {
                "apply" -> {
                    check(store.legacyResult.isEmpty()) { targetContext.getString(R.string.broker_restore_legacy_first) }
                    applyAll(rows, recovery, out)
                }
                "restore" -> {
                    val legacy = store.legacyResult
                    if (legacy.isNotEmpty()) LegacyApnRestore(rows).restore(legacy, activeSims().map { it.subscriptionId })
                    val failures = recovery.restore()
                    failures.forEach { Log.w(TAG, "APN restore failed", it) }
                    check(failures.isEmpty()) { targetContext.getString(R.string.broker_restore_incomplete) }
                    store.clearResult()
                    out.putString("status", "ok")
                }
                else -> error("Unknown APN operation")
            }
        } catch (error: Exception) {
            Log.w(TAG, "APN operation failed", error)
            out.putString("status", "error")
            out.putString("error", when (error) {
                is ApnConflict -> targetContext.getString(R.string.broker_restore_incomplete)
                else -> (error.message ?: targetContext.getString(R.string.err_apply_failed)).replace('\n', ' ')
            })
        } finally {
            runCatching { automation?.dropShellPermissionIdentity() }
        }
        finish(0, out)
    }

    private fun applyAll(rows: TelephonyApns, recovery: ApnRecovery, out: Bundle) {
        val sims = activeSims()
        check(sims.isNotEmpty()) { targetContext.getString(R.string.broker_no_sub) }
        // Resolve all subscriptions before changing shared APN rows.
        val candidates = sims.map { sim -> runCatching { rows.current(sim.subscriptionId) } }
        val unreadableSim = candidates.any { it.isFailure }
        val ambiguousIds = candidates.flatMap { candidate ->
            candidate.getOrNull()?.takeIf { it.selected == null }?.ids.orEmpty()
        }.toSet()
        val changes = mutableMapOf<Long, Result<ApnRow>>()
        val results = sims.zip(candidates).map { (sim, candidate) ->
            val sub = sim.subscriptionId
            val one = Bundle().apply {
                putString("sim_slot", (sim.simSlotIndex + 1).toString())
                putString("sim_carrier", sim.carrierName?.toString().orEmpty())
            }
            val selection = candidate.getOrNull()?.selected
            val result = runCatching {
                val row = selection ?: throw ApnConflict()
                if (!supportsDun(row.row.type) && (unreadableSim || row.row.id in ambiguousIds)) throw ApnConflict()
                val applied = changes.getOrPut(row.row.id) { runCatching { recovery.apply(row.row) } }
                rows.describe(row, applied.getOrThrow(), one)
            }
            val success = result.isSuccess
            one.putString("status", if (success) "ok" else "error")
            if (!success) {
                Log.w(TAG, "APN apply failed for subscription $sub", result.exceptionOrNull())
                val reason = when {
                    selection == null -> R.string.broker_no_internet_apn
                    unreadableSim -> R.string.broker_sim_permission
                    selection.row.id in ambiguousIds -> R.string.broker_no_internet_apn
                    else -> R.string.broker_dun_failed
                }
                one.putString("error", targetContext.getString(reason))
            }
            for (key in one.keySet()) out.putString("$sub.$key", one.getString(key))
            success
        }
        out.putString("format", "2")
        out.putString("subs", sims.joinToString(",") { it.subscriptionId.toString() })
        out.putString("status", when {
            results.all { it } -> "ok"
            results.any { it } -> "partial"
            else -> "error"
        })
        if (results.none { it }) out.putString("error", targetContext.getString(R.string.broker_dun_failed))
    }

    private fun activeSims(): List<SubscriptionInfo> = try {
        targetContext.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }.distinctBy { it.subscriptionId }
    } catch (error: SecurityException) {
        throw IllegalStateException(targetContext.getString(R.string.broker_sim_permission), error)
    }

    companion object {
        private const val TAG = "ApnBroker"
    }
}
