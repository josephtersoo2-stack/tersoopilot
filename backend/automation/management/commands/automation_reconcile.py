import json
from django.core.management.base import BaseCommand
from django.utils import timezone
from django.db import transaction
import datetime
from automation.models import AutomationRun, AutomationRunStatus
from executions.models import Execution, ExecutionStatus, ExecutionLease, ExecutionPlan, RecoveryStatus
from devices.models import Device, DeviceStatus


class Command(BaseCommand):
    help = (
        "Audits database state for orphan executions, expired/inconsistent leases, "
        "missing plans, and stale device assignments. Safely repairs inconsistencies when --fix is provided."
    )

    def add_arguments(self, parser):
        parser.add_argument(
            "--fix",
            "--repair",
            action="store_true",
            dest="fix",
            help="Perform explicitly safe repairs. If omitted, operates in dry-run/audit mode."
        )
        parser.add_argument(
            "--json",
            action="store_true",
            help="Output audit and reconciliation results in JSON format."
        )

    def handle(self, *args, **options):
        now = timezone.now()
        do_fix = options.get("fix", False)
        as_json = options.get("json", False)

        report = {
            "timestamp": now.isoformat(),
            "mode": "REPAIR" if do_fix else "AUDIT_ONLY",
            "inconsistencies_found": 0,
            "repairs_performed": 0,
            "categories": {
                "orphan_executions": [],
                "expired_active_leases": [],
                "duplicate_profile_leases": [],
                "executions_missing_plans": [],
                "stale_device_assignments": [],
                "abandoned_running_executions": []
            }
        }

        # ------------------------------------------------------------------
        # 1. Orphan Executions
        # ------------------------------------------------------------------
        terminal_runs = AutomationRun.objects.filter(
            status__in=[AutomationRunStatus.COMPLETED, AutomationRunStatus.FAILED, AutomationRunStatus.CANCELLED]
        )
        orphans = Execution.objects.filter(
            automation_run__in=terminal_runs,
            status__in=[ExecutionStatus.PENDING, ExecutionStatus.RUNNING]
        ).select_related("automation_run", "task")

        for o in orphans:
            report["inconsistencies_found"] += 1
            entry = {
                "execution_id": str(o.id),
                "task": o.task.name if o.task else "None",
                "status": o.status,
                "run_id": str(o.automation_run.id),
                "run_status": o.automation_run.status
            }
            report["categories"]["orphan_executions"].append(entry)

            if do_fix:
                with transaction.atomic():
                    o.status = ExecutionStatus.CANCELLED
                    o.error_message = "Cancelled by automation_reconcile: parent run already terminated."
                    o.save(update_fields=["status", "error_message", "updated_at"])
                    report["repairs_performed"] += 1

        # ------------------------------------------------------------------
        # 2. Inconsistent Leases: Expired Active Leases
        # ------------------------------------------------------------------
        expired_active = ExecutionLease.objects.filter(
            status="ACTIVE",
            expires_at__lt=now
        ).select_related("profile")

        for l in expired_active:
            report["inconsistencies_found"] += 1
            entry = {
                "lease_id": str(l.id),
                "profile": l.profile.name if l.profile else "None",
                "expired_at": l.expires_at.isoformat()
            }
            report["categories"]["expired_active_leases"].append(entry)

            if do_fix:
                with transaction.atomic():
                    l.status = "EXPIRED"
                    l.save(update_fields=["status", "updated_at"])
                    report["repairs_performed"] += 1

        # ------------------------------------------------------------------
        # 3. Duplicate Active Leases for Same Profile
        # ------------------------------------------------------------------
        from django.db.models import Count
        dupe_profiles = ExecutionLease.objects.filter(
            status="ACTIVE",
            expires_at__gt=now
        ).values("profile_id").annotate(cnt=Count("id")).filter(cnt__gt=1)

        for dp in dupe_profiles:
            active_for_p = list(ExecutionLease.objects.filter(
                profile_id=dp["profile_id"],
                status="ACTIVE",
                expires_at__gt=now
            ).order_by("-expires_at"))

            # Keep the one that expires latest, expire the rest
            winner = active_for_p[0]
            losers = active_for_p[1:]
            for loser in losers:
                report["inconsistencies_found"] += 1
                entry = {
                    "lease_id": str(loser.id),
                    "profile_id": str(dp["profile_id"]),
                    "action": "Expire duplicate active lease, keeping winner " + str(winner.id)
                }
                report["categories"]["duplicate_profile_leases"].append(entry)
                if do_fix:
                    with transaction.atomic():
                        loser.status = "EXPIRED"
                        loser.save(update_fields=["status", "updated_at"])
                        report["repairs_performed"] += 1

        # ------------------------------------------------------------------
        # 4. Executions Missing Plans
        # ------------------------------------------------------------------
        missing_plans = Execution.objects.filter(
            plan__isnull=True,
            status__in=[ExecutionStatus.PENDING, ExecutionStatus.RUNNING, ExecutionStatus.STALLED]
        ).select_related("task")

        for m in missing_plans:
            report["inconsistencies_found"] += 1
            entry = {
                "execution_id": str(m.id),
                "task": m.task.name if m.task else "None",
                "status": m.status
            }
            report["categories"]["executions_missing_plans"].append(entry)

            if do_fix and m.task:
                canonical = ExecutionPlan.objects.filter(task=m.task).order_by("-version").first()
                if canonical:
                    with transaction.atomic():
                        m.plan = canonical
                        if not m.compiled_dag:
                            m.compiled_dag = canonical.compiled_dag
                        m.save(update_fields=["plan", "compiled_dag", "updated_at"])
                        report["repairs_performed"] += 1

        # ------------------------------------------------------------------
        # 5. Stale Device Assignments
        # ------------------------------------------------------------------
        busy_devices = Device.objects.filter(status=DeviceStatus.BUSY)
        for dev in busy_devices:
            stale = False
            reason = ""
            if not dev.current_execution_id:
                stale = True
                reason = "Device status is BUSY but current_execution_id is empty."
            else:
                exec_obj = Execution.objects.filter(id=dev.current_execution_id).first()
                if not exec_obj or exec_obj.status not in [ExecutionStatus.RUNNING]:
                    stale = True
                    reason = f"Device references execution {dev.current_execution_id} which is {exec_obj.status if exec_obj else 'missing'}."

            if stale:
                report["inconsistencies_found"] += 1
                entry = {
                    "device_id": dev.device_id,
                    "reason": reason
                }
                report["categories"]["stale_device_assignments"].append(entry)

                if do_fix:
                    with transaction.atomic():
                        dev.status = DeviceStatus.ONLINE
                        dev.current_execution = None
                        dev.save(update_fields=["status", "current_execution", "updated_at"])
                        report["repairs_performed"] += 1

        # ------------------------------------------------------------------
        # 6. Abandoned Running Executions (No Lease, Stale > 5 mins)
        # ------------------------------------------------------------------
        cutoff = now - datetime.timedelta(minutes=5)
        abandoned_candidates = Execution.objects.filter(
            status=ExecutionStatus.RUNNING,
            updated_at__lt=cutoff
        ).select_related("profile")

        for cand in abandoned_candidates:
            active_lease_exists = ExecutionLease.objects.filter(
                profile=cand.profile,
                status="ACTIVE",
                expires_at__gt=now
            ).exists()

            if not active_lease_exists:
                report["inconsistencies_found"] += 1
                entry = {
                    "execution_id": str(cand.id),
                    "profile": cand.profile.name if cand.profile else "None",
                    "reason": "RUNNING with no active lease and no updates for >5m"
                }
                report["categories"]["abandoned_running_executions"].append(entry)

                if do_fix:
                    with transaction.atomic():
                        if cand.retry_count < cand.max_retries:
                            cand.status = ExecutionStatus.STALLED
                            cand.recovery_status = RecoveryStatus.PENDING
                            cand.error_message = "Reconciled by automation_reconcile: lease abandoned."
                        else:
                            cand.status = ExecutionStatus.FAILED
                            cand.recovery_status = RecoveryStatus.EXHAUSTED
                            cand.error_message = "Failed by automation_reconcile: max retries reached on abandoned execution."
                        cand.save(update_fields=["status", "recovery_status", "error_message", "updated_at"])
                        report["repairs_performed"] += 1

        # ------------------------------------------------------------------
        # Output Presentation
        # ------------------------------------------------------------------
        if as_json:
            self.stdout.write(json.dumps(report, indent=2))
            return

        self.stdout.write(self.style.SUCCESS("=" * 65))
        self.stdout.write(self.style.SUCCESS(f"  TersoPilot Automation V2 Reconcile Report [{report['mode']} MODE]"))
        self.stdout.write(self.style.SUCCESS("=" * 65))

        self.stdout.write(f"\nTotal Inconsistencies Detected: {report['inconsistencies_found']}")
        self.stdout.write(f"Total Safe Repairs Performed: {report['repairs_performed']}")

        for cat_name, items in report["categories"].items():
            title = cat_name.replace("_", " ").title()
            if items:
                self.stdout.write(self.style.WARNING(f"\n[!] {title} ({len(items)} found):"))
                for it in items:
                    self.stdout.write(f"    * {it}")
            else:
                self.stdout.write(self.style.SUCCESS(f"\n[OK] {title}: 0 detected"))

        if not do_fix and report["inconsistencies_found"] > 0:
            self.stdout.write(self.style.NOTICE(
                "\nRun 'python manage.py automation_reconcile --fix' to safely repair the above inconsistencies."
            ))

        self.stdout.write(self.style.SUCCESS("\n" + "=" * 65 + "\n"))
