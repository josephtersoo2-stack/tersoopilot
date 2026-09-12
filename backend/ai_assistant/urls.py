from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    AIProviderConfigViewSet,
    PromptTemplateViewSet,
    GlobalAISettingView,
)

router = DefaultRouter()
router.register(r"providers", AIProviderConfigViewSet, basename="ai-provider")
router.register(r"prompts", PromptTemplateViewSet, basename="ai-prompt")

urlpatterns = [
    path("settings/", GlobalAISettingView.as_view(), name="ai-global-settings"),
    path("", include(router.urls)),
]
