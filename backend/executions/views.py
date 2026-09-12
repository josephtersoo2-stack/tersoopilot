import uuid
from rest_framework import status, viewsets
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from rest_framework.decorators import action
from rest_framework.exceptions import ValidationError
from django.db import transaction
from django.http import StreamingHttpResponse
from core.permissions import visible_profiles
from devices.models import SavedProfile
from .models import Execution, ExecutionStatus
from .services import LeaseService, ExecutionService
from .serializers import (
    ExecutionSerializer,
    ExecutionLeaseSerializer,
    LeaseAcquireRequestSerializer,
    LeaseHeartbeatRequestSerializer,
    LeaseReleaseRequestSerializer,
)
from automation.decision_engine import GhostPilotDecisionEngine

class LeaseAcquireView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        serializer = LeaseAcquireRequestSerializer(data=request.data)
        if not serializer.is_valid():
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        data = serializer.validated_data
        lease, error = LeaseService.acquire_lease(
            user=request.user,
            profile_id=data["profile_id"],
            device_id=data["device_id"],
            duration_seconds=data.get("duration_seconds", 60)
        )

        if error:
            if "permission" in error.lower() or "unauthorized" in error.lower():
                return Response({"error": error}, status=status.HTTP_403_FORBIDDEN)
            if "already leased" in error.lower() or "conflict" in error.lower():
                return Response({"error": error}, status=status.HTTP_409_CONFLICT)
            if "not found" in error.lower():
                return Response({"error": error}, status=status.HTTP_404_NOT_FOUND)
            return Response({"error": error}, status=status.HTTP_400_BAD_REQUEST)

        return Response(ExecutionLeaseSerializer(lease).data, status=status.HTTP_200_OK)


class LeaseHeartbeatView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        serializer = LeaseHeartbeatRequestSerializer(data=request.data)
        if not serializer.is_valid():
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        data = serializer.validated_data
        lease, error = LeaseService.heartbeat(
            user=request.user,
            lease_id=data["lease_id"],
            device_id=data["device_id"],
            extension_seconds=data.get("extension_seconds", 60)
        )

        if error:
            if "unauthorized" in error.lower():
                return Response({"error": error}, status=status.HTTP_403_FORBIDDEN)
            if "not found" in error.lower():
                return Response({"error": error}, status=status.HTTP_404_NOT_FOUND)
            if "expired" in error.lower():
                return Response({"error": error}, status=status.HTTP_410_GONE)
            return Response({"error": error}, status=status.HTTP_400_BAD_REQUEST)

        return Response(ExecutionLeaseSerializer(lease).data, status=status.HTTP_200_OK)


class LeaseReleaseView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        serializer = LeaseReleaseRequestSerializer(data=request.data)
        if not serializer.is_valid():
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        data = serializer.validated_data
        success, error = LeaseService.release_lease(
            user=request.user,
            lease_id=data["lease_id"],
            device_id=data["device_id"]
        )

        if error:
            if "unauthorized" in error.lower():
                return Response({"error": error}, status=status.HTTP_403_FORBIDDEN)
            if "not found" in error.lower():
                return Response({"error": error}, status=status.HTTP_404_NOT_FOUND)
            return Response({"error": error}, status=status.HTTP_400_BAD_REQUEST)

        return Response({"status": "released", "lease_id": str(data["lease_id"])}, status=status.HTTP_200_OK)


class LeaseStatusView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        profile_id = request.query_params.get("profile_id")
        if not profile_id:
            return Response({"error": "profile_id query parameter is required"}, status=status.HTTP_400_BAD_REQUEST)

        result = LeaseService.get_profile_lease_status(request.user, profile_id)
        return Response(result, status=status.HTTP_200_OK)


class ExecutionEventsView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request, execution_id):
        from .models import ExecutionEvent
        from .serializers import ExecutionEventSerializer
        events = ExecutionEvent.objects.filter(execution_id=execution_id).order_by("created_at")
        serializer = ExecutionEventSerializer(events, many=True)
        return Response(serializer.data, status=status.HTTP_200_OK)


