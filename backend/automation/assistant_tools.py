"""
TersoAssistant Tool Catalog & Registry
=======================================
Concrete tool implementations that interface directly with existing Django models,
plus dual-format schema declarations for OpenRouter (OpenAI-compatible) and Gemini
Automatic Function Calling.
"""

import json
import uuid
from typing import Dict, Any, List, Optional
from devices.models import SavedProfile
from automation.models import (
    Niche,
    ProfilePersona,
    ProfileNicheAffiliation,
    AutomationTask,
    TaskExecutionQueue,
    PlatformCategory
)
from executions.models import Execution, ExecutionStatus
from executions.services import ExecutionService
from automation.compiler import RecipeCompiler


# ---------------------------------------------------------------------------
# Concrete Tool Implementations
# ---------------------------------------------------------------------------

def tool_get_fleet_status() -> Dict[str, Any]:
    """Returns a high-level summary of total browser profiles, running jobs,
    pending jobs, failed jobs, and maturation score distributions."""
    total_profiles = SavedProfile.objects.count()
    active_jobs = Execution.objects.filter(
        status=ExecutionStatus.RUNNING
    ).count()
    pending_jobs = Execution.objects.filter(
        status=ExecutionStatus.PENDING
    ).count()
    failed_jobs = Execution.objects.filter(
        status=ExecutionStatus.FAILED
    ).count()

    stages = {}
    for p in ProfilePersona.objects.all():
        stages[p.maturation_stage] = stages.get(p.maturation_stage, 0) + 1

    return {
        "total_profiles": total_profiles,
        "active_running_jobs": active_jobs,
        "pending_queued_jobs": pending_jobs,
        "failed_jobs": failed_jobs,
        "maturation_distribution": stages
    }


def tool_list_profiles(maturation_stage: Optional[str] = None) -> List[Dict[str, Any]]:
    """List browser profiles with their trust score, assigned niches, and maturation status.
    Optionally filter by maturation_stage (INFANT, SEEDING, MATURING, MATURE)."""
    qs = SavedProfile.objects.select_related("persona").prefetch_related(
        "niche_affiliations__niche"
    ).all()
    if maturation_stage:
        qs = qs.filter(persona__maturation_stage=maturation_stage.upper())

    results = []
    for p in qs[:25]:
        persona = getattr(p, "persona", None)
        niches = [
            {"name": a.niche.name, "weight": a.weight_percentage}
            for a in p.niche_affiliations.all()
        ]
        results.append({
            "id": str(p.id),
            "name": p.name,
            "model": f"{p.brand} {p.model_name}",
            "trust_score": persona.trust_score if persona else 0,
            "maturation_stage": persona.maturation_stage if persona else "UNKNOWN",
            "niches": niches
        })
    return results


def tool_create_niche(
    name: str,
    description: str = "",
    seed_keywords: Optional[List[str]] = None,
    seed_websites: Optional[List[str]] = None,
    target_youtube_channels: Optional[List[str]] = None
) -> Dict[str, Any]:
    """Create a new target audience niche with seed keywords, target channels,
    and authority sites."""
    niche = Niche.objects.create(
        name=name,
        description=description,
        seed_keywords=seed_keywords or [],
        seed_websites=seed_websites or [],
        target_youtube_channels=target_youtube_channels or []
    )
    return {"status": "SUCCESS", "niche_id": str(niche.id), "name": niche.name}


import uuid


def _resolve_profile(identifier: Any) -> Optional[SavedProfile]:
    """Resolves a profile by UUID, UUID string, exact name, or case-insensitive substring."""
    if not identifier:
        return None
    if isinstance(identifier, SavedProfile):
        return identifier

    ident_str = str(identifier).strip()

    # 1. UUID lookup
    try:
        u = uuid.UUID(ident_str)
        p = SavedProfile.objects.filter(id=u).first()
        if p:
            return p
    except (ValueError, AttributeError):
        pass

    # 2. Exact name match (case-insensitive)
    p = SavedProfile.objects.filter(name__iexact=ident_str).first()
    if p:
        return p

    # 3. Partial match on name
    p = SavedProfile.objects.filter(name__icontains=ident_str).first()
    if p:
        return p

    # 4. Partial match on brand or model_name
    p = SavedProfile.objects.filter(model_name__icontains=ident_str).first()
    if p:
        return p
    p = SavedProfile.objects.filter(brand__icontains=ident_str).first()
    if p:
        return p

    # 5. Token split match (e.g., 'infinix hot 60 pro')
    tokens = [t for t in ident_str.split() if len(t) > 2]
    if tokens:
        qs = SavedProfile.objects.all()
        for tok in tokens:
            qs = qs.filter(name__icontains=tok)
        p = qs.first()
        if p:
            return p

    return None


