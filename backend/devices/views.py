import json
import logging
import uuid
import requests
from django.http import HttpResponse
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status, viewsets
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

