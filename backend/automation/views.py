import os
import time
import json
import random
import uuid
import logging
from rest_framework import viewsets, status, serializers

logger = logging.getLogger(__name__)
from rest_framework.permissions import IsAdminUser, IsAuthenticated
from rest_framework.exceptions import ValidationError
from rest_framework.views import APIView
from django.db import transaction
from django.db.models import Q
from core.permissions import visible_profiles
from rest_framework.decorators import action
from rest_framework.response import Response
from django.utils import timezone
from django.http import StreamingHttpResponse, FileResponse
from rest_framework.parsers import MultiPartParser, FormParser, JSONParser

from .models import (
    Niche,
    ProfilePersona,
    ProfileNicheAffiliation,
    AutomationTask,
    TaskExecutionQueue,
    Execution,
    ExecutionStatus,
    AIPromptConfig,
    AssistantSession,
    AssistantMessage,
    Automation,
    AutomationRun,
    AutomationRunStatus,
    CustomWorkflow,
    WorkflowAddon,
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
    AutomationSerializer,
    AutomationRunSerializer,
    CustomWorkflowSerializer,
    WorkflowAddonSerializer,
)
from .addons_manager import AddonManager
from .compiler import RecipeCompiler
from .validator import DAGValidator
from executions.services import ExecutionService
from devices.models import SavedProfile


class NicheViewSet(viewsets.ModelViewSet):
    permission_classes = [IsAuthenticated]
    queryset = Niche.objects.all().order_by("name")
    serializer_class = NicheSerializer


class AutomationTaskViewSet(viewsets.ModelViewSet):
    permission_classes = [IsAuthenticated]
    queryset = AutomationTask.objects.all().order_by("-created_at")
    serializer_class = AutomationTaskSerializer

    def get_queryset(self):
        user = self.request.user
        if not user or not user.is_authenticated:
            return AutomationTask.objects.none()
        if user.is_superuser or user.is_staff:
            return AutomationTask.objects.all().order_by("-created_at")
        return AutomationTask.objects.filter(Q(owner=user) | Q(owner__isnull=True)).order_by("-created_at")

    def perform_create(self, serializer):
        serializer.save(owner=self.request.user)

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

        valid_profiles = visible_profiles(request.user).filter(pk__in=profile_ids)
        if valid_profiles.count() != len(profile_ids):
            owned_profiles = SavedProfile.objects.filter(user=request.user, pk__in=profile_ids)
            if owned_profiles.count() != len(profile_ids) and not (request.user.is_staff or request.user.is_superuser):
                raise ValidationError({"profile_ids": "One or more profiles do not exist or are inaccessible."})

        created_jobs = []
        for index, pid in enumerate(profile_ids):
            try:
                profile = SavedProfile.objects.get(id=pid)
            except (SavedProfile.DoesNotExist, ValueError):
                continue

            # Compile personalized DAG
            compiled_dag = RecipeCompiler.compile_recipe(task, profile)

            # If batch has multiple profiles, stagger entry start
            if index > 0 and "entry_state" in compiled_dag and "states" in compiled_dag:
                stagger_seconds = index * random.randint(20, 45)
                orig_entry = compiled_dag["entry_state"]
                stagger_node_id = f"batch_stagger_wait_{index}"
                compiled_dag["states"][stagger_node_id] = {
                    "command": "WAIT",
                    "params": {"seconds": stagger_seconds, "reason": "Batch profile launch stagger"},
                    "transitions": {"SUCCESS": orig_entry, "FAILURE": orig_entry}
                }
                compiled_dag["entry_state"] = stagger_node_id

            DAGValidator.validate(compiled_dag)

            execution = Execution.objects.create(
                task=task,
                profile=profile,
                status=ExecutionStatus.PENDING,
                entry_state_id=compiled_dag.get("entry_state", "start"),
                compiled_dag=compiled_dag,
                current_state_id=compiled_dag.get("entry_state", "start")
            )
            TaskExecutionQueue.objects.create(
                id=execution.id,
                task=task,
                profile=profile,
                execution=execution
            )
            created_jobs.append(str(execution.id))

        return Response({
            "status": "DISPATCHED",
            "task_id": str(task.id),
            "dispatched_count": len(created_jobs),
            "queue_ids": created_jobs
        }, status=status.HTTP_201_CREATED)


