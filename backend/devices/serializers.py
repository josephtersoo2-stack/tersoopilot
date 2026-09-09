import json
from rest_framework import serializers
from .models import SavedProfile, GlobalSetting

class GlobalSettingSerializer(serializers.ModelSerializer):
    class Meta:
        model = GlobalSetting
        fields = [
            "max_active_profiles", 
            "force_global_mute", 
            "default_video_resolution", 
            "selected_ai_provider", 
            "selected_ai_model", 
            "saved_gemini_model",
            "saved_openrouter_model",
            "ai_generation_prompt",
            "target_search_sites",
            "updated_at"
        ]

    def validate_max_active_profiles(self, value):
        if not (1 <= value <= 10):
            raise serializers.ValidationError("max_active_profiles must be between 1 and 10.")
        return value

class DeviceGenerateRequestSerializer(serializers.Serializer):
    query = serializers.CharField(max_length=255, required=True)

class DeviceFingerprintResponseSerializer(serializers.Serializer):
    brand = serializers.CharField()
    model_name = serializers.CharField()
    model_code = serializers.CharField()
    android_version = serializers.IntegerField()
    soc = serializers.CharField()
    webgl_vendor = serializers.CharField()
    webgl_renderer = serializers.CharField()
    ram_gb = serializers.IntegerField()
    cpu_cores = serializers.IntegerField()
    screen_width = serializers.IntegerField()
    screen_height = serializers.IntegerField()
    dpr = serializers.FloatField()
    user_agent = serializers.CharField()

class IPLookupResponseSerializer(serializers.Serializer):
    ip = serializers.CharField()
    country = serializers.CharField()
    country_code = serializers.CharField()
    city = serializers.CharField()
    timezone = serializers.CharField()
    latitude = serializers.FloatField()
    longitude = serializers.FloatField()

class SavedProfileSerializer(serializers.ModelSerializer):
    class Meta:
        model = SavedProfile
        fields = "__all__"
        read_only_fields = ["user", "cookie_count", "created_at", "updated_at"]

    def validate(self, attrs):
        for field in ("cookies_data", "history_data", "tabs_data"):
            if field in attrs:
                try:
                    items = json.loads(attrs[field])
                except (ValueError, TypeError):
                    raise serializers.ValidationError({field: "Must contain a JSON array."})
                if not isinstance(items, list) or len(items) > 10000:
                    raise serializers.ValidationError({field: "Must contain an array of at most 10000 items."})
                if field == "cookies_data":
                    validator = CookieImportExportSerializer(data={"cookies": items})
                    validator.is_valid(raise_exception=True)
                    attrs["cookie_count"] = len(items)
        for field, low, high in (("android_version", 1, 100), ("ram_gb", 1, 1024),
                                  ("cpu_cores", 1, 256), ("screen_width", 1, 32768),
                                  ("screen_height", 1, 32768), ("dpr", 0.1, 16),
                                  ("proxy_port", 0, 65535), ("last_used_timestamp", 0, 2**63-1)):
            if field in attrs and not low <= attrs[field] <= high:
                raise serializers.ValidationError({field: f"Must be between {low} and {high}."})
        return attrs

class CookieImportExportSerializer(serializers.Serializer):
    cookies = serializers.ListField(
        child=serializers.DictField(),
        required=True
    )

    def validate_cookies(self, value):
        for item in value:
            if "name" not in item or "value" not in item or ("domain" not in item and "host" not in item):
                raise serializers.ValidationError("Each cookie must have 'name', 'value', and 'domain'/'host'.")
        return value
