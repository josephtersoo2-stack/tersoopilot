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