def _resolve_niche(identifier: Any) -> Optional[Niche]:
    """Resolves a niche by UUID, UUID string, exact name, or case-insensitive substring."""
    if not identifier:
        return None
    if isinstance(identifier, Niche):
        return identifier

    ident_str = str(identifier).strip()

    # 1. UUID lookup
    try:
        u = uuid.UUID(ident_str)
        n = Niche.objects.filter(id=u).first()
        if n:
            return n
    except (ValueError, AttributeError):
        pass

    # 2. Exact name match (case-insensitive)
    n = Niche.objects.filter(name__iexact=ident_str).first()
    if n:
        return n

    # 3. Partial match on name
    n = Niche.objects.filter(name__icontains=ident_str).first()
    if n:
        return n

    # 4. Token match
    tokens = [t for t in ident_str.split() if len(t) > 2]
    if tokens:
        qs = Niche.objects.all()
        for tok in tokens:
            qs = qs.filter(name__icontains=tok)
        n = qs.first()
        if n:
            return n

    return None


def tool_set_profile_niches(
    profile_id: Optional[str] = None,
    profile_name: Optional[str] = None,
    profile: Optional[str] = None,
    niche_weights: Optional[List[Dict[str, Any]]] = None,
    niche_id: Optional[str] = None,
    niche_name: Optional[str] = None,
    niche: Optional[str] = None,
    weight: int = 100
) -> Dict[str, Any]:
    """Assign weighted niche(s) to a browser profile.
    Accepts either UUIDs or human-readable names (e.g. 'Infinix Hot 60 Pro', 'Gaming Enthusiasts')
    for both profile and niche(s)."""
    target_profile_ident = profile or profile_id or profile_name
    target_profile = _resolve_profile(target_profile_ident)
    if not target_profile:
        avail = [f"'{p.name}'" for p in SavedProfile.objects.all()[:10]]
        return {"error": f"Profile '{target_profile_ident}' could not be resolved. Available profiles: {', '.join(avail)}"}

    items_to_assign = []
    single_niche_ident = niche or niche_name or niche_id
    if single_niche_ident:
        n_obj = _resolve_niche(single_niche_ident)
        if not n_obj:
            avail_n = [f"'{n.name}'" for n in Niche.objects.all()[:10]]
            return {"error": f"Niche '{single_niche_ident}' could not be resolved. Available niches: {', '.join(avail_n)}"}
        items_to_assign.append({"niche": n_obj, "weight": weight})

    if niche_weights and isinstance(niche_weights, list):
        for item in niche_weights:
            n_ident = item.get("niche_id") or item.get("niche_name") or item.get("niche")
            n_obj = _resolve_niche(n_ident)
            if n_obj:
                items_to_assign.append({"niche": n_obj, "weight": item.get("weight", 100)})

    if not items_to_assign:
        return {"error": "No valid niches provided. Specify niche_name (e.g. 'Gaming Enthusiasts') or niche_weights."}

    ProfileNicheAffiliation.objects.filter(profile=target_profile).delete()
    created = []
    for item in items_to_assign:
        aff = ProfileNicheAffiliation.objects.create(
            profile=target_profile,
            niche=item["niche"],
            weight_percentage=item.get("weight", 100)
        )
        created.append({"niche": aff.niche.name, "weight_percentage": aff.weight_percentage})

    return {
        "status": "SUCCESS",
        "profile_id": str(target_profile.id),
        "profile_name": target_profile.name,
        "affiliations": created
    }


def tool_assign_niche_to_profile(
    profile: str,
    niche: str,
    weight: int = 100
) -> Dict[str, Any]:
    """Directly assigns a target audience niche to a browser profile by name or UUID.
    Example: assign_niche_to_profile(profile='Infinix Hot 60 Pro', niche='Gaming Enthusiasts', weight=100)"""
    return tool_set_profile_niches(profile=profile, niche=niche, weight=weight)


