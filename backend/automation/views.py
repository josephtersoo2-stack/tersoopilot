import time
import json
import random
import uuid
from rest_framework import viewsets, status, serializers
from rest_framework.permissions import IsAdminUser
from rest_framework.exceptions import ValidationError
from django.db import transaction
from core.permissions import visible_profiles
from rest_framework.decorators import action
from rest_framework.response import Response
from django.utils import timezone
from django.http import StreamingHttpResponse

from .models import (
    Niche,
    ProfilePersona,
    ProfileNicheAffiliation,
    AutomationTask,
    TaskExecutionQueue,
    AIPromptConfig,
    AssistantSession,
    AssistantMessage,
)
from .serializers import (
    NicheSerializer,
    ProfilePersonaSerializer,
    ProfileNicheAffiliationSerializer,
    AutomationTaskSerializer,
    TaskExecutionQueueSerializer,
    AIPromptConfigSerializer,
    AssistantSessionSerializer,
    AssistantMessageSerializer,
)
from .compiler import RecipeCompiler
from .decision_engine import GhostPilotDecisionEngine
from devices.models import SavedProfile


class NicheViewSet(viewsets.ModelViewSet):
    permission_classes = [IsAdminUser]
    queryset = Niche.objects.all().order_by("name")
    serializer_class = NicheSerializer


class AutomationTaskViewSet(viewsets.ModelViewSet):
    permission_classes = [IsAdminUser]
    queryset = AutomationTask.objects.all().order_by("-created_at")
    serializer_class = AutomationTaskSerializer

    @action(detail=True, methods=["post"], url_path="dispatch")
    @transaction.atomic
    def dispatch_task(self, request, pk=None):
        """
        Compiles distinct, persona-tuned DAG recipes and creates queue entries.
        Payload:
        {
            "profile_ids": ["<uuid1>", "<uuid2>"]
        }
        """
        task = self.get_object()
        validator = serializers.ListField(child=serializers.UUIDField(), min_length=1, max_length=100)
        profile_ids = validator.run_validation(request.data.get("profile_ids", []))
        profile_ids = list(dict.fromkeys(profile_ids))
        if visible_profiles(request.user).filter(pk__in=profile_ids).count() != len(profile_ids):
            raise ValidationError({"profile_ids": "One or more profiles do not exist or are inaccessible."})

        created_jobs = []
        for pid in profile_ids:
            try:
                profile = SavedProfile.objects.get(id=pid)
            except (SavedProfile.DoesNotExist, ValueError):
                continue

            # Compile personalized DAG
            compiled_dag = RecipeCompiler.compile_recipe(task, profile)

            job = TaskExecutionQueue.objects.create(
                task=task,
                profile=profile,
                status=TaskExecutionQueue.ExecutionStatus.PENDING,
                entry_state_id=compiled_dag.get("entry_state", "start"),
                compiled_dag=compiled_dag,
                current_state_id=compiled_dag.get("entry_state", "start")
            )
            created_jobs.append(str(job.id))

        return Response({
            "status": "DISPATCHED",
            "task_id": str(task.id),
            "dispatched_count": len(created_jobs),
            "queue_ids": created_jobs
        }, status=status.HTTP_201_CREATED)


class GhostPilotViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Fleet control API consumed by the mobile GhostPilot runner and React admin dashboard.
    Supports polling, state machine transitions, heartbeat liveness, operator aborts,
    AI-driven fallback decision engine, and SSE live telemetry streaming.
    """
    queryset = TaskExecutionQueue.objects.all().order_by("-started_at")
    serializer_class = TaskExecutionQueueSerializer

    def get_queryset(self):
        return TaskExecutionQueue.objects.filter(profile__in=visible_profiles(self.request.user)).order_by("-started_at")

    def _handle_poll(self, request, profile_id):
        profiles = visible_profiles(request.user)
        identifiers = [request.query_params.get("cloud_sync_id"), profile_id, request.query_params.get("profile_id")]
        profile = None
        for identifier in filter(None, identifiers):
            try:
                profile = profiles.filter(id=uuid.UUID(str(identifier))).first()
            except (ValueError, TypeError, AttributeError):
                pass
            if not profile:
                matches = list(profiles.filter(device_sync_id=identifier)[:2])
                profile = matches[0] if len(matches) == 1 else None
            if profile:
                break
        if not profile:
            name = request.query_params.get("profile_name") or request.query_params.get("name") or profile_id
            matches = list(profiles.filter(name=name)[:2]) if name else []
            profile = matches[0] if len(matches) == 1 else None
        if not profile:
            return Response({"work_available": False})
        pending = self.get_queryset().filter(profile=profile, status="PENDING").order_by("task__created_at")
        for job in pending:
            # Compare-and-swap prevents two polling clients from claiming the same job.
            claimed = TaskExecutionQueue.objects.filter(pk=job.pk, status="PENDING").update(status="DISPATCHED", started_at=timezone.now())
            if claimed:
                return Response({"work_available": True, "job_id": str(job.id),
                                 "backend_profile_id": str(job.profile_id),
                                 "entry_state": job.entry_state_id, "dag": job.compiled_dag})
        return Response({"work_available": False})

    @action(detail=False, methods=["get"], url_path="poll/(?P<profile_id>[^/.]+)")
    def poll_by_id(self, request, profile_id=None):
        """Fetch the next pending DAG execution job for a profile via path."""
        return self._handle_poll(request, profile_id)

    @action(detail=False, methods=["get"], url_path="poll")
    def poll(self, request):
        """Fetch the next pending DAG execution job for a profile via query param."""
        profile_id = request.query_params.get("profile_id")
        return self._handle_poll(request, profile_id)

    @action(detail=True, methods=["post"], url_path="transition")
    @transaction.atomic
    def transition_state(self, request, pk=None):
        """
        Advance DAG state based on client execution outcome.
        Payload:
        {
            "outcome": "SUCCESS" | "CONSENT_WALL" | "AD_ACTIVE" | "FAILURE",
            "context_update": {"watch_time": 120},
            "error": "Optional error string"
        }
        """
        job = self.get_object()
        job = TaskExecutionQueue.objects.select_for_update().get(pk=job.pk)
        transition_id = request.data.get("transition_id")
        if transition_id is not None and (not isinstance(transition_id, str) or len(transition_id) > 100):
            raise ValidationError({"transition_id": "Must be a string of at most 100 characters."})
        if transition_id and any(entry.get("transition_id") == transition_id for entry in (job.logs or []) if isinstance(entry, dict)):
            node = job.compiled_dag.get("states", {}).get(job.current_state_id, {})
            return Response({"status": "ADVANCED", "current_state_id": job.current_state_id,
                             "is_terminal": job.status in ("SUCCESS", "FAILED"),
                             "command": node.get("command"), "params": node.get("params", {})})
        if job.status in ("SUCCESS", "FAILED"):
            return Response({"error": "Execution is already terminal."}, status=409)
        outcome = request.data.get("outcome", "SUCCESS")
        context_update = request.data.get("context_update", {})
        error = request.data.get("error")
        if not isinstance(context_update, dict) or not isinstance(outcome, str):
            raise ValidationError("outcome must be a string and context_update an object.")

        states = job.compiled_dag.get("states", {})
        current_node = states.get(job.current_state_id)

        if not current_node:
            job.status = TaskExecutionQueue.ExecutionStatus.FAILED
            job.error_message = f"Node '{job.current_state_id}' not found in DAG."
            job.save()
            return Response({"status": "ERROR", "message": job.error_message}, status=status.HTTP_400_BAD_REQUEST)

        transitions = current_node.get("transitions", {})
        if outcome not in transitions and outcome != "FAILURE":
            raise ValidationError({"outcome": "Outcome is not defined for the current state."})
        next_state_id = transitions.get(outcome, transitions.get("FAILURE", "exit"))
        if next_state_id != "exit" and next_state_id not in states:
            raise ValidationError("Transition references a missing state.")

        # Update execution context and logs
        if not isinstance(job.execution_context, dict):
            job.execution_context = {}
        job.execution_context.update(context_update)

        if not isinstance(job.logs, list):
            job.logs = []
        job.logs.append({
            "transition_id": transition_id,
            "from_state": job.current_state_id,
            "outcome": outcome,
            "to_state": next_state_id,
            "timestamp": timezone.now().isoformat()
        })

        job.current_state_id = next_state_id

        # Check for terminal state
        terminal = next_state_id == "exit" or states.get(next_state_id, {}).get("command") == "TERMINATE"
        if terminal and outcome == "FAILURE":
            job.status = TaskExecutionQueue.ExecutionStatus.FAILED
            job.error_message = error or "Execution failed at terminal state."
            job.completed_at = timezone.now()
        elif terminal:
            job.status = TaskExecutionQueue.ExecutionStatus.SUCCESS
            job.completed_at = timezone.now()

            # Handle trust score increments on completion
            complete_params = current_node.get("params", {})
            inc = complete_params.get("increment_trust_score", 0)
            if inc > 0 and hasattr(job.profile, "persona"):
                persona = job.profile.persona
                persona.trust_score = min(100, persona.trust_score + inc)
                persona.save()
        else:
            job.status = TaskExecutionQueue.ExecutionStatus.RUNNING

        job.save()

        next_node = states.get(next_state_id, {})
        return Response({
            "status": "ADVANCED",
            "current_state_id": job.current_state_id,
            "is_terminal": job.status in [TaskExecutionQueue.ExecutionStatus.SUCCESS, TaskExecutionQueue.ExecutionStatus.FAILED],
            "command": next_node.get("command"),
            "params": next_node.get("params", {})
        }, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="heartbeat")
    @transaction.atomic
    def heartbeat(self, request, pk=None):
        """
        Heartbeat ping from mobile runner to signify active job execution.
        Prevents marking profile execution as zombie/disconnected.
        """
        job = self.get_object()
        job = TaskExecutionQueue.objects.select_for_update().get(pk=job.pk)
        now = timezone.now()

        if job.status in [TaskExecutionQueue.ExecutionStatus.SUCCESS, TaskExecutionQueue.ExecutionStatus.FAILED]:
            return Response({
                "status": "TERMINAL",
                "job_id": str(job.id),
                "current_state": job.current_state_id,
                "job_status": job.status
            }, status=status.HTTP_200_OK)

        if job.status == TaskExecutionQueue.ExecutionStatus.DISPATCHED:
            job.status = TaskExecutionQueue.ExecutionStatus.RUNNING

        if not isinstance(job.execution_context, dict):
            job.execution_context = {}
        job.execution_context["last_heartbeat"] = now.isoformat()
        job.save()

        return Response({
            "status": "ALIVE",
            "job_id": str(job.id),
            "current_state": job.current_state_id,
            "job_status": job.status
        }, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="abort")
    @transaction.atomic
    def abort(self, request, pk=None):
        """
        Operator emergency kill-switch. Immediately cancels execution and marks as FAILED.
        """
        job = self.get_object()
        job = TaskExecutionQueue.objects.select_for_update().get(pk=job.pk)
        job.status = TaskExecutionQueue.ExecutionStatus.FAILED
        job.error_message = "Task manually aborted by operator."
        job.completed_at = timezone.now()

        if not isinstance(job.logs, list):
            job.logs = []
        job.logs.append({
            "type": "ABORT",
            "message": "Task manually aborted by operator.",
            "timestamp": timezone.now().isoformat()
        })
        job.save()

        return Response({
            "status": "ABORTED",
            "job_id": str(job.id)
        }, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="decision")
    @transaction.atomic
    def decision(self, request, pk=None):
        """
        Tier-2 / Tier-3 AI Recovery Decision:
        Accepts DOM JSON tree and optional compressed JPEG screenshot.
        Payload:
        {
            "page_snapshot": {
                "url": "https://m.youtube.com",
                "interactables": [
                    {"tag": "button", "text": "Reject all", "bounds": {"x": 192, "y": 420}}
                ]
            },
            "image_base64": "<optional_jpeg_base64_string>"
        }
        """
        job = self.get_object()
        job = TaskExecutionQueue.objects.select_for_update().get(pk=job.pk)
        snapshot = request.data.get("page_snapshot", {})
        image_b64 = request.data.get("image_base64") or request.data.get("screenshot")

        if not snapshot and not image_b64:
            return Response(
                {"error": "At least one of 'page_snapshot' or 'image_base64' must be provided."},
                status=status.HTTP_400_BAD_REQUEST
            )

        decision_action = GhostPilotDecisionEngine.resolve_stuck_state(job, snapshot, image_b64)

        # Apply state override if recommended by AI
        if decision_action.next_state_override and decision_action.next_state_override not in job.compiled_dag.get("states", {}):
            return Response({"error": "Recovery returned an unknown state."}, status=502)
        if decision_action.next_state_override:
            job.current_state_id = decision_action.next_state_override
            job.save()

        return Response({
            "status": "DECISION_RESOLVED",
            "action": decision_action.action,
            "target_x": decision_action.target_x,
            "target_y": decision_action.target_y,
            "swipe_direction": decision_action.swipe_direction,
            "navigate_url": decision_action.navigate_url,
            "wait_seconds": decision_action.wait_seconds,
            "next_state_override": decision_action.next_state_override,
            "reasoning": decision_action.reasoning
        }, status=status.HTTP_200_OK)

    @action(detail=True, methods=["get"], url_path="stream")
    def stream_telemetry(self, request, pk=None):
        """
        Server-Sent Events (SSE) telemetry stream for React Admin Dashboard.
        Yields live JSON log entries in text/event-stream format.
        """
        job = self.get_object()

        def event_stream():
            last_idx = 0
            logs = job.logs if isinstance(job.logs, list) else []
            for entry in logs:
                yield f"data: {json.dumps(entry)}\n\n"
            last_idx = len(logs)

            once = request.query_params.get("once", "false").lower() in ("true", "1")
            if once or job.status in [
                TaskExecutionQueue.ExecutionStatus.SUCCESS,
                TaskExecutionQueue.ExecutionStatus.FAILED,
            ]:
                if job.status in [
                    TaskExecutionQueue.ExecutionStatus.SUCCESS,
                    TaskExecutionQueue.ExecutionStatus.FAILED,
                ]:
                    yield f"data: {json.dumps({'type': 'STATUS', 'status': job.status})}\n\n"
                return

            max_ticks = 30
            ticks = 0
            while ticks < max_ticks:
                time.sleep(1)
                ticks += 1
                try:
                    job.refresh_from_db()
                except Exception:
                    break

                current_logs = job.logs if isinstance(job.logs, list) else []
                if len(current_logs) > last_idx:
                    for entry in current_logs[last_idx:]:
                        yield f"data: {json.dumps(entry)}\n\n"
                    last_idx = len(current_logs)

                if job.status in [
                    TaskExecutionQueue.ExecutionStatus.SUCCESS,
                    TaskExecutionQueue.ExecutionStatus.FAILED,
                ]:
                    yield f"data: {json.dumps({'type': 'STATUS', 'status': job.status})}\n\n"
                    break

        response = StreamingHttpResponse(event_stream(), content_type="text/event-stream")
        response["Cache-Control"] = "no-cache"
        response["X-Accel-Buffering"] = "no"
        return response


GhostPilotExecutionViewSet = GhostPilotViewSet


class ProfileNicheManagementViewSet(viewsets.ViewSet):
    permission_classes = [IsAdminUser]
    """Endpoints for profile persona inspection and batch niche assignments."""

    @action(detail=True, methods=["post"], url_path="set-niches")
    def set_niches(self, request, pk=None):
        try:
            profile = SavedProfile.objects.get(id=pk)
        except (SavedProfile.DoesNotExist, ValueError, Exception):
            return Response({"error": "Profile not found"}, status=status.HTTP_404_NOT_FOUND)

        niche_data = request.data.get("niches", [])
        if not isinstance(niche_data, list):
            raise ValidationError("niches must be an array.")
        validated = []
        for item in niche_data:
            if not isinstance(item, dict):
                raise ValidationError("Each niche must be an object.")
            serializer = ProfileNicheAffiliationSerializer(data={
                "niche": item.get("niche_id") or item.get("niche"),
                "weight_percentage": item.get("weight", item.get("weight_percentage", 100)),
            })
            serializer.is_valid(raise_exception=True)
            validated.append(serializer.validated_data)
        ids = [item["niche"].pk for item in validated]
        if len(set(ids)) != len(ids) or (validated and sum(item["weight_percentage"] for item in validated) != 100):
            raise ValidationError("Use unique niches whose weights total 100.")
        with transaction.atomic():
            ProfileNicheAffiliation.objects.filter(profile=profile).delete()
            created = [ProfileNicheAffiliation.objects.create(profile=profile, **item) for item in validated]

        return Response(
            ProfileNicheAffiliationSerializer(created, many=True).data,
            status=status.HTTP_200_OK
        )

    @action(detail=True, methods=["get", "post"], url_path="get-persona")
    def get_persona(self, request, pk=None):
        if request.method == "POST":
            return self.set_persona(request, pk=pk)
        try:
            profile = SavedProfile.objects.get(id=pk)
            persona, _ = ProfilePersona.objects.get_or_create(
                profile=profile,
                defaults={
                    "patience_index": round(random.uniform(0.4, 0.85), 2),
                    "engagement_rate": round(random.uniform(0.08, 0.25), 2),
                    "typing_wpm": random.randint(55, 85),
                    "typo_probability": round(random.uniform(0.02, 0.05), 3),
                    "trust_score": 10,
                }
            )
            return Response(ProfilePersonaSerializer(persona).data)
        except (SavedProfile.DoesNotExist, ValueError, Exception):
            return Response({"error": "Profile not found"}, status=status.HTTP_404_NOT_FOUND)

    @action(detail=True, methods=["post"], url_path="set-persona")
    def set_persona(self, request, pk=None):
        try:
            profile = SavedProfile.objects.get(id=pk)
            persona, _ = ProfilePersona.objects.get_or_create(
                profile=profile,
                defaults={
                    "patience_index": 0.6,
                    "engagement_rate": 0.15,
                    "typing_wpm": 65,
                    "typo_probability": 0.03,
                    "trust_score": 10,
                }
            )

            data = request.data
            if "trust_score" in data:
                try:
                    ts = int(data["trust_score"])
                    persona.trust_score = max(0, min(100, ts))
                    if "maturation_stage" not in data:
                        if persona.trust_score <= 25:
                            persona.maturation_stage = ProfilePersona.MaturationStage.INFANT
                        elif persona.trust_score <= 50:
                            persona.maturation_stage = ProfilePersona.MaturationStage.SEEDING
                        elif persona.trust_score <= 75:
                            persona.maturation_stage = ProfilePersona.MaturationStage.MATURING
                        else:
                            persona.maturation_stage = ProfilePersona.MaturationStage.MATURE
                except (ValueError, TypeError):
                    pass

            if "maturation_stage" in data:
                stage = str(data["maturation_stage"]).upper()
                if stage in ProfilePersona.MaturationStage.values:
                    persona.maturation_stage = stage

            if "typing_wpm" in data:
                try:
                    wpm = int(data["typing_wpm"])
                    persona.typing_wpm = max(30, min(120, wpm))
                except (ValueError, TypeError):
                    pass

            if "typo_probability" in data:
                try:
                    tp = float(data["typo_probability"])
                    persona.typo_probability = max(0.0, min(0.15, round(tp, 3)))
                except (ValueError, TypeError):
                    pass

            if "patience_index" in data:
                try:
                    pi = float(data["patience_index"])
                    persona.patience_index = max(0.1, min(1.0, round(pi, 2)))
                except (ValueError, TypeError):
                    pass

            if "engagement_rate" in data:
                try:
                    er = float(data["engagement_rate"])
                    persona.engagement_rate = max(0.0, min(0.5, round(er, 2)))
                except (ValueError, TypeError):
                    pass

            persona.save()
            return Response(ProfilePersonaSerializer(persona).data, status=status.HTTP_200_OK)
        except SavedProfile.DoesNotExist:
            return Response({"error": "Profile not found"}, status=status.HTTP_404_NOT_FOUND)
        except Exception:
            return Response({"error": "Failed to update persona. Please try again."}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=["get"], url_path="get-niches")
    def get_niches(self, request, pk=None):
        try:
            profile = SavedProfile.objects.get(id=pk)
            affiliations = ProfileNicheAffiliation.objects.filter(profile=profile)
            return Response(ProfileNicheAffiliationSerializer(affiliations, many=True).data)
        except (SavedProfile.DoesNotExist, ValueError, Exception):
            return Response({"error": "Profile not found"}, status=status.HTTP_404_NOT_FOUND)


class AIPromptConfigViewSet(viewsets.ModelViewSet):
    permission_classes = [IsAdminUser]
    """Allows inspection and dynamic modification of active prompts and AI models."""
    queryset = AIPromptConfig.objects.all().order_by("-updated_at")
    serializer_class = AIPromptConfigSerializer

    @action(detail=False, methods=["get"], url_path="active")
    def get_active(self, request):
        config = AIPromptConfig.get_active_config()
        return Response(AIPromptConfigSerializer(config).data)

    @action(detail=False, methods=["post"], url_path="set-active")
    def set_active(self, request):
        config_id = request.data.get("config_id")
        if not config_id:
            return Response({"error": "config_id is required"}, status=status.HTTP_400_BAD_REQUEST)
        
        try:
            with transaction.atomic():
                target = AIPromptConfig.objects.get(id=config_id)
                AIPromptConfig.objects.update(is_active=False)
                target.is_active = True
                target.save()
            return Response(AIPromptConfigSerializer(target).data)
        except (AIPromptConfig.DoesNotExist, ValueError):
            return Response({"error": "Config not found"}, status=status.HTTP_404_NOT_FOUND)


class AssistantViewSet(viewsets.ModelViewSet):
    permission_classes = [IsAdminUser]
    """
    Endpoints for interacting with the TersoAssistant tool-calling engine.
    Provides session management and a chat endpoint that routes prompts
    through the multi-provider agent loop.
    """
    queryset = AssistantSession.objects.all().order_by("-updated_at")
    serializer_class = AssistantSessionSerializer

    @action(detail=True, methods=["post"], url_path="chat")
    def chat(self, request, pk=None):
        """
        Send a natural language prompt to TersoAssistant.
        Payload: { "message": "List all mature profiles and dispatch a YouTube run." }
        """
        from .assistant_engine import TersoAssistantEngine

        session = self.get_object()
        user_message = request.data.get("message", "").strip()
        page_context = request.data.get("page_context")

        if not user_message:
            return Response(
                {"error": "Message content cannot be empty."},
                status=status.HTTP_400_BAD_REQUEST,
            )

        reply_text = TersoAssistantEngine.process_prompt(
            session, user_message, page_context=page_context
        )

        return Response(
            {
                "session_id": str(session.id),
                "reply": reply_text,
                "messages": AssistantMessageSerializer(
                    session.messages.all(), many=True
                ).data,
            },
            status=status.HTTP_200_OK,
        )

    @action(detail=False, methods=["post"], url_path="new-session")
    def new_session(self, request):
        """Create a fresh assistant conversation session."""
        title = request.data.get("title", "New Conversation")
        session = AssistantSession.objects.create(title=title)
        return Response(
            AssistantSessionSerializer(session).data,
            status=status.HTTP_201_CREATED,
        )
