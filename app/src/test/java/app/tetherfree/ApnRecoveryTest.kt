package app.tetherfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApnRecoveryTest {
    private val first = ApnRow(1, "first", "default,mms", 0)
    private val second = ApnRow(2, "second", "default,supl", 0)
    private val journal = MemoryJournal()
    private val rows = FakeRows(first, second)
    private val recovery = ApnRecovery(rows, journal)

    @Test fun emptyAndWildcardTypesKeepExistingInternetCapabilities() {
        listOf(null, "", "*").forEach { type ->
            val original = first.copy(type = type)
            rows.state[first.id] = original
            assertEquals(original, recovery.apply(original))
        }
        assertEquals(0, rows.writes)
        assertTrue(journal.entries().isEmpty())
    }

    @Test fun originalIsJournaledBeforeWritingAndSurvivesRepeatedApply() {
        rows.beforeWrite = { assertEquals(first, journal.entries().single().original) }
        val applied = recovery.apply(first)
        assertEquals("default,mms,dun", applied.type)
        recovery.apply(applied)
        assertEquals(1, rows.writes)
        assertEquals(first, journal.entries().single().original)
        assertTrue(ApnRecovery(rows, journal).restore().isEmpty())
        assertEquals(first, rows.read(first.id))
        assertTrue(journal.entries().isEmpty())
    }

    @Test fun bothSubscriptionsCanUseOneApnWithoutDuplicateChanges() {
        recovery.apply(first)
        recovery.apply(first)
        assertEquals(1, rows.writes)
        assertEquals(1, journal.entries().size)
    }

    @Test fun failedWriteIsRolledBackEvenWhenProviderChangedTheRow() {
        rows.throwAfterWrite = true
        assertThrows(IllegalStateException::class.java) { recovery.apply(first) }
        assertEquals(first, rows.read(first.id))
        assertTrue(journal.entries().isEmpty())
    }

    @Test fun refusedRestoreRetainsTheRecordForRetry() {
        val applied = recovery.apply(first)
        rows.blocked += first.id
        assertEquals(1, recovery.restore().size)
        assertEquals(applied, rows.read(first.id))
        assertEquals(first, journal.entries().single().original)
        rows.blocked.clear()
        assertTrue(recovery.restore().isEmpty())
        assertEquals(first, rows.read(first.id))
    }

    @Test fun partialRestoreRetainsOnlyTheUnverifiedRow() {
        recovery.apply(first)
        recovery.apply(second)
        rows.blocked += first.id
        assertEquals(1, recovery.restore().size)
        assertEquals(second, rows.read(second.id))
        assertEquals(listOf(first), journal.entries().map { it.original })
    }

    @Test fun rowIdReuseOrChangedTypeIsNotOverwritten() {
        recovery.apply(first)
        recovery.apply(second)
        val replacement = first.copy(identity = "new-sim", type = "default,dun")
        val changed = second.copy(type = "default,supl,mms,dun", edited = 1)
        rows.state[first.id] = replacement
        rows.state[second.id] = changed
        assertEquals(2, recovery.restore().size)
        assertEquals(replacement, rows.read(first.id))
        assertEquals(changed, rows.read(second.id))
        assertEquals(2, journal.entries().size)
    }

    @Test fun journalFailurePreventsWriting() {
        journal.blockSave = true
        assertThrows(IllegalStateException::class.java) { recovery.apply(first) }
        assertEquals(0, rows.writes)
    }

    @Test fun concurrentEditIsPreserved() {
        val changed = first.copy(type = "default,xcap")
        rows.beforeWrite = { rows.state[first.id] = changed }
        assertThrows(ApnConflict::class.java) { recovery.apply(first) }
        assertEquals(changed, rows.read(first.id))
        assertEquals(0, rows.writes)
        assertEquals(first, journal.entries().single().original)
    }

    @Test fun interruptionBetweenJournalAndWriteCanBeRecovered() {
        journal.save(ApnBackup(first, "default,mms,dun"))
        assertTrue(recovery.restore().isEmpty())
        assertEquals(0, rows.writes)
        assertFalse(journal.entries().any())
    }

    private class FakeRows(vararg initial: ApnRow) : ApnRows {
        val state = initial.associateBy { it.id }.toMutableMap()
        val blocked = mutableSetOf<Long>()
        var writes = 0
        var throwAfterWrite = false
        var beforeWrite: () -> Unit = {}

        override fun read(id: Long) = state[id]

        override fun write(expected: ApnRow, type: String?, original: ApnRow?) {
            val id = expected.id
            beforeWrite()
            if (id in blocked) return
            if (state[id] != expected) throw ApnConflict()
            writes++
            state[id] = state.getValue(id).copy(type = type, edited = if (original == null) 1 else original.edited)
            if (throwAfterWrite) {
                throwAfterWrite = false
                error("provider failed after write")
            }
        }
    }

    private class MemoryJournal : ApnJournal {
        private val backups = linkedMapOf<Long, ApnBackup>()
        var blockSave = false

        override fun entries() = backups.values.toList()

        override fun save(backup: ApnBackup) {
            check(!blockSave)
            backups[backup.original.id] = backup
        }

        override fun remove(id: Long) { backups.remove(id) }
    }
}
