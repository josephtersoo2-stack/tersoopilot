"""
TersoAssistant Tool Catalog & Registry
=======================================
Concrete tool implementations that interface directly with existing Django models,
plus dual-format schema declarations for OpenRouter (OpenAI-compatible) and Gemini
Automatic Function Calling.
"""

import json
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
from automation.compiler import RecipeCompiler


# ---------------------------------------------------------------------------
# Concrete Tool Implementations
# ---------------------------------------------------------------------------

def tool_get_fleet_status() -> Dict[str, Any]:
    """Returns a high-level summary of total browser profiles, running jobs,
    pending jobs, failed jobs, and maturation score distributions."""
    total_profiles = SavedProfile.objects.count()
    active_jobs = TaskExecutionQueue.objects.filter(
        status=TaskExecutionQueue.ExecutionStatus.RUNNING
    ).count()
    pending_jobs = TaskExecutionQueue.objects.filter(
        status=TaskExecutionQueue.ExecutionStatus.PENDING
    ).count()
    failed_jobs = TaskExecutionQueue.objects.filter(
        status=TaskExecutionQueue.ExecutionStatus.FAILED
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
        job = TaskExecutionQueue.objects.create(
            task=task,
            profile=profile,
            status=TaskExecutionQueue.ExecutionStatus.PENDING,
            entry_state_id=dag["entry_state"],
            compiled_dag=dag,
            current_state_id=dag["entry_state"]
        )
        created_jobs.append(str(job.id))

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
        job = TaskExecutionQueue.objects.get(id=job_id)
        job.status = TaskExecutionQueue.ExecutionStatus.FAILED
        job.error_message = f"TersoAssistant abort: {reason}"
        job.save()
        return {
            "status": "ABORTED",
            "job_id": str(job.id),
            "new_status": "FAILED"
        }
    except TaskExecutionQueue.DoesNotExist:
        return {"error": f"Job {job_id} not found."}


def tool_get_job_telemetry(job_id: str) -> Dict[str, Any]:
    """Fetches current state node, outcome history, and error logs for an execution job."""
    try:
        job = TaskExecutionQueue.objects.get(id=job_id)
        logs = job.logs if isinstance(job.logs, list) else []
        return {
            "job_id": str(job.id),
            "profile": job.profile.name,
            "task": job.task.name,
            "status": job.status,
            "current_state_id": job.current_state_id,
            "logs_count": len(logs),
            "recent_logs": logs[-5:],
            "error_message": job.error_message
        }
    except TaskExecutionQueue.DoesNotExist:
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
# Tool Dispatch Router
# ---------------------------------------------------------------------------

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
    }
]
