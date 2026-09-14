"""
TersoPilot Automation V2 Scheduler Package
Authoritative control plane scheduling, eligibility evaluation, and atomic job dispatch.
"""
from .service import SchedulerService
from .planner import EligibilityEngine
from .dispatcher import JobDispatcher
from .watchdog import AutomationWatchdogService
from .policies import (
    is_automation_due,
    is_profile_in_cooldown,
    get_active_executions_count,
    can_dispatch_more,
)

__all__ = [
    "SchedulerService",
    "EligibilityEngine",
    "JobDispatcher",
    "AutomationWatchdogService",
    "is_automation_due",
    "is_profile_in_cooldown",
    "get_active_executions_count",
    "can_dispatch_more",
]

