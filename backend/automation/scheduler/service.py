import logging
import datetime
from typing import List, Dict, Any
from django.db import transaction, IntegrityError
from django.utils import timezone
from automation.models import Automation, AutomationRun, AutomationRunStatus, AutomationTask
from executions.models import Execution, ExecutionStatus, ExecutionPlan, RecoveryStatus
from automation.compiler import RecipeCompiler
from .policies import is_automation_due
from .planner import EligibilityEngine, EligibilityReason

logger = logging.getLogger(__name__)


class SchedulerService:
    """
    Authoritative service orchestrating automation schedule evaluation,
    idempotent run minting, plan compilation, and execution queueing.
    """

    @classmethod
    def evaluate_due_automations(cls, now_dt: datetime.datetime | None = None) -> List[AutomationRun]:
        """
        Scans all enabled automations, identifies those due for execution,
        and atomically creates runs and queued executions.
        """
        now = now_dt or timezone.now()
        due_runs: List[AutomationRun] = []

        automations = Automation.objects.filter(enabled=True).select_related("task")
        for automation in automations:
            try:
                is_due, run_key, next_run = is_automation_due(automation, now_dt=now)
                if next_run and automation.next_run_at != next_run:
                    automation.next_run_at = next_run
                    automation.save(update_fields=["next_run_at", "updated_at"])

                if is_due and run_key:
                    run = cls.create_run_if_due(
                        automation=automation,
                        run_key=run_key,
                        now_dt=now
                    )
                    if run:
                        due_runs.append(run)
            except Exception as e:
                logger.error(
                    f"Error evaluating automation '{automation.name}' ({automation.id}): {e}",
                    exc_info=True
                )

        return due_runs

    @classmethod
    @transaction.atomic
    def build_execution_plan(cls, task: AutomationTask) -> ExecutionPlan:
        """
        Retrieves or compiles a versioned ExecutionPlan for the task.
        If the task configuration changed since the latest plan, a new immutable version is compiled.
        """
        latest_plan = ExecutionPlan.objects.filter(task=task).order_by("-version").first()
        task_config = task.config or {}

        if latest_plan and latest_plan.config_snapshot == task_config:
            return latest_plan

        next_version = (latest_plan.version + 1) if latest_plan else 1
        try:
            compiled_dag = RecipeCompiler.compile_recipe(task, profile=None)
        except Exception as e:
            logger.warning(
                f"Canonical recipe compile with null profile failed for task {task.id}: {e}. "
                "Compiling default fallback DAG."
            )
            compiled_dag = {
                "entry_state": "init",
                "states": {
                    "init": {"cmd": "WAIT", "params": {"seconds": 2}, "on_success": "exit"},
                    "exit": {"cmd": "TERMINATE", "params": {}}
                }
            }

        plan = ExecutionPlan.objects.create(
            task=task,
            version=next_version,
            compiler_version="v2.0.0",
            compiled_dag=compiled_dag,
            config_snapshot=task_config
        )
        logger.info(f"Minted ExecutionPlan v{plan.version} for task '{task.name}' ({task.id})")
        return plan

    @classmethod
    def calculate_eligible_profiles(cls, automation: Automation, now_dt=None) -> List[Dict[str, Any]]:
        """Delegates profile eligibility determination to EligibilityEngine."""
        return EligibilityEngine.calculate_eligible_profiles(automation, now_dt=now_dt)

    @classmethod
    @transaction.atomic
    def create_run_if_due(
        cls,
        automation: Automation,
        run_key: str,
        now_dt: datetime.datetime | None = None
    ) -> AutomationRun | None:
        """
        Atomically mints an AutomationRun and associated PENDING executions.
        Guarantees strict idempotency: if run_key already exists, duplicate runs are prevented.
        """
        now = now_dt or timezone.now()

        # Idempotent acquisition of run
        try:
            run, created = AutomationRun.objects.get_or_create(
                automation=automation,
                run_key=run_key,
                defaults={
                    "scheduled_for": now,
                    "status": AutomationRunStatus.SCHEDULED,
                    "started_at": now
                }
            )
        except IntegrityError:
            # Another concurrent scheduler worker minted this exact run_key
            logger.info(f"Run {run_key} was already created concurrently.")
            return AutomationRun.objects.filter(automation=automation, run_key=run_key).first()

        if not created:
            logger.debug(f"Run {run_key} already exists. Skipping duplicate dispatch.")
            return run

        # Resolve candidates and filter eligible profiles
        evaluation_results = cls.calculate_eligible_profiles(automation, now_dt=now)
        eligible_items = [item for item in evaluation_results if item["eligible"]]

        run.total_target_profiles = len(evaluation_results)

        if not eligible_items:
            logger.info(
                f"Automation '{automation.name}' run {run_key} had 0 eligible profiles out of "
                f"{len(evaluation_results)} candidates. Marking COMPLETED."
            )
            run.status = AutomationRunStatus.COMPLETED
            run.completed_at = now
            run.summary_metrics = {
                "evaluations": [
                    {"profile_id": item["profile_id"], "reason": item["reason"]}
                    for item in evaluation_results
                ]
            }
            run.save()

            automation.last_run_at = now
            automation.save(update_fields=["last_run_at", "updated_at"])
            return run

        # Ensure task has a valid ExecutionPlan
        plan = cls.build_execution_plan(automation.task)

        # Batch create PENDING executions
        executions_to_create = []
        for item in eligible_items:
            profile = item["profile"]
            # Personalize DAG for this profile/persona if compiler supports it
            try:
                profile_dag = RecipeCompiler.compile_recipe(automation.task, profile=profile)
            except Exception:
                profile_dag = plan.compiled_dag

            exec_instance = Execution(
                task=automation.task,
                profile=profile,
                automation_run=run,
                plan=plan,
                plan_version=str(plan.version),
                status=ExecutionStatus.PENDING,
                retry_count=0,
                max_retries=automation.max_retries,
                recovery_status=RecoveryStatus.NONE,
                compiled_dag=profile_dag,
                entry_state_id=profile_dag.get("entry_state", "start"),
                current_state_id=profile_dag.get("entry_state", "start"),
                execution_context={
                    "automation_id": str(automation.id),
                    "automation_name": automation.name,
                    "run_key": run_key
                }
            )
            executions_to_create.append(exec_instance)

        created_executions = Execution.objects.bulk_create(executions_to_create)
        run.queued_count = len(created_executions)
        run.status = AutomationRunStatus.SCHEDULED
        run.summary_metrics = {
            "queued_profile_ids": [str(e.profile_id) for e in created_executions],
            "excluded": [
                {"profile_id": item["profile_id"], "reason": item["reason"]}
                for item in evaluation_results if not item["eligible"]
            ]
        }
        run.save()

        automation.last_run_at = now
        automation.save(update_fields=["last_run_at", "updated_at"])

        logger.info(
            f"Successfully minted Run '{run_key}' for Automation '{automation.name}' with "
            f"{len(created_executions)} queued executions."
        )
        return run

    @classmethod
    def queue_run(cls, run: AutomationRun) -> None:
        """Transitions scheduled run to RUNNING when executions begin dispatching."""
        if run.status == AutomationRunStatus.SCHEDULED:
            run.status = AutomationRunStatus.RUNNING
            if not run.started_at:
                run.started_at = timezone.now()
            run.save(update_fields=["status", "started_at", "updated_at"])
