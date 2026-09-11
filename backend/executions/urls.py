from django.urls import path
from .views import (
    LeaseAcquireView,
    LeaseHeartbeatView,
    LeaseReleaseView,
    LeaseStatusView,
    ExecutionEventsView,
    StalledReaperView,
)

urlpatterns = [
    path("lease/acquire/", LeaseAcquireView.as_view(), name="lease-acquire"),
    path("lease/heartbeat/", LeaseHeartbeatView.as_view(), name="lease-heartbeat"),
    path("lease/release/", LeaseReleaseView.as_view(), name="lease-release"),
    path("lease/status/", LeaseStatusView.as_view(), name="lease-status"),
    path("<uuid:execution_id>/events/", ExecutionEventsView.as_view(), name="execution-events"),
    path("reap-stalled/", StalledReaperView.as_view(), name="execution-reap-stalled"),
]
