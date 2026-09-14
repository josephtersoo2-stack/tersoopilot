import json
from django.core.management.base import BaseCommand
from django.utils import timezone
from django.db.models import Count, Q
from automation.models import Automation, AutomationRun, AutomationRunStatus
from executions.models import Execution, ExecutionStatus, ExecutionLease
from devices.models import Device, DeviceStatus


class Command(BaseCommand):
    help = "Reports status of automation schedules, active execution runs, queue depth, and worker fleet health."

    def add_arguments(self, parser):
        parser.add_argument(
            "--json",
            action="store_true",
            help="Output status summary in JSON format"
        )

    def handle(self, *args, **options):
        now = timezone.now()
        as_json = options.get("json", False)

        # 1. Automations summary
        total_automations = Automation.objects.count()
        enabled_automations = Automation.objects.filter(enabled=True).count()
        disabled_automations = total_automations - enabled_automations

        upcoming_schedules = []
        for auto in Automation.objects.filter(enabled=True).order_by("next_run_at")[:5]:
            next_run_str = auto.next_run_at.isoformat() if auto.next_run_at else "N/A"
            diff_mins = int((auto.next_run_at - now).total_seconds() / 60) if auto.next_run_at else None
            upcoming_schedules.append({
                "id": str(auto.id),
                "name": auto.name,
                "schedule_type": auto.schedule_type,
                "next_run_at": next_run_str,
                "due_in_minutes": diff_mins,
                "concurrency_limit": auto.concurrency_limit
            })

        # 2. Runs & Queue Depth
        active_runs_qs = AutomationRun.objects.filter(
            status__in=[AutomationRunStatus.SCHEDULED, AutomationRunStatus.RUNNING, AutomationRunStatus.PARTIAL]
        ).select_related("automation")

        active_runs = []
        for run in active_runs_qs:
            active_runs.append({
                "id": str(run.id),
                "automation": run.automation.name if run.automation else "None",
                "run_key": run.run_key,
                "status": run.status,
                "queued": run.queued_count,
                "running": run.running_count,
                "success": run.success_count,
                "failed": run.failure_count,
                "stalled": run.stalled_count,
                "started_at": run.started_at.isoformat() if run.started_at else None
            })

        total_queued_executions = Execution.objects.filter(status=ExecutionStatus.PENDING).count()
        total_running_executions = Execution.objects.filter(status=ExecutionStatus.RUNNING).count()
        total_stalled_executions = Execution.objects.filter(status=ExecutionStatus.STALLED).count()

        # 3. Fleet & Devices
        devices_qs = Device.objects.all()
        total_devices = devices_qs.count()
        device_status_counts = {
            "ONLINE": devices_qs.filter(status=DeviceStatus.ONLINE).count(),
            "BUSY": devices_qs.filter(status=DeviceStatus.BUSY).count(),
            "OFFLINE": devices_qs.filter(status=DeviceStatus.OFFLINE).count(),
            "DISABLED": devices_qs.filter(status=DeviceStatus.DISABLED).count(),
        }

        low_battery_devices = []
        stale_devices = []
        for dev in devices_qs:
            if dev.battery_percent < 20:
                low_battery_devices.append({
                    "device_id": dev.device_id,
                    "battery": dev.battery_percent
                })
            if dev.last_heartbeat and dev.status != DeviceStatus.DISABLED:
                hb_diff = int((now - dev.last_heartbeat).total_seconds())
                if hb_diff > 120:
                    stale_devices.append({
                        "device_id": dev.device_id,
                        "last_seen_seconds_ago": hb_diff
                    })

        # 4. Active Leases
        active_leases_qs = ExecutionLease.objects.filter(
            status="ACTIVE",
            expires_at__gt=now
        ).select_related("profile", "registered_device")

        active_leases = []
        for lease in active_leases_qs:
            remaining_secs = int((lease.expires_at - now).total_seconds())
            active_leases.append({
                "lease_id": str(lease.id),
                "device_id": lease.registered_device.device_id if lease.registered_device else lease.device_id,
                "profile": lease.profile.name,
                "expires_in_seconds": remaining_secs
            })

        data = {
            "timestamp": now.isoformat(),
            "automations": {
                "total": total_automations,
                "enabled": enabled_automations,
                "disabled": disabled_automations,
                "upcoming": upcoming_schedules
            },
            "queue": {
                "pending_executions": total_queued_executions,
                "running_executions": total_running_executions,
                "stalled_executions": total_stalled_executions,
                "active_runs": active_runs
            },
            "fleet": {
                "total_devices": total_devices,
                "status_breakdown": device_status_counts,
                "active_leases": active_leases,
                "low_battery_alerts": low_battery_devices,
                "stale_heartbeat_alerts": stale_devices
            }
        }

        if as_json:
            self.stdout.write(json.dumps(data, indent=2))
            return

        # Human-readable formatted output
        self.stdout.write(self.style.SUCCESS("=" * 65))
        self.stdout.write(self.style.SUCCESS(f"  TersoPilot Automation V2 System Status [{now.strftime('%Y-%m-%d %H:%M:%S')}]"))
        self.stdout.write(self.style.SUCCESS("=" * 65))

        self.stdout.write(self.style.MIGRATE_HEADING("\n[1] Automation Rules & Schedules"))
        self.stdout.write(f"  Total Automations: {total_automations} (Enabled: {enabled_automations}, Disabled: {disabled_automations})")
        if upcoming_schedules:
            self.stdout.write("  Upcoming Scheduled Runs:")
            for s in upcoming_schedules:
                due = f"in {s['due_in_minutes']}m" if s['due_in_minutes'] is not None and s['due_in_minutes'] >= 0 else "DUE NOW"
                self.stdout.write(f"    - [{s['schedule_type']}] {s['name']} -> {due} ({s['next_run_at']})")
        else:
            self.stdout.write("  Upcoming Scheduled Runs: None")

        self.stdout.write(self.style.MIGRATE_HEADING("\n[2] Execution Queue Depth"))
        self.stdout.write(f"  Pending / Queued: {total_queued_executions}")
        self.stdout.write(f"  Currently Running: {total_running_executions}")
        self.stdout.write(f"  Stalled (Awaiting Recovery): {total_stalled_executions}")
        if active_runs:
            self.stdout.write(f"  Active Batches ({len(active_runs)}):")
            for r in active_runs:
                self.stdout.write(
                    f"    * [{r['status']}] {r['automation']} ({r['run_key']}) | "
                    f"Q:{r['queued']} R:{r['running']} S:{r['success']} F:{r['failed']}"
                )

        self.stdout.write(self.style.MIGRATE_HEADING("\n[3] Mobile Worker Fleet & Leases"))
        self.stdout.write(
            f"  Total Devices: {total_devices} | "
            f"Online: {device_status_counts['ONLINE']} | "
            f"Busy: {device_status_counts['BUSY']} | "
            f"Offline: {device_status_counts['OFFLINE']} | "
            f"Disabled: {device_status_counts['DISABLED']}"
        )
        if active_leases:
            self.stdout.write(f"  Active Leases ({len(active_leases)}):")
            for l in active_leases:
                self.stdout.write(f"    * Device '{l['device_id']}' -> Profile '{l['profile']}' (expires in {l['expires_in_seconds']}s)")
        else:
            self.stdout.write("  Active Leases: 0")

        if low_battery_devices:
            self.stdout.write(self.style.WARNING(f"  [!] Low Battery Warnings: {len(low_battery_devices)} device(s) < 20%"))
            for b in low_battery_devices:
                self.stdout.write(self.style.WARNING(f"      - {b['device_id']}: {b['battery']}%"))

        if stale_devices:
            self.stdout.write(self.style.WARNING(f"  [!] Stale Heartbeat Warnings: {len(stale_devices)} device(s) > 120s"))
            for s in stale_devices:
                self.stdout.write(self.style.WARNING(f"      - {s['device_id']}: last seen {s['last_seen_seconds_ago']}s ago"))

        self.stdout.write(self.style.SUCCESS("\n" + "=" * 65 + "\n"))
