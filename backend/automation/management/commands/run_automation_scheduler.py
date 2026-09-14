import time
import signal
import sys
import logging
from django.core.management.base import BaseCommand
from django.conf import settings
from django.utils import timezone
from automation.scheduler.service import SchedulerService

logger = logging.getLogger("automation.scheduler")


class Command(BaseCommand):
    help = "Long-running daemon evaluating and triggering due automations deterministically."

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._running = True

    def add_arguments(self, parser):
        parser.add_argument(
            "--interval",
            type=int,
            default=getattr(settings, "AUTOMATION_SCHEDULER_INTERVAL_SECONDS", 15),
            help="Polling interval in seconds (default: 15s or settings.AUTOMATION_SCHEDULER_INTERVAL_SECONDS)"
        )
        parser.add_argument(
            "--once",
            action="store_true",
            help="Run a single evaluation cycle and exit immediately (useful for testing/crons)"
        )

    def handle(self, *args, **options):
        interval = options["interval"]
        run_once = options["once"]

        def _signal_handler(sig, frame):
            self.stdout.write(self.style.WARNING("\nReceived shutdown signal. Stopping scheduler gracefully..."))
            self._running = False

        signal.signal(signal.SIGINT, _signal_handler)
        signal.signal(signal.SIGTERM, _signal_handler)

        self.stdout.write(self.style.SUCCESS(
            f"=== TersoPilot Automation V2 Scheduler started (Interval: {interval}s) ==="
        ))

        while self._running:
            start_time = time.time()
            now = timezone.now()
            try:
                due_runs = SchedulerService.evaluate_due_automations(now_dt=now)
                if due_runs:
                    self.stdout.write(self.style.SUCCESS(
                        f"[{now.strftime('%Y-%m-%d %H:%M:%S')}] Evaluated schedule: Triggered {len(due_runs)} runs "
                        f"({', '.join(r.run_key for r in due_runs)})"
                    ))
                else:
                    self.stdout.write(
                        f"[{now.strftime('%Y-%m-%d %H:%M:%S')}] Heartbeat tick: 0 automations due."
                    )
            except Exception as e:
                self.stdout.write(self.style.ERROR(
                    f"[{now.strftime('%Y-%m-%d %H:%M:%S')}] Scheduler tick error: {e}"
                ))
                logger.error(f"Scheduler tick error: {e}", exc_info=True)

            if run_once or not self._running:
                break

            elapsed = time.time() - start_time
            sleep_time = max(0.5, interval - elapsed)
            time.sleep(sleep_time)

        self.stdout.write(self.style.SUCCESS("Scheduler shut down cleanly."))