def tool_dispatch_campaign(
    task_name: str,
    category: str,
    profile_ids: List[str],
    niche_id: Optional[str] = None,
    niche_name: Optional[str] = None,
    config: Optional[Dict[str, Any]] = None
) -> Dict[str, Any]:
    """Compiles and dispatches a DAG automation campaign (WARMING, YOUTUBE, or BLOG)
    to target profiles. Resolves profile IDs or names, and niche ID or name.
    Optional config fields: target_keyword, min_watch_seconds, max_watch_seconds,
    video_format ('long_form', 'shorts', or 'both')."""
    niche = _resolve_niche(niche_id or niche_name)

    task = AutomationTask.objects.create(
        name=task_name,
        category=category.upper(),
        niche=niche,
        config=config or {}
    )

    created_jobs = []
    for pid in profile_ids:
        profile = _resolve_profile(pid)
        if not profile:
            continue
        dag = RecipeCompiler.compile_recipe(task, profile)
        execution = Execution.objects.create(
            task=task,
            profile=profile,
            status=ExecutionStatus.PENDING,
            entry_state_id=dag.get("entry_state", "start"),
            compiled_dag=dag,
            current_state_id=dag.get("entry_state", "start")
        )
        TaskExecutionQueue.objects.create(
            id=execution.id,
            task=task,
            profile=profile,
            execution=execution
        )
        created_jobs.append(str(execution.id))

    return {
        "status": "DISPATCHED",
        "task_id": str(task.id),
        "dispatched_count": len(created_jobs),
        "queue_ids": created_jobs
    }


def tool_abort_job(
    job_id: str,
    reason: str = "Aborted by TersoAssistant"
) -> Dict[str, Any]:
    """Immediately halts an active or running profile execution job."""
    try:
        execution = Execution.objects.get(id=job_id)
        ExecutionService.abort(job=execution, reason=f"TersoAssistant abort: {reason}")
        return {
            "status": "ABORTED",
            "job_id": str(execution.id),
            "new_status": "FAILED"
        }
    except (Execution.DoesNotExist, ValueError):
        return {"error": f"Job {job_id} not found."}


def tool_get_job_telemetry(job_id: str) -> Dict[str, Any]:
    """Fetches current state node, outcome history, and error logs for an execution job."""
    try:
        execution = Execution.objects.get(id=job_id)
        logs = execution.logs if isinstance(execution.logs, list) else []
        return {
            "job_id": str(execution.id),
            "profile": execution.profile.name,
            "task": execution.task.name,
            "status": execution.status,
            "current_state_id": execution.current_state_id,
            "logs_count": len(logs),
            "recent_logs": logs[-5:],
            "error_message": execution.error_message
        }
    except (Execution.DoesNotExist, ValueError):
        return {"error": f"Job {job_id} not found."}


def tool_execute_task_on_profiles(
    profile_ids: List[str],
    task_category: str = "WARMING",
    task_name: Optional[str] = None,
    niche_id: Optional[str] = None,
    config: Optional[Dict[str, Any]] = None
) -> Dict[str, Any]:
    """Executes an automation task or warming routine directly on a selected list of browser profiles/files."""
    t_name = task_name or f"Selected Profiles {task_category.capitalize()} Task"
    return tool_dispatch_campaign(
        task_name=t_name,
        category=task_category,
        profile_ids=profile_ids,
        niche_id=niche_id,
        config=config
    )


# ---------------------------------------------------------------------------
# Phase 7: Automation V2 & Fleet Operational Tools
# ---------------------------------------------------------------------------

def tool_list_automations(enabled_only: bool = False) -> List[Dict[str, Any]]:
    """List configured V2 automations with their schedules, targeting, and operational status.
    Optionally filter by enabled_only=True."""
    from automation.models import Automation
    qs = Automation.objects.select_related("task").all()
    if enabled_only:
        qs = qs.filter(enabled=True)

    results = []
    for auto in qs:
        results.append({
            "id": str(auto.id),
            "name": auto.name,
            "task_name": auto.task.name if auto.task else "None",
            "task_category": auto.task.category if auto.task else "CUSTOM",
            "enabled": auto.enabled,
            "schedule_type": auto.schedule_type,
            "schedule_config": auto.schedule_config or {},
            "timezone": auto.timezone,
            "selection_mode": auto.selection_mode,
            "concurrency_limit": auto.concurrency_limit,
            "cooldown_minutes": auto.cooldown_minutes,
            "max_retries": auto.max_retries,
            "failure_threshold_percent": auto.failure_threshold_percent,
            "last_run_at": auto.last_run_at.isoformat() if auto.last_run_at else None,
            "next_run_at": auto.next_run_at.isoformat() if auto.next_run_at else None,
        })
    return results


