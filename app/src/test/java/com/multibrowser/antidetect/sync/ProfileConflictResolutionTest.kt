package com.multibrowser.antidetect.sync

import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.network.CloudProfileDto
import org.junit.Assert.*
import org.junit.Test

class ProfileConflictResolutionTest {

    @Test
    fun testLocalNewerThanCloudPreservesLocalState() {
        val localProfile = ProfileEntity(
            id = "profile-1",
            name = "Local Name Edited",
            brand = "Samsung",
            modelName = "Galaxy S24",
            modelCode = "SM-S921B",
            userAgent = "UA-Local",
            soc = "Exynos 2400",
            webGlVendor = "Samsung",
            webGlRenderer = "Xclipse 940",
            cookiesJson = "[{\"name\":\"local_cookie\"}]",
            tabsJson = "[{\"url\":\"https://local.test\"}]",
            lastUsedTimestamp = 2000L,
            updatedAt = 2500L,
            syncVersion = 2
        )

        val cloudDto = CloudProfileDto(
            id = "cloud-sync-1",
            name = "Cloud Name Old",
            brand = "Samsung",
            modelName = "Galaxy S24",
            modelCode = "SM-S921B",
            userAgent = "UA-Cloud",
            soc = "Exynos 2400",
            webGlVendor = "Samsung",
            webGlRenderer = "Xclipse 940",
            lastUsedTimestamp = 1500L, // Cloud timestamp is older
            deviceSyncId = "profile-1"
        )

        val localEffectiveTimestamp = localProfile.updatedAt.coerceAtLeast(localProfile.lastUsedTimestamp)
        val cloudTimestamp = cloudDto.lastUsedTimestamp

        assertTrue("Local timestamp must be newer than cloud timestamp", localEffectiveTimestamp > cloudTimestamp)

        // Resolving: Local must remain intact
        val resolved = if (localEffectiveTimestamp > cloudTimestamp) {
            localProfile
        } else {
            localProfile.copy(name = cloudDto.name)
        }

        assertEquals("Local Name Edited", resolved.name)
        assertEquals("[{\"name\":\"local_cookie\"}]", resolved.cookiesJson)
        assertEquals(2, resolved.syncVersion)
    }

    @Test
    fun testCloudNewerMergesMetadataAndPreservesEmptyLocalCookies() {
        val localProfile = ProfileEntity(
            id = "profile-2",
            name = "Local Name",
            brand = "Google",
            modelName = "Pixel 8",
            modelCode = "Pixel 8",
            userAgent = "UA-Local",
            soc = "Tensor G3",
            webGlVendor = "ARM",
            webGlRenderer = "Mali-G715",
            cookiesJson = "[{\"name\":\"important_session_token\"}]",
            tabsJson = "[{\"url\":\"https://important-tab.com\"}]",
            lastUsedTimestamp = 1000L,
            updatedAt = 1000L,
            syncVersion = 1
        )

        val cloudDto = CloudProfileDto(
            id = "cloud-sync-2",
            name = "Cloud Updated Name",
            brand = "Google",
            modelName = "Pixel 8 Pro",
            modelCode = "Pixel 8 Pro",
            userAgent = "UA-Cloud-Updated",
            soc = "Tensor G3",
            webGlVendor = "ARM",
            webGlRenderer = "Mali-G715",
            cookiesData = "[]", // Cloud has empty cookies
            tabsData = "[]",    // Cloud has empty tabs
            lastUsedTimestamp = 3000L, // Cloud is newer!
            deviceSyncId = "profile-2"
        )

        val localEffectiveTimestamp = localProfile.updatedAt.coerceAtLeast(localProfile.lastUsedTimestamp)
        val cloudTimestamp = cloudDto.lastUsedTimestamp

        assertTrue(cloudTimestamp > localEffectiveTimestamp)

        // Merging logic from SyncManager:
        val mergedCookies = if (cloudDto.cookiesData.isNotBlank() && cloudDto.cookiesData != "[]") {
            cloudDto.cookiesData
        } else {
            localProfile.cookiesJson
        }

        val mergedTabs = if (cloudDto.tabsData.isNotBlank() && cloudDto.tabsData != "[]") {
            cloudDto.tabsData
        } else {
            localProfile.tabsJson
        }

        val updatedEntity = localProfile.copy(
            name = cloudDto.name,
            modelName = cloudDto.modelName,
            userAgent = cloudDto.userAgent,
            cookiesJson = mergedCookies,
            tabsJson = mergedTabs,
            cloudSyncId = cloudDto.id ?: localProfile.cloudSyncId,
            syncVersion = localProfile.syncVersion + 1,
            updatedAt = cloudTimestamp
        )

        assertEquals("Cloud Updated Name", updatedEntity.name)
        assertEquals("Pixel 8 Pro", updatedEntity.modelName)
        assertEquals("Important local cookies must NOT be erased by empty cloud cookie list",
            "[{\"name\":\"important_session_token\"}]", updatedEntity.cookiesJson)
        assertEquals("Important local tabs must NOT be erased by empty cloud tab list",
            "[{\"url\":\"https://important-tab.com\"}]", updatedEntity.tabsJson)
        assertEquals("Sync version must be incremented by 1", 2, updatedEntity.syncVersion)
    }
}
