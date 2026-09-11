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

    def test_lease_execution_onetoone_relation_and_cascade(self):
        from executions.models import Execution, ExecutionStatus
        from automation.models import AutomationTask
        task = AutomationTask.objects.create(name="Lease Task")
        execution = Execution.objects.create(
            task=task,
            profile=self.profile,
            status=ExecutionStatus.PENDING
        )
        lease = ExecutionLease.objects.create(
            profile=self.profile,
            execution=execution,
            device_id="device_cascade_test",
            status=ExecutionLeaseStatus.ACTIVE,
            expires_at=timezone.now() + datetime.timedelta(seconds=60)
        )
        self.assertEqual(lease.execution, execution)
        self.assertEqual(execution.lease, lease)

        # Deleting execution cascades to lease
        execution.delete()
        self.assertFalse(ExecutionLease.objects.filter(id=lease.id).exists())


class DAGValidatorTests(TestCase):
    def test_valid_dag_passes(self):
        from automation.validator import DAGValidator
        dag = {
            "entry_state": "start",
            "states": {
                "start": {
                    "command": "NAVIGATE",
                    "params": {"url": "https://example.com"},
                    "transitions": {"SUCCESS": "read", "FAILURE": "exit"}
                },
                "read": {
                    "command": "WAIT",
                    "params": {"seconds": 5},
                    "transitions": {"SUCCESS": "exit", "FAILURE": "exit"}
                },
                "exit": {
                    "command": "TERMINATE",
                    "params": {},
                    "transitions": {}
                }
            }
        }
        self.assertTrue(DAGValidator.validate(dag))

    def test_dag_missing_entry_state_fails(self):
        from automation.validator import DAGValidator, DAGValidationError
        dag = {
            "entry_state": "non_existent",
            "states": {
                "start": {"command": "NAVIGATE", "transitions": {"SUCCESS": "exit"}},
                "exit": {"command": "TERMINATE"}
            }
        }
        with self.assertRaises(DAGValidationError):
            DAGValidator.validate(dag)

    def test_dag_invalid_transition_target_fails(self):
        from automation.validator import DAGValidator, DAGValidationError
        dag = {
            "entry_state": "start",
            "states": {
                "start": {"command": "NAVIGATE", "transitions": {"SUCCESS": "ghost_node"}},
                "exit": {"command": "TERMINATE"}
            }
        }
        with self.assertRaises(DAGValidationError):
            DAGValidator.validate(dag)

    def test_dag_exceeding_max_states_fails(self):
        from automation.validator import DAGValidator, DAGValidationError
        states = {f"node_{i}": {"command": "WAIT", "transitions": {"SUCCESS": f"node_{i+1}"}} for i in range(502)}
        states["node_502"] = {"command": "TERMINATE", "transitions": {}}
        dag = {"entry_state": "node_0", "states": states}
        with self.assertRaises(DAGValidationError):
            DAGValidator.validate(dag)

    def test_dag_unreachable_terminal_fails(self):
        from automation.validator import DAGValidator, DAGValidationError
        dag = {
            "entry_state": "loop1",
            "states": {
                "loop1": {"command": "WAIT", "transitions": {"SUCCESS": "loop2"}},
                "loop2": {"command": "WAIT", "transitions": {"SUCCESS": "loop1"}},
                "isolated_exit": {"command": "TERMINATE", "transitions": {}}
            }
        }
        with self.assertRaises(DAGValidationError):
            DAGValidator.validate(dag)


