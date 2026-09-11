package com.multibrowser.antidetect.data.db

import com.multibrowser.antidetect.data.model.RecoveryAttemptEntity
import org.junit.Assert.*
import org.junit.Test

class DatabaseMigrationSchemaTest {

    @Test
    fun testDatabaseVersionIs4() {
        assertEquals("Production hardened database schema version must be 4", 4, AppDatabase.DATABASE_VERSION)
    }

    @Test
    fun testMigrationsDefinedInOrder() {
        val migrations = AppDatabase.MIGRATIONS
        assertEquals("Must contain exactly 3 migration definitions (1->2, 2->3, and 3->4)", 3, migrations.size)

        val m12 = migrations[0]
        assertEquals(1, m12.fromVersion)
        assertEquals(2, m12.toVersion)

        val m23 = migrations[1]
        assertEquals(2, m23.fromVersion)
        assertEquals(3, m23.toVersion)

        val m34 = migrations[2]
        assertEquals(3, m34.fromVersion)
        assertEquals(4, m34.toVersion)
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
