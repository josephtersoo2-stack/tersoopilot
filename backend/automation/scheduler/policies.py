import datetime
import zoneinfo
import logging
from django.utils import timezone
from automation.models import Automation, ScheduleType, AutomationRun
from executions.models import Execution, ExecutionStatus

logger = logging.getLogger(__name__)


def parse_timezone(tz_name: str | None) -> zoneinfo.ZoneInfo:
    """Safely resolves an IANA timezone or falls back to UTC."""
    if not tz_name:
        return zoneinfo.ZoneInfo("UTC")
    try:
        return zoneinfo.ZoneInfo(tz_name.strip())
    except Exception:
        logger.warning(f"Unrecognized timezone '{tz_name}', falling back to UTC.")
        return zoneinfo.ZoneInfo("UTC")


def is_automation_due(
    automation: Automation,
    now_dt: datetime.datetime | None = None
) -> tuple[bool, str, datetime.datetime | None]:
    """
    Evaluates whether an Automation is due for execution.
    Returns a tuple: (is_due: bool, run_key: str, next_run_at: datetime | None)
    
    Guarantees deterministic, timezone-aware run keys for idempotency.
    """
    if not automation.enabled:
        return False, "", None

    now = now_dt or timezone.now()

    # Verify active date bounds if specified
    if automation.active_from and now < automation.active_from:
        return False, "", automation.active_from
    if automation.active_until and now > automation.active_until:
        return False, "", None

    tz = parse_timezone(automation.timezone)
    local_now = now.astimezone(tz)
    cfg = automation.schedule_config or {}

    # 1. ONE_TIME Schedule
    if automation.schedule_type == ScheduleType.ONE_TIME:
        run_key = f"{automation.id}_onetime"
        if AutomationRun.objects.filter(automation=automation, run_key=run_key).exists():
            return False, "", None

        raw_run_at = cfg.get("run_at")
        if raw_run_at:
            try:
                scheduled_dt = datetime.datetime.fromisoformat(raw_run_at)
                if scheduled_dt.tzinfo is None:
                    scheduled_dt = scheduled_dt.replace(tzinfo=tz)
            except (ValueError, TypeError):
                scheduled_dt = now
        else:
            scheduled_dt = now

        if now >= scheduled_dt:
            return True, run_key, None
        return False, "", scheduled_dt

    # 2. DAILY Schedule
    elif automation.schedule_type == ScheduleType.DAILY:
        raw_time = cfg.get("time", "00:00")
        try:
            hour_str, min_str = raw_time.split(":")
            target_hour = int(hour_str)
            target_minute = int(min_str)
        except (ValueError, AttributeError):
            target_hour, target_minute = 0, 0

        target_local_today = local_now.replace(
            hour=target_hour, minute=target_minute, second=0, microsecond=0
        )
        date_str = target_local_today.strftime("%Y%m%d")
        run_key = f"{automation.id}_{date_str}"

        # Target time in UTC for database queries / next_run_at
        target_utc_today = target_local_today.astimezone(zoneinfo.ZoneInfo("UTC"))
        tomorrow_local = target_local_today + datetime.timedelta(days=1)
        target_utc_tomorrow = tomorrow_local.astimezone(zoneinfo.ZoneInfo("UTC"))

        already_run = AutomationRun.objects.filter(
            automation=automation,
            run_key=run_key
        ).exists()

        if local_now >= target_local_today:
            if not already_run:
                return True, run_key, target_utc_tomorrow
            return False, "", target_utc_tomorrow
        else:
            return False, "", target_utc_today

    # 3. INTERVAL Schedule
    elif automation.schedule_type == ScheduleType.INTERVAL:
        interval_minutes = int(cfg.get("interval_minutes", 60))
        if interval_minutes <= 0:
            interval_minutes = 60
        interval_seconds = interval_minutes * 60

        if not automation.last_run_at:
            bucket = int(now.timestamp()) // interval_seconds
            run_key = f"{automation.id}_{bucket}"
            next_run = now + datetime.timedelta(seconds=interval_seconds)
            return True, run_key, next_run

        elapsed = (now - automation.last_run_at).total_seconds()
        next_run = automation.last_run_at + datetime.timedelta(seconds=interval_seconds)

        if elapsed >= interval_seconds:
            bucket = int(now.timestamp()) // interval_seconds
            run_key = f"{automation.id}_{bucket}"
            if not AutomationRun.objects.filter(automation=automation, run_key=run_key).exists():
                return True, run_key, next_run

        return False, "", next_run

    return False, "", None


def is_profile_in_cooldown(
    profile_id,
    cooldown_minutes: int,
    now_dt: datetime.datetime | None = None
) -> tuple[bool, int]:
    """
    Determines if a profile is currently in cooldown from a recently completed execution.
    Returns (in_cooldown: bool, remaining_seconds: int).
    """
    if cooldown_minutes <= 0:
        return False, 0

    now = now_dt or timezone.now()
    cooldown_threshold = now - datetime.timedelta(minutes=cooldown_minutes)

    last_execution = Execution.objects.filter(
        profile_id=profile_id,
        status__in=[ExecutionStatus.SUCCESS, ExecutionStatus.FAILED],
        updated_at__gte=cooldown_threshold
    ).order_by("-updated_at").first()

    if last_execution and last_execution.updated_at:
        elapsed = (now - last_execution.updated_at).total_seconds()
        remaining = int((cooldown_minutes * 60) - elapsed)
        if remaining > 0:
            return True, remaining

    return False, 0


def get_active_executions_count(automation: Automation) -> int:
    """Counts currently active (DISPATCHED or RUNNING) executions for this automation."""
    return Execution.objects.filter(
        automation_run__automation=automation,
        status__in=[ExecutionStatus.DISPATCHED, ExecutionStatus.RUNNING]
    ).count()


def can_dispatch_more(automation: Automation) -> bool:
    """Checks if the automation has capacity below its concurrency_limit."""
    active_count = get_active_executions_count(automation)
    return active_count < automation.concurrency_limit
