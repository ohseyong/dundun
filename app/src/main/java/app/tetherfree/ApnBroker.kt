package app.tetherfree

import android.Manifest
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * 무선 디버깅 셸이 `am instrument` 로 띄우는 작업자.
 *
 * 셸이 띄운 instrumentation 은 셸 권한(WRITE_APN_SETTINGS)을 잠깐 빌릴 수 있다.
 * 그 권한으로 지금 쓰는 인터넷 APN을 복사하면서 타입에 dun 을 더하고, 그 APN을 기본으로 고른다.
 * 그러면 핫스팟도 일반 데이터와 같은 APN으로 나간다.
 */
class ApnBroker : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val out = Bundle()
        var automation: UiAutomation? = null
        try {
            val args = arguments ?: Bundle()
            automation = getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
            automation.adoptShellPermissionIdentity(
                Manifest.permission.WRITE_APN_SETTINGS,
                "android.permission.READ_PRIVILEGED_PHONE_STATE",
            )
            when (args.getString("op")) {
                "apply" -> applyAll(args, out)
                "restore" -> restoreAll(args)
                else -> error("unknown op")
            }
            out.putString("status", "ok")
        } catch (t: Throwable) {
            Log.e(TAG, "broker failed", t)
            out.putString("status", "error")
            out.putString("error", (t.message ?: t.javaClass.simpleName).replace('\n', ' '))
        } finally {
            runCatching { automation?.dropShellPermissionIdentity() }
        }
        finish(0, out)
    }

    /**
     * 쓰고 있는 유심 모두에 dun 을 더한다. 듀얼심이면 데이터 유심을 바꿔도 그대로 쓸 수 있게 둘 다 한다.
     * 결과는 유심마다 "<subId>.<키>" 로 남긴다. 되돌릴 때도 유심마다 이 기록으로 돌린다.
     */
    private fun applyAll(args: Bundle, out: Bundle) {
        val subs = activeSubs().ifEmpty { listOfNotNull(args.getString("sub")?.toIntOrNull()) }
        if (subs.isEmpty()) error(targetContext.getString(R.string.broker_no_sub))
        var applied = 0
        var firstError: String? = null
        for (sub in subs) {
            val one = Bundle()
            simInfo(sub, one)
            try {
                ApnOps(targetContext, sub).apply(one)
                one.putString("status", "ok")
                applied++
            } catch (t: Throwable) {
                Log.w(TAG, "apply failed for sub $sub", t)
                val message = (t.message ?: t.javaClass.simpleName).replace('\n', ' ')
                one.putString("status", "error")
                one.putString("error", message)
                if (firstError == null) firstError = message
            }
            for (key in one.keySet()) out.putString("$sub.$key", one.getString(key))
        }
        out.putString("subs", subs.joinToString(","))
        if (applied == 0) error(firstError ?: targetContext.getString(R.string.broker_dun_failed))
    }

    private fun restoreAll(args: Bundle) {
        val subs = args.getString("subs")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        if (subs.isEmpty()) {
            // 1.4.0 까지의 기록은 유심 하나뿐이고 유심 번호가 없다. 그때는 지금 데이터 유심으로 돌린다.
            val sub = args.getString("sub")?.toIntOrNull() ?: error(targetContext.getString(R.string.broker_no_sub))
            ApnOps(targetContext, sub).restore(args)
            return
        }
        for (sub in subs) {
            val one = Bundle()
            for (key in args.keySet()) if (key.startsWith("$sub.")) one.putString(key.removePrefix("$sub."), args.getString(key))
            if (one.getString("status") != "ok" || one.getString("source") == null) continue
            ApnOps(targetContext, sub).restore(one)
        }
    }

    private fun activeSubs(): List<Int> = runCatching {
        targetContext.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList
            ?.sortedBy { it.simSlotIndex }
            ?.map { it.subscriptionId }
    }.getOrNull().orEmpty().distinct()

    private fun simInfo(sub: Int, out: Bundle) {
        runCatching {
            val info = targetContext.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(sub)
            if (info != null) {
                out.putString("sim_slot", (info.simSlotIndex + 1).toString())
                out.putString("sim_carrier", info.carrierName?.toString().orEmpty())
            }
        }
    }

    companion object {
        private const val TAG = "ApnBroker"
    }
}

internal class ApnOps(private val context: Context, private val sub: Int) {
    private val resolver: ContentResolver = context.contentResolver
    private val carriers: Uri = Telephony.Carriers.CONTENT_URI
    private val preferUri: Uri = Uri.parse("content://telephony/carriers/preferapn/subId/$sub")
    private val simUri: Uri = Uri.withAppendedPath(Telephony.Carriers.SIM_APN_URI, sub.toString())

