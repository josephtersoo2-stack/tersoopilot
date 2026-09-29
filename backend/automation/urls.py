from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    NicheViewSet,
    AutomationTaskViewSet,
    ProfileNicheManagementViewSet,
    GhostPilotViewSet,
    AIPromptConfigViewSet,
    AssistantViewSet,
    AutomationViewSet,
    AutomationRunViewSet,
    YouTubeAnalyzeView,
    WorkflowListView,
    PlatformCalibrationView,
    CalibrateWithVLMView,
    CaptureDeviceScreenView,
    DownloadCalibrationJsonView,
    FlushCalibrationView,
    ImportCalibrationJsonView,
    SingleAnchorView,
    SingleAnchorDetailView,
    TestAnchorTapView,
    PullCalibrationFromDeviceView,
    PushCalibrationToDeviceView,
    CustomWorkflowViewSet,
    WorkflowAddonViewSet,
)
from devices.views import DeviceViewSet

router = DefaultRouter()
router.register(r"niches", NicheViewSet, basename="niche")
router.register(r"tasks", AutomationTaskViewSet, basename="automation-task")
router.register(r"profiles-orchestration", ProfileNicheManagementViewSet, basename="profile-orchestration")
router.register(r"ghostpilot", GhostPilotViewSet, basename="ghostpilot")
router.register(r"executions", GhostPilotViewSet, basename="ghostpilot-execution")
router.register(r"custom-workflows", CustomWorkflowViewSet, basename="custom-workflow")
router.register(r"addons", WorkflowAddonViewSet, basename="workflow-addon")
router.register(r"ai-config", AIPromptConfigViewSet, basename="ai-config")
router.register(r"assistant", AssistantViewSet, basename="assistant")
router.register(r"automations", AutomationViewSet, basename="automation")
router.register(r"rules", AutomationViewSet, basename="automation-rule")
router.register(r"runs", AutomationRunViewSet, basename="automation-run")
router.register(r"fleet", DeviceViewSet, basename="automation-fleet")

urlpatterns = [
    path("youtube/analyze/", YouTubeAnalyzeView.as_view(), name="youtube-analyze"),
    path("workflows/", WorkflowListView.as_view(), name="workflows-list"),
    path("calibration/", PlatformCalibrationView.as_view(), name="platform-calibration"),
    path("calibration/calibrate-with-llm/", CalibrateWithVLMView.as_view(), name="calibration-vlm"),
    path("calibration/capture-device/", CaptureDeviceScreenView.as_view(), name="calibration-capture-device"),
    path("calibration/download-json/", DownloadCalibrationJsonView.as_view(), name="calibration-download-json"),
    path("calibration/flush/", FlushCalibrationView.as_view(), name="calibration-flush"),
    path("calibration/import-json/", ImportCalibrationJsonView.as_view(), name="calibration-import-json"),
    path("calibration/test-tap/", TestAnchorTapView.as_view(), name="calibration-test-tap"),
    path("calibration/anchor/", SingleAnchorView.as_view(), name="calibration-single-anchor"),
    path("calibration/anchor/<str:anchor_id>/", SingleAnchorDetailView.as_view(), name="calibration-single-anchor-detail"),
    path("calibration/pull-from-device/", PullCalibrationFromDeviceView.as_view(), name="calibration-pull-from-device"),
    path("calibration/push-to-device/", PushCalibrationToDeviceView.as_view(), name="calibration-push-to-device"),
    path("", include(router.urls)),
]

