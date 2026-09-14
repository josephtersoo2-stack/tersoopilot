import logging
from typing import Dict, Any, Optional
from django.db import transaction, models
from django.utils import timezone
from devices.models import Device, DeviceStatus
from executions.models import (
    Execution,
    ExecutionStatus,
    ExecutionEventType,
    ExecutionEvent,
    ExecutionLease,
    RecoveryStatus,
)
from executions.services import LeaseService
from automation.models import AutomationRunStatus
from .policies import can_dispatch_more

logger = logging.getLogger(__name__)


class JobDispatcher:
    """
    Atomic job allocator matching compatible PENDING executions to active worker devices.
    Enforces row locking, lease acquisition, concurrency limits, and audit event emission.
    """

    @classmethod
    def allocate_next_execution(
        cls,
        device_id: str,
        user=None
    ) -> Optional[Dict[str, Any]]:
        """
        Atomically selects the highest-priority compatible PENDING execution,
        acquires an exclusive profile lease for device_id, transitions status to DISPATCHED,
        and returns the execution payload.
        
        Guarantees zero race conditions across concurrent device pollers.
        """
        device_id = (device_id or "").strip()
        if not device_id:
            logger.warning("allocate_next_execution called without device_id.")
            return None

        now = timezone.now()

        # Update device presence
        device_obj, _ = Device.objects.get_or_create(
            device_id=device_id,
            defaults={
                "owner": user if user and user.is_authenticated else None,
                "status": DeviceStatus.ONLINE,
                "last_seen": now,
                "last_heartbeat": now,
            }
        )

        # Check device operational eligibility
        if device_obj.status in [DeviceStatus.DISABLED, DeviceStatus.REJECTED]:
            logger.warning(f"Device '{device_id}' is {device_obj.status}. Rejecting allocation request.")
            return None

        device_obj.last_seen = now
        device_obj.last_heartbeat = now
        device_obj.save(update_fields=["last_seen", "last_heartbeat", "updated_at"])

        with transaction.atomic():
            # Query next pending or stalled-recovery execution with skip_locked to avoid blocking concurrent pollers
            pending_query = Execution.objects.select_for_update(skip_locked=True).filter(
                models.Q(status=ExecutionStatus.PENDING) |
                models.Q(status=ExecutionStatus.STALLED, recovery_status=RecoveryStatus.PENDING)
            ).select_related(
                "automation_run__automation",
                "profile",
                "task",
                "plan"
            ).order_by(
                "-automation_run__automation__priority",
                "created_at"
            )

            candidate: Optional[Execution] = None
            for candidate_exec in pending_query[:10]:
                # 1. Validate automation state
                if candidate_exec.automation_run and candidate_exec.automation_run.automation:
                    automation = candidate_exec.automation_run.automation
                    if not automation.enabled:
                        continue
                    if not can_dispatch_more(automation):
                        logger.debug(
                            f"Automation '{automation.name}' reached concurrency limit. "
                            "Skipping execution candidate."
                        )
                        continue

                # 2. Check Device Capabilities against Task requirements
                task_config = candidate_exec.task.config or {}
                required_caps = task_config.get("required_capabilities", [])
                device_caps = device_obj.capabilities or {}
                if device_caps.get("geckoview") is False:
                    logger.debug(f"Device '{device_id}' lacks GeckoView capability. Skipping.")
                    continue
                missing_caps = [cap for cap in required_caps if device_caps.get(cap) is False]
                if missing_caps:
                    logger.debug(
                        f"Device '{device_id}' lacks required capabilities {missing_caps} "
                        f"for task '{candidate_exec.task.name}'. Skipping."
                    )
                    continue

                candidate = candidate_exec
                break

            if not candidate:
                return None

            # 3. Acquire exclusive execution lease for the profile
            profile = candidate.profile
            lease, err = LeaseService.acquire_lease(
                user=user or profile.user,
                profile_id=profile.id,
                device_id=device_id
            )
            if not lease:
                logger.warning(
                    f"Could not acquire lease on profile '{profile.name}' for device '{device_id}': {err}"
                )
                return None

            # 4. Associate lease and transition execution to DISPATCHED
            was_stalled = (candidate.status == ExecutionStatus.STALLED)
            candidate.status = ExecutionStatus.DISPATCHED
            if candidate.recovery_status == RecoveryStatus.PENDING:
                candidate.recovery_status = RecoveryStatus.RUNNING
            candidate.save(update_fields=["status", "recovery_status", "updated_at"])

            lease.execution = candidate
            lease.save(update_fields=["execution", "updated_at"])

            # Link current_execution and mark BUSY on device
            device_obj.current_execution = candidate
            device_obj.status = DeviceStatus.BUSY
            device_obj.save(update_fields=["current_execution", "status", "updated_at"])

            # 4. Update parent AutomationRun metrics
            if candidate.automation_run:
                run = candidate.automation_run
                if run.status == AutomationRunStatus.SCHEDULED:
                    run.status = AutomationRunStatus.RUNNING
                    if not run.started_at:
                        run.started_at = now
                run.dispatched_count = models.F("dispatched_count") + 1
                run.running_count = models.F("running_count") + 1
                update_fields = ["status", "started_at", "dispatched_count", "running_count", "updated_at"]
                if was_stalled and run.stalled_count > 0:
                    run.stalled_count = models.F("stalled_count") - 1
                    update_fields.append("stalled_count")
                run.save(update_fields=update_fields)

            # 5. Record RUN_STARTED audit event
            ExecutionEvent.objects.create(
                execution=candidate,
                event_type=ExecutionEventType.RUN_STARTED,
                payload={
                    "device_id": device_id,
                    "lease_id": str(lease.id),
                    "plan_version": candidate.plan_version,
                    "is_recovery": was_stalled,
                    "automation_id": str(candidate.automation_run.automation_id) if candidate.automation_run else None,
                    "run_key": candidate.automation_run.run_key if candidate.automation_run else None
                }
            )

            logger.info(
                f"Dispatched execution {candidate.id} (Profile: {profile.name}, Recovery: {was_stalled}) to device {device_id}."
            )

            return {
                "execution_id": str(candidate.id),
                "task_id": str(candidate.task.id),
                "task_name": candidate.task.name,
                "profile_id": str(profile.id),
                "profile_name": profile.name,
                "plan_version": candidate.plan_version,
                "compiled_dag": candidate.compiled_dag,
                "lease_id": str(lease.id),
                "lease_expires_at": lease.expires_at.isoformat(),
                "entry_state": candidate.entry_state_id,
                "checkpoint_version": candidate.checkpoint_version,
                "last_confirmed_state": candidate.last_confirmed_state,
                "recovery_status": candidate.recovery_status,
                "execution_context": candidate.execution_context or {}
            }
