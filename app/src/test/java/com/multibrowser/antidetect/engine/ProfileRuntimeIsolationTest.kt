package com.multibrowser.antidetect.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileWriter

class ProfileRuntimeIsolationTest {

    @Test
    fun testAtomicConfigurationReplacement() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "mb_atomic_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val targetConfigFile = File(tempDir, "geckoview-config.yaml")

            // Write initial configuration
            targetConfigFile.writeText("initial_config: true\n")
            assertEquals("initial_config: true\n", targetConfigFile.readText())

            // Simulate atomic update
            val newYaml = "env:\n  MOZ_REMOTE_SETTINGS_DEV: \"1\"\nargs:\n  - \"--pref\"\n  - \"privacy.resistFingerprinting=true\"\n"
            val tempFile = File(targetConfigFile.parentFile, "${targetConfigFile.name}.tmp_${System.nanoTime()}")

            FileWriter(tempFile, false).use { it.write(newYaml) }
            assertTrue("Temporary file must exist before rename", tempFile.exists())

            val renameSuccess = tempFile.renameTo(targetConfigFile)
            if (!renameSuccess) {
                tempFile.copyTo(targetConfigFile, overwrite = true)
                tempFile.delete()
            }

            assertFalse("Temporary file must be cleared after atomic replace", tempFile.exists())
            assertTrue("Target config file must exist", targetConfigFile.exists())
            assertEquals(newYaml, targetConfigFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRuntimePoolKeyIsolation() {
        val runtimePool = mutableMapOf<String, String>()

        val profileA = "profile_alpha_1"
        val profileB = "profile_beta_2"

        runtimePool[profileA] = "GeckoRuntime_A"
        runtimePool[profileB] = "GeckoRuntime_B"

        assertEquals("GeckoRuntime_A", runtimePool[profileA])
        assertEquals("GeckoRuntime_B", runtimePool[profileB])
        assertNotEquals(runtimePool[profileA], runtimePool[profileB])

        // Closing profile A should not affect profile B
        runtimePool.remove(profileA)
        assertNull(runtimePool[profileA])
        assertEquals("GeckoRuntime_B", runtimePool[profileB])
    }

    @Test
    fun testCredentialVaultDecryptionOfPlaintext() {
        val plainUser = "socks_user"
        val decrypted = CredentialVault.decrypt(plainUser)
        assertEquals("Plaintext credential should be returned as-is for backward compatibility",
            plainUser, decrypted)
    }
}
