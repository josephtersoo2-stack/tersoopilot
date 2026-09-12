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
    Execution,
    ExecutionStatus,
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
from .validator import DAGValidator
from executions.services import ExecutionService
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
