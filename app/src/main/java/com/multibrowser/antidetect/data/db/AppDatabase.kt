package com.multibrowser.antidetect.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.data.model.SavedTabEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AppDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "antidetect_browser.db",
    null,
    2
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
                lastSyncedAt INTEGER DEFAULT 0
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

        // Seed initial preset profile
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
            dpr = 2.625
        )
        insertProfileInternal(db, defaultProfile)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
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

        // Ensure new columns exist in existing database without breaking user data
        safelyAddColumn(db, "browser_profiles", "cookiesJson", "TEXT DEFAULT '[]'")
        safelyAddColumn(db, "browser_profiles", "historyJson", "TEXT DEFAULT '[]'")
        safelyAddColumn(db, "browser_profiles", "tabsJson", "TEXT DEFAULT '[]'")
        safelyAddColumn(db, "browser_profiles", "cloudSyncId", "TEXT DEFAULT ''")
        safelyAddColumn(db, "browser_profiles", "lastSyncedAt", "INTEGER DEFAULT 0")
    }

    private fun safelyAddColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
        try {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        } catch (_: Exception) {
            // Column already exists
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            safelyAddColumn(db, "browser_profiles", "cookiesJson", "TEXT DEFAULT '[]'")
            safelyAddColumn(db, "browser_profiles", "historyJson", "TEXT DEFAULT '[]'")
            safelyAddColumn(db, "browser_profiles", "tabsJson", "TEXT DEFAULT '[]'")
            safelyAddColumn(db, "browser_profiles", "cloudSyncId", "TEXT DEFAULT ''")
            safelyAddColumn(db, "browser_profiles", "lastSyncedAt", "INTEGER DEFAULT 0")
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
            lastSyncedAt = getColumnLong(c, "lastSyncedAt", 0L)
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
            }
            db.update("browser_profiles", cv, "id = ?", arrayOf(id))
            refreshProfiles()
        }

        override suspend fun updateCookieCount(id: String, count: Int) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("cookieCount", count)
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
            }
            db.update("browser_profiles", cv, "id = ?", arrayOf(id))
            refreshProfiles()
        }

        override suspend fun updateCloudSync(id: String, cloudSyncId: String, lastSyncedAt: Long) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("cloudSyncId", cloudSyncId)
                put("lastSyncedAt", lastSyncedAt)
            }
            db.update("browser_profiles", cv, "id = ?", arrayOf(id))
            refreshProfiles()
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
    }

    fun profileDao(): ProfileDao = dao

    companion object {
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
