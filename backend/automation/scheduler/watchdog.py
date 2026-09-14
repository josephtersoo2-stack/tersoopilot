import logging
import datetime
from typing import Dict, Any, List, Optional
from django.db import transaction, models
from django.utils import timezone
from devices.models import Device, DeviceStatus
from executions.models import (
    Execution,
    ExecutionStatus,
    ExecutionLease,
    ExecutionLeaseStatus,
    ExecutionEvent,
    ExecutionEventType,
    RecoveryStatus,
)
from automation.models import (
    AutomationRun,
    AutomationRunStatus,
    Automation,
)

logger = logging.getLogger("automation.watchdog")


class AutomationWatchdogService:
    """
    Authoritative watchdog and self-healing recovery supervisor for TersoPilot Automation V2.
    
    Responsibilities (per Sections 16, 17, and 26 of implementation specification):
    1. Lease Reaper: Expires leases that have passed expires_at and resets device operational state.
    2. Stalled Execution Reconciler: Detects DISPATCHED/RUNNING jobs whose lease has expired,
       evaluates retry limits, and transitions them to STALLED (recovery_status=PENDING) or FAILED (EXHAUSTED).
    3. Device Health Monitor: Detects unresponsive worker nodes (last_heartbeat > threshold) and marks them OFFLINE.
    4. Failure Threshold Circuit Breaker: Evaluates failure rates against automation.failure_threshold_percent,
       pausing runaway error cascades and protecting target accounts.
    """

    @classmethod
    def reap_expired_leases(cls, now_dt: Optional[datetime.datetime] = None) -> List[ExecutionLease]:
        """
        Scans for ACTIVE leases whose expires_at timestamp is in the past.
        Marks them EXPIRED, emits audit event, and resets the associated device state.
        """
        now = now_dt or timezone.now()
        reaped_leases = []

        with transaction.atomic():
            expired_leases = ExecutionLease.objects.select_for_update(skip_locked=True).filter(
                status=ExecutionLeaseStatus.ACTIVE,
                expires_at__lte=now
            ).select_related("registered_device", "execution", "profile")

            for lease in expired_leases:
                lease.status = ExecutionLeaseStatus.EXPIRED
                lease.save(update_fields=["status", "updated_at"])
                reaped_leases.append(lease)

                logger.info(
                    f"Watchdog reaped expired lease {lease.id} for profile '{lease.profile.name}' "
                    f"(device '{lease.device_id}', expired at {lease.expires_at.isoformat()})."
                )

                # Emit audit event if execution is linked
                if lease.execution:
                    ExecutionEvent.objects.create(
                        execution=lease.execution,
                        event_type=ExecutionEventType.STALLED,
                        payload={
                            "reason": "Lease expired past timeout",
                            "lease_id": str(lease.id),
                            "device_id": lease.device_id,
                            "expired_at": lease.expires_at.isoformat()
                        }
                    )

                # Reset device operational state if this was its current execution
                if lease.registered_device:
                    dev = lease.registered_device
                    device_updated = False
                    if dev.current_execution_id == (lease.execution_id if lease.execution else None):
                        dev.current_execution = None
                        device_updated = True

                    # Check if device has any other active unexpired leases
                    has_other_active = ExecutionLease.objects.filter(
                        registered_device=dev,
                        status=ExecutionLeaseStatus.ACTIVE,
                        expires_at__gt=now
                    ).exclude(id=lease.id).exists()

                    if not has_other_active and dev.status == DeviceStatus.BUSY:
                        dev.status = DeviceStatus.ONLINE
                        device_updated = True

                    if device_updated:
                        dev.save(update_fields=["current_execution", "status", "updated_at"])

        return reaped_leases

    @classmethod
    def reconcile_stalled_executions(cls, now_dt: Optional[datetime.datetime] = None) -> List[Execution]:
        """
        Scans DISPATCHED and RUNNING executions whose lease is expired, released, or missing.
        Applies retry policies:
        - If retry_count < max_retries: transitions to STALLED (recovery_status=PENDING).
        - If retry_count >= max_retries: transitions to FAILED (recovery_status=EXHAUSTED).
        """
        now = now_dt or timezone.now()
        reconciled = []

        with transaction.atomic():
            candidates = Execution.objects.select_for_update(skip_locked=True).filter(
                status__in=[ExecutionStatus.DISPATCHED, ExecutionStatus.RUNNING]
            ).select_related("automation_run__automation", "profile")

            for execution in candidates:
                lease = ExecutionLease.objects.filter(execution=execution).order_by("-created_at").first()

                is_stalled = False
                stall_reason = ""

                if not lease:
                    # Missing lease altogether: if created/updated more than 60s ago
                    age = (now - execution.updated_at).total_seconds()
                    if age > 60:
                        is_stalled = True
                        stall_reason = "No lease associated with active execution"
                elif lease.status == ExecutionLeaseStatus.EXPIRED:
                    is_stalled = True
                    stall_reason = f"Lease {lease.id} expired at {lease.expires_at.isoformat()}"
                elif lease.status == ExecutionLeaseStatus.RELEASED:
                    is_stalled = True
                    stall_reason = f"Lease {lease.id} was released while execution was in {execution.status}"
                elif lease.status == ExecutionLeaseStatus.ACTIVE and lease.expires_at <= now:
                    lease.status = ExecutionLeaseStatus.EXPIRED
                    lease.save(update_fields=["status", "updated_at"])
                    is_stalled = True
                    stall_reason = f"Lease {lease.id} expired at {lease.expires_at.isoformat()}"

                if not is_stalled:
                    continue

                max_retries = execution.max_retries
                if execution.automation_run and execution.automation_run.automation:
                    max_retries = execution.automation_run.automation.max_retries

                if execution.retry_count < max_retries:
                    execution.retry_count += 1
                    execution.status = ExecutionStatus.STALLED
                    execution.recovery_status = RecoveryStatus.PENDING
                    execution.error_message = (
                        f"Execution stalled ({stall_reason}). "
                        f"Retry {execution.retry_count}/{max_retries} queued for recovery."
                    )
                    execution.save(update_fields=[
                        "status", "recovery_status", "retry_count", "error_message", "updated_at"
                    ])

                    ExecutionEvent.objects.create(
                        execution=execution,
                        event_type=ExecutionEventType.STALLED,
                        payload={
                            "reason": stall_reason,
                            "retry_count": execution.retry_count,
                            "max_retries": max_retries,
                            "recovery_status": RecoveryStatus.PENDING
                        }
                    )
                    logger.warning(
                        f"Watchdog marked execution {execution.id} as STALLED (Recovery PENDING, "
                        f"retry {execution.retry_count}/{max_retries}). Reason: {stall_reason}"
                    )
                else:
                    execution.status = ExecutionStatus.FAILED
                    execution.recovery_status = RecoveryStatus.EXHAUSTED
                    execution.completed_at = now
                    execution.error_message = (
                        f"Execution failed ({stall_reason}). "
                        f"Retry limit ({max_retries}) exhausted."
                    )
                    execution.save(update_fields=[
                        "status", "recovery_status", "completed_at", "error_message", "updated_at"
                    ])

                    ExecutionEvent.objects.create(
                        execution=execution,
                        event_type=ExecutionEventType.FAILED,
                        payload={
                            "reason": stall_reason,
                            "retry_count": execution.retry_count,
                            "max_retries": max_retries,
                            "recovery_status": RecoveryStatus.EXHAUSTED
                        }
                    )
                    logger.error(
                        f"Watchdog marked execution {execution.id} as FAILED (Retries EXHAUSTED, "
                        f"{execution.retry_count}/{max_retries}). Reason: {stall_reason}"
                    )

                # Update parent run metrics if attached
                if execution.automation_run:
                    run = execution.automation_run
                    run.stalled_count = run.executions.filter(status=ExecutionStatus.STALLED).count()
                    run.failure_count = run.executions.filter(status=ExecutionStatus.FAILED).count()
                    run.running_count = run.executions.filter(
                        status__in=[ExecutionStatus.RUNNING, ExecutionStatus.DISPATCHED]
                    ).count()
                    run.save(update_fields=["stalled_count", "failure_count", "running_count", "updated_at"])

                reconciled.append(execution)

        return reconciled

    @classmethod
    def check_device_health(
        cls,
        offline_threshold_seconds: int = 120,
        now_dt: Optional[datetime.datetime] = None
    ) -> List[Device]:
        """
        Scans for ONLINE or BUSY devices that have not sent a heartbeat or presence ping
        within offline_threshold_seconds (default 120s). Marks them OFFLINE.
        """
        now = now_dt or timezone.now()
        threshold_dt = now - datetime.timedelta(seconds=offline_threshold_seconds)
        marked_offline = []

        with transaction.atomic():
            stale_devices = Device.objects.select_for_update(skip_locked=True).filter(
                status__in=[DeviceStatus.ONLINE, DeviceStatus.BUSY],
                last_heartbeat__lt=threshold_dt,
                last_seen__lt=threshold_dt
            )

            for device in stale_devices:
                prev_status = device.status
                device.status = DeviceStatus.OFFLINE

                # Clear device current_execution if its lease is no longer valid
                if device.current_execution:
                    curr_lease = ExecutionLease.objects.filter(
                        registered_device=device,
                        execution=device.current_execution,
                        status=ExecutionLeaseStatus.ACTIVE,
                        expires_at__gt=now
                    ).first()
                    if not curr_lease:
                        device.current_execution = None

                device.save(update_fields=["status", "current_execution", "updated_at"])
                marked_offline.append(device)
                logger.info(
                    f"Watchdog marked device '{device.device_id}' as OFFLINE "
                    f"(previous status: {prev_status}, last heartbeat: {device.last_heartbeat.isoformat()})."
                )

        return marked_offline

    @classmethod
    def evaluate_failure_thresholds(cls, now_dt: Optional[datetime.datetime] = None) -> List[AutomationRun]:
        """
        Evaluates failure rates of active AutomationRuns against automation.failure_threshold_percent.
        If failures >= threshold:
          - Automatically pauses remaining queued work (transitions PENDING to CANCELLED).
          - Marks AutomationRun as PARTIAL.
          - Creates detailed audit entry in summary_metrics.
        Also transitions runs to COMPLETED or PARTIAL when all executions have reached terminal states.
        """
        now = now_dt or timezone.now()
        evaluated_runs = []

        with transaction.atomic():
            active_runs = AutomationRun.objects.select_for_update(skip_locked=True).filter(
                status__in=[AutomationRunStatus.RUNNING, AutomationRunStatus.SCHEDULED]
            ).select_related("automation")

            for run in active_runs:
                automation = run.automation
                total_target = run.total_target_profiles or run.executions.count()
                if total_target == 0:
                    continue

                exec_counts = run.executions.aggregate(
                    succeeded=models.Count("id", filter=models.Q(status=ExecutionStatus.SUCCESS)),
                    failed=models.Count("id", filter=models.Q(status=ExecutionStatus.FAILED)),
                    stalled=models.Count("id", filter=models.Q(status=ExecutionStatus.STALLED)),
                    running=models.Count("id", filter=models.Q(status__in=[ExecutionStatus.RUNNING, ExecutionStatus.DISPATCHED])),
                    pending=models.Count("id", filter=models.Q(status=ExecutionStatus.PENDING)),
                    cancelled=models.Count("id", filter=models.Q(status=ExecutionStatus.CANCELLED)),
                )

                succeeded = exec_counts["succeeded"] or 0
                failed = exec_counts["failed"] or 0
                stalled = exec_counts["stalled"] or 0
                running = exec_counts["running"] or 0
                pending = exec_counts["pending"] or 0
                cancelled = exec_counts["cancelled"] or 0

                run.success_count = succeeded
                run.failure_count = failed
                run.stalled_count = stalled
                run.running_count = running
                run.queued_count = pending
                run.cancelled_count = cancelled

                threshold_pct = automation.failure_threshold_percent

                # Section 26 Failure Threshold Circuit Breaker
                if total_target > 0 and failed > 0:
                    failure_rate = (failed / total_target) * 100.0
                    if failure_rate >= threshold_pct:
                        cancelled_count = run.executions.filter(status=ExecutionStatus.PENDING).update(
                            status=ExecutionStatus.CANCELLED,
                            error_message=f"Run paused: failure threshold of {threshold_pct}% reached ({failed}/{total_target} failed)."
                        )
                        run.queued_count = 0
                        run.cancelled_count += cancelled_count
                        run.status = AutomationRunStatus.PARTIAL
                        run.summary_metrics["failure_threshold_exceeded"] = True
                        run.summary_metrics["threshold_percent"] = threshold_pct
                        run.summary_metrics["actual_failure_percent"] = round(failure_rate, 2)
                        run.summary_metrics["pause_reason"] = (
                            f"Failure threshold of {threshold_pct}% exceeded "
                            f"({failed}/{total_target} failed, {failure_rate:.1f}%). "
                            f"Cancelled {cancelled_count} remaining queued executions."
                        )
                        run.save(update_fields=[
                            "status", "success_count", "failure_count", "stalled_count",
                            "running_count", "queued_count", "cancelled_count",
                            "summary_metrics", "updated_at"
                        ])
                        logger.warning(
                            f"Watchdog tripped failure threshold circuit breaker for run {run.run_key} "
                            f"({run.automation.name}): {failed}/{total_target} failed ({failure_rate:.1f}% >= {threshold_pct}%). "
                            f"Marked PARTIAL, cancelled {cancelled_count} queued executions."
                        )
                        evaluated_runs.append(run)
                        continue

                # Finalize run if all work has concluded
                if running == 0 and pending == 0 and stalled == 0:
                    if failed == 0:
                        run.status = AutomationRunStatus.COMPLETED
                    else:
                        run.status = AutomationRunStatus.PARTIAL
                    run.completed_at = now
                    run.save(update_fields=[
                        "status", "success_count", "failure_count", "stalled_count",
                        "running_count", "queued_count", "cancelled_count",
                        "completed_at", "updated_at"
                    ])
                    logger.info(
                        f"Watchdog finalized run {run.run_key} ({run.automation.name}) -> {run.status} "
                        f"(Success: {succeeded}, Failed: {failed}, Cancelled: {cancelled})."
                    )
                    evaluated_runs.append(run)
                else:
                    run.save(update_fields=[
                        "success_count", "failure_count", "stalled_count",
                        "running_count", "queued_count", "cancelled_count",
                        "updated_at"
                    ])

        return evaluated_runs

    @classmethod
    def run_sweep(
        cls,
        offline_threshold_seconds: int = 120,
        now_dt: Optional[datetime.datetime] = None
    ) -> Dict[str, Any]:
        """
        Executes a complete watchdog cycle covering lease reaping, stalled execution
        reconciliation, device health assessment, and failure threshold evaluation.
        """
        now = now_dt or timezone.now()
        expired_leases = cls.reap_expired_leases(now_dt=now)
        reconciled_execs = cls.reconcile_stalled_executions(now_dt=now)
        offline_devices = cls.check_device_health(offline_threshold_seconds=offline_threshold_seconds, now_dt=now)
        evaluated_runs = cls.evaluate_failure_thresholds(now_dt=now)

        stalled_count = len([e for e in reconciled_execs if e.status == ExecutionStatus.STALLED])
        exhausted_count = len([e for e in reconciled_execs if e.status == ExecutionStatus.FAILED])

        return {
            "timestamp": now.isoformat(),
            "expired_leases_count": len(expired_leases),
            "stalled_executions_count": stalled_count,
            "exhausted_executions_count": exhausted_count,
            "offline_devices_count": len(offline_devices),
            "evaluated_runs_count": len(evaluated_runs),
        }
