import json
import logging
import uuid
import requests
from django.http import HttpResponse
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.permissions import IsAuthenticated
from django.utils import timezone
from .models import SavedProfile, GlobalSetting
from core.permissions import AdminWritePermission, visible_profiles
from django.core.exceptions import ValidationError
from .serializers import (
    DeviceGenerateRequestSerializer,
    DeviceFingerprintResponseSerializer,
    IPLookupResponseSerializer,
    SavedProfileSerializer,
    GlobalSettingSerializer,
    CookieImportExportSerializer,
)
from .services import generate_device_specs_with_llm, fetch_available_models_from_provider

class GlobalSettingView(APIView):
    permission_classes = [AdminWritePermission]
    def get(self, request):
        settings = GlobalSetting.load()
        serializer = GlobalSettingSerializer(settings)
        return Response(serializer.data, status=status.HTTP_200_OK)

    def patch(self, request):
        settings = GlobalSetting.load()
        serializer = GlobalSettingSerializer(settings, data=request.data, partial=True)
        if serializer.is_valid():
            instance = serializer.save()
            
            # Sync saved model per provider to LLMConfig
            from .models import LLMConfig
            prov = instance.selected_ai_provider
            chosen_model = instance.selected_ai_model
            if prov == "gemini" and chosen_model:
                instance.saved_gemini_model = chosen_model
                LLMConfig.objects.filter(provider="gemini").update(model_name=chosen_model)
            elif prov == "openrouter" and chosen_model:
                instance.saved_openrouter_model = chosen_model
                LLMConfig.objects.filter(provider="openrouter").update(model_name=chosen_model)

            if "saved_gemini_model" in request.data:
                LLMConfig.objects.filter(provider="gemini").update(model_name=request.data["saved_gemini_model"])
            if "saved_openrouter_model" in request.data:
                LLMConfig.objects.filter(provider="openrouter").update(model_name=request.data["saved_openrouter_model"])

            instance.save()
            return Response(GlobalSettingSerializer(instance).data, status=status.HTTP_200_OK)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

class AvailableModelsView(APIView):
    throttle_scope = "ai"
    """
    Fetches all available models directly from the selected AI provider.
    No models are hardcoded.
    """
    def get(self, request):
        provider = request.query_params.get("provider", "openrouter").lower().strip()
        try:
            result = fetch_available_models_from_provider(provider)
            return Response(result, status=status.HTTP_200_OK)
        except Exception:
            return Response(
                {"error": "Failed to fetch models. Please check your API key and try again."},
                status=status.HTTP_500_INTERNAL_SERVER_ERROR
            )

