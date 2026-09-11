import uuid
import datetime
import logging
from django.db import transaction
from django.utils import timezone
from devices.models import SavedProfile
from .models import (
    Execution,
    ExecutionStatus,
    ExecutionLease,
    ExecutionLeaseStatus,
    ExecutionEvent,
    ExecutionEventType,
)

logger = logging.getLogger(__name__)

class LeaseService:
    DEFAULT_LEASE_DURATION_SECONDS = 60
    DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30

    @classmethod
    @transaction.atomic
    def acquire_lease(
        cls,
        user,
        profile_id,
        device_id: str,
        duration_seconds: int = DEFAULT_LEASE_DURATION_SECONDS
    ) -> tuple[ExecutionLease | None, str | None]:
        """
        Attempts to acquire an exclusive execution lease for the given profile and device.
        If another device holds an active, unexpired lease, returns an error (409 Conflict).
        """
        device_id = (device_id or "").strip()
        if not device_id:
            return None, "Device ID is required to acquire an execution lease."

        try:
            profile = SavedProfile.objects.select_for_update().get(id=profile_id)
        except (SavedProfile.DoesNotExist, ValueError):
            return None, "Profile not found."

        # Verify user ownership
        if profile.user and profile.user != user:
            return None, "You do not have permission to execute this profile."

        now = timezone.now()

        # Find existing active leases for this profile
        active_leases = ExecutionLease.objects.select_for_update().filter(
            profile=profile,
            status=ExecutionLeaseStatus.ACTIVE
        )

        for lease in active_leases:
            # Check if expired
            if now >= lease.expires_at:
                lease.status = ExecutionLeaseStatus.EXPIRED
                lease.save(update_fields=["status", "updated_at"])
                logger.info(f"Lease {lease.id} expired past timeout ({lease.expires_at})")
            elif lease.device_id == device_id:
                # Same device re-acquiring or renewing
                lease.heartbeat_at = now
                lease.expires_at = now + datetime.timedelta(seconds=duration_seconds)
                lease.save(update_fields=["heartbeat_at", "expires_at", "updated_at"])
                logger.info(f"Renewed execution lease {lease.id} for device {device_id}")
                return lease, None
            else:
                # Different device currently holds active lease!
                remaining = int((lease.expires_at - now).total_seconds())
                msg = (
                    f"Profile '{profile.name}' is already leased by device '{lease.device_id}' "
                    f"(lease expires in {remaining}s)."
                )
                logger.warning(f"Lease conflict on profile {profile.id}: {msg}")
                return None, msg

        # No active lease exists: create new lease
        new_lease = ExecutionLease.objects.create(
            profile=profile,
            device_id=device_id,
            status=ExecutionLeaseStatus.ACTIVE,
            heartbeat_at=now,
            expires_at=now + datetime.timedelta(seconds=duration_seconds)
        )
        logger.info(f"Granted new execution lease {new_lease.id} for profile {profile.name} to {device_id}")
        return new_lease, None

    @classmethod
    @transaction.atomic
    def heartbeat(
        cls,
        user,
        lease_id,
        device_id: str,
        extension_seconds: int = DEFAULT_LEASE_DURATION_SECONDS
    ) -> tuple[ExecutionLease | None, str | None]:
        """
        Refreshes heartbeat and extends expires_at for an active lease.
        """
        device_id = (device_id or "").strip()
        try:
            lease = ExecutionLease.objects.select_for_update().get(id=lease_id)
        except (ExecutionLease.DoesNotExist, ValueError):
            return None, "Execution lease not found."

        if lease.profile.user and lease.profile.user != user:
            return None, "Unauthorized: Lease profile belongs to another user."

        if lease.device_id != device_id:
            return None, f"Device mismatch: Lease was issued to '{lease.device_id}', not '{device_id}'."

        now = timezone.now()

        if lease.status != ExecutionLeaseStatus.ACTIVE:
            return None, f"Cannot renew lease: Current status is '{lease.status}'."

        if now >= lease.expires_at:
            lease.status = ExecutionLeaseStatus.EXPIRED
            lease.save(update_fields=["status", "updated_at"])
            return None, "Execution lease has expired. Please acquire a new lease."

        lease.heartbeat_at = now
        lease.expires_at = now + datetime.timedelta(seconds=extension_seconds)
        lease.save(update_fields=["heartbeat_at", "expires_at", "updated_at"])
        return lease, None

    @classmethod
    @transaction.atomic
    def release_lease(
        cls,
        user,
        lease_id,
        device_id: str
    ) -> tuple[bool, str | None]:
        """
        Releases an active execution lease immediately when automation or browser session stops.
        """
        device_id = (device_id or "").strip()
        try:
            lease = ExecutionLease.objects.select_for_update().get(id=lease_id)
        except (ExecutionLease.DoesNotExist, ValueError):
            return False, "Execution lease not found."

        if lease.profile.user and lease.profile.user != user:
            return False, "Unauthorized: Lease profile belongs to another user."

        if lease.device_id != device_id:
            return False, f"Device mismatch: Lease was issued to '{lease.device_id}', not '{device_id}'."

        lease.status = ExecutionLeaseStatus.RELEASED
        lease.save(update_fields=["status", "updated_at"])
        logger.info(f"Released execution lease {lease.id} for device {device_id}")
        return True, None

    @classmethod
    def get_profile_lease_status(cls, user, profile_id) -> dict:
        try:
            profile = SavedProfile.objects.get(id=profile_id)
        except (SavedProfile.DoesNotExist, ValueError):
            return {"status": "NOT_FOUND", "is_leased": False}

        if profile.user and profile.user != user:
            return {"status": "UNAUTHORIZED", "is_leased": False}

        now = timezone.now()
        active_lease = ExecutionLease.objects.filter(
            profile=profile,
            status=ExecutionLeaseStatus.ACTIVE,
            expires_at__gt=now
        ).first()

        if active_lease:
            return {
                "is_leased": True,
                "lease_id": str(active_lease.id),
                "device_id": active_lease.device_id,
                "status": active_lease.status,
                "expires_at": active_lease.expires_at.isoformat(),
                "remaining_seconds": max(0, int((active_lease.expires_at - now).total_seconds()))
            }
        return {"is_leased": False, "status": "AVAILABLE"}


