package com.multibrowser.antidetect.automation

import com.multibrowser.antidetect.data.model.ProfileEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates strict profile resolution rules for Automation V2:
 * 1. Direct local ID match
 * 2. CloudSyncId match
 * 3. Case-insensitive profile name match
 * 4. NEVER falling back to arbitrary/first profiles in the database
 */
class WorkerProfileResolutionLogicTest {

    private fun resolveFromList(
        profileId: String,
        profileName: String,
        byId: ProfileEntity?,
        allProfiles: List<ProfileEntity>
    ): ProfileEntity? {
        if (byId != null) return byId

        return allProfiles.firstOrNull { it.id == profileId }
            ?: allProfiles.firstOrNull {
                it.cloudSyncId.isNotBlank() && it.cloudSyncId == profileId
            } ?: if (profileName.isNotBlank()) {
                allProfiles.firstOrNull {
                    it.name.equals(profileName, ignoreCase = true)
                }
            } else {
                null
            }
    }

    private fun createDummyProfile(
        id: String,
        name: String,
        cloudSyncId: String = ""
    ): ProfileEntity {
        return ProfileEntity(
            id = id,
            name = name,
            cloudSyncId = cloudSyncId,
            brand = "Samsung",
            modelName = "Galaxy S24",
            modelCode = "SM-S921B",
            androidVersion = 14,
            soc = "Snapdragon 8 Gen 3",
            webGlVendor = "Qualcomm",
            webGlRenderer = "Adreno 750",
            ramGb = 8,
            cpuCores = 8,
            screenWidth = 1080,
            screenHeight = 2340,
            dpr = 3.0,
            userAgent = "Mozilla/5.0",
            proxyType = "DIRECT"
        )
    }

    @Test
    fun testDirectIdMatch() {
        val target = createDummyProfile(id = "p-100", name = "Target Profile")
        val other = createDummyProfile(id = "p-200", name = "Other Profile")

        val result = resolveFromList(
            profileId = "p-100",
            profileName = "",
            byId = target,
            allProfiles = listOf(other, target)
        )
        assertNotNull(result)
        assertEquals("p-100", result?.id)
    }

    @Test
    fun testCloudSyncIdMatch() {
        val first = createDummyProfile(id = "local-first", name = "First Profile")
        val cloudMatched = createDummyProfile(id = "local-target", name = "Cloud Synced", cloudSyncId = "server-uuid-123")

        val result = resolveFromList(
            profileId = "server-uuid-123",
            profileName = "",
            byId = null,
            allProfiles = listOf(first, cloudMatched)
        )
        assertNotNull(result)
        assertEquals("local-target", result?.id)
        assertEquals("server-uuid-123", result?.cloudSyncId)
    }

    @Test
    fun testNameMatchCaseInsensitive() {
        val first = createDummyProfile(id = "p-1", name = "Primary Device")
        val named = createDummyProfile(id = "p-2", name = "Instagram Farm 01")

        val result = resolveFromList(
            profileId = "unknown-uuid",
            profileName = "instagram farm 01",
            byId = null,
            allProfiles = listOf(first, named)
        )
        assertNotNull(result)
        assertEquals("p-2", result?.id)
    }

    @Test
    fun testUnresolvedProfileReturnsNullAndNeverFallsBackToFirstProfile() {
        val firstLocalProfile = createDummyProfile(id = "p-victim", name = "Alice Private Profile")
        val secondLocalProfile = createDummyProfile(id = "p-bob", name = "Bob Profile")

        val result = resolveFromList(
            profileId = "non-existent-server-profile-id",
            profileName = "Non Existent Server Name",
            byId = null,
            allProfiles = listOf(firstLocalProfile, secondLocalProfile)
        )

        // Must be null! If it returned firstLocalProfile, that would violate profile isolation
        assertNull("Unresolved profile must return null to reject execution rather than hijacking another profile", result)
    }
}
