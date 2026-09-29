from rest_framework import serializers
from .models import (
    Niche,
    ProfilePersona,
    ProfileNicheAffiliation,
    AutomationTask,
    TaskExecutionQueue,
    Execution,
    AIPromptConfig,
    AssistantSession,
    AssistantMessage,
    Automation,
    AutomationRun,
    CustomWorkflow,
    WorkflowAddon,
)

class NicheSerializer(serializers.ModelSerializer):
    class Meta:
        model = Niche
        fields = "__all__"

class ProfilePersonaSerializer(serializers.ModelSerializer):
    class Meta:
        model = ProfilePersona
        fields = "__all__"

class ProfileNicheAffiliationSerializer(serializers.ModelSerializer):
    niche_name = serializers.ReadOnlyField(source="niche.name")
    weight_percentage = serializers.IntegerField(min_value=0, max_value=100)

    class Meta:
        model = ProfileNicheAffiliation
        fields = ["id", "niche", "niche_name", "weight_percentage"]


class AutomationTaskSerializer(serializers.ModelSerializer):
    niche_name = serializers.ReadOnlyField(source="niche.name")

    class Meta:
        model = AutomationTask
        fields = "__all__"
        read_only_fields = ["id", "owner", "created_at"]

    def validate_config(self, value):
        if not isinstance(value, dict):
            raise serializers.ValidationError("Task config must be an object.")
        for name, maximum in (("min_watch_seconds", 3600), ("max_watch_seconds", 3600),
                              ("shorts_count", 100), ("rabbit_hole_depth", 50), ("max_search_scroll_depth", 100)):
            if name in value and (isinstance(value[name], bool) or not isinstance(value[name], int) or not 1 <= value[name] <= maximum):
                raise serializers.ValidationError(f"{name} must be an integer between 1 and {maximum}.")
        if value.get("min_watch_seconds", 90) > value.get("max_watch_seconds", 240):
            raise serializers.ValidationError("Minimum watch duration must not exceed maximum duration.")
        return value

class TaskExecutionQueueSerializer(serializers.ModelSerializer):
    profile_name = serializers.ReadOnlyField(source="profile.name")
    task_name = serializers.ReadOnlyField(source="task.name")

    class Meta:
        model = Execution
        fields = "__all__"

class AIPromptConfigSerializer(serializers.ModelSerializer):
    class Meta:
        model = AIPromptConfig
        fields = "__all__"


class AssistantMessageSerializer(serializers.ModelSerializer):
    class Meta:
        model = AssistantMessage
        fields = ["id", "role", "content", "tool_calls", "tool_call_id", "created_at"]


class AssistantSessionSerializer(serializers.ModelSerializer):
    messages = AssistantMessageSerializer(many=True, read_only=True)

    class Meta:
        model = AssistantSession
        fields = ["id", "title", "created_at", "updated_at", "messages"]


class AutomationSerializer(serializers.ModelSerializer):
    task_name = serializers.ReadOnlyField(source="task.name")
    runs_count = serializers.IntegerField(source="runs.count", read_only=True)

    class Meta:
        model = Automation
        fields = "__all__"


class AutomationRunSerializer(serializers.ModelSerializer):
    automation_name = serializers.ReadOnlyField(source="automation.name")

    class Meta:
        model = AutomationRun
        fields = "__all__"


class CustomWorkflowSerializer(serializers.ModelSerializer):
    journeys_count = serializers.SerializerMethodField()
    steps_count = serializers.SerializerMethodField()

    class Meta:
        model = CustomWorkflow
        fields = "__all__"

    def get_journeys_count(self, obj):
        return len(obj.journeys or [])

    def get_steps_count(self, obj):
        total = 0
        for j in (obj.journeys or []):
            total += len(j.get("steps") or [])
        return total


class WorkflowAddonSerializer(serializers.ModelSerializer):
    steps_count = serializers.SerializerMethodField()
    templates_count = serializers.SerializerMethodField()

    class Meta:
        model = WorkflowAddon
        fields = [
            "id", "slug", "name", "version", "category", "platform",
            "author", "description", "icon", "is_active", "manifest",
            "steps_count", "templates_count", "created_at", "updated_at"
        ]

    def get_steps_count(self, obj):
        manifest = obj.manifest or {}
        return len(manifest.get("steps") or [])

    def get_templates_count(self, obj):
        manifest = obj.manifest or {}
        return len(manifest.get("templates") or [])




