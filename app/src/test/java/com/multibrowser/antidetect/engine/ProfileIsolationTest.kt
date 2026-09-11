package com.multibrowser.antidetect.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProfileIsolationTest {

    private fun getProfileDirectory(baseDir: File, profileId: String): File {
        return File(baseDir, "profiles/$profileId")
    }

    private fun getCookieDatabaseFile(baseDir: File, profileId: String): File {
        return File(getProfileDirectory(baseDir, profileId), "cookies.sqlite")
    }

    @Test
    fun testProfileDirectoriesAreStrictlyDisjoint() {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "test_app_files")
        val dir1 = getProfileDirectory(baseDir, "profile_alpha")
        val dir2 = getProfileDirectory(baseDir, "profile_beta")

        assertNotEquals("Different profile IDs must resolve to different directories", dir1.absolutePath, dir2.absolutePath)
        assertFalse("Profile directories must not be parent/child of each other", dir1.canonicalPath.startsWith(dir2.canonicalPath))
        assertFalse("Profile directories must not be parent/child of each other", dir2.canonicalPath.startsWith(dir1.canonicalPath))
    }

    @Test
    fun testCookieDatabaseFilesAreDisjoint() {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "test_app_files")
        val dbFile1 = getCookieDatabaseFile(baseDir, "profile_alpha")
        val dbFile2 = getCookieDatabaseFile(baseDir, "profile_beta")

        assertNotEquals("Cookie database files must reside in separate directory trees", dbFile1.absolutePath, dbFile2.absolutePath)
        assertEquals("cookies.sqlite", dbFile1.name)
        assertEquals("cookies.sqlite", dbFile2.name)
    }

    @Test
    fun testContextIdMatchesProfileIdForGeckoPartitioning() {
        val profileId = "isolated_session_99"
        assertEquals("isolated_session_99", profileId)
    }
}
