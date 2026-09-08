import json
import uuid
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status
from rest_framework.permissions import IsAuthenticated
from .models import SavedProfile
from .serializers import SavedProfileSerializer

class SyncPushView(APIView):
    """
    Pushes local profiles (including cookies, history, tabs, and hardware fingerprint)
    from the Android device to the user's cloud account.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        user = request.user
        data = request.data

        # Support both a single profile object or a list under 'profiles'
        profiles_to_sync = data.get("profiles")
        if profiles_to_sync is None:
            if isinstance(data, list):
                profiles_to_sync = data
            elif isinstance(data, dict):
                profiles_to_sync = [data]
            else:
                return Response({"error": "Invalid profile payload"}, status=status.HTTP_400_BAD_REQUEST)

        synced_results = []
        for p in profiles_to_sync:
            device_sync_id = str(p.get("device_sync_id") or p.get("id") or "").strip()
            name = str(p.get("name") or "Unnamed Profile").strip()

            # Serialize cookies / history / tabs if sent as raw JSON objects
            cookies_data = p.get("cookies_data", "[]")
            if not isinstance(cookies_data, str):
                cookies_data = json.dumps(cookies_data)

            history_data = p.get("history_data", "[]")
            if not isinstance(history_data, str):
                history_data = json.dumps(history_data)

            tabs_data = p.get("tabs_data", "[]")
            if not isinstance(tabs_data, str):
                tabs_data = json.dumps(tabs_data)

            # Try to find existing profile for this user
            existing = None
            if device_sync_id:
                existing = SavedProfile.objects.filter(user=user, device_sync_id=device_sync_id).first()
                if not existing:
                    # Check if device_sync_id is a valid UUID matching SavedProfile.id
                    try:
                        valid_uuid = uuid.UUID(device_sync_id)
                        existing = SavedProfile.objects.filter(user=user, id=valid_uuid).first()
                    except (ValueError, TypeError):
                        pass

            if not existing:
                existing = SavedProfile.objects.filter(user=user, name=name).first()

            fields = {
                "name": name,
                "tag": str(p.get("tag") or "Default"),
                "brand": str(p.get("brand") or "Google"),
                "model_name": str(p.get("model_name") or "Pixel"),
                "model_code": str(p.get("model_code") or "GP-01"),
                "android_version": int(p.get("android_version") or 14),
                "soc": str(p.get("soc") or "Snapdragon"),
                "webgl_vendor": str(p.get("webgl_vendor") or "Qualcomm"),
                "webgl_renderer": str(p.get("webgl_renderer") or "Adreno (TM) 750"),
                "ram_gb": int(p.get("ram_gb") or 8),
                "cpu_cores": int(p.get("cpu_cores") or 8),
                "screen_width": int(p.get("screen_width") or 384),
                "screen_height": int(p.get("screen_height") or 854),
                "dpr": float(p.get("dpr") or 2.8125),
                "user_agent": str(p.get("user_agent") or ""),
                "proxy_type": str(p.get("proxy_type") or "DIRECT"),
                "proxy_host": str(p.get("proxy_host") or ""),
                "proxy_port": int(p.get("proxy_port") or 0),
                "proxy_user": str(p.get("proxy_user") or ""),
                "proxy_pass": str(p.get("proxy_pass") or ""),
                "web_rtc_mode": str(p.get("web_rtc_mode") or "Mdns"),
                "cookies_data": cookies_data,
                "history_data": history_data,
                "tabs_data": tabs_data,
                "last_used_timestamp": int(p.get("last_used_timestamp") or 0),
                "cookie_count": int(p.get("cookie_count") or 0),
                "device_sync_id": device_sync_id,
            }

            if existing:
                for key, val in fields.items():
                    setattr(existing, key, val)
                existing.save()
                synced_results.append(existing)
            else:
                new_profile = SavedProfile.objects.create(user=user, **fields)
                synced_results.append(new_profile)

        serializer = SavedProfileSerializer(synced_results, many=True)
        return Response(
            {
                "status": "success",
                "message": f"Successfully synced {len(synced_results)} profiles.",
                "synced_count": len(synced_results),
                "profiles": serializer.data,
            },
            status=status.HTTP_200_OK
        )


class SyncPullView(APIView):
    """
    Pulls all saved profiles belonging to the authenticated user from the cloud.
    This enables restoring profiles, cookies, history, and tabs on a new phone.
    """
    permission_classes = [IsAuthenticated]

    def get(self, request):
        user = request.user
        profiles = SavedProfile.objects.filter(user=user).order_by("-updated_at")
        serializer = SavedProfileSerializer(profiles, many=True)
        return Response(
            {
                "status": "success",
                "count": profiles.count(),
                "profiles": serializer.data,
            },
            status=status.HTTP_200_OK
        )


class AutoSaveSessionView(APIView):
    """
    Real-time auto-saving endpoint called as the user browses.
    Saves isolated cookies, visited URL history, and tabs.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        user = request.user
        data = request.data

        device_sync_id = str(data.get("device_sync_id") or data.get("profile_id") or "").strip()
        name = str(data.get("name") or "").strip()

        profile = None
        if device_sync_id:
            profile = SavedProfile.objects.filter(user=user, device_sync_id=device_sync_id).first()
            if not profile:
                try:
                    valid_uuid = uuid.UUID(device_sync_id)
                    profile = SavedProfile.objects.filter(user=user, id=valid_uuid).first()
                except (ValueError, TypeError):
                    pass

        if not profile and name:
            profile = SavedProfile.objects.filter(user=user, name=name).first()

        if not profile:
            # Fallback to most recently updated profile for this user
            profile = SavedProfile.objects.filter(user=user).order_by("-updated_at").first()

        if not profile:
            return Response(
                {"error": "Profile not found to auto-save session."},
                status=status.HTTP_404_NOT_FOUND
            )

        if "cookies_data" in data:
            c = data["cookies_data"]
            profile.cookies_data = c if isinstance(c, str) else json.dumps(c)
        if "history_data" in data:
            h = data["history_data"]
            profile.history_data = h if isinstance(h, str) else json.dumps(h)
        if "tabs_data" in data:
            t = data["tabs_data"]
            profile.tabs_data = t if isinstance(t, str) else json.dumps(t)
        if "cookie_count" in data:
            profile.cookie_count = int(data["cookie_count"])
        if "last_used_timestamp" in data:
            profile.last_used_timestamp = int(data["last_used_timestamp"])

        profile.save()

        return Response(
            {
                "status": "saved",
                "profile_id": str(profile.id),
                "cookie_count": profile.cookie_count,
            },
            status=status.HTTP_200_OK
        )