class ExecutionService:
    """
    Core execution orchestration service.
    Decouples state-machine transitions, lifecycle heartbeats, and audit event emission
    from REST views into a modular, reusable domain service operating directly on Execution.
    """

    @classmethod
    def record_event(cls, execution, event_type: str, payload: dict = None) -> ExecutionEvent:
        """Emits a structured time-series event for telemetry and timeline dashboards."""
        if isinstance(execution, (str, uuid.UUID)):
            return ExecutionEvent.objects.create(
                execution_id=execution,
                event_type=event_type,
                payload=payload or {}
            )
        return ExecutionEvent.objects.create(
            execution=execution,
            event_type=event_type,
            payload=payload or {}
        )

    @classmethod
    @transaction.atomic
    def poll_next_job(cls, profile, device_id: str = None) -> Execution | None:
        """
        Polls the next PENDING execution for a given profile, atomically claiming it (CAS).
        If device_id is provided, automatically records lease association.
        """
        now = timezone.now()
        pending = Execution.objects.filter(
            profile=profile,
            status=ExecutionStatus.PENDING
        ).order_by("created_at")

        for job in pending:
            claimed = Execution.objects.filter(
                pk=job.pk,
                status=ExecutionStatus.PENDING
            ).update(
                status=ExecutionStatus.DISPATCHED,
                started_at=now
            )
            if claimed:
                job.refresh_from_db()
                if device_id:
                    active_lease = ExecutionLease.objects.filter(
                        profile=profile,
                        device_id=device_id,
                        status=ExecutionLeaseStatus.ACTIVE
                    ).first()
                    if active_lease:
                        active_lease.execution = job
                        active_lease.save(update_fields=["execution", "updated_at"])

                cls.record_event(
                    execution=job,
                    event_type=ExecutionEventType.RUN_STARTED,
                    payload={
                        "profile_id": str(profile.id),
                        "task_id": str(job.task_id),
                        "device_id": device_id or "",
                        "entry_state": job.entry_state_id
                    }
                )
                return job
        return None

    @classmethod
    @transaction.atomic
    def transition_state(
        cls,
        job,
        outcome: str = "SUCCESS",
        context_update: dict = None,
        error: str = None,
        transition_id: str = None
    ) -> dict:
        """
        Advances the state machine of an execution job.
        Handles idempotency, terminal states, trust score increments, and event emission.
        """
        from rest_framework.exceptions import ValidationError

        if isinstance(job, (str, uuid.UUID)):
            job = Execution.objects.select_for_update().get(id=job)
        elif hasattr(job, "execution") and job.execution:
            job = Execution.objects.select_for_update().get(pk=job.execution.pk)
        else:
            job = Execution.objects.select_for_update().get(pk=job.pk)

        context_update = context_update or {}

        # Idempotency validation
        if transition_id and any(entry.get("transition_id") == transition_id for entry in (job.logs or []) if isinstance(entry, dict)):
            node = job.compiled_dag.get("states", {}).get(job.current_state_id, {})
            return {
                "status": "ADVANCED",
                "current_state_id": job.current_state_id,
                "is_terminal": job.status in (ExecutionStatus.SUCCESS, ExecutionStatus.FAILED),
                "command": node.get("command"),
                "params": node.get("params", {}),
                "status_code": 200
            }

        if job.status in (ExecutionStatus.SUCCESS, ExecutionStatus.FAILED):
            return {"error": "Execution is already terminal.", "status_code": 409}

        states = job.compiled_dag.get("states", {})
        current_node = states.get(job.current_state_id)

        if not current_node:
            job.status = ExecutionStatus.FAILED
            job.error_message = f"Node '{job.current_state_id}' not found in DAG."
            job.save()
            cls.record_event(
                execution=job,
                event_type=ExecutionEventType.FAILED,
                payload={"error": job.error_message, "state": job.current_state_id}
            )
            return {"status": "ERROR", "message": job.error_message, "status_code": 400}

        transitions = current_node.get("transitions", {})
        if outcome not in transitions and outcome != "FAILURE":
            raise ValidationError({"outcome": "Outcome is not defined for the current state."})

        next_state_id = transitions.get(outcome, transitions.get("FAILURE", "exit"))
        if next_state_id != "exit" and next_state_id not in states:
            raise ValidationError("Transition references a missing state.")

        # Update context
        if not isinstance(job.execution_context, dict):
            job.execution_context = {}
        job.execution_context.update(context_update)

        # Update logs
        if not isinstance(job.logs, list):
            job.logs = []
        log_entry = {
            "transition_id": transition_id,
            "from_state": job.current_state_id,
            "outcome": outcome,
            "to_state": next_state_id,
            "timestamp": timezone.now().isoformat()
        }
        job.logs.append(log_entry)

        from_state = job.current_state_id
        job.current_state_id = next_state_id

        # Terminal check
        terminal = next_state_id == "exit" or states.get(next_state_id, {}).get("command") == "TERMINATE"
        now = timezone.now()

        if terminal and outcome == "FAILURE":
            job.status = ExecutionStatus.FAILED
            job.error_message = error or "Execution failed at terminal state."
            job.completed_at = now
            event_type = ExecutionEventType.FAILED
        elif terminal:
            job.status = ExecutionStatus.SUCCESS
            job.completed_at = now
            event_type = ExecutionEventType.SUCCESS

            # Trust score increments
            complete_params = current_node.get("params", {})
            inc = complete_params.get("increment_trust_score", 0)
            if inc > 0 and hasattr(job.profile, "persona"):
                persona = job.profile.persona
                persona.trust_score = min(100, persona.trust_score + inc)
                persona.save()
        else:
            job.status = ExecutionStatus.RUNNING
            event_type = ExecutionEventType.STATE_CHANGED

        job.save()

        # Emit time-series execution event
        cls.record_event(
            execution=job,
            event_type=event_type,
            payload={
                "transition_id": transition_id,
                "from_state": from_state,
                "to_state": next_state_id,
                "outcome": outcome,
                "error": error or "",
                "status": job.status
            }
        )

        next_node = states.get(next_state_id, {})
        return {
            "status": "ADVANCED",
            "current_state_id": job.current_state_id,
            "is_terminal": job.status in [ExecutionStatus.SUCCESS, ExecutionStatus.FAILED],
            "command": next_node.get("command"),
            "params": next_node.get("params", {}),
            "status_code": 200
        }

    @classmethod
    @transaction.atomic
    def heartbeat(cls, job, device_id: str = None) -> dict:
        """
        Processes client heartbeat. Updates last_heartbeat timestamp and transitions
        DISPATCHED -> RUNNING. Also updates lease heartbeat if present.
        """
        if isinstance(job, (str, uuid.UUID)):
            job = Execution.objects.select_for_update().get(id=job)
        elif hasattr(job, "execution") and job.execution:
            job = Execution.objects.select_for_update().get(pk=job.execution.pk)
        else:
            job = Execution.objects.select_for_update().get(pk=job.pk)

        now = timezone.now()

        if job.status in [ExecutionStatus.SUCCESS, ExecutionStatus.FAILED]:
            return {
                "status": "TERMINAL",
                "job_id": str(job.id),
                "current_state": job.current_state_id,
                "job_status": job.status,
                "status_code": 200
            }

        if job.status == ExecutionStatus.DISPATCHED:
            job.status = ExecutionStatus.RUNNING

        if not isinstance(job.execution_context, dict):
            job.execution_context = {}
        job.execution_context["last_heartbeat"] = now.isoformat()
        job.save()

        if device_id:
            ExecutionLease.objects.filter(
                profile=job.profile,
                device_id=device_id,
                status=ExecutionLeaseStatus.ACTIVE
            ).update(heartbeat_at=now)

        cls.record_event(
            execution=job,
            event_type=ExecutionEventType.HEARTBEAT,
            payload={"current_state": job.current_state_id, "timestamp": now.isoformat()}
        )

        return {
            "status": "ALIVE",
            "job_id": str(job.id),
            "current_state": job.current_state_id,
            "job_status": job.status,
            "status_code": 200
        }

    @classmethod
    @transaction.atomic
    def abort(cls, job, reason: str = "Task manually aborted by operator.") -> dict:
        """
        Emergency operator abort. Transitions status to FAILED, releases active leases.
        """
        if isinstance(job, (str, uuid.UUID)):
            job = Execution.objects.select_for_update().get(id=job)
        elif hasattr(job, "execution") and job.execution:
            job = Execution.objects.select_for_update().get(pk=job.execution.pk)
        else:
            job = Execution.objects.select_for_update().get(pk=job.pk)

        now = timezone.now()
        job.status = ExecutionStatus.FAILED
        job.error_message = reason
        job.completed_at = now

        if not isinstance(job.logs, list):
            job.logs = []
        job.logs.append({
            "type": "ABORT",
            "message": reason,
            "timestamp": now.isoformat()
        })
        job.save()

        # Release associated leases
        ExecutionLease.objects.filter(
            profile=job.profile,
            status=ExecutionLeaseStatus.ACTIVE
        ).update(status=ExecutionLeaseStatus.RELEASED)

        cls.record_event(
            execution=job,
            event_type=ExecutionEventType.ABORTED,
            payload={"reason": reason, "timestamp": now.isoformat()}
        )

        return {
            "status": "ABORTED",
            "job_id": str(job.id),
            "status_code": 200
        }

    @classmethod
    @transaction.atomic
    def reap_stalled_executions(cls, timeout_seconds: int = 60) -> int:
        """
        Reclaims orphaned executions that stopped sending heartbeats or whose device leases expired.
        Transitions them to STALLED and emits STALLED events.
        """
        threshold = timezone.now() - datetime.timedelta(seconds=timeout_seconds)
        active_jobs = Execution.objects.select_for_update().filter(
            status__in=[
                ExecutionStatus.DISPATCHED,
                ExecutionStatus.RUNNING
            ]
        )

        reaped_count = 0
        now = timezone.now()
        for job in active_jobs:
            last_hb_str = (job.execution_context or {}).get("last_heartbeat")
            is_stalled = False

            if last_hb_str:
                try:
                    last_hb = datetime.datetime.fromisoformat(last_hb_str)
                    if timezone.is_naive(last_hb):
                        last_hb = timezone.make_aware(last_hb)
                    if last_hb < threshold:
                        is_stalled = True
                except Exception:
                    is_stalled = True
            elif job.started_at and job.started_at < threshold:
                is_stalled = True

            if is_stalled:
                job.status = ExecutionStatus.STALLED
                job.error_message = f"Execution timed out past {timeout_seconds}s heartbeat threshold."
                job.completed_at = now
                job.save(update_fields=["status", "error_message", "completed_at"])

                cls.record_event(
                    execution=job,
                    event_type=ExecutionEventType.STALLED,
                    payload={"reason": job.error_message, "reaped_at": now.isoformat()}
                )
                reaped_count += 1

        return reaped_count
