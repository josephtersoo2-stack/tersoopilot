from rest_framework import viewsets, status
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated

from core.permissions import AdminWritePermission
from .models import (
    AIProviderConfig,
    PromptTemplate,
    GlobalAISetting,
)
from .serializers import (
    AIProviderConfigSerializer,
    PromptTemplateSerializer,
    GlobalAISettingSerializer,
)


class AIProviderConfigViewSet(viewsets.ModelViewSet):
    """
    CRUD ViewSet for managing AI provider credentials and model configurations.
    """
    queryset = AIProviderConfig.objects.all()
    serializer_class = AIProviderConfigSerializer
    permission_classes = [AdminWritePermission]
    filterset_fields = ["provider", "is_active"]
    search_fields = ["name", "provider", "model_name"]


class PromptTemplateViewSet(viewsets.ModelViewSet):
    """
    CRUD ViewSet for managing category-based system prompt templates.
    """
    queryset = PromptTemplate.objects.all()
    serializer_class = PromptTemplateSerializer
    permission_classes = [AdminWritePermission]
    filterset_fields = ["category", "is_active"]
    search_fields = ["name", "system_prompt"]


class GlobalAISettingView(APIView):
    """
    Operator settings for global AI provider defaults, concurrency, and search sites.
    """
    permission_classes = [AdminWritePermission]

    def get(self, request):
        settings = GlobalAISetting.load()
        return Response(GlobalAISettingSerializer(settings).data, status=status.HTTP_200_OK)

    def patch(self, request):
        settings = GlobalAISetting.load()
        serializer = GlobalAISettingSerializer(settings, data=request.data, partial=True)
        if serializer.is_valid():
            instance = serializer.save()
            # Also sync to devices.GlobalSetting for backward compatibility
            try:
                from devices.models import GlobalSetting
                dev_setting = GlobalSetting.load()
                for attr in [
                    "selected_ai_provider", "selected_ai_model",
                    "saved_gemini_model", "saved_openrouter_model",
                    "max_active_profiles", "force_global_mute",
                    "default_video_resolution", "ai_generation_prompt",
                    "target_search_sites"
                ]:
                    if hasattr(instance, attr):
                        setattr(dev_setting, attr, getattr(instance, attr))
                dev_setting.save()
            except Exception:
                pass
            return Response(GlobalAISettingSerializer(instance).data, status=status.HTTP_200_OK)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)
