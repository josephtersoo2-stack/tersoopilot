from django.urls import path
from .views import (
    LeaseAcquireView,
    LeaseHeartbeatView,
    LeaseReleaseView,
    LeaseStatusView,
)

urlpatterns = [
    path("lease/acquire/", LeaseAcquireView.as_view(), name="lease-acquire"),
    path("lease/heartbeat/", LeaseHeartbeatView.as_view(), name="lease-heartbeat"),
    path("lease/release/", LeaseReleaseView.as_view(), name="lease-release"),
    path("lease/status/", LeaseStatusView.as_view(), name="lease-status"),
]