from executions.views import GhostPilotExecutionViewSet

# Backward Compatibility ViewSet Aliases
GhostPilotViewSet = GhostPilotExecutionViewSet


class ProfileNicheManagementViewSet(viewsets.ViewSet):
    permission_classes = [IsAuthenticated]
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


class AutomationViewSet(viewsets.ModelViewSet):
    """
    CRUD management for durable automation rules, cadences, targeting, and concurrency limits.
    """
    permission_classes = [IsAdminUser]
    queryset = Automation.objects.all().order_by("-created_at")
    serializer_class = AutomationSerializer

    @action(detail=True, methods=["post"], url_path="trigger")
    def trigger_run(self, request, pk=None):
        """
        Manually forces an immediate execution run for this automation regardless of schedule.
        """
        from .scheduler.service import SchedulerService

        automation = self.get_object()
        run_key = f"{automation.id}_manual_{int(time.time())}"
        run = SchedulerService.create_run_if_due(
            automation=automation,
            run_key=run_key
        )
        if run:
            return Response(AutomationRunSerializer(run).data, status=status.HTTP_201_CREATED)
        return Response(
            {"error": "Unable to trigger run. Check profile eligibility and active leases."},
            status=status.HTTP_400_BAD_REQUEST
        )

    @action(detail=True, methods=["post"], url_path="run-now")
    def run_now(self, request, pk=None):
        """Alias for trigger to match V2 specification POST /api/automation/rules/{id}/run-now/."""
        return self.trigger_run(request, pk=pk)

    @action(detail=True, methods=["post"], url_path="pause")
    def pause(self, request, pk=None):
        """Pauses the automation schedule."""
        automation = self.get_object()
        automation.enabled = False
        automation.save(update_fields=["enabled", "updated_at"])
        return Response(AutomationSerializer(automation).data, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="resume")
    def resume(self, request, pk=None):
        """Resumes the automation schedule."""
        automation = self.get_object()
        automation.enabled = True
        automation.save(update_fields=["enabled", "updated_at"])
        return Response(AutomationSerializer(automation).data, status=status.HTTP_200_OK)


class AutomationRunViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Audit and lifecycle inspection for campaign execution runs.
    """
    permission_classes = [IsAdminUser]
    queryset = AutomationRun.objects.all().order_by("-created_at")
    serializer_class = AutomationRunSerializer

    @action(detail=True, methods=["post"], url_path="cancel")
    def cancel_run(self, request, pk=None):
        """Cancels a scheduled or running execution run and fails pending executions."""
        from executions.models import Execution, ExecutionStatus

        run = self.get_object()
        if run.status in [AutomationRunStatus.COMPLETED, AutomationRunStatus.CANCELLED]:
            return Response(
                {"error": f"Cannot cancel run in status '{run.status}'."},
                status=status.HTTP_400_BAD_REQUEST
            )

        now = timezone.now()
        with transaction.atomic():
            run.status = AutomationRunStatus.CANCELLED
            run.completed_at = now

            # 1. Transition pending executions to CANCELLED (not FAILED)
            Execution.objects.filter(
                automation_run=run,
                status=ExecutionStatus.PENDING
            ).update(
                status=ExecutionStatus.CANCELLED,
                completed_at=now,
                error_message="Cancelled by operator run abort."
            )

            # 2. Flag active executions for immediate cooperative cancellation
            Execution.objects.filter(
                automation_run=run,
                status__in=[ExecutionStatus.DISPATCHED, ExecutionStatus.RUNNING]
            ).update(
                cancel_requested=True
            )

            # 3. Update cancelled_count accurately
            cancelled_total = run.executions.filter(status=ExecutionStatus.CANCELLED).count()
            run.cancelled_count = cancelled_total
            run.save(update_fields=["status", "completed_at", "cancelled_count", "updated_at"])

        return Response(AutomationRunSerializer(run).data, status=status.HTTP_200_OK)

    @action(detail=True, methods=["get"], url_path="executions")
    def list_executions(self, request, pk=None):
        """Returns all executions associated with this execution run."""
        from executions.serializers import ExecutionSerializer

        run = self.get_object()
        executions = run.executions.all().order_by("-created_at")
        return Response(ExecutionSerializer(executions, many=True).data, status=status.HTTP_200_OK)


class YouTubeAnalyzeView(APIView):
    """
    Endpoint for one-click AI analysis of YouTube video URLs.
    Extracts metadata, suggested ranking keywords, and contextual comments.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        url = request.data.get("url", "").strip()
        if not url:
            return Response({"error": "A YouTube video URL is required."}, status=status.HTTP_400_BAD_REQUEST)
        provider = request.data.get("provider")
        try:
            from .services.youtube_analyzer import YouTubeAnalyzerService
            result = YouTubeAnalyzerService.analyze(url, provider_override=provider)
            return Response(result, status=status.HTTP_200_OK)
        except Exception as e:
            return Response({"error": str(e)}, status=status.HTTP_400_BAD_REQUEST)


