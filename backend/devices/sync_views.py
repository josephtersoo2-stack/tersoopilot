import json
import uuid
from django.db import transaction
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.exceptions import ValidationError
from .models import SavedProfile
from .serializers import SavedProfileSerializer


def resolve_owned_profile(user, identifier):
    if not identifier:
        return None
    profiles = SavedProfile.objects.filter(user=user)
    try:
        profile = profiles.filter(id=uuid.UUID(str(identifier))).first()
        if profile:
            return profile
    except (ValueError, TypeError, AttributeError):
        pass
    matches = list(profiles.filter(device_sync_id=identifier)[:2])
    if len(matches) > 1:
        raise ValidationError({"device_sync_id": "Ambiguous profile identifier."})
    return matches[0] if matches else None


def normalized_payload(payload):
    if not isinstance(payload, dict):
        raise ValidationError("Each profile must be an object.")
    data = dict(payload)
    for field in ("cookies_data", "history_data", "tabs_data"):
        if field in data and not isinstance(data[field], str):
            data[field] = json.dumps(data[field])
    return data


class SyncPushView(APIView):
    @transaction.atomic
    def post(self, request):
        data = request.data
        profiles = data if isinstance(data, list) else data.get("profiles", [data]) if isinstance(data, dict) else None
        if not isinstance(profiles, list) or not 1 <= len(profiles) <= 100:
            raise ValidationError("Supply between 1 and 100 profiles.")
        saved = []
        for payload in profiles:
            fields = normalized_payload(payload)
            identifier = str(fields.get("device_sync_id") or fields.get("id") or "").strip()
            if not identifier:
                raise ValidationError({"device_sync_id": "A stable profile identifier is required."})
            existing = resolve_owned_profile(request.user, identifier)
            fields["device_sync_id"] = identifier
            serializer = SavedProfileSerializer(existing, data=fields, partial=True)
            serializer.is_valid(raise_exception=True)
            saved.append(serializer.save(user=request.user))
        return Response({"status": "success", "synced_count": len(saved),
                         "profiles": SavedProfileSerializer(saved, many=True).data})


class SyncPullView(APIView):
    def get(self, request):
        profiles = SavedProfile.objects.filter(user=request.user).order_by("-updated_at")
        return Response({"status": "success", "count": profiles.count(),
                         "profiles": SavedProfileSerializer(profiles, many=True).data})


class AutoSaveSessionView(APIView):
    @transaction.atomic
    def post(self, request):
        data = normalized_payload(request.data)
        identifier = data.get("device_sync_id") or data.get("profile_id")
        if not identifier:
            raise ValidationError({"device_sync_id": "A stable profile identifier is required."})
        profile = resolve_owned_profile(request.user, identifier)
        if not profile:
            return Response({"error": "Profile not found to auto-save session."}, status=404)
        fields = {k: v for k, v in data.items() if k in
                  ("cookies_data", "history_data", "tabs_data", "last_used_timestamp")}
        serializer = SavedProfileSerializer(profile, data=fields, partial=True)
        serializer.is_valid(raise_exception=True)
        profile = serializer.save()
        return Response({"status": "saved", "profile_id": str(profile.id), "cookie_count": profile.cookie_count})
