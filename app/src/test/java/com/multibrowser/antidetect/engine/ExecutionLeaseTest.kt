package com.multibrowser.antidetect.engine

import org.junit.Assert.*
import org.junit.Test

class ExecutionLeaseTest {

    private class LeaseManager {
        private val leases = mutableMapOf<String, ExecutionLease>()

        fun acquire(profileId: String, deviceId: String, durationMs: Long = 30_000L, currentTime: Long = System.currentTimeMillis()): ExecutionLease? {
            val existing = leases[profileId]
            if (existing != null && existing.expiresAt > currentTime && existing.deviceId != deviceId) {
                return null // Locked by another device
            }
            val lease = ExecutionLease(
                profileId = profileId,
                deviceId = deviceId,
                acquiredAt = currentTime,
                expiresAt = currentTime + durationMs
            )
            leases[profileId] = lease
            return lease
        }

        fun renew(profileId: String, leaseToken: String, durationMs: Long = 30_000L, currentTime: Long = System.currentTimeMillis()): Boolean {
            val existing = leases[profileId] ?: return false
            if (existing.leaseToken != leaseToken) return false
            leases[profileId] = existing.copy(expiresAt = currentTime + durationMs)
            return true
        }

        fun release(profileId: String, leaseToken: String): Boolean {
            val existing = leases[profileId] ?: return false
            if (existing.leaseToken != leaseToken) return false
            leases.remove(profileId)
            return true
        }

        fun isValid(profileId: String, deviceId: String? = null, currentTime: Long = System.currentTimeMillis()): Boolean {
            val existing = leases[profileId] ?: return false
            if (existing.expiresAt <= currentTime) {
                leases.remove(profileId)
                return false
            }
            if (deviceId != null && existing.deviceId != deviceId) {
                return false
            }
            return true
        }
    }

    @Test
    fun testAcquireLeaseGrantsExclusiveLock() {
        val manager = LeaseManager()
        val now = 100000L

        val leaseA = manager.acquire("profile-1", "device-A", durationMs = 30000L, currentTime = now)
        assertNotNull("Device A should successfully acquire lease", leaseA)
        assertTrue(manager.isValid("profile-1", "device-A", currentTime = now))

        // Device B tries to acquire same profile while lease is active
        val leaseB = manager.acquire("profile-1", "device-B", durationMs = 30000L, currentTime = now + 5000L)
        assertNull("Device B must be denied acquisition while Device A holds valid lease", leaseB)
    }

    @Test
    fun testLeaseExpiresAndAllowsNewOwner() {
        val manager = LeaseManager()
        val now = 100000L

        val leaseA = manager.acquire("profile-2", "device-A", durationMs = 10000L, currentTime = now)
        assertNotNull(leaseA)

        // Advance time past expiration
        val pastExpiry = now + 15000L
        assertFalse("Lease should no longer be valid after TTL expiry", manager.isValid("profile-2", "device-A", currentTime = pastExpiry))

        // Device B can now acquire the expired lease
        val leaseB = manager.acquire("profile-2", "device-B", durationMs = 30000L, currentTime = pastExpiry)
        assertNotNull("Device B should successfully acquire lease after Device A's lease expired", leaseB)
        assertEquals("device-B", leaseB!!.deviceId)
    }

    @Test
    fun testLeaseRenewalExtendsExpiration() {
        val manager = LeaseManager()
        val now = 100000L

        val lease = manager.acquire("profile-3", "device-A", durationMs = 10000L, currentTime = now)
        assertNotNull(lease)

        // Renew at 8s
        val renewed = manager.renew("profile-3", lease!!.leaseToken, durationMs = 20000L, currentTime = now + 8000L)
        assertTrue(renewed)

        // At 15s (past original 10s expiry, but within renewed 28s expiry)
        assertTrue("Lease should remain valid due to renewal", manager.isValid("profile-3", "device-A", currentTime = now + 15000L))
    }

    @Test
    fun testReleaseLeaseFreesProfileImmediately() {
        val manager = LeaseManager()
        val now = 100000L

        val leaseA = manager.acquire("profile-4", "device-A", durationMs = 30000L, currentTime = now)
        assertNotNull(leaseA)

        val released = manager.release("profile-4", leaseA!!.leaseToken)
        assertTrue(released)
        assertFalse(manager.isValid("profile-4", currentTime = now))

        // Device B can acquire immediately
        val leaseB = manager.acquire("profile-4", "device-B", durationMs = 30000L, currentTime = now)
        assertNotNull("Device B should acquire immediately after release", leaseB)
    }
}
