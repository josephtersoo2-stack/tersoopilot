from rest_framework import status
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .services import LeaseService
from .serializers import (
    ExecutionLeaseSerializer,
    LeaseAcquireRequestSerializer,
    LeaseHeartbeatRequestSerializer,
    LeaseReleaseRequestSerializer,
)

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
