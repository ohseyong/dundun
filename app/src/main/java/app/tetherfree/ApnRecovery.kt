package app.tetherfree

internal data class ApnRow(
    val id: Long,
    val identity: String,
    val type: String?,
    val edited: Int?,
)

internal data class ApnBackup(val original: ApnRow, val appliedType: String)

internal interface ApnRows {
    fun read(id: Long): ApnRow?
    fun write(expected: ApnRow, type: String?, original: ApnRow? = null)
}

internal interface ApnJournal {
    fun entries(): List<ApnBackup>
    fun save(backup: ApnBackup)
    fun remove(id: Long)
}

internal class ApnConflict : IllegalStateException()

/** 기록을 디스크에 먼저 남기고, 실제 APN을 다시 읽어서 작업 결과를 확인한다. */
internal class ApnRecovery(private val rows: ApnRows, private val journal: ApnJournal) {
    fun apply(row: ApnRow): ApnRow {
        val current = readSameRow(row)
        if (current.type != row.type && !supportsDun(current.type)) throw ApnConflict()
        val existing = journal.entries().firstOrNull { it.original.id == row.id }
        if (existing != null) {
            checkOwnedType(current, existing)
            if (current.type == existing.appliedType) return current
        }
        if (supportsDun(current.type)) return current

        val backup = existing ?: ApnBackup(current, (types(current.type) + "dun").joinToString(","))
        if (existing == null) journal.save(backup)
        try {
            rows.write(current, backup.appliedType)
            return readSameRow(row).also { check(it.type == backup.appliedType) }
        } catch (error: Exception) {
            // Reapplying must retain the first snapshot, even if this attempt fails.
            runCatching { undo(backup, remove = existing == null) }.onFailure(error::addSuppressed)
            throw error
        }
    }

    fun restore(): List<Exception> = journal.entries().mapNotNull { backup ->
        try {
            undo(backup, remove = true)
            null
        } catch (error: Exception) {
            error
        }
    }

    private fun undo(backup: ApnBackup, remove: Boolean) {
        val original = backup.original
        val current = readSameRow(original)
        checkOwnedType(current, backup)
        if (current.type != original.type || current.edited != original.edited) {
            rows.write(current, original.type, original)
        }
        val restored = readSameRow(original)
        check(restored.type == original.type && restored.edited == original.edited)
        if (remove) journal.remove(original.id)
    }

    private fun readSameRow(expected: ApnRow): ApnRow = rows.read(expected.id)
        ?.takeIf { it.identity == expected.identity } ?: throw ApnConflict()

    private fun checkOwnedType(row: ApnRow, backup: ApnBackup) {
        if (row.identity != backup.original.identity ||
            (row.type != backup.original.type && row.type != backup.appliedType)
        ) throw ApnConflict()
    }
}

internal fun types(raw: String?) = raw.orEmpty().split(",").map(String::trim).filter(String::isNotEmpty)

// Android interprets an unset APN type as a wildcard, including DEFAULT and DUN.
internal fun supportsDun(raw: String?) = types(raw).let { it.isEmpty() || it.any { type -> type == "*" || type.equals("dun", true) } }
internal fun supportsInternet(raw: String?) = types(raw).let { it.isEmpty() || it.any { type -> type == "*" || type.equals("default", true) } }
