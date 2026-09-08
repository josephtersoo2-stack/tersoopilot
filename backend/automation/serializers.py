from rest_framework import serializers
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

    class Meta:
        model = ProfileNicheAffiliation
        fields = ["id", "niche", "niche_name", "weight_percentage"]

class AutomationTaskSerializer(serializers.ModelSerializer):
    niche_name = serializers.ReadOnlyField(source="niche.name")

    class Meta:
        model = AutomationTask
        fields = "__all__"

class TaskExecutionQueueSerializer(serializers.ModelSerializer):
    profile_name = serializers.ReadOnlyField(source="profile.name")
    task_name = serializers.ReadOnlyField(source="task.name")

    class Meta:
        model = TaskExecutionQueue
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