def _resolve_automation(identifier: Any):
    from automation.models import Automation
    if not identifier:
        return None
    if isinstance(identifier, Automation):
        return identifier
    ident_str = str(identifier).strip()
    try:
        u = uuid.UUID(ident_str)
        a = Automation.objects.filter(id=u).first()
        if a:
            return a
    except (ValueError, AttributeError):
        pass
    a = Automation.objects.filter(name__iexact=ident_str).first()
    if a:
        return a
    return Automation.objects.filter(name__icontains=ident_str).first()


def tool_get_automation(automation: str) -> Dict[str, Any]:
    """Inspect a specific automation schedule definition by name or UUID.
    Returns its task, cadence, targeting policy, concurrency, cooldown, and last/next run times."""
    auto = _resolve_automation(automation)
    if not auto:
        return {"error": f"Automation '{automation}' not found."}
    return {
        "id": str(auto.id),
        "name": auto.name,
        "description": auto.description,
        "task_id": str(auto.task.id) if auto.task else None,
        "task_name": auto.task.name if auto.task else "None",
        "task_category": auto.task.category if auto.task else "CUSTOM",
        "enabled": auto.enabled,
        "schedule_type": auto.schedule_type,
        "schedule_config": auto.schedule_config or {},
        "timezone": auto.timezone,
        "selection_mode": auto.selection_mode,
        "target_profile_ids": [str(p.id) for p in auto.target_profiles.all()],
        "target_niche_ids": [str(n.id) for n in auto.target_niches.all()],
        "concurrency_limit": auto.concurrency_limit,
        "cooldown_minutes": auto.cooldown_minutes,
        "max_runtime_seconds": auto.max_runtime_seconds,
        "max_retries": auto.max_retries,
        "failure_threshold_percent": auto.failure_threshold_percent,
        "last_run_at": auto.last_run_at.isoformat() if auto.last_run_at else None,
        "next_run_at": auto.next_run_at.isoformat() if auto.next_run_at else None,
        "created_at": auto.created_at.isoformat() if auto.created_at else None,
        "updated_at": auto.updated_at.isoformat() if auto.updated_at else None,
    }


def tool_get_automation_run(run_id_or_key: str) -> Dict[str, Any]:
    """Inspects a specific AutomationRun execution batch by UUID or run_key.
    Returns status, queued count, running count, success count, failure count, and breaker status."""
    from automation.models import AutomationRun
    run = None
    try:
        u = uuid.UUID(run_id_or_key.strip())
        run = AutomationRun.objects.filter(id=u).first()
    except (ValueError, AttributeError):
        pass

    if not run:
        run = AutomationRun.objects.filter(run_key__iexact=run_id_or_key.strip()).first()
    if not run:
        run = AutomationRun.objects.filter(run_key__icontains=run_id_or_key.strip()).first()

    if not run:
        return {"error": f"AutomationRun '{run_id_or_key}' not found."}

    return {
        "id": str(run.id),
        "automation_name": run.automation.name if run.automation else "None",
        "run_key": run.run_key,
        "status": run.status,
        "scheduled_for": run.scheduled_for.isoformat() if run.scheduled_for else None,
        "started_at": run.started_at.isoformat() if run.started_at else None,
        "completed_at": run.completed_at.isoformat() if run.completed_at else None,
        "total_target_profiles": run.total_target_profiles,
        "queued_count": run.queued_count,
        "dispatched_count": run.dispatched_count,
        "running_count": run.running_count,
        "success_count": run.success_count,
        "failure_count": run.failure_count,
        "stalled_count": run.stalled_count,
        "cancelled_count": run.cancelled_count,
        "summary_metrics": run.summary_metrics or {}
    }


