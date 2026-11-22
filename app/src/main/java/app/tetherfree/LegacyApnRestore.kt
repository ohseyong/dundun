package app.tetherfree

import android.content.ContentValues

/** 호환 경로. 예전 기록과 일치하는 행만 복구한다. */
internal class LegacyApnRestore(private val apns: TelephonyApns) {
    private data class Change(
        val sub: Int,
        val mode: String,
        val source: Long,
        val target: Long,
        val previous: Long?,
        val originalType: String,
        val appliedType: String,
        val metadata: Map<String, String>,
    )

    fun restore(values: Map<String, String>, active: List<Int>) {
        val subs = values["subs"]
        val changes = if (subs.isNullOrBlank()) {
            listOf(readChange(active.singleOrNull() ?: throw ApnConflict(), values))
        } else {
            subs.split(",").mapNotNull { key ->
                val sim = values.filterKeys { it.startsWith("$key.") }.mapKeys { it.key.removePrefix("$key.") }
                if (sim["status"] == "ok") readChange(key.toInt(), sim) else null
            }
        }
        if (changes.isEmpty()) throw ApnConflict()
        changes.forEach { verify(it, active) }
        // Switch preferences back before removing an APN that both SIMs may use.
        changes.forEach { change ->
            if (apns.preferredId(change.sub) == change.target && change.previous != change.target) {
                apns.setPreferred(change.sub, change.previous)
            }
        }
        changes.distinctBy { it.target to it.mode }.forEach { change ->
            when (change.mode) {
                "copy" -> apns.read(change.target)?.let { target ->
                    if (active.any { apns.preferredId(it) == change.target } || target.type != change.appliedType) throw ApnConflict()
                    apns.delete(target)
                }
                "update", "merge" -> {
                    val source = apns.read(change.source) ?: throw ApnConflict()
                    if (source.type.orEmpty() != change.originalType) {
                        if (source.type != change.appliedType) throw ApnConflict()
                        apns.write(source, change.originalType)
                        check(apns.read(change.source)?.type == change.originalType)
                    }
                }
            }
        }
    }

    private fun verify(change: Change, active: List<Int>) {
        if (change.sub !in active || !apns.belongsTo(change.sub, change.source)) throw ApnConflict()
        val source = apns.row(change.source) ?: throw ApnConflict()
        if (!matches(source, change.metadata)) throw ApnConflict()
        val target = apns.row(change.target)
        if (change.mode == "copy") {
            if (source.getAsString("type").orEmpty() != change.originalType) throw ApnConflict()
            if (target != null && (!matches(target, change.metadata) || target.getAsString("type") != change.appliedType)) {
                throw ApnConflict()
            }
        } else if (target == null || target.getAsString("type").orEmpty() !in setOf(change.originalType, change.appliedType)) {
            throw ApnConflict()
        }
        val preferred = apns.preferredId(change.sub)
        if (preferred == change.target && change.previous != null && !apns.belongsTo(change.sub, change.previous)) throw ApnConflict()
    }

    private fun matches(row: ContentValues, metadata: Map<String, String>): Boolean =
        listOf("apn", "name", "mcc", "mnc").all { key -> row.getAsString(key).orEmpty() == metadata[key].orEmpty() }

    private fun readChange(sub: Int, values: Map<String, String>): Change {
        val mode = values["mode"]?.takeIf { it in setOf("copy", "update", "merge", "already") } ?: throw ApnConflict()
        val source = values["source"]?.toLongOrNull() ?: throw ApnConflict()
        val target = values["target"]?.toLongOrNull() ?: throw ApnConflict()
        if ((mode == "copy") == (source == target) || values["apn"].isNullOrBlank() || !values.containsKey("type")) {
            throw ApnConflict()
        }
        return Change(sub, mode, source, target, values["prev"]?.toLongOrNull(), values["orig"].orEmpty(), values.getValue("type"), values)
    }
}
