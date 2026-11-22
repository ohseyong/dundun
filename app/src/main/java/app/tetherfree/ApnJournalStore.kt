package app.tetherfree

import android.content.Context
import org.json.JSONObject

internal class ApnJournalStore(context: Context) : ApnJournal {
    private val prefs = context.applicationContext.getSharedPreferences("apn_recovery", Context.MODE_PRIVATE)

    val hasEntries: Boolean get() = prefs.all.isNotEmpty()

    override fun entries(): List<ApnBackup> = prefs.all.map { (key, value) ->
        val json = JSONObject(value as String)
        val row = ApnRow(
            key.toLong(), json.getString("identity"),
            if (json.isNull("type")) null else json.getString("type"),
            if (json.isNull("edited")) null else json.getInt("edited"),
        )
        ApnBackup(row, json.getString("applied"))
    }

    override fun save(backup: ApnBackup) {
        val row = backup.original
        val json = JSONObject()
            .put("identity", row.identity)
            .put("type", row.type ?: JSONObject.NULL)
            .put("edited", row.edited ?: JSONObject.NULL)
            .put("applied", backup.appliedType)
        check(prefs.edit().putString(row.id.toString(), json.toString()).commit())
    }

    override fun remove(id: Long) {
        check(prefs.edit().remove(id.toString()).commit())
    }
}