def tool_run_automation_now(automation: str) -> Dict[str, Any]:
    """Immediately triggers an automation schedule by name or UUID without waiting for cron/cadence.
    Mints an authoritative AutomationRun and queues eligible profile executions."""
    from automation.scheduler.service import SchedulerService
    import time
    auto_obj = _resolve_automation(automation)
    if not auto_obj:
        return {"error": f"Automation '{automation}' not found."}

    run_key = f"{auto_obj.id}_assistant_{int(time.time())}"
    run = SchedulerService.create_run_if_due(
        automation=auto_obj,
        run_key=run_key
    )
    if not run:
        return {
            "status": "FAILED",
            "error": "Unable to mint run. Check profile eligibility, concurrency limits, or active leases."
        }

    return {
        "status": "RUNNING",
        "run_id": str(run.id),
        "run_key": run.run_key,
        "automation": auto_obj.name,
        "queued_executions": run.queued_count,
        "total_targets": run.total_target_profiles
    }


def tool_pause_automation(automation: str) -> Dict[str, Any]:
    """Pauses an automation schedule without deleting configurations.
    Stops the scheduler from minting new runs."""
    auto_obj = _resolve_automation(automation)
    if not auto_obj:
        return {"error": f"Automation '{automation}' not found."}

    auto_obj.enabled = False
    auto_obj.save(update_fields=["enabled", "updated_at"])
    return {
        "status": "PAUSED",
        "automation_id": str(auto_obj.id),
        "name": auto_obj.name,
        "enabled": False
    }


def tool_resume_automation(automation: str) -> Dict[str, Any]:
    """Resumes an automation schedule so the scheduler actively evaluates and dispatches it."""
    auto_obj = _resolve_automation(automation)
    if not auto_obj:
        return {"error": f"Automation '{automation}' not found."}

    auto_obj.enabled = True
    auto_obj.save(update_fields=["enabled", "updated_at"])
    return {
        "status": "RESUMED",
        "automation_id": str(auto_obj.id),
        "name": auto_obj.name,
        "enabled": True
    }


def tool_diagnose_fleet() -> Dict[str, Any]:
    """Performs a comprehensive diagnostic health check across all registered worker nodes.
    Reports online/busy/offline/disabled device counts, low battery alerts, and stalled devices."""
    from devices.models import Device, DeviceStatus
    from django.utils import timezone
    import datetime

    now = timezone.now()
    all_devices = Device.objects.all()
    total = all_devices.count()
    online = all_devices.filter(status=DeviceStatus.ONLINE).count()
    busy = all_devices.filter(status=DeviceStatus.BUSY).count()
    offline = all_devices.filter(status=DeviceStatus.OFFLINE).count()
    disabled = all_devices.filter(status=DeviceStatus.DISABLED).count()

    device_summaries = []
    low_battery_nodes = []
    stale_nodes = []

    for dev in all_devices[:20]:
        diff_hb = int((now - dev.last_heartbeat).total_seconds()) if dev.last_heartbeat else 9999
        summary = {
            "device_id": dev.device_id,
            "brand": dev.brand,
            "model_name": dev.model_name,
            "status": dev.status,
            "battery_percent": dev.battery_percent,
            "android_version": dev.android_version,
            "geckoview_version": dev.geckoview_version or "N/A",
            "last_heartbeat_seconds_ago": diff_hb,
            "active_execution": str(dev.current_execution_id) if dev.current_execution_id else None
        }
        device_summaries.append(summary)
        if dev.battery_percent < 20:
            low_battery_nodes.append(dev.device_id)
        if diff_hb > 120 and dev.status != DeviceStatus.DISABLED:
            stale_nodes.append(dev.device_id)

    return {
        "total_nodes": total,
        "online_nodes": online,
        "busy_nodes": busy,
        "offline_nodes": offline,
        "disabled_nodes": disabled,
        "low_battery_alerts": low_battery_nodes,
        "stale_heartbeat_alerts": stale_nodes,
        "devices": device_summaries
    }


def tool_get_device_status(device_id: Optional[str] = None) -> Dict[str, Any]:
    """Inspects status, battery level, online state, hardware specs, and active execution lease
    for a specific device, or returns full fleet diagnostics if device_id is omitted."""
    from devices.models import Device
    from django.utils import timezone
    if not device_id:
        return tool_diagnose_fleet()

    now = timezone.now()
    dev = Device.objects.filter(device_id__iexact=device_id.strip()).first()
    if not dev:
        return {"error": f"Device '{device_id}' not found."}

    diff_hb = int((now - dev.last_heartbeat).total_seconds()) if dev.last_heartbeat else 9999
    return {
        "device_id": dev.device_id,
        "brand": dev.brand,
        "model_name": dev.model_name,
        "status": dev.status,
        "battery_percent": dev.battery_percent,
        "android_version": dev.android_version,
        "geckoview_version": dev.geckoview_version or "N/A",
        "last_heartbeat_seconds_ago": diff_hb,
        "current_execution_id": str(dev.current_execution_id) if dev.current_execution_id else None,
        "capabilities": dev.capabilities or {}
    }


