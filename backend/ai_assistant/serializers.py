from rest_framework import serializers
from .models import (
    AIProviderConfig,
    PromptTemplate,
    GlobalAISetting,
)


class AIProviderConfigSerializer(serializers.ModelSerializer):
    api_key_configured = serializers.SerializerMethodField()

    class Meta:
        model = AIProviderConfig
        fields = [
            "id",
            "name",
            "provider",
            "api_key",
            "api_key_configured",
            "model_name",
            "temperature",
            "is_active",
            "metadata",
            "created_at",
            "updated_at",
        ]
        extra_kwargs = {
            "api_key": {"write_only": True, "required": False},
        }

    def get_api_key_configured(self, obj) -> bool:
        return bool(obj.api_key)


class PromptTemplateSerializer(serializers.ModelSerializer):
    class Meta:
        model = PromptTemplate
        fields = [
            "id",
            "category",
            "name",
            "system_prompt",
            "is_active",
            "created_at",
            "updated_at",
        ]


class GlobalAISettingSerializer(serializers.ModelSerializer):
    class Meta:
        model = GlobalAISetting
        fields = [
            "singleton_id",
            "max_active_profiles",
            "force_global_mute",
            "default_video_resolution",
            "selected_ai_provider",
            "selected_ai_model",
            "saved_gemini_model",
            "saved_openrouter_model",
            "ai_generation_prompt",
            "target_search_sites",
            "updated_at",
        ]
        read_only_fields = ["singleton_id", "updated_at"]