    fun apply(out: Bundle) {
        val prevPref = preferredId()
        val source = prevPref?.let { row(it) }?.takeIf { isInternet(it.getAsString("type")) }
            ?: firstInternetRow()
            ?: error(context.getString(R.string.broker_no_internet_apn))
        val sourceId = source.getAsLong("_id")
        val origType = source.getAsString("type").orEmpty()

        out.putString("source", sourceId.toString())
        out.putString("orig", origType)
        out.putString("prev", prevPref?.toString().orEmpty())
        out.putString("apn", source.getAsString("apn").orEmpty())

        out.putString("old_name", source.getAsString("name").orEmpty())

        if (hasDun(origType)) {
            if (prevPref != sourceId) setPreferred(sourceId)
            out.putString("mode", "already")
            out.putString("target", sourceId.toString())
            describe(sourceId, out)
            return
        }

        val withDun = addDun(origType)

        // 1) 복사본을 새로 넣는다. 안드로이드가 같은 APN으로 보고 원래 줄에 합칠 수도 있다.
        val copy = ContentValues(source).apply {
            remove("_id")
            remove("edited")
            remove("owned_by")
            put("type", withDun)
            put("sub_id", sub)
        }
        var mode = "copy"
        var target = runCatching { resolver.insert(carriers, copy)?.let(ContentUris::parseId) }
            .getOrNull()?.takeIf { it > 0 }
        if (target == null) {
            target = sourceId
            mode = "merge"
        }

        // 2) 그래도 dun 이 안 들어갔으면 원래 줄의 타입을 직접 고친다.
        if (!hasDun(row(target)?.getAsString("type"))) {
            if (mode == "copy" && target != sourceId) delete(target)
            resolver.update(carriers, ContentValues().apply { put("type", withDun) },
                "_id=?", arrayOf(sourceId.toString()))
            target = sourceId
            mode = "update"
        }
        check(hasDun(row(target)?.getAsString("type"))) { context.getString(R.string.broker_dun_failed) }

        if (preferredId() != target) setPreferred(target)
        check(preferredId() == target) { context.getString(R.string.broker_preferred_failed) }

        out.putString("mode", mode)
        out.putString("target", target.toString())
        describe(target, out)
    }

    /** 적용된 APN 을 다시 읽어 화면에 보여 줄 값을 담는다. */
    private fun describe(id: Long, out: Bundle) {
        val r = row(id) ?: return
        fun put(key: String, column: String) = out.putString(key, r.getAsString(column).orEmpty())
        put("name", "name")
        put("apn", "apn")
        put("mcc", "mcc")
        put("mnc", "mnc")
        put("type", "type")
        put("protocol", "protocol")
        put("roaming_protocol", "roaming_protocol")
        put("mvno_type", "mvno_type")
        out.putString("preferred", (preferredId() == id).toString())
    }

    fun restore(args: Bundle) {
        val source = args.getString("source")?.toLongOrNull() ?: error(context.getString(R.string.broker_no_restore))
        val target = args.getString("target")?.toLongOrNull() ?: source
        val prev = args.getString("prev")?.toLongOrNull() ?: source
        when (args.getString("mode")) {
            "copy" -> delete(target)
            "merge", "update" -> resolver.update(carriers,
                ContentValues().apply { put("type", args.getString("orig").orEmpty()) },
                "_id=?", arrayOf(source.toString()))
        }
        if (row(prev) != null) setPreferred(prev)
    }

    private fun row(id: Long): ContentValues? =
        resolver.query(carriers, null, "_id=?", arrayOf(id.toString()), null)?.use { c ->
            if (c.moveToFirst()) ContentValues().also { DatabaseUtils.cursorRowToContentValues(c, it) } else null
        }

    private fun firstInternetRow(): ContentValues? =
        resolver.query(simUri, null, null, null, "_id ASC")?.use { c ->
            while (c.moveToNext()) {
                val cv = ContentValues().also { DatabaseUtils.cursorRowToContentValues(c, it) }
                if (isInternet(cv.getAsString("type"))) return@use cv
            }
            null
        }

    private fun preferredId(): Long? =
        resolver.query(preferUri, arrayOf("_id"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    private fun setPreferred(id: Long) {
        resolver.update(preferUri, ContentValues().apply { put("apn_id", id) }, null, null)
    }

    private fun delete(id: Long) {
        resolver.delete(carriers, "_id=?", arrayOf(id.toString()))
    }

    companion object {
        private fun types(raw: String?) = raw.orEmpty().split(",").map(String::trim).filter(String::isNotEmpty)
        fun hasDun(raw: String?) = types(raw).any { it == "*" || it.equals("dun", true) }
        fun isInternet(raw: String?) = types(raw).let { t -> t.isEmpty() || t.any { it == "*" || it.equals("default", true) } }
        fun addDun(raw: String?) = if (hasDun(raw)) raw.orEmpty() else (types(raw) + "dun").joinToString(",")
    }
}