def tool_explain_recovery(execution_id: str) -> Dict[str, Any]:
    """Inspects an execution's self-healing recovery trail, checkpoint versions,
    retry history, and recent audit events to explain why it stalled or how it resumed."""
    from executions.models import Execution, ExecutionEvent
    try:
        u = uuid.UUID(execution_id.strip())
        execution = Execution.objects.filter(id=u).first()
    except (ValueError, AttributeError):
        execution = None

    if not execution:
        return {"error": f"Execution '{execution_id}' not found."}

    events = ExecutionEvent.objects.filter(execution=execution).order_by("-created_at")[:8]
    event_trail = [
        {
            "event_type": ev.event_type,
            "created_at": ev.created_at.isoformat(),
            "payload": ev.payload
        }
        for ev in events
    ]

    diagnosis = "Execution is progressing normally."
    if execution.status == "STALLED":
        diagnosis = (
            f"Execution stalled at state '{execution.last_confirmed_state or execution.current_state_id}' "
            f"(Checkpoint v{execution.checkpoint_version}). Worker lease timed out or lost connectivity. "
            f"Recovery status is '{execution.recovery_status}' with {execution.retry_count}/{execution.max_retries} retries used. "
            "It will be automatically claimed by an eligible online device."
        )
    elif execution.status == "FAILED":
        if execution.recovery_status == "EXHAUSTED":
            diagnosis = f"Execution failed permanently: retry limit ({execution.max_retries}) exhausted after repeated stalls."
        else:
            diagnosis = f"Execution failed with error: {execution.error_message or 'Unknown error'}."
    elif execution.status == "SUCCESS":
        diagnosis = f"Execution successfully completed all DAG actions."

    return {
        "execution_id": str(execution.id),
        "profile": execution.profile.name if execution.profile else "Unknown",
        "status": execution.status,
        "recovery_status": execution.recovery_status,
        "retry_count": execution.retry_count,
        "max_retries": execution.max_retries,
        "checkpoint_version": execution.checkpoint_version,
        "last_confirmed_state": execution.last_confirmed_state,
        "last_confirmed_step": execution.last_confirmed_step,
        "error_message": execution.error_message,
        "diagnosis_explanation": diagnosis,
        "recent_audit_events": event_trail
    }


# ---------------------------------------------------------------------------
# Tool Dispatch Router
# ---------------------------------------------------------------------------

# Apply the same server-controlled write policy to Gemini callables and the
# OpenRouter dispatcher. Model-generated arguments cannot enable write access.
from functools import wraps
from django.conf import settings


def _controlled_write(function):
    @wraps(function)
    def guarded(*args, **kwargs):
        if not settings.ASSISTANT_ALLOW_WRITES:
            return {"error": "Assistant is in read-only mode. Use the dashboard controls for changes."}
        return function(*args, **kwargs)
    return guarded


tool_create_niche = _controlled_write(tool_create_niche)
tool_set_profile_niches = _controlled_write(tool_set_profile_niches)
tool_assign_niche_to_profile = _controlled_write(tool_assign_niche_to_profile)
tool_dispatch_campaign = _controlled_write(tool_dispatch_campaign)
tool_execute_task_on_profiles = _controlled_write(tool_execute_task_on_profiles)
tool_abort_job = _controlled_write(tool_abort_job)
tool_run_automation_now = _controlled_write(tool_run_automation_now)
tool_pause_automation = _controlled_write(tool_pause_automation)
tool_resume_automation = _controlled_write(tool_resume_automation)