class WorkflowListView(APIView):
    """
    Endpoint listing all registered modular automation workflows.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request):
        from .workflows import WorkflowRegistry
        platform = request.query_params.get("platform")
        if platform:
            workflows = WorkflowRegistry.list_for_platform(platform)
        else:
            workflows = WorkflowRegistry.list_all()
        return Response(workflows, status=status.HTTP_200_OK)


class PlatformCalibrationView(APIView):
    """
    Retrieves and updates the platform visual coordinate calibration and settings.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        platform = request.query_params.get("platform", "YOUTUBE")
        cal = InterfaceCalibrationService.get_or_create_calibration(platform)
        anchors = InterfaceCalibrationService.get_all_anchors_with_addons(platform)
        devices = InterfaceCalibrationService.get_connected_devices()
        return Response({
            "id": str(cal.id),
            "platform": cal.platform,
            "device_model": cal.device_model,
            "viewport_width": cal.viewport_width,
            "viewport_height": cal.viewport_height,
            "anchors": anchors,
            "settings": cal.settings or InterfaceCalibrationService.DEFAULT_SETTINGS,
            "screenshot_base64": cal.screenshot_base64 or "",
            "connected_devices": devices,
            "updated_at": cal.updated_at.isoformat()
        }, status=status.HTTP_200_OK)

    def post(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        platform = request.data.get("platform", "YOUTUBE")
        cal = InterfaceCalibrationService.get_or_create_calibration(platform)

        if "anchors" in request.data:
            cal.anchors = request.data["anchors"]
        if "settings" in request.data:
            cal.settings = request.data["settings"]
        if "viewport_width" in request.data:
            cal.viewport_width = int(request.data["viewport_width"])
        if "viewport_height" in request.data:
            cal.viewport_height = int(request.data["viewport_height"])
        if "screenshot_base64" in request.data:
            cal.screenshot_base64 = request.data["screenshot_base64"]

        cal.save()

        # Immediately sync canonical anchors to connected ADB device(s)
        push_res = {}
        try:
            push_res = InterfaceCalibrationService.push_anchors_to_device(platform=cal.platform)
        except Exception as e:
            logger.warning(f"Auto-push to connected devices failed: {e}")

        return Response({
            "status": "SAVED_AND_SYNCED" if push_res.get("status") == "SYNCED" else "SAVED",
            "platform": cal.platform,
            "anchors_count": len(cal.anchors or {}),
            "synced_devices": push_res.get("synced_devices", []),
            "sync_status": push_res.get("status", "NO_DEVICES"),
            "updated_at": cal.updated_at.isoformat()
        }, status=status.HTTP_200_OK)


class SingleAnchorView(APIView):
    """
    Atomic per-anchor save and update. Allows saving each calibrated anchor
    individually without risking or overwriting other anchors in the registry.
    Immediately synchronizes the updated anchor to connected devices.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        platform = request.data.get("platform", "YOUTUBE")
        anchor_id = request.data.get("anchor_id")
        if not anchor_id:
            return Response({"error": "Field 'anchor_id' is required."}, status=status.HTTP_400_BAD_REQUEST)

        x = request.data.get("x")
        y = request.data.get("y")
        label = request.data.get("label")
        description = request.data.get("description")
        category = request.data.get("category")

        saved = InterfaceCalibrationService.save_single_anchor(
            platform=platform,
            anchor_id=anchor_id,
            x=x,
            y=y,
            label=label,
            description=description,
            category=category
        )

        # Immediately sync to connected device(s)
        push_res = {}
        try:
            push_res = InterfaceCalibrationService.push_anchors_to_device(platform=platform)
        except Exception as e:
            logger.warning(f"Single anchor push to connected devices failed: {e}")

        return Response({
            "status": "SAVED",
            "anchor_id": anchor_id.upper(),
            "anchor": saved,
            "synced_devices": push_res.get("synced_devices", []),
            "sync_status": push_res.get("status", "NO_DEVICES")
        }, status=status.HTTP_200_OK)


class PullCalibrationFromDeviceView(APIView):
    """
    Pulls current spatial anchors and settings directly from connected Android device via ADB.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        platform = request.data.get("platform", "YOUTUBE")
        device_id = request.data.get("device_id")
        try:
            res = InterfaceCalibrationService.pull_anchors_from_device(device_id=device_id, platform=platform)
            return Response(res, status=status.HTTP_200_OK)
        except Exception as e:
            logger.error(f"Pull calibration from device failed: {e}", exc_info=True)
            return Response({"error": str(e)}, status=status.HTTP_400_BAD_REQUEST)


class PushCalibrationToDeviceView(APIView):
    """
    Explicitly pushes current spatial anchors directly to connected Android device(s) via ADB.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        platform = request.data.get("platform", "YOUTUBE")
        device_id = request.data.get("device_id")
        try:
            res = InterfaceCalibrationService.push_anchors_to_device(device_id=device_id, platform=platform)
            return Response(res, status=status.HTTP_200_OK)
        except Exception as e:
            logger.error(f"Push calibration to device failed: {e}", exc_info=True)
            return Response({"error": str(e)}, status=status.HTTP_400_BAD_REQUEST)



class SingleAnchorDetailView(APIView):
    """
    Atomic per-anchor deletion.
    """
    permission_classes = [IsAuthenticated]

    def delete(self, request, anchor_id):
        from .services.calibration_service import InterfaceCalibrationService
        platform = request.query_params.get("platform", "YOUTUBE")
        deleted = InterfaceCalibrationService.delete_single_anchor(platform=platform, anchor_id=anchor_id)
        if not deleted:
            return Response({"error": f"Anchor '{anchor_id}' not found."}, status=status.HTTP_404_NOT_FOUND)
        return Response({
            "status": "DELETED",
            "anchor_id": anchor_id.upper(),
            "message": f"Anchor '{anchor_id}' was removed."
        }, status=status.HTTP_200_OK)


class TestAnchorTapView(APIView):
    """
    Live Hardware Test Tap: Translates normalized (0..1000) coordinates to actual
    device display pixels and fires an instant physical tap via ADB.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        x = request.data.get("x")
        y = request.data.get("y")
        if x is None or y is None:
            return Response({"error": "Coordinates 'x' and 'y' (0..1000) are required for test tap."}, status=status.HTTP_400_BAD_REQUEST)

        device_id = request.data.get("device_id")
        anchor_id = request.data.get("anchor_id")

        try:
            result = InterfaceCalibrationService.test_anchor_tap(
                x=int(x),
                y=int(y),
                device_id=device_id,
                anchor_id=anchor_id
            )
            return Response(result, status=status.HTTP_200_OK)
        except RuntimeError as e:
            return Response({"error": str(e)}, status=status.HTTP_400_BAD_REQUEST)
        except Exception as e:
            logger.error("Failed to execute test tap: %s", str(e), exc_info=True)
            return Response({"error": f"Test tap failed: {str(e)}"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)


class CalibrateWithVLMView(APIView):
    """
    Uses Vision LLM (Gemini 2.5 Flash / OpenRouter) to identify interactive
    UI element coordinates on a 0..1000 normalized scale.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        image_b64 = request.data.get("image_base64", "").strip()
        platform = request.data.get("platform", "YOUTUBE")
        provider = request.data.get("provider")

        if not image_b64:
            # Check if screenshot is already stored in calibration model
            cal = InterfaceCalibrationService.get_or_create_calibration(platform)
            image_b64 = cal.screenshot_base64
            if not image_b64:
                return Response(
                    {"error": "No screenshot provided or available for calibration."},
                    status=status.HTTP_400_BAD_REQUEST
                )

        try:
            result = InterfaceCalibrationService.calibrate_with_vlm(
                image_base64=image_b64,
                platform=platform,
                provider=provider
            )
            return Response(result, status=status.HTTP_200_OK)
        except Exception as e:
            logger.error(f"VLM calibration error: {e}", exc_info=True)
            return Response({"error": str(e)}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)


class CaptureDeviceScreenView(APIView):
    """
    Captures live screen from connected physical Android device via ADB
    and stores/returns base64 image for the visual calibration canvas.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        import subprocess
        import base64
        import shutil
        from .services.calibration_service import InterfaceCalibrationService

        platform = request.data.get("platform", "YOUTUBE")
        device_id = request.data.get("device_id")

        adb_path = shutil.which("adb")
        if not adb_path:
            localappdata = os.environ.get("LOCALAPPDATA", "")
            candidate = os.path.join(localappdata, "Android", "Sdk", "platform-tools", "adb.exe")
            if os.path.exists(candidate):
                adb_path = candidate
            else:
                adb_path = "adb"

        cmd = [adb_path]
        if device_id:
            cmd.extend(["-s", device_id])
        cmd.extend(["exec-out", "screencap", "-p"])

        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12)
            if res.returncode != 0 or len(res.stdout) < 100:
                err_msg = res.stderr.decode("utf-8", errors="ignore")
                return Response(
                    {"error": f"ADB screencap failed: {err_msg or 'Device not connected or unresponsive'}"},
                    status=status.HTTP_400_BAD_REQUEST
                )

            b64_image = base64.b64encode(res.stdout).decode("utf-8")
            cal = InterfaceCalibrationService.get_or_create_calibration(platform)
            cal.screenshot_base64 = b64_image
            cal.save(update_fields=["screenshot_base64", "updated_at"])

            return Response({
                "status": "CAPTURED",
                "image_size_bytes": len(res.stdout),
                "screenshot_base64": b64_image
            }, status=status.HTTP_200_OK)
        except Exception as e:
            return Response({"error": f"Failed to capture device screen: {e}"}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)


class DownloadCalibrationJsonView(APIView):
    """
    Exports the canonical JSON payload saved by Android clients
    as `youtube_spatial_anchors.json`.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        platform = request.query_params.get("platform", "YOUTUBE")
        data = InterfaceCalibrationService.export_anchors_json(platform)
        return Response(data, status=status.HTTP_200_OK)


class FlushCalibrationView(APIView):
    """
    Flushes all saved coordinates for the platform, resetting all anchor
    spots to None and confidence to 0.0 for a clean slate.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        platform = request.data.get("platform", "YOUTUBE")
        cal = InterfaceCalibrationService.flush_calibration(platform)
        return Response({
            "status": "FLUSHED",
            "platform": cal.platform,
            "anchors": cal.anchors,
            "anchors_count": len(cal.anchors or {}),
            "updated_at": cal.updated_at.isoformat()
        }, status=status.HTTP_200_OK)


class ImportCalibrationJsonView(APIView):
    """
    Imports and applies a JSON payload of spatial anchors / settings.
    Accepts JSON body or multipart file upload (.json).
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        from .services.calibration_service import InterfaceCalibrationService
        import json

        platform = request.data.get("platform", "YOUTUBE")
        json_data = None

        if "file" in request.FILES:
            try:
                uploaded_file = request.FILES["file"]
                content = uploaded_file.read().decode("utf-8")
                json_data = json.loads(content)
            except Exception as e:
                return Response({"error": f"Failed to parse uploaded JSON file: {e}"}, status=status.HTTP_400_BAD_REQUEST)
        elif "data" in request.data:
            val = request.data["data"]
            if isinstance(val, str):
                try:
                    json_data = json.loads(val)
                except Exception as e:
                    return Response({"error": f"Invalid JSON string: {e}"}, status=status.HTTP_400_BAD_REQUEST)
            elif isinstance(val, dict):
                json_data = val
        elif "anchors" in request.data:
            json_data = request.data

        if not json_data or not isinstance(json_data, dict):
            return Response({"error": "No valid JSON anchors payload provided."}, status=status.HTTP_400_BAD_REQUEST)

        try:
            res = InterfaceCalibrationService.import_anchors_json(json_data, platform=platform)
            return Response(res, status=status.HTTP_200_OK)
        except Exception as e:
            logger.error(f"Import anchors error: {e}", exc_info=True)
            return Response({"error": str(e)}, status=status.HTTP_400_BAD_REQUEST)


class CustomWorkflowViewSet(viewsets.ModelViewSet):
    """
    CRUD and dispatch endpoints for user-defined Visual Workflows.
    """
    from .models import CustomWorkflow
    from .serializers import CustomWorkflowSerializer
    queryset = CustomWorkflow.objects.all()
    serializer_class = CustomWorkflowSerializer
    permission_classes = [IsAuthenticated]

    @action(detail=True, methods=["post"], url_path="dispatch")
    def dispatch_workflow(self, request, pk=None):
        workflow = self.get_object()
        profile_ids = request.data.get("profile_ids", [])
        overrides = request.data.get("overrides", {})
        if not profile_ids:
            return Response({"error": "No profile_ids provided."}, status=status.HTTP_400_BAD_REQUEST)

        valid_profiles = visible_profiles(request.user).filter(pk__in=profile_ids)
        if not valid_profiles.exists():
            valid_profiles = SavedProfile.objects.filter(pk__in=profile_ids)
            if not valid_profiles.exists():
                return Response({"error": "No valid profiles found."}, status=status.HTTP_400_BAD_REQUEST)

        # Create or find a matching AutomationTask container
        campaign_title = overrides.get("campaign_title") or f"Workflow: {workflow.name}"
        task, _ = AutomationTask.objects.get_or_create(
            name=campaign_title,
            defaults={
                "category": workflow.platform or "YOUTUBE",
                "config": {"custom_workflow_id": str(workflow.id), "journeys": workflow.journeys, "overrides": overrides}
            }
        )
        task.config = {"custom_workflow_id": str(workflow.id), "journeys": workflow.journeys, "overrides": overrides}
        task.save(update_fields=["config"])

        created_jobs = []
        for index, profile in enumerate(valid_profiles):
            compiled_dag = workflow.compile_dag(profile, overrides=overrides)
            DAGValidator.validate(compiled_dag)

            execution = Execution.objects.create(
                task=task,
                profile=profile,
                status=ExecutionStatus.PENDING,
                entry_state_id=compiled_dag.get("entry_state", "start"),
                compiled_dag=compiled_dag,
                current_state_id=compiled_dag.get("entry_state", "start")
            )
            TaskExecutionQueue.objects.create(
                id=execution.id,
                task=task,
                profile=profile,
                execution=execution
            )
            created_jobs.append(str(execution.id))

        return Response({
            "status": "DISPATCHED",
            "workflow_id": str(workflow.id),
            "dispatched_count": len(created_jobs),
            "execution_ids": created_jobs
        }, status=status.HTTP_200_OK)


class WorkflowAddonViewSet(viewsets.ModelViewSet):
    """
    CRUD + Upload/Manage API for WordPress-style Workflow Addons and Extensions.
    """
    queryset = WorkflowAddon.objects.all().order_by("-created_at")
    serializer_class = WorkflowAddonSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        qs = super().get_queryset()
        platform = self.request.query_params.get("platform")
        if platform:
            qs = qs.filter(platform__iexact=platform)
        category = self.request.query_params.get("category")
        if category:
            qs = qs.filter(category__iexact=category)
        is_active = self.request.query_params.get("is_active")
        if is_active is not None:
            qs = qs.filter(is_active=is_active.lower() == "true")
        return qs

    @action(detail=False, methods=["post"], url_path="upload_zip", parser_classes=[MultiPartParser, FormParser])
    def upload_zip(self, request):
        return self.upload(request)

    @action(detail=False, methods=["post"], parser_classes=[MultiPartParser, FormParser])
    def upload(self, request):
        """
        Uploads and installs an addon from a .zip package file.
        """
        file_obj = request.FILES.get("package") or request.FILES.get("file")
        if not file_obj:
            return Response({"error": "No .zip file uploaded. Specify 'package' in form-data."}, status=status.HTTP_400_BAD_REQUEST)

        if not file_obj.name.endswith(".zip"):
            return Response({"error": "Addon package must be a .zip file archive."}, status=status.HTTP_400_BAD_REQUEST)

        try:
            addon = AddonManager.install_from_zip(file_obj)
            serializer = self.get_serializer(addon)
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        except Exception as e:
            logger.error("Failed to install addon package: %s", str(e), exc_info=True)
            return Response({"error": f"Failed to install addon: {str(e)}"}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=True, methods=["post"], url_path="toggle_active")
    def toggle_active(self, request, pk=None):
        return self.toggle(request, pk)

    @action(detail=True, methods=["post"])
    def toggle(self, request, pk=None):
        """
        Toggles active/inactive state of the addon.
        """
        addon = self.get_object()
        requested_active = request.data.get("is_active")
        if requested_active is None:
            requested_active = not addon.is_active
        else:
            requested_active = bool(requested_active)

        updated = AddonManager.toggle_active(str(addon.id), requested_active)
        if not updated:
            return Response({"error": "Addon not found"}, status=status.HTTP_404_NOT_FOUND)

        serializer = self.get_serializer(updated)
        return Response(serializer.data, status=status.HTTP_200_OK)

    @action(detail=True, methods=["delete", "post"], url_path="uninstall")
    def uninstall(self, request, pk=None):
        return self.destroy(request, pk)

    def destroy(self, request, *args, **kwargs):
        """
        Completely uninstalls the addon from disk and database with 0 residue.
        """
        addon = self.get_object()
        slug = addon.slug
        success = AddonManager.uninstall(str(addon.id))
        if success:
            return Response({"status": "UNINSTALLED", "slug": slug, "message": f"Addon '{slug}' uninstalled completely."}, status=status.HTTP_200_OK)
        return Response({"error": "Failed to uninstall addon"}, status=status.HTTP_400_BAD_REQUEST)

    @action(detail=False, methods=["get"])
    def steps(self, request):
        """
        Returns all custom step definitions from all currently active addons.
        Used by the Workflow Builder UI to dynamically populate available steps.
        """
        steps = AddonManager.get_active_steps()
        return Response(steps, status=status.HTTP_200_OK)

    @action(detail=False, methods=["get"])
    def templates(self, request):
        """
        Returns all pre-built workflow templates provided by active addons.
        """
        templates = AddonManager.get_addon_templates()
        return Response(templates, status=status.HTTP_200_OK)

    @action(detail=False, methods=["post"], url_path="import_template")
    def import_template(self, request):
        """
        Imports bundled workflow template by addon_id (UUID or slug) and template_name into CustomWorkflow.
        """
        addon_id = request.data.get("addon_id")
        template_name = request.data.get("template_name")
        if not addon_id or not template_name:
            return Response({"error": "Both 'addon_id' and 'template_name' are required."}, status=status.HTTP_400_BAD_REQUEST)

        workflow = AddonManager.import_template_to_workflows(str(addon_id), template_name)
        if not workflow:
            return Response({"error": f"No template '{template_name}' found for addon '{addon_id}'."}, status=status.HTTP_404_NOT_FOUND)

        return Response({
            "status": "IMPORTED",
            "message": f"Successfully imported workflow '{workflow.name}'.",
            "workflow": CustomWorkflowSerializer(workflow).data
        }, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=["post"])
    def import_templates(self, request, pk=None):
        """
        Imports bundled workflow templates from this addon into CustomWorkflow.
        """
        addon = self.get_object()
        template_name = request.data.get("template_name")
        workflow = AddonManager.import_template_to_workflows(str(addon.id), template_name)
        if not workflow:
            return Response({"error": "No matching template found in this addon."}, status=status.HTTP_404_NOT_FOUND)

        return Response({
            "status": "IMPORTED",
            "message": f"Successfully imported workflow '{workflow.name}' from addon '{addon.name}'.",
            "workflow": CustomWorkflowSerializer(workflow).data
        }, status=status.HTTP_201_CREATED)

    @action(detail=True, methods=["get"], url_path="export_zip")
    def export_zip(self, request, pk=None):
        return self.download(request, pk)

    @action(detail=True, methods=["get"])
    def download(self, request, pk=None):
        """
        Exports the addon package as a .zip file for download.
        """
        addon = self.get_object()
        zip_path = AddonManager.export_addon_zip(str(addon.id))
        if not zip_path or not os.path.exists(zip_path):
            return Response({"error": "Could not generate zip archive for this addon."}, status=status.HTTP_404_NOT_FOUND)

        filename = f"{addon.slug}_v{addon.version}.zip"
        return FileResponse(open(zip_path, "rb"), as_attachment=True, filename=filename, content_type="application/zip")