class ExecutionServiceTests(TestCase):
    def setUp(self):
        from automation.models import AutomationTask, TaskExecutionQueue, ProfilePersona
        self.user = User.objects.create_user(username="exec_user", password="password123")
        self.profile = SavedProfile.objects.create(
            user=self.user,
            name="Execution Test Profile",
            brand="Google",
            model_name="Pixel 8 Pro",
            user_agent="Mozilla/5.0"
        )
        self.task = AutomationTask.objects.create(name="Test Automation Task", category="WARMING")
        self.compiled_dag = {
            "entry_state": "start",
            "states": {
                "start": {
                    "command": "NAVIGATE",
                    "params": {"url": "https://example.com"},
                    "transitions": {"SUCCESS": "complete", "FAILURE": "exit"}
                },
                "complete": {
                    "command": "COMPLETE",
                    "params": {"increment_trust_score": 5},
                    "transitions": {"SUCCESS": "exit", "FAILURE": "exit"}
                },
                "exit": {
                    "command": "TERMINATE",
                    "params": {},
                    "transitions": {}
                }
            }
        }
        self.job = TaskExecutionQueue.objects.create(
            task=self.task,
            profile=self.profile,
            status=TaskExecutionQueue.ExecutionStatus.PENDING,
            entry_state_id="start",
            current_state_id="start",
            compiled_dag=self.compiled_dag
        )

    def test_poll_claims_and_attaches_lease_and_event(self):
        from .services import ExecutionService, LeaseService
        from .models import ExecutionEvent, ExecutionEventType
        # Acquire lease for device
        lease, _ = LeaseService.acquire_lease(self.user, self.profile.id, "device_poll_1")
        self.assertIsNotNone(lease)

        job = ExecutionService.poll_next_job(self.profile, device_id="device_poll_1")
        self.assertIsNotNone(job)
        self.assertEqual(job.status, "DISPATCHED")

        # Lease is linked to job
        lease.refresh_from_db()
        self.assertEqual(lease.execution_id, job.id)

        # RUN_STARTED event emitted
        event = ExecutionEvent.objects.filter(execution_id=job.id, event_type=ExecutionEventType.RUN_STARTED).first()
        self.assertIsNotNone(event)
        self.assertEqual(event.payload["device_id"], "device_poll_1")

    def test_transition_advances_and_records_event(self):
        from .services import ExecutionService
        from .models import ExecutionEvent, ExecutionEventType
        res = ExecutionService.transition_state(
            job=self.job,
            outcome="SUCCESS",
            context_update={"tab_id": 10},
            transition_id="t-001"
        )
        self.assertEqual(res["status"], "ADVANCED")
        self.assertEqual(res["current_state_id"], "complete")

        self.job.refresh_from_db()
        self.assertEqual(self.job.status, "RUNNING")
        self.assertEqual(self.job.execution_context["tab_id"], 10)

        # Verify STATE_CHANGED event
        event = ExecutionEvent.objects.filter(execution_id=self.job.id, event_type=ExecutionEventType.STATE_CHANGED).first()
        self.assertIsNotNone(event)
        self.assertEqual(event.payload["from_state"], "start")
        self.assertEqual(event.payload["to_state"], "complete")

    def test_transition_idempotency(self):
        from .services import ExecutionService
        # First transition
        res1 = ExecutionService.transition_state(job=self.job, outcome="SUCCESS", transition_id="idemp-1")
        self.assertEqual(res1["current_state_id"], "complete")

        # Second duplicate transition
        res2 = ExecutionService.transition_state(job=self.job, outcome="SUCCESS", transition_id="idemp-1")
        self.assertEqual(res2["current_state_id"], "complete")
        self.job.refresh_from_db()
        self.assertEqual(len(self.job.logs), 1)

    def test_transition_terminal_success_and_trust_increment(self):
        from .services import ExecutionService
        from .models import ExecutionEvent, ExecutionEventType
        # Advance to complete
        ExecutionService.transition_state(job=self.job, outcome="SUCCESS")
        # Advance from complete to exit
        res = ExecutionService.transition_state(job=self.job, outcome="SUCCESS")
        self.assertTrue(res["is_terminal"])

        self.job.refresh_from_db()
        self.assertEqual(self.job.status, "SUCCESS")

        # Persona trust score incremented
        self.profile.persona.refresh_from_db()
        self.assertEqual(self.profile.persona.trust_score, 15)

        # SUCCESS event emitted
        event = ExecutionEvent.objects.filter(execution_id=self.job.id, event_type=ExecutionEventType.SUCCESS).first()
        self.assertIsNotNone(event)

    def test_heartbeat_extends_and_emits_event(self):
        from .services import ExecutionService
        from .models import ExecutionEvent, ExecutionEventType
        self.job.status = "DISPATCHED"
        self.job.save()

        res = ExecutionService.heartbeat(job=self.job)
        self.assertEqual(res["status"], "ALIVE")
        self.assertEqual(res["job_status"], "RUNNING")

        self.job.refresh_from_db()
        self.assertEqual(self.job.status, "RUNNING")
        self.assertIn("last_heartbeat", self.job.execution_context)

        event = ExecutionEvent.objects.filter(execution_id=self.job.id, event_type=ExecutionEventType.HEARTBEAT).first()
        self.assertIsNotNone(event)

    def test_abort_fails_and_releases_lease(self):
        from .services import ExecutionService, LeaseService
        from .models import ExecutionEvent, ExecutionEventType, ExecutionLease
        lease, _ = LeaseService.acquire_lease(self.user, self.profile.id, "abort_device")
        res = ExecutionService.abort(job=self.job, reason="Operator stop")
        self.assertEqual(res["status"], "ABORTED")

        self.job.refresh_from_db()
        self.assertEqual(self.job.status, "FAILED")
        self.assertEqual(self.job.error_message, "Operator stop")

        lease.refresh_from_db()
        self.assertEqual(lease.status, "RELEASED")

        event = ExecutionEvent.objects.filter(execution_id=self.job.id, event_type=ExecutionEventType.ABORTED).first()
        self.assertIsNotNone(event)

    def test_reap_stalled_executions(self):
        from .services import ExecutionService
        from .models import ExecutionEvent, ExecutionEventType
        old_time = (timezone.now() - datetime.timedelta(seconds=120)).isoformat()
        self.job.status = "RUNNING"
        self.job.execution_context = {"last_heartbeat": old_time}
        self.job.save()

        reaped = ExecutionService.reap_stalled_executions(timeout_seconds=60)
        self.assertEqual(reaped, 1)

        self.job.refresh_from_db()
        self.assertEqual(self.job.status, "STALLED")

        event = ExecutionEvent.objects.filter(execution_id=self.job.id, event_type=ExecutionEventType.STALLED).first()
        self.assertIsNotNone(event)