TOOL_MAP = {
    "get_fleet_status": tool_get_fleet_status,
    "list_profiles": tool_list_profiles,
    "create_niche": tool_create_niche,
    "set_profile_niches": tool_set_profile_niches,
    "assign_niche_to_profile": tool_assign_niche_to_profile,
    "dispatch_campaign": tool_dispatch_campaign,
    "execute_task_on_profiles": tool_execute_task_on_profiles,
    "abort_job": tool_abort_job,
    "get_job_telemetry": tool_get_job_telemetry,
    # Phase 7 tools
    "list_automations": tool_list_automations,
    "get_automation": tool_get_automation,
    "get_automation_run": tool_get_automation_run,
    "run_automation_now": tool_run_automation_now,
    "pause_automation": tool_pause_automation,
    "resume_automation": tool_resume_automation,
    "diagnose_fleet": tool_diagnose_fleet,
    "get_device_status": tool_get_device_status,
    "explain_recovery": tool_explain_recovery,
}


def execute_tool(name: str, arguments: Dict[str, Any]) -> Any:
    """Central dispatcher: looks up a tool by name and executes it with the given arguments."""
    handler = TOOL_MAP.get(name)
    if not handler:
        return {"error": f"Tool '{name}' is not recognized."}
    try:
        return handler(**arguments)
    except Exception as e:
        return {"error": f"Execution failed for tool '{name}': {str(e)}"}


# ---------------------------------------------------------------------------
# Standardized Tool Declarations (OpenAI / OpenRouter format)
# ---------------------------------------------------------------------------

