from rest_framework.permissions import BasePermission, SAFE_METHODS


class AdminWritePermission(BasePermission):
    """Authenticated users may read shared configuration; only staff may edit."""

    def has_permission(self, request, view):
        return bool(request.user and request.user.is_authenticated and (
            request.method in SAFE_METHODS or request.user.is_staff
        ))


def visible_profiles(user):
    from devices.models import SavedProfile
    if not user or not user.is_authenticated:
        return SavedProfile.objects.none()
    if user.is_staff:
        return SavedProfile.objects.all()
    return SavedProfile.objects.filter(user=user)
