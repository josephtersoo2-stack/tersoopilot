from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    GenerateDeviceProfileView,
    IPLookupView,
    SavedProfileViewSet,
    AvailableModelsView,
    GlobalSettingView,
    ProfileCookieExportView,
    ProfileCookieImportView,
    DeviceRegisterView,
    DeviceHeartbeatView,
    DeviceViewSet,
)
from .auth_views import RegisterView, LoginView, UserMeView, LogoutView
from .sync_views import SyncPushView, SyncPullView, AutoSaveSessionView

router = DefaultRouter()
router.register(r"profiles", SavedProfileViewSet, basename="saved-profile")
router.register(r"devices/registry", DeviceViewSet, basename="device-registry")

urlpatterns = [
    # Auth endpoints
    path("auth/register/", RegisterView.as_view(), name="auth-register"),
    path("auth/login/", LoginView.as_view(), name="auth-login"),
    path("auth/me/", UserMeView.as_view(), name="auth-me"),

    path("auth/logout/", LogoutView.as_view(), name="auth-logout"),

    # Sync endpoints
    path("sync/push/", SyncPushView.as_view(), name="sync-push"),
    path("sync/pull/", SyncPullView.as_view(), name="sync-pull"),
    path("sync/auto-save/", AutoSaveSessionView.as_view(), name="sync-auto-save"),

    # Cookie Import/Export endpoints
    path("profiles/<str:profile_id>/cookies/export/", ProfileCookieExportView.as_view(), name="cookie-export"),
    path("profiles/<str:profile_id>/cookies/import/", ProfileCookieImportView.as_view(), name="cookie-import"),

    # Device Registration & Heartbeat endpoints
    path("devices/register/", DeviceRegisterView.as_view(), name="device-register"),
    path("devices/heartbeat/", DeviceHeartbeatView.as_view(), name="device-heartbeat"),

    # Global settings and devices
    path("settings/global/", GlobalSettingView.as_view(), name="global-settings"),
    path("devices/models/", AvailableModelsView.as_view(), name="available-models"),
    path("devices/generate/", GenerateDeviceProfileView.as_view(), name="generate-device"),
    path("ip/lookup/", IPLookupView.as_view(), name="ip-lookup"),
    path("", include(router.urls)),
]

