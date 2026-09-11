package com.multibrowser.antidetect.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.multibrowser.antidetect.data.model.ActionExecutionEntity
import com.multibrowser.antidetect.data.model.ExecutionCheckpointEntity
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.data.model.RecoveryAttemptEntity
import com.multibrowser.antidetect.data.model.SavedTabEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class DatabaseMigration(
    val fromVersion: Int,
    val toVersion: Int,
    val migrate: (SQLiteDatabase) -> Unit
)

class AppDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    private val profilesFlow = MutableStateFlow<List<ProfileEntity>>(emptyList())

    init {
        refreshProfiles()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS browser_profiles (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT DEFAULT '',
                tag TEXT DEFAULT '',
                brand TEXT NOT NULL,
                modelName TEXT NOT NULL,
                modelCode TEXT NOT NULL,
                androidVersion INTEGER DEFAULT 14,
                userAgent TEXT NOT NULL,
                soc TEXT NOT NULL,
                webGlVendor TEXT NOT NULL,
                webGlRenderer TEXT NOT NULL,
                ramGb INTEGER DEFAULT 8,
                cpuCores INTEGER DEFAULT 8,
                screenWidth INTEGER DEFAULT 384,
                screenHeight INTEGER DEFAULT 854,
                dpr REAL DEFAULT 2.8125,
                proxyType TEXT DEFAULT 'DIRECT',
                proxyHost TEXT DEFAULT '',
                proxyPort INTEGER DEFAULT 0,
                proxyUser TEXT DEFAULT '',
                proxyPass TEXT DEFAULT '',
                timezone TEXT DEFAULT 'Auto',
                language TEXT DEFAULT 'Auto',
                webRtcMode TEXT DEFAULT 'Mdns',
                lastUsedTimestamp INTEGER DEFAULT 0,
                cookieCount INTEGER DEFAULT 0,
                selectedCameraVideoPath TEXT,
                cookiesJson TEXT DEFAULT '[]',
                historyJson TEXT DEFAULT '[]',
                tabsJson TEXT DEFAULT '[]',
                cloudSyncId TEXT DEFAULT '',
                lastSyncedAt INTEGER DEFAULT 0,
                syncVersion INTEGER DEFAULT 1,
                updatedAt INTEGER DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS browser_saved_tabs (
                id TEXT PRIMARY KEY,
                profileId TEXT NOT NULL,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                tabOrder INTEGER DEFAULT 0,
                isCurrentTab INTEGER DEFAULT 0,
                updatedAt INTEGER DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS execution_checkpoints (
                jobId TEXT PRIMARY KEY,
                profileId TEXT NOT NULL,
                currentStateId TEXT NOT NULL,
                executedSteps INTEGER DEFAULT 0,
                lastCommand TEXT DEFAULT '',
                status TEXT DEFAULT 'RUNNING',
                updatedAt INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_checkpoint_profile ON execution_checkpoints(profileId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS action_executions (
                actionId TEXT PRIMARY KEY,
                jobId TEXT NOT NULL,
                stateId TEXT NOT NULL,
                command TEXT NOT NULL,
                outcome TEXT NOT NULL,
                executedAt INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_action_job ON action_executions(jobId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recovery_attempts (
                jobId TEXT NOT NULL,
                failedStep TEXT NOT NULL,
                reason TEXT DEFAULT '',
                attemptCount INTEGER DEFAULT 1,
                lastAttempt INTEGER DEFAULT 0,
                PRIMARY KEY (jobId, failedStep)
            )
            """.trimIndent()
        )

        // Seed default profile
        val defaultProfile = ProfileEntity(
            id = "default_samsung_a54",
            name = "Samsung Galaxy A54",
            description = "Default Verified Android 14 Profile",
            brand = "Samsung",
            modelName = "Galaxy A54 5G",
            modelCode = "SM-A546B",
            androidVersion = 14,
            userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
            soc = "Exynos 1380",
            webGlVendor = "ARM",
            webGlRenderer = "Mali-G68 MP5",
            ramGb = 8,
            cpuCores = 8,
            screenWidth = 412,
            screenHeight = 915,
            dpr = 2.625,
            syncVersion = 1,
            updatedAt = System.currentTimeMillis()
        )
        insertProfileInternal(db, defaultProfile)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading database from schema version $oldVersion to $newVersion")
        for (migration in MIGRATIONS) {
            if (oldVersion < migration.toVersion && newVersion >= migration.toVersion) {
                db.beginTransaction()
                try {
                    Log.i(TAG, "Executing migration: ${migration.fromVersion} -> ${migration.toVersion}")
                    migration.migrate(db)
                    db.setTransactionSuccessful()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed migration ${migration.fromVersion} -> ${migration.toVersion}", e)
                    throw e
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    private fun refreshProfiles() {
        val list = queryAllFromDb()
        profilesFlow.value = list
    }

    private fun queryAllFromDb(): List<ProfileEntity> {
        val list = mutableListOf<ProfileEntity>()
        val db = readableDatabase
        val cursor: Cursor = db.rawQuery(
            "SELECT * FROM browser_profiles ORDER BY lastUsedTimestamp DESC",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToProfile(it))
            }
        }
        return list
    }

    private fun insertProfileInternal(db: SQLiteDatabase, p: ProfileEntity) {
        val values = ContentValues().apply {
            put("id", p.id)
            put("name", p.name)
            put("description", p.description)
            put("tag", p.tag)
            put("brand", p.brand)
            put("modelName", p.modelName)
            put("modelCode", p.modelCode)
            put("androidVersion", p.androidVersion)
            put("userAgent", p.userAgent)
            put("soc", p.soc)
            put("webGlVendor", p.webGlVendor)
            put("webGlRenderer", p.webGlRenderer)
            put("ramGb", p.ramGb)
            put("cpuCores", p.cpuCores)
            put("screenWidth", p.screenWidth)
            put("screenHeight", p.screenHeight)
            put("dpr", p.dpr)
            put("proxyType", p.proxyType)
            put("proxyHost", p.proxyHost)
            put("proxyPort", p.proxyPort)
            put("proxyUser", p.proxyUser)
            put("proxyPass", p.proxyPass)
            put("timezone", p.timezone)
            put("language", p.language)
            put("webRtcMode", p.webRtcMode)
            put("lastUsedTimestamp", p.lastUsedTimestamp)
            put("cookieCount", p.cookieCount)
            put("selectedCameraVideoPath", p.selectedCameraVideoPath)
            put("cookiesJson", p.cookiesJson)
            put("historyJson", p.historyJson)
            put("tabsJson", p.tabsJson)
            put("cloudSyncId", p.cloudSyncId)
            put("lastSyncedAt", p.lastSyncedAt)
            put("syncVersion", p.syncVersion)
            put("updatedAt", if (p.updatedAt > 0L) p.updatedAt else System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            "browser_profiles",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun getColumnString(c: Cursor, colName: String, defaultVal: String = ""): String {
        val idx = c.getColumnIndex(colName)
        return if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else defaultVal
    }

    private fun getColumnLong(c: Cursor, colName: String, defaultVal: Long = 0L): Long {
        val idx = c.getColumnIndex(colName)
        return if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else defaultVal
    }

    private fun getColumnInt(c: Cursor, colName: String, defaultVal: Int = 0): Int {
        val idx = c.getColumnIndex(colName)
        return if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) else defaultVal
    }

    private fun getColumnDouble(c: Cursor, colName: String, defaultVal: Double = 0.0): Double {
        val idx = c.getColumnIndex(colName)
        return if (idx >= 0 && !c.isNull(idx)) c.getDouble(idx) else defaultVal
    }

    private fun cursorToProfile(c: Cursor): ProfileEntity {
        return ProfileEntity(
            id = getColumnString(c, "id"),
            name = getColumnString(c, "name"),
            description = getColumnString(c, "description"),
            tag = getColumnString(c, "tag"),
            brand = getColumnString(c, "brand"),
            modelName = getColumnString(c, "modelName"),
            modelCode = getColumnString(c, "modelCode"),
            androidVersion = getColumnInt(c, "androidVersion", 14),
            userAgent = getColumnString(c, "userAgent"),
            soc = getColumnString(c, "soc"),
            webGlVendor = getColumnString(c, "webGlVendor"),
            webGlRenderer = getColumnString(c, "webGlRenderer"),
            ramGb = getColumnInt(c, "ramGb", 8),
            cpuCores = getColumnInt(c, "cpuCores", 8),
            screenWidth = getColumnInt(c, "screenWidth", 384),
            screenHeight = getColumnInt(c, "screenHeight", 854),
            dpr = getColumnDouble(c, "dpr", 2.8125),
            proxyType = getColumnString(c, "proxyType", "DIRECT"),
            proxyHost = getColumnString(c, "proxyHost"),
            proxyPort = getColumnInt(c, "proxyPort", 0),
            proxyUser = getColumnString(c, "proxyUser"),
            proxyPass = getColumnString(c, "proxyPass"),
            timezone = getColumnString(c, "timezone", "Auto"),
            language = getColumnString(c, "language", "Auto"),
            webRtcMode = getColumnString(c, "webRtcMode", "Mdns"),
            lastUsedTimestamp = getColumnLong(c, "lastUsedTimestamp", 0L),
            cookieCount = getColumnInt(c, "cookieCount", 0),
            selectedCameraVideoPath = if (c.getColumnIndex("selectedCameraVideoPath") >= 0 && !c.isNull(c.getColumnIndex("selectedCameraVideoPath"))) c.getString(c.getColumnIndex("selectedCameraVideoPath")) else null,
            cookiesJson = getColumnString(c, "cookiesJson", "[]"),
            historyJson = getColumnString(c, "historyJson", "[]"),
            tabsJson = getColumnString(c, "tabsJson", "[]"),
            cloudSyncId = getColumnString(c, "cloudSyncId", ""),
            lastSyncedAt = getColumnLong(c, "lastSyncedAt", 0L),
            syncVersion = getColumnInt(c, "syncVersion", 1),
            updatedAt = getColumnLong(c, "updatedAt", 0L)
        )
    }

    val dao: ProfileDao = object : ProfileDao {
        override fun getAllProfiles(): Flow<List<ProfileEntity>> {
            refreshProfiles()
            return profilesFlow.asStateFlow()
        }

        override suspend fun getProfileById(id: String): ProfileEntity? = withContext(Dispatchers.IO) {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM browser_profiles WHERE id = ? LIMIT 1",
                arrayOf(id)
            )
            cursor.use {
                if (it.moveToFirst()) cursorToProfile(it) else null
            }
        }

        override suspend fun insertProfile(profile: ProfileEntity) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            insertProfileInternal(db, profile)
            refreshProfiles()
        }

        override suspend fun insertOrUpdateProfile(profile: ProfileEntity) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            insertProfileInternal(db, profile)
            refreshProfiles()
        }

        override suspend fun deleteProfile(profile: ProfileEntity) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.delete("browser_profiles", "id = ?", arrayOf(profile.id))
            refreshProfiles()
        }

        override suspend fun updateLastUsed(id: String, timestamp: Long) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("lastUsedTimestamp", timestamp)
                put("updatedAt", System.currentTimeMillis())
            }
            db.update("browser_profiles", cv, "id = ?", arrayOf(id))
            refreshProfiles()
        }

        override suspend fun updateCookieCount(id: String, count: Int) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("cookieCount", count)
                put("updatedAt", System.currentTimeMillis())
            }
            db.update("browser_profiles", cv, "id = ?", arrayOf(id))
            refreshProfiles()
        }

        override suspend fun updateSessionData(
            id: String,
            cookiesJson: String,
            historyJson: String,
            tabsJson: String,
            cookieCount: Int
        ) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("cookiesJson", cookiesJson)
                put("historyJson", historyJson)
                put("tabsJson", tabsJson)
                put("cookieCount", cookieCount)
                put("lastUsedTimestamp", System.currentTimeMillis())
                put("updatedAt", System.currentTimeMillis())
            }
            db.update("browser_profiles", cv, "id = ?", arrayOf(id))
            refreshProfiles()
        }

        override suspend fun updateCloudSync(id: String, cloudSyncId: String, lastSyncedAt: Long) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("cloudSyncId", cloudSyncId)
                put("lastSyncedAt", lastSyncedAt)
                put("updatedAt", System.currentTimeMillis())
            }
            db.update("browser_profiles", cv, "id = ?", arrayOf(id))
            refreshProfiles()
        }

        override suspend fun updateProfileWithVersion(profile: ProfileEntity, expectedVersion: Int): Boolean = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val newVersion = expectedVersion + 1
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("name", profile.name)
                put("description", profile.description)
                put("tag", profile.tag)
                put("brand", profile.brand)
                put("modelName", profile.modelName)
                put("modelCode", profile.modelCode)
                put("androidVersion", profile.androidVersion)
                put("userAgent", profile.userAgent)
                put("soc", profile.soc)
                put("webGlVendor", profile.webGlVendor)
                put("webGlRenderer", profile.webGlRenderer)
                put("ramGb", profile.ramGb)
                put("cpuCores", profile.cpuCores)
                put("screenWidth", profile.screenWidth)
                put("screenHeight", profile.screenHeight)
                put("dpr", profile.dpr)
                put("proxyType", profile.proxyType)
                put("proxyHost", profile.proxyHost)
                put("proxyPort", profile.proxyPort)
                put("proxyUser", profile.proxyUser)
                put("proxyPass", profile.proxyPass)
                put("timezone", profile.timezone)
                put("language", profile.language)
                put("webRtcMode", profile.webRtcMode)
                put("cookiesJson", profile.cookiesJson)
                put("historyJson", profile.historyJson)
                put("tabsJson", profile.tabsJson)
                put("cookieCount", profile.cookieCount)
                put("cloudSyncId", profile.cloudSyncId)
                put("lastSyncedAt", profile.lastSyncedAt)
                put("syncVersion", newVersion)
                put("updatedAt", now)
            }
            val rowsAffected = db.update(
                "browser_profiles",
                cv,
                "id = ? AND syncVersion = ?",
                arrayOf(profile.id, expectedVersion.toString())
            )
            if (rowsAffected > 0) {
                refreshProfiles()
                true
            } else {
                false
            }
        }

        override suspend fun getTabsForProfile(profileId: String): List<SavedTabEntity> = withContext(Dispatchers.IO) {
            val list = mutableListOf<SavedTabEntity>()
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM browser_saved_tabs WHERE profileId = ? ORDER BY tabOrder ASC",
                arrayOf(profileId)
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        SavedTabEntity(
                            id = it.getString(it.getColumnIndexOrThrow("id")),
                            profileId = it.getString(it.getColumnIndexOrThrow("profileId")),
                            title = it.getString(it.getColumnIndexOrThrow("title")),
                            url = it.getString(it.getColumnIndexOrThrow("url")),
                            tabOrder = it.getInt(it.getColumnIndexOrThrow("tabOrder")),
                            isCurrentTab = it.getInt(it.getColumnIndexOrThrow("isCurrentTab")) == 1,
                            updatedAt = it.getLong(it.getColumnIndexOrThrow("updatedAt"))
                        )
                    )
                }
            }
            list
        }

        override suspend fun saveTabsForProfile(profileId: String, tabs: List<SavedTabEntity>) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("browser_saved_tabs", "profileId = ?", arrayOf(profileId))
                for ((index, tab) in tabs.withIndex()) {
                    val values = ContentValues().apply {
                        put("id", tab.id)
                        put("profileId", profileId)
                        put("title", tab.title)
                        put("url", tab.url)
                        put("tabOrder", index)
                        put("isCurrentTab", if (tab.isCurrentTab) 1 else 0)
                        put("updatedAt", System.currentTimeMillis())
                    }
                    db.insertWithOnConflict("browser_saved_tabs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        override suspend fun clearTabsForProfile(profileId: String): Unit = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.delete("browser_saved_tabs", "profileId = ?", arrayOf(profileId))
            Unit
        }

        // --- Execution Checkpoints ---

        override suspend fun saveCheckpoint(checkpoint: ExecutionCheckpointEntity) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("jobId", checkpoint.jobId)
                put("profileId", checkpoint.profileId)
                put("currentStateId", checkpoint.currentStateId)
                put("executedSteps", checkpoint.executedSteps)
                put("lastCommand", checkpoint.lastCommand)
                put("status", checkpoint.status)
                put("updatedAt", checkpoint.updatedAt)
            }
            db.insertWithOnConflict("execution_checkpoints", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            Unit
        }

        override suspend fun getCheckpoint(jobId: String): ExecutionCheckpointEntity? = withContext(Dispatchers.IO) {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM execution_checkpoints WHERE jobId = ? LIMIT 1",
                arrayOf(jobId)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    ExecutionCheckpointEntity(
                        jobId = it.getString(it.getColumnIndexOrThrow("jobId")),
                        profileId = it.getString(it.getColumnIndexOrThrow("profileId")),
                        currentStateId = it.getString(it.getColumnIndexOrThrow("currentStateId")),
                        executedSteps = it.getInt(it.getColumnIndexOrThrow("executedSteps")),
                        lastCommand = it.getString(it.getColumnIndexOrThrow("lastCommand")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow("updatedAt"))
                    )
                } else null
            }
        }

        override suspend fun getLatestCheckpointForProfile(profileId: String): ExecutionCheckpointEntity? = withContext(Dispatchers.IO) {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM execution_checkpoints WHERE profileId = ? ORDER BY updatedAt DESC LIMIT 1",
                arrayOf(profileId)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    ExecutionCheckpointEntity(
                        jobId = it.getString(it.getColumnIndexOrThrow("jobId")),
                        profileId = it.getString(it.getColumnIndexOrThrow("profileId")),
                        currentStateId = it.getString(it.getColumnIndexOrThrow("currentStateId")),
                        executedSteps = it.getInt(it.getColumnIndexOrThrow("executedSteps")),
                        lastCommand = it.getString(it.getColumnIndexOrThrow("lastCommand")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow("updatedAt"))
                    )
                } else null
            }
        }

        override suspend fun findActiveCheckpoint(profileId: String): ExecutionCheckpointEntity? = withContext(Dispatchers.IO) {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM execution_checkpoints WHERE profileId = ? AND status = 'RUNNING' ORDER BY updatedAt DESC LIMIT 1",
                arrayOf(profileId)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    ExecutionCheckpointEntity(
                        jobId = it.getString(it.getColumnIndexOrThrow("jobId")),
                        profileId = it.getString(it.getColumnIndexOrThrow("profileId")),
                        currentStateId = it.getString(it.getColumnIndexOrThrow("currentStateId")),
                        executedSteps = it.getInt(it.getColumnIndexOrThrow("executedSteps")),
                        lastCommand = it.getString(it.getColumnIndexOrThrow("lastCommand")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow("updatedAt"))
                    )
                } else null
            }
        }

        override suspend fun updateCheckpointStatus(jobId: String, status: String) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("status", status)
                put("updatedAt", System.currentTimeMillis())
            }
            db.update("execution_checkpoints", cv, "jobId = ?", arrayOf(jobId))
            Unit
        }

        override suspend fun clearCheckpoint(jobId: String) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.delete("execution_checkpoints", "jobId = ?", arrayOf(jobId))
            Unit
        }

        // --- Idempotent Physical Action Tracking ---

        override suspend fun getActionOutcome(actionId: String): String? = withContext(Dispatchers.IO) {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT outcome FROM action_executions WHERE actionId = ? LIMIT 1",
                arrayOf(actionId)
            )
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }

        override suspend fun recordAction(action: ActionExecutionEntity) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("actionId", action.actionId)
                put("jobId", action.jobId)
                put("stateId", action.stateId)
                put("command", action.command)
                put("outcome", action.outcome)
                put("executedAt", action.executedAt)
            }
            db.insertWithOnConflict("action_executions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            Unit
        }

        override suspend fun clearJobActions(jobId: String) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.delete("action_executions", "jobId = ?", arrayOf(jobId))
            Unit
        }

        // --- Recovery Attempts ---

        override suspend fun recordRecoveryAttempt(jobId: String, failedStep: String, reason: String): Int = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val currentCount = getRecoveryAttemptCount(jobId, failedStep)
            val newCount = currentCount + 1
            val cv = ContentValues().apply {
                put("jobId", jobId)
                put("failedStep", failedStep)
                put("reason", reason)
                put("attemptCount", newCount)
                put("lastAttempt", System.currentTimeMillis())
            }
            db.insertWithOnConflict("recovery_attempts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            newCount
        }

        override suspend fun getRecoveryAttemptCount(jobId: String, failedStep: String): Int = withContext(Dispatchers.IO) {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT attemptCount FROM recovery_attempts WHERE jobId = ? AND failedStep = ? LIMIT 1",
                arrayOf(jobId, failedStep)
            )
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        }

        override suspend fun clearRecoveryAttempts(jobId: String) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.delete("recovery_attempts", "jobId = ?", arrayOf(jobId))
            Unit
        }
    }

    fun profileDao(): ProfileDao = dao

    companion object {
        const val DATABASE_NAME = "antidetect_browser.db"
        const val DATABASE_VERSION = 4
        private const val TAG = "AppDatabase"

        private fun safelyAddColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
            try {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
            } catch (_: Exception) {
                // Column already exists
            }
        }

        val MIGRATIONS = listOf(
            DatabaseMigration(1, 2) { db ->
                safelyAddColumn(db, "browser_profiles", "cookiesJson", "TEXT DEFAULT '[]'")
                safelyAddColumn(db, "browser_profiles", "historyJson", "TEXT DEFAULT '[]'")
                safelyAddColumn(db, "browser_profiles", "tabsJson", "TEXT DEFAULT '[]'")
                safelyAddColumn(db, "browser_profiles", "cloudSyncId", "TEXT DEFAULT ''")
                safelyAddColumn(db, "browser_profiles", "lastSyncedAt", "INTEGER DEFAULT 0")
            },
            DatabaseMigration(2, 3) { db ->
                safelyAddColumn(db, "browser_profiles", "syncVersion", "INTEGER DEFAULT 1")
                safelyAddColumn(db, "browser_profiles", "updatedAt", "INTEGER DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS execution_checkpoints (
                        jobId TEXT PRIMARY KEY,
                        profileId TEXT NOT NULL,
                        currentStateId TEXT NOT NULL,
                        executedSteps INTEGER DEFAULT 0,
                        lastCommand TEXT DEFAULT '',
                        updatedAt INTEGER DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_checkpoint_profile ON execution_checkpoints(profileId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recovery_attempts (
                        jobId TEXT NOT NULL,
                        failedStep TEXT NOT NULL,
                        reason TEXT DEFAULT '',
                        attemptCount INTEGER DEFAULT 1,
                        lastAttempt INTEGER DEFAULT 0,
                        PRIMARY KEY (jobId, failedStep)
                    )
                    """.trimIndent()
                )
            },
            DatabaseMigration(3, 4) { db ->
                safelyAddColumn(db, "execution_checkpoints", "status", "TEXT DEFAULT 'RUNNING'")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS action_executions (
                        actionId TEXT PRIMARY KEY,
                        jobId TEXT NOT NULL,
                        stateId TEXT NOT NULL,
                        command TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        executedAt INTEGER DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_action_job ON action_executions(jobId)")
            }
        )

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = AppDatabase(context)
                INSTANCE = instance
                instance
            }
        }
    }
}
