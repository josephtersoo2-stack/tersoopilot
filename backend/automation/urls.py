from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    NicheViewSet,
    AutomationTaskViewSet,
    ProfileNicheManagementViewSet,
    GhostPilotViewSet,
    AIPromptConfigViewSet,
    AssistantViewSet,
)

router = DefaultRouter()
router.register(r"niches", NicheViewSet, basename="niche")
router.register(r"tasks", AutomationTaskViewSet, basename="automation-task")
router.register(r"profiles-orchestration", ProfileNicheManagementViewSet, basename="profile-orchestration")
router.register(r"ghostpilot", GhostPilotViewSet, basename="ghostpilot")
router.register(r"executions", GhostPilotViewSet, basename="ghostpilot-execution")
router.register(r"ai-config", AIPromptConfigViewSet, basename="ai-config")
router.register(r"assistant", AssistantViewSet, basename="assistant")

urlpatterns = [
    path("", include(router.urls)),
]

