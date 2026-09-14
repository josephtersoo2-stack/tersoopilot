import logging
from typing import Dict, Any, List
from django.utils import timezone
from devices.models import SavedProfile
from automation.models import Automation, SelectionMode
from executions.models import Execution, ExecutionStatus, ExecutionLease, ExecutionLeaseStatus
from .policies import is_profile_in_cooldown

logger = logging.getLogger(__name__)


class EligibilityReason:
    OK = "OK"
    NOT_FOUND = "PROFILE_NOT_FOUND"
    DISABLED = "PROFILE_DISABLED"
    TARGET_EXCLUDED = "TARGET_EXCLUDED"
    NO_NICHE_AFFILIATION = "NO_NICHE_AFFILIATION"
    ACTIVE_LEASE_CONFLICT = "ACTIVE_LEASE_CONFLICT"
    COOLDOWN_ACTIVE = "COOLDOWN_ACTIVE"
    ALREADY_QUEUED = "ALREADY_QUEUED"
    INVALID_TASK = "INVALID_TASK"


class EligibilityEngine:
    """
    Evaluates profile eligibility against automation target criteria,
    lease states, cooldown intervals, and concurrency limits.
    """

    @classmethod
    def get_candidate_profiles(cls, automation: Automation) -> List[SavedProfile]:
        """Resolves raw candidate profiles according to selection_mode."""
        if automation.selection_mode == SelectionMode.EXPLICIT_PROFILES:
            return list(automation.target_profiles.all())

        elif automation.selection_mode == SelectionMode.NICHE:
            target_niches = automation.target_niches.all()
            if not target_niches.exists():
                return []
            return list(
                SavedProfile.objects.filter(
                    niche_affiliations__niche__in=target_niches
                ).distinct()
            )

        elif automation.selection_mode == SelectionMode.ALL_ELIGIBLE:
            # Respect user boundary if automation task has a user
            if automation.task and automation.task.user:
                return list(SavedProfile.objects.filter(user=automation.task.user))
            return list(SavedProfile.objects.all())

        return []

    @classmethod
    def evaluate_profile(
        cls,
        automation: Automation,
        profile: SavedProfile,
        now_dt=None
    ) -> Dict[str, Any]:
        """
        Runs comprehensive eligibility evaluation for a single profile against an automation.
        Returns a dict containing:
          - profile: SavedProfile
          - profile_id: str
          - eligible: bool
          - reason: str
          - details: dict (optional telemetry)
        """
        now = now_dt or timezone.now()

        # 1. Profile validity
        if not profile:
            return {
                "profile": None,
                "profile_id": None,
                "eligible": False,
                "reason": EligibilityReason.NOT_FOUND,
            }

        # 2. Check task validity
        if not automation.task:
            return {
                "profile": profile,
                "profile_id": str(profile.id),
                "eligible": False,
                "reason": EligibilityReason.INVALID_TASK,
            }

        # 3. Check active lease conflict
        has_active_lease = ExecutionLease.objects.filter(
            profile=profile,
            status=ExecutionLeaseStatus.ACTIVE,
            expires_at__gt=now
        ).exists()
        if has_active_lease:
            return {
                "profile": profile,
                "profile_id": str(profile.id),
                "eligible": False,
                "reason": EligibilityReason.ACTIVE_LEASE_CONFLICT,
            }

        # 4. Check whether an execution is already queued/running for this profile
        already_active = Execution.objects.filter(
            profile=profile,
            status__in=[
                ExecutionStatus.PENDING,
                ExecutionStatus.DISPATCHED,
                ExecutionStatus.RUNNING
            ]
        ).exists()
        if already_active:
            return {
                "profile": profile,
                "profile_id": str(profile.id),
                "eligible": False,
                "reason": EligibilityReason.ALREADY_QUEUED,
            }

        # 5. Check cooldown period
        in_cooldown, remaining_seconds = is_profile_in_cooldown(
            profile_id=profile.id,
            cooldown_minutes=automation.cooldown_minutes,
            now_dt=now
        )
        if in_cooldown:
            return {
                "profile": profile,
                "profile_id": str(profile.id),
                "eligible": False,
                "reason": EligibilityReason.COOLDOWN_ACTIVE,
                "details": {"remaining_seconds": remaining_seconds},
            }

        return {
            "profile": profile,
            "profile_id": str(profile.id),
            "eligible": True,
            "reason": EligibilityReason.OK,
        }

    @classmethod
    def calculate_eligible_profiles(
        cls,
        automation: Automation,
        now_dt=None
    ) -> List[Dict[str, Any]]:
        """
        Evaluates all candidate profiles for the given automation.
        Returns a list of evaluation dictionaries.
        """
        candidates = cls.get_candidate_profiles(automation)
        results = []
        for profile in candidates:
            eval_result = cls.evaluate_profile(automation, profile, now_dt=now_dt)
            results.append(eval_result)
        return results