OPENROUTER_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_fleet_status",
            "description": (
                "Returns a high-level summary of total browser profiles, "
                "running jobs, pending jobs, and maturation score distributions."
            ),
            "parameters": {"type": "object", "properties": {}, "required": []}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_profiles",
            "description": (
                "List browser profiles with their trust score, assigned niches, "
                "and maturation status."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "maturation_stage": {
                        "type": "string",
                        "enum": ["INFANT", "SEEDING", "MATURING", "MATURE"],
                        "description": "Optional filter for profile maturity."
                    }
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "create_niche",
            "description": (
                "Create a new target audience niche with seed keywords, "
                "target channels, and authority sites."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the niche, e.g. Mechanical Keyboards"
                    },
                    "description": {
                        "type": "string",
                        "description": "Brief description of the domain"
                    },
                    "seed_keywords": {
                        "type": "array",
                        "items": {"type": "string"}
                    },
                    "seed_websites": {
                        "type": "array",
                        "items": {"type": "string"}
                    },
                    "target_youtube_channels": {
                        "type": "array",
                        "items": {"type": "string"}
                    }
                },
                "required": ["name"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "assign_niche_to_profile",
            "description": (
                "Directly assigns a target audience niche to a browser profile. "
                "Accepts profile name (e.g. 'Infinix Hot 60 Pro') OR profile UUID, "
                "and niche name (e.g. 'Gaming Enthusiasts') OR niche UUID. "
                "NEVER ask the operator for a UUID when they provided a profile name."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "profile": {
                        "type": "string",
                        "description": "Profile name (e.g. 'Infinix Hot 60 Pro') or profile UUID"
                    },
                    "niche": {
                        "type": "string",
                        "description": "Niche name (e.g. 'Gaming Enthusiasts') or niche UUID"
                    },
                    "weight": {
                        "type": "integer",
                        "description": "Weight percentage (1 to 100, default 100)"
                    }
                },
                "required": ["profile", "niche"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "set_profile_niches",
            "description": (
                "Assign multiple weighted niches to a profile (e.g. 70% Tech, 30% Crypto). "
                "Profile and niches can be specified by names or UUIDs."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "profile": {
                        "type": "string",
                        "description": "Profile name (e.g. 'Infinix Hot 60 Pro') or profile UUID"
                    },
                    "profile_id": {
                        "type": "string",
                        "description": "Alias for profile (name or UUID)"
                    },
                    "niche_name": {
                        "type": "string",
                        "description": "Single niche name to assign"
                    },
                    "niche_weights": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "niche": {"type": "string", "description": "Niche name or UUID"},
                                "niche_id": {"type": "string", "description": "Niche name or UUID"},
                                "weight": {"type": "integer"}
                            }
                        }
                    }
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "dispatch_campaign",
            "description": (
                "Compiles and dispatches a DAG automation campaign "
                "(WARMING or YOUTUBE) to target profiles."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "task_name": {
                        "type": "string",
                        "description": "Title of the campaign"
                    },
                    "category": {
                        "type": "string",
                        "enum": ["WARMING", "YOUTUBE", "BLOG"]
                    },
                    "profile_ids": {
                        "type": "array",
                        "items": {"type": "string"}
                    },
                    "niche_id": {
                        "type": "string",
                        "description": "Optional Niche UUID"
                    },
                    "config": {
                        "type": "object",
                        "description": (
                            "Optional task settings such as min_watch_seconds, "
                            "max_watch_seconds, target_keyword, video_format ('long_form', 'shorts', or 'both')"
                        )
                    }
                },
                "required": ["task_name", "category", "profile_ids"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "abort_job",
            "description": (
                "Immediately halts an active or running profile execution job."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "job_id": {
                        "type": "string",
                        "description": "UUID of the execution queue entry"
                    },
                    "reason": {
                        "type": "string",
                        "description": "Why the task is being aborted"
                    }
                },
                "required": ["job_id"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_job_telemetry",
            "description": (
                "Fetches current state node, outcome history, and error logs "
                "for an execution job."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "job_id": {
                        "type": "string",
                        "description": "UUID of the execution queue entry"
                    }
                },
                "required": ["job_id"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "execute_task_on_profiles",
            "description": (
                "Executes an automation task or warming routine directly on a "
                "specified list of profile UUIDs (e.g. profiles/files selected by the operator on screen)."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "profile_ids": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "List of profile UUIDs to execute the task on"
                    },
                    "task_category": {
                        "type": "string",
                        "enum": ["WARMING", "YOUTUBE", "BLOG"],
                        "description": "Category of task to dispatch"
                    },
                    "task_name": {
                        "type": "string",
                        "description": "Descriptive name for this execution"
                    },
                    "niche_id": {
                        "type": "string",
                        "description": "Optional UUID of the niche to apply"
                    }
                },
                "required": ["profile_ids"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_automations",
            "description": (
                "List configured V2 automation schedules with their task, cadence, "
                "targeting policy, concurrency, cooldown, and operational status."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "enabled_only": {
                        "type": "boolean",
                        "description": "If true, only returns active/enabled automations."
                    }
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_automation",
            "description": (
                "Inspect a specific automation schedule definition by name or UUID, "
                "returning cadence, target profiles/niches, concurrency, cooldown, and last/next run times."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "automation": {
                        "type": "string",
                        "description": "Name or UUID of the automation schedule to inspect."
                    }
                },
                "required": ["automation"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_automation_run",
            "description": (
                "Inspects a specific AutomationRun execution batch by UUID or run_key. "
                "Returns status, queued count, running count, success count, failure count, and breaker status."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "run_id_or_key": {
                        "type": "string",
                        "description": "UUID or run_key of the automation run batch to inspect."
                    }
                },
                "required": ["run_id_or_key"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "run_automation_now",
            "description": (
                "Immediately triggers an automation schedule by name or UUID without waiting for scheduled cadence. "
                "Mints an authoritative AutomationRun and queues eligible profile executions."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "automation": {
                        "type": "string",
                        "description": "Name or UUID of the automation schedule to trigger."
                    }
                },
                "required": ["automation"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "pause_automation",
            "description": (
                "Pauses an automation schedule without deleting configurations, "
                "preventing the scheduler from minting new runs."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "automation": {
                        "type": "string",
                        "description": "Name or UUID of the automation schedule to pause."
                    }
                },
                "required": ["automation"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "resume_automation",
            "description": (
                "Resumes an automation schedule so the scheduler actively evaluates and dispatches it."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "automation": {
                        "type": "string",
                        "description": "Name or UUID of the automation schedule to resume."
                    }
                },
                "required": ["automation"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "diagnose_fleet",
            "description": (
                "Performs a comprehensive diagnostic health check across all registered worker nodes, "
                "reporting online/busy/offline/disabled device counts, low battery alerts, and stalled devices."
            ),
            "parameters": {
                "type": "object",
                "properties": {}
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_device_status",
            "description": (
                "Inspects status, battery level, online state, hardware specs, and active execution lease "
                "for a specific device, or diagnoses all devices if device_id is omitted."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "device_id": {
                        "type": "string",
                        "description": "Optional hardware device ID to inspect specifically."
                    }
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "explain_recovery",
            "description": (
                "Inspects an execution's self-healing recovery trail, checkpoint versions, "
                "retry history, and recent audit events to explain why it stalled or how it resumed."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "execution_id": {
                        "type": "string",
                        "description": "UUID of the execution to analyze."
                    }
                },
                "required": ["execution_id"]
            }
        }
    }
]