class StalledReaperView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        timeout = int(request.data.get("timeout_seconds", 60))
        reaped_count = ExecutionService.reap_stalled_executions(timeout_seconds=timeout)
        return Response({"reaped_count": reaped_count}, status=status.HTTP_200_OK)


class GhostPilotExecutionViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Fleet control API consumed by the mobile GhostPilot runner and React admin dashboard.
    Supports polling, state machine transitions, heartbeat liveness, operator aborts,
    AI-driven fallback decision engine, and SSE live telemetry streaming.
    """
    queryset = Execution.objects.all().order_by("-started_at")
    serializer_class = ExecutionSerializer

    def get_queryset(self):
        return Execution.objects.filter(profile__in=visible_profiles(self.request.user)).order_by("-started_at")

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

        device_id = request.query_params.get("device_id")
        job = ExecutionService.poll_next_job(profile=profile, device_id=device_id)
        if job:
            return Response({
                "work_available": True,
                "job_id": str(job.id),
                "backend_profile_id": str(job.profile_id),
                "entry_state": job.entry_state_id,
                "dag": job.compiled_dag
            })
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
    def transition_state(self, request, pk=None):
        """
        Advance DAG state based on client execution outcome.
        """
        job = self.get_object()
        transition_id = request.data.get("transition_id")
        if transition_id is not None and (not isinstance(transition_id, str) or len(transition_id) > 100):
            raise ValidationError({"transition_id": "Must be a string of at most 100 characters."})

        outcome = request.data.get("outcome", "SUCCESS")
        context_update = request.data.get("context_update", {})
        error = request.data.get("error")
        if not isinstance(context_update, dict) or not isinstance(outcome, str):
            raise ValidationError("outcome must be a string and context_update an object.")

        res = ExecutionService.transition_state(
            job=job,
            outcome=outcome,
            context_update=context_update,
            error=error,
            transition_id=transition_id
        )
        status_code = res.pop("status_code", status.HTTP_200_OK)
        return Response(res, status=status_code)

    @action(detail=True, methods=["post"], url_path="heartbeat")
    def heartbeat(self, request, pk=None):
        """
        Heartbeat ping from mobile runner to signify active job execution.
        """
        job = self.get_object()
        device_id = request.data.get("device_id")
        res = ExecutionService.heartbeat(job=job, device_id=device_id)
        status_code = res.pop("status_code", status.HTTP_200_OK)
        return Response(res, status=status_code)

    @action(detail=True, methods=["post"], url_path="abort")
    def abort(self, request, pk=None):
        """
        Operator emergency kill-switch. Immediately cancels execution and marks as FAILED.
        """
        job = self.get_object()
        reason = request.data.get("reason") or "Task manually aborted by operator."
        res = ExecutionService.abort(job=job, reason=reason)
        status_code = res.pop("status_code", status.HTTP_200_OK)
        return Response(res, status=status_code)

    @action(detail=True, methods=["post"], url_path="decision")
    @transaction.atomic
    def decision(self, request, pk=None):
        """
        Tier-2 / Tier-3 AI Recovery Decision:
        Accepts DOM JSON tree and optional compressed JPEG screenshot.
        """
        job = self.get_object()
        job = Execution.objects.select_for_update().get(pk=job.pk)
        snapshot = request.data.get("page_snapshot", {})
        image_b64 = request.data.get("image_base64") or request.data.get("screenshot")

        if not snapshot and not image_b64:
            return Response(
                {"error": "At least one of 'page_snapshot' or 'image_base64' must be provided."},
                status=status.HTTP_400_BAD_REQUEST
            )

        decision_action = GhostPilotDecisionEngine.resolve_stuck_state(job, snapshot, image_b64)

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
        Yields live JSON log entries and ExecutionEvents in text/event-stream format.
        """
        job = self.get_object()
        once = request.query_params.get("once", "false").lower() in ("true", "1")
        response = StreamingHttpResponse(
            ExecutionService.stream_events(execution_id=job.id, once=once),
            content_type="text/event-stream"
        )
        response["Cache-Control"] = "no-cache"
        response["X-Accel-Buffering"] = "no"
        return response


# Compatibility Alias
GhostPilotViewSet = GhostPilotExecutionViewSet
