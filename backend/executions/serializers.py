from rest_framework import serializers
from .models import ExecutionLease, Execution, ExecutionEvent

class ExecutionEventSerializer(serializers.ModelSerializer):
    class Meta:
        model = ExecutionEvent
        fields = [
            "id",
            "execution_id",
            "event_type",
            "payload",
            "created_at",
        ]
        read_only_fields = fields


class ExecutionSerializer(serializers.ModelSerializer):
    profile_name = serializers.CharField(source="profile.name", read_only=True)
    task_name = serializers.CharField(source="task.name", read_only=True)

    class Meta:
        model = Execution
        fields = [
            "id",
            "task",
            "task_name",
            "profile",
            "profile_name",
            "status",
            "entry_state_id",
            "current_state_id",
            "compiled_dag",
            "execution_context",
            "logs",
            "error_message",
            "started_at",
            "completed_at",
            "created_at",
            "updated_at",
        ]
        read_only_fields = fields


class ExecutionLeaseSerializer(serializers.ModelSerializer):
    profile_name = serializers.CharField(source="profile.name", read_only=True)
    is_valid = serializers.BooleanField(source="is_active_and_valid", read_only=True)

    class Meta:
        model = ExecutionLease
        fields = [
            "id",
            "profile",
            "profile_name",
            "execution",
            "device_id",
            "status",
            "heartbeat_at",
            "expires_at",
            "created_at",
            "updated_at",
            "is_valid"
        ]
        read_only_fields = fields

class LeaseAcquireRequestSerializer(serializers.Serializer):
    profile_id = serializers.UUIDField(required=True)
    device_id = serializers.CharField(max_length=255, required=True)
    duration_seconds = serializers.IntegerField(default=60, min_value=10, max_value=600)

class LeaseHeartbeatRequestSerializer(serializers.Serializer):
    lease_id = serializers.UUIDField(required=True)
    device_id = serializers.CharField(max_length=255, required=True)
    extension_seconds = serializers.IntegerField(default=60, min_value=10, max_value=600)

class LeaseReleaseRequestSerializer(serializers.Serializer):
    lease_id = serializers.UUIDField(required=True)
    device_id = serializers.CharField(max_length=255, required=True)
