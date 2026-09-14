import time
import signal
import sys
import logging
from django.core.management.base import BaseCommand
from django.conf import settings
from django.utils import timezone
from automation.scheduler.watchdog import AutomationWatchdogService

logger = logging.getLogger("automation.watchdog")


class Command(BaseCommand):
    help = "Long-running daemon supervising lease validity, stalled executions, device health, and failure thresholds."

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._running = True

    def add_arguments(self, parser):
        parser.add_argument(
            "--interval",
            type=int,
            default=getattr(settings, "AUTOMATION_WATCHDOG_INTERVAL_SECONDS", 10),
            help="Watchdog sweep interval in seconds (default: 10s or settings.AUTOMATION_WATCHDOG_INTERVAL_SECONDS)"
        )
        parser.add_argument(
            "--device-timeout",
            type=int,
            default=getattr(settings, "AUTOMATION_DEVICE_OFFLINE_SECONDS", 120),
            help="Device heartbeat timeout in seconds before marking OFFLINE (default: 120s)"
        )
        parser.add_argument(
            "--once",
            action="store_true",
            help="Run a single watchdog sweep cycle and exit immediately (useful for testing and cron jobs)"
        )

    def handle(self, *args, **options):
        interval = options["interval"]
        device_timeout = options["device_timeout"]
        run_once = options["once"]

        def _signal_handler(sig, frame):
            self.stdout.write(self.style.WARNING("\nReceived shutdown signal. Stopping watchdog gracefully..."))
            self._running = False

        signal.signal(signal.SIGINT, _signal_handler)
        signal.signal(signal.SIGTERM, _signal_handler)

        self.stdout.write(self.style.SUCCESS(
            f"=== TersoPilot Automation V2 Watchdog started (Interval: {interval}s, Device Timeout: {device_timeout}s) ==="
        ))

        while self._running:
            start_time = time.time()
            now = timezone.now()
            try:
                summary = AutomationWatchdogService.run_sweep(
                    offline_threshold_seconds=device_timeout,
                    now_dt=now
                )

                has_action = (
                    summary["expired_leases_count"] > 0 or
                    summary["stalled_executions_count"] > 0 or
                    summary["exhausted_executions_count"] > 0 or
                    summary["offline_devices_count"] > 0 or
                    summary["evaluated_runs_count"] > 0
                )

                if has_action:
                    msg = (
                        f"[{now.strftime('%Y-%m-%d %H:%M:%S')}] Watchdog Action: "
                        f"Reaped {summary['expired_leases_count']} leases, "
                        f"Stalled {summary['stalled_executions_count']} jobs, "
                        f"Exhausted {summary['exhausted_executions_count']} jobs, "
                        f"Marked {summary['offline_devices_count']} devices offline, "
                        f"Evaluated {summary['evaluated_runs_count']} runs."
                    )
                    self.stdout.write(self.style.WARNING(msg))
                    logger.info(msg)
                else:
                    self.stdout.write(
                        f"[{now.strftime('%Y-%m-%d %H:%M:%S')}] Watchdog tick: All leases and devices healthy."
                    )

            except Exception as e:
                self.stdout.write(self.style.ERROR(
                    f"[{now.strftime('%Y-%m-%d %H:%M:%S')}] Watchdog sweep error: {e}"
                ))
                logger.error(f"Watchdog sweep error: {e}", exc_info=True)

            if run_once or not self._running:
                break

            elapsed = time.time() - start_time
            sleep_time = max(0.5, interval - elapsed)
            time.sleep(sleep_time)

        self.stdout.write(self.style.SUCCESS("Watchdog shut down cleanly."))
