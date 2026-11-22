package app.tetherfree

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import java.security.MessageDigest

internal class TelephonyApns(context: Context) : ApnRows {
    private val resolver = context.contentResolver
    private val carriers = Telephony.Carriers.CONTENT_URI

    override fun read(id: Long): ApnRow? = row(id)?.let(::snapshot)

    override fun write(expected: ApnRow, type: String?, original: ApnRow?) {
        val row = row(expected.id)?.takeIf { snapshot(it) == expected } ?: throw ApnConflict()
        val (where, args) = guard(row)
        val values = ContentValues().apply {
            if (type == null) putNull("type") else put("type", type)
            if (original != null && row.containsKey("edited")) {
                if (original.edited == null) putNull("edited") else put("edited", original.edited)
            }
        }
        if (resolver.update(carriers, values, where, args) != 1) throw ApnConflict()
    }

    data class Selection(val row: ApnRow, val preferred: Boolean, val details: Map<String, String>)

    data class Candidates(val selected: Selection?, val ids: Set<Long>)

    fun current(sub: Int): Candidates {
        val preferred = preferredId(sub)
        val matches = forSub(sub).filter {
            it.getAsInteger("carrier_enabled") != 0 && !it.getAsString("apn").isNullOrBlank() &&
                supportsInternet(it.getAsString("type"))
        }
        val selected = if (preferred == null) matches.singleOrNull() else matches.firstOrNull { it.getAsLong("_id") == preferred }
        val selection = selected?.let { values ->
            Selection(snapshot(values), preferred != null, DISPLAY_FIELDS.associateWith { values.getAsString(it).orEmpty() })
        }
        return Candidates(selection, matches.map { it.getAsLong("_id") }.toSet())
    }

    fun describe(selection: Selection, result: ApnRow, out: Bundle) {
        for ((key, value) in selection.details) out.putString(key, value)
        out.putString("type", result.type.orEmpty())
        out.putString("orig", selection.row.type.orEmpty())
        out.putString("mode", if (selection.row.type == result.type) "already" else "update")
        out.putString("preferred", selection.preferred.toString())
    }

    fun row(id: Long): ContentValues? = query(carriers, "_id=?", arrayOf(id.toString())).singleOrNull()

    fun preferredId(sub: Int): Long? = query(preferredUri(sub), columns = arrayOf("_id"))
        .singleOrNull()?.getAsLong("_id")

    fun setPreferred(sub: Int, id: Long?) {
        resolver.update(preferredUri(sub), ContentValues().apply { put("apn_id", id ?: -1L) }, null, null)
        check(preferredId(sub) == id)
    }

    fun belongsTo(sub: Int, id: Long) = forSub(sub).any { it.getAsLong("_id") == id }

    fun delete(expected: ApnRow) {
        val row = row(expected.id)?.takeIf { snapshot(it) == expected } ?: throw ApnConflict()
        val (where, args) = guard(row)
        resolver.delete(carriers, where, args)
        check(row(expected.id) == null)
    }

    private fun forSub(sub: Int) = query(Uri.withAppendedPath(Telephony.Carriers.SIM_APN_URI, sub.toString()))

    private fun guard(row: ContentValues): Pair<String, Array<String>> {
        val args = mutableListOf<String>()
        val where = (listOf("_id", "type", "edited") + IDENTITY_FIELDS).filter(row::containsKey).joinToString(" AND ") { column ->
            val value = row.getAsString(column)
            if (value == null) "$column IS NULL" else {
                args.add(value)
                "$column=?"
            }
        }
        return where to args.toTypedArray()
    }

    private fun preferredUri(sub: Int): Uri = Uri.parse("content://telephony/carriers/preferapn/subId/$sub")

    private fun query(
        uri: Uri,
        selection: String? = null,
        args: Array<String>? = null,
        columns: Array<String>? = null,
    ): List<ContentValues> = checkNotNull(resolver.query(uri, columns, selection, args, null)).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) })
        }
    }

    private fun snapshot(values: ContentValues): ApnRow {
        val digest = MessageDigest.getInstance("SHA-256")
        val identity = IDENTITY_FIELDS.joinToString("|") { key ->
            val value = values.getAsString(key)
            if (value == null) "-" else "${value.length}:$value"
        }
        return ApnRow(
            values.getAsLong("_id"), digest.digest(identity.toByteArray()).joinToString("") { "%02x".format(it) },
            values.getAsString("type"), values.getAsInteger("edited"),
        )
    }

    companion object {
        private val DISPLAY_FIELDS = listOf("name", "apn", "mcc", "mnc", "type", "protocol")
        private val IDENTITY_FIELDS = listOf(
            "name", "apn", "numeric", "mcc", "mnc", "carrier_id", "sub_id", "profile_id", "apn_set_id", "owned_by",
            "mvno_type", "mvno_match_data", "user", "password", "authtype", "protocol", "roaming_protocol", "carrier_enabled",
            "proxy", "port", "mmsc", "mmsproxy", "mmsport", "network_type_bitmask",
        )
    }
}
