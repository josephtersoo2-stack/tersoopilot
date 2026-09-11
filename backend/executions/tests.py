import datetime
import uuid
from django.contrib.auth.models import User
from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework import status
from devices.models import SavedProfile
from .models import ExecutionLease, ExecutionLeaseStatus
from .services import LeaseService

class ExecutionLeaseTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.user1 = User.objects.create_user(username="device_owner", password="password123")
        self.user2 = User.objects.create_user(username="other_user", password="password123")

        self.profile = SavedProfile.objects.create(
            user=self.user1,
            name="Test Samsung S24",
            brand="Samsung",
            model_name="Galaxy S24",
            model_code="SM-S921B",
            android_version=14,
            soc="Exynos 2400",
            webgl_vendor="ARM",
            webgl_renderer="Mali-G720",
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
            proxy_type="SOCKS5",
            proxy_host="1.2.3.4",
            proxy_port=1080,
            proxy_user="testuser",
            proxy_pass="plainpassword"
        )
        self.client.force_authenticate(user=self.user1)

    def test_acquire_lease_success(self):
        response = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "android_pixel_8_pro",
            "duration_seconds": 60
        }, format="json")

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        data = response.json()
        self.assertEqual(data["status"], "ACTIVE")
        self.assertEqual(data["device_id"], "android_pixel_8_pro")
        self.assertTrue(data["is_valid"])

        lease = ExecutionLease.objects.get(id=data["id"])
        self.assertEqual(lease.profile, self.profile)
        self.assertEqual(lease.status, ExecutionLeaseStatus.ACTIVE)

    def test_duplicate_lease_prevention_conflict(self):
        # Device A acquires lease
        res1 = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "device_A",
            "duration_seconds": 60
        }, format="json")
        self.assertEqual(res1.status_code, status.HTTP_200_OK)

        # Device B attempts to acquire lease on the same profile -> 409 Conflict
        res2 = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "device_B",
            "duration_seconds": 60
        }, format="json")
        self.assertEqual(res2.status_code, status.HTTP_409_CONFLICT)
        self.assertIn("already leased", res2.json()["error"])

    def test_same_device_renews_lease(self):
        res1 = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "device_A",
            "duration_seconds": 60
        }, format="json")
        lease_id = res1.json()["id"]

        # Same device calls acquire again -> reuses and extends lease
        res2 = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "device_A",
            "duration_seconds": 90
        }, format="json")
        self.assertEqual(res2.status_code, status.HTTP_200_OK)
        self.assertEqual(res2.json()["id"], lease_id)

    def test_heartbeat_extends_expiry(self):
        res1 = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "device_heartbeat_test",
            "duration_seconds": 30
        }, format="json")
        lease_id = res1.json()["id"]
        initial_expiry = res1.json()["expires_at"]

        # Send heartbeat to extend
        res2 = self.client.post("/api/executions/lease/heartbeat/", {
            "lease_id": lease_id,
            "device_id": "device_heartbeat_test",
            "extension_seconds": 120
        }, format="json")
        self.assertEqual(res2.status_code, status.HTTP_200_OK)
        new_expiry = res2.json()["expires_at"]
        self.assertTrue(new_expiry > initial_expiry)

    def test_expired_lease_allows_reclaim(self):
        now = timezone.now()
        # Create an expired lease directly
        ExecutionLease.objects.create(
            profile=self.profile,
            device_id="old_stale_device",
            status=ExecutionLeaseStatus.ACTIVE,
            heartbeat_at=now - datetime.timedelta(seconds=120),
            expires_at=now - datetime.timedelta(seconds=60)
        )

        # New device should now succeed because old lease has expired
        response = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "new_fresh_device",
            "duration_seconds": 60
        }, format="json")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.json()["device_id"], "new_fresh_device")

    def test_release_lease_allows_immediate_acquisition(self):
        res1 = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "device_A",
            "duration_seconds": 60
        }, format="json")
        lease_id = res1.json()["id"]

        # Device A releases lease
        rel_resp = self.client.post("/api/executions/lease/release/", {
            "lease_id": lease_id,
            "device_id": "device_A"
        }, format="json")
        self.assertEqual(rel_resp.status_code, status.HTTP_200_OK)

        # Device B can now acquire immediately
        res2 = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "device_B",
            "duration_seconds": 60
        }, format="json")
        self.assertEqual(res2.status_code, status.HTTP_200_OK)
        self.assertEqual(res2.json()["device_id"], "device_B")

    def test_ownership_enforcement(self):
        # Authenticate as user2 who does not own profile
        self.client.force_authenticate(user=self.user2)

        response = self.client.post("/api/executions/lease/acquire/", {
            "profile_id": str(self.profile.id),
            "device_id": "hacker_device",
            "duration_seconds": 60
        }, format="json")
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
