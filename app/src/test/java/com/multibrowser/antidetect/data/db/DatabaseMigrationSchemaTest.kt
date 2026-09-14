package com.multibrowser.antidetect.data.db

import com.multibrowser.antidetect.data.model.RecoveryAttemptEntity
import org.junit.Assert.*
import org.junit.Test

class DatabaseMigrationSchemaTest {

    @Test
    fun testDatabaseVersionIs5() {
        assertEquals("Production hardened database schema version must be 5", 5, AppDatabase.DATABASE_VERSION)
    }

    @Test
    fun testMigrationsDefinedInOrder() {
        val migrations = AppDatabase.MIGRATIONS
        assertEquals("Must contain exactly 4 migration definitions (1->2, 2->3, 3->4, and 4->5)", 4, migrations.size)

        val m12 = migrations[0]
        assertEquals(1, m12.fromVersion)
        assertEquals(2, m12.toVersion)

        val m23 = migrations[1]
        assertEquals(2, m23.fromVersion)
        assertEquals(3, m23.toVersion)

        val m34 = migrations[2]
        assertEquals(3, m34.fromVersion)
        assertEquals(4, m34.toVersion)

        val m45 = migrations[3]
        assertEquals(4, m45.fromVersion)
        assertEquals(5, m45.toVersion)
    }

    @Test
    fun testRecoveryAttemptEntityStructure() {
        val attempt = RecoveryAttemptEntity(
            jobId = "job-rec-1",
            failedStep = "click_button",
            reason = "Scroll sweep timed out",
            attemptCount = 2,
            lastAttempt = 5000L
        )

        assertEquals("job-rec-1", attempt.jobId)
        assertEquals("click_button", attempt.failedStep)
        assertEquals("Scroll sweep timed out", attempt.reason)
        assertEquals(2, attempt.attemptCount)
        assertEquals(5000L, attempt.lastAttempt)
    }
}
