from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    LeaseAcquireView,
    LeaseHeartbeatView,
    LeaseReleaseView,
    LeaseStatusView,
    ExecutionEventsView,
    StalledReaperView,
    GhostPilotExecutionViewSet,
)

router = DefaultRouter()
router.register(r"", GhostPilotExecutionViewSet, basename="execution")

urlpatterns = [
    path("lease/acquire/", LeaseAcquireView.as_view(), name="lease-acquire"),
    path("lease/heartbeat/", LeaseHeartbeatView.as_view(), name="lease-heartbeat"),
    path("lease/release/", LeaseReleaseView.as_view(), name="lease-release"),
    path("lease/status/", LeaseStatusView.as_view(), name="lease-status"),
    path("<uuid:execution_id>/events/", ExecutionEventsView.as_view(), name="execution-events"),
    path("reap-stalled/", StalledReaperView.as_view(), name="execution-reap-stalled"),
    path("", include(router.urls)),
]