class GenerateDeviceProfileView(APIView):
    throttle_scope = "ai"
    def post(self, request):
        input_serializer = DeviceGenerateRequestSerializer(data=request.data)
        if not input_serializer.is_valid():
            return Response(input_serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        query = input_serializer.validated_data["query"]
        provider = request.data.get("provider")
        model = request.data.get("model")

        try:
            device_specs = generate_device_specs_with_llm(query, provider_override=provider, model_override=model)
            output_serializer = DeviceFingerprintResponseSerializer(data=device_specs)
            output_serializer.is_valid(raise_exception=True)
            return Response(output_serializer.data, status=status.HTTP_200_OK)
        except Exception:
            return Response(
                {"error": "Failed to generate device specifications. Please try again."},
                status=status.HTTP_500_INTERNAL_SERVER_ERROR
            )

class IPLookupView(APIView):
    def get(self, request):
        target_ip = request.query_params.get("ip", "").strip()

        # Primary: ipwho.is (HTTPS, fast, no auth)
        try:
            lookup_url = f"https://ipwho.is/{target_ip}" if target_ip else "https://ipwho.is/"
            resp = requests.get(lookup_url, timeout=5.0)
            if resp.status_code == 200:
                data = resp.json()
                if data.get("success", True):
                    tz = data.get("timezone", {})
                    tz_id = tz.get("id") if isinstance(tz, dict) else str(tz)
                    payload = {
                        "ip": data.get("ip", "Unknown"),
                        "country": data.get("country", "Unknown"),
                        "country_code": data.get("country_code", "US"),
                        "city": data.get("city", "Unknown"),
                        "timezone": tz_id or "UTC",
                        "latitude": float(data.get("latitude", 0.0) or 0.0),
                        "longitude": float(data.get("longitude", 0.0) or 0.0),
                    }
                    serializer = IPLookupResponseSerializer(data=payload)
                    serializer.is_valid(raise_exception=True)
                    return Response(serializer.data, status=status.HTTP_200_OK)
        except Exception:
            pass

        # Secondary fallback: ip-api.com
        try:
            lookup_url = f"http://ip-api.com/json/{target_ip}" if target_ip else "http://ip-api.com/json/"
            resp = requests.get(lookup_url, timeout=5.0)
            if resp.status_code == 200:
                data = resp.json()
                if data.get("status") == "success":
                    payload = {
                        "ip": data.get("query", "Unknown"),
                        "country": data.get("country", "Unknown"),
                        "country_code": data.get("countryCode", "US"),
                        "city": data.get("city", "Unknown"),
                        "timezone": data.get("timezone", "UTC"),
                        "latitude": float(data.get("lat", 0.0) or 0.0),
                        "longitude": float(data.get("lon", 0.0) or 0.0),
                    }
                    serializer = IPLookupResponseSerializer(data=payload)
                    serializer.is_valid(raise_exception=True)
                    return Response(serializer.data, status=status.HTTP_200_OK)
        except Exception:
            return Response({"error": "IP lookup service unavailable."}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

        return Response({"error": "Failed to query IP service"}, status=status.HTTP_502_BAD_GATEWAY)

class SavedProfileViewSet(viewsets.ModelViewSet):
    """
    CRUD endpoints for syncing profiles across cloud and devices.
    If authenticated, scopes to request.user or unassigned profiles.
    """
    serializer_class = SavedProfileSerializer
    throttle_scope = "profiles"

    def get_queryset(self):
        return visible_profiles(self.request.user).order_by("-updated_at")

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)


def get_profile_for_cookies(profile_id, user):
    profiles = visible_profiles(user)
    try:
        profile = profiles.filter(id=profile_id).first()
        if profile:
            return profile
    except (ValidationError, ValueError):
        pass
    matches = list(profiles.filter(device_sync_id=profile_id)[:2])
    return matches[0] if len(matches) == 1 else None

class ProfileCookieExportView(APIView):
    """
    Exports cookies for a profile as a downloadable JSON file or raw JSON payload.
    GET /api/profiles/<profile_id>/cookies/export/?download=true
    """
    throttle_scope = "cookies"
    def get(self, request, profile_id):
        profile = get_profile_for_cookies(profile_id, request.user)
        if not profile:
            return Response({"error": "Profile not found"}, status=status.HTTP_404_NOT_FOUND)

        raw_cookies = profile.get_decrypted_cookies() or "[]"
        try:
            cookies_data = json.loads(raw_cookies)
        except Exception:
            cookies_data = []

        # Check if user requested direct file attachment
        as_file = request.query_params.get("download", "false").lower() == "true"
        if as_file:
            clean_name = str(profile.id)
            response = HttpResponse(
                json.dumps(cookies_data, indent=2),
                content_type="application/json"
            )
            response["Content-Disposition"] = f'attachment; filename="cookies_{clean_name}.json"'
            return response

        return Response({
            "profile_id": str(profile.id),
            "profile_name": profile.name,
            "cookie_count": len(cookies_data),
            "cookies": cookies_data
        }, status=status.HTTP_200_OK)

class ProfileCookieImportView(APIView):
    """
    Imports raw Netscape/JSON cookies directly into a backend profile record.
    POST /api/profiles/<profile_id>/cookies/import/
    """
    throttle_scope = "cookies"
    def post(self, request, profile_id):
        profile = get_profile_for_cookies(profile_id, request.user)
        if not profile:
            return Response({"error": "Profile not found. Sync it before importing cookies."}, status=404)

        serializer = CookieImportExportSerializer(data=request.data)
        if not serializer.is_valid():
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        cookie_list = serializer.validated_data["cookies"]
        profile.cookies_data = json.dumps(cookie_list)
        profile.cookie_count = len(cookie_list)
        profile.save(update_fields=["cookies_data", "cookie_count", "updated_at"])

        return Response({
            "status": "SUCCESS",
            "profile_id": str(profile.id),
            "profile_name": profile.name,
            "imported_count": profile.cookie_count
        }, status=status.HTTP_200_OK)


class DeviceRegisterView(APIView):
    """
    Registers or updates mobile or desktop automation nodes.
    POST /api/devices/register/
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        from .models import Device, DeviceStatus
        from .serializers import DeviceSerializer, DeviceRegisterRequestSerializer
        serializer = DeviceRegisterRequestSerializer(data=request.data)
        if not serializer.is_valid():
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        data = serializer.validated_data
        now = timezone.now()
        device_id = data["device_id"].strip()

        device, _ = Device.objects.update_or_create(
            device_id=device_id,
            defaults={
                "owner": request.user,
                "device_sync_id": data.get("device_sync_id", ""),
                "platform": data.get("platform", "ANDROID"),
                "brand": data.get("brand", ""),
                "model_name": data.get("model_name", ""),
                "app_version": data.get("app_version", ""),
                "android_version": data.get("android_version", 14),
                "geckoview_version": data.get("geckoview_version", ""),
                "battery_percent": data.get("battery_percent", 100),
                "screen_width": data.get("screen_width", 384),
                "screen_height": data.get("screen_height", 854),
                "capabilities": data.get("capabilities", {}),
                "metadata": data.get("metadata", {}),
                "status": DeviceStatus.ONLINE,
                "last_seen": now,
                "last_heartbeat": now,
            }
        )

        return Response(DeviceSerializer(device).data, status=status.HTTP_200_OK)


class DeviceHeartbeatView(APIView):
    """
    Periodic ping from mobile runner to maintain online status and update telemetry.
    POST /api/devices/heartbeat/
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        from .models import Device, DeviceStatus
        device_id = (request.data.get("device_id") or "").strip()
        if not device_id:
            return Response({"error": "device_id is required."}, status=status.HTTP_400_BAD_REQUEST)

        now = timezone.now()
        device = Device.objects.filter(device_id=device_id).first()
        if not device:
            device = Device.objects.create(
                owner=request.user,
                device_id=device_id,
                status=DeviceStatus.ONLINE,
                last_seen=now,
                last_heartbeat=now,
            )
        else:
            update_fields = ["last_seen", "last_heartbeat", "updated_at"]
            device.last_seen = now
            device.last_heartbeat = now
            if "battery_percent" in request.data:
                try:
                    device.battery_percent = int(request.data["battery_percent"])
                    update_fields.append("battery_percent")
                except (ValueError, TypeError):
                    pass
            if "capabilities" in request.data and isinstance(request.data["capabilities"], dict):
                device.capabilities.update(request.data["capabilities"])
                update_fields.append("capabilities")
            if "metadata" in request.data and isinstance(request.data["metadata"], dict):
                device.metadata.update(request.data["metadata"])
                update_fields.append("metadata")
            if "status" in request.data and request.data["status"] in DeviceStatus.values:
                device.status = request.data["status"]
                update_fields.append("status")
            elif device.status == DeviceStatus.OFFLINE:
                device.status = DeviceStatus.ONLINE
                update_fields.append("status")

            device.save(update_fields=update_fields)

        return Response({
            "status": "ALIVE",
            "device_id": device.device_id,
            "device_status": device.status,
            "battery_percent": device.battery_percent,
            "last_seen": device.last_seen.isoformat(),
            "last_heartbeat": device.last_heartbeat.isoformat()
        }, status=status.HTTP_200_OK)


class DeviceViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Inspects and manages registered nodes across the device fleet.
    GET /api/devices/registry/
    POST /api/devices/registry/{id}/disable/
    POST /api/devices/registry/{id}/enable/
    """
    from .serializers import DeviceSerializer
    serializer_class = DeviceSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        from .models import Device
        user = self.request.user
        if user.is_staff or user.is_superuser:
            return Device.objects.all().order_by("-last_seen")
        return Device.objects.filter(owner=user).order_by("-last_seen")

    @action(detail=True, methods=["post"], url_path="disable")
    def disable(self, request, pk=None):
        from .models import DeviceStatus
        device = self.get_object()
        device.status = DeviceStatus.DISABLED
        device.save(update_fields=["status", "updated_at"])
        return Response({"status": device.status, "device_id": device.device_id}, status=status.HTTP_200_OK)

    @action(detail=True, methods=["post"], url_path="enable")
    def enable(self, request, pk=None):
        from .models import DeviceStatus
        device = self.get_object()
        device.status = DeviceStatus.ONLINE
        device.save(update_fields=["status", "updated_at"])
        return Response({"status": device.status, "device_id": device.device_id}, status=status.HTTP_200_OK)

