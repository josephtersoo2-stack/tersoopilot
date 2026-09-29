from django.urls import path
from .views import (
    SystemUpdatesListView,
    SystemUpdateUploadView,
    SystemUpdateRollbackView,
)

urlpatterns = [
    path("", SystemUpdatesListView.as_view(), name="system-updates-list"),
    path("upload/", SystemUpdateUploadView.as_view(), name="system-update-upload"),
    path("rollback/", SystemUpdateRollbackView.as_view(), name="system-update-rollback"),
]