class ExecutionApiTests(TestCase):
    def setUp(self):
        from automation.models import AutomationTask, TaskExecutionQueue
        self.client = APIClient()
        self.user = User.objects.create_user(username="api_tester", password="password123")
        self.client.force_authenticate(user=self.user)
        self.profile = SavedProfile.objects.create(user=self.user, name="Api Profile")
        self.task = AutomationTask.objects.create(name="Api Task")
        self.job = TaskExecutionQueue.objects.create(
            task=self.task,
            profile=self.profile,
            status="RUNNING"
        )

    def test_get_execution_events(self):
        from .services import ExecutionService
        from .models import ExecutionEventType
        ExecutionService.record_event(self.job.id, ExecutionEventType.RUN_STARTED, {"step": 1})
        ExecutionService.record_event(self.job.id, ExecutionEventType.STATE_CHANGED, {"from": "a", "to": "b"})

        response = self.client.get(f"/api/executions/{self.job.id}/events/")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        events = response.json()
        self.assertEqual(len(events), 2)
        self.assertEqual(events[0]["event_type"], "RUN_STARTED")
        self.assertEqual(events[1]["event_type"], "STATE_CHANGED")

    def test_post_reap_stalled_api(self):
        response = self.client.post("/api/executions/reap-stalled/", {"timeout_seconds": 30}, format="json")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn("reaped_count", response.json())
