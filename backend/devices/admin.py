from django.contrib import admin
from django import forms
from .models import SavedProfile, LLMConfig, GlobalSetting, Device

class GlobalSettingAdminForm(forms.ModelForm):
    class Meta:
        model = GlobalSetting
        fields = "__all__"
        widgets = {
            "ai_generation_prompt": forms.Textarea(attrs={
                "rows": 14,
                "cols": 95,
                "style": "font-family: Consolas, 'Courier New', monospace; font-size: 13px; line-height: 1.5; background: #fdfdfd;"
            }),
            "target_search_sites": forms.Textarea(attrs={
                "rows": 5,
                "cols": 95,
                "style": "font-family: Consolas, 'Courier New', monospace; font-size: 13px; line-height: 1.5; background: #fdfdfd;",
                "placeholder": "gsmarena.com\ndevicespecifications.com\nphonearena.com\nkimovil.com\nnanoreview.net"
            }),
        }

@admin.register(GlobalSetting)
class GlobalSettingAdmin(admin.ModelAdmin):
    form = GlobalSettingAdminForm
    list_display = ("selected_ai_provider", "saved_gemini_model", "saved_openrouter_model", "prompt_preview", "force_global_mute", "updated_at")
    fieldsets = (
        ("AI Provider & Models Selection", {
            "description": "Select the active provider and view the saved models chosen for device fingerprint synthesis.",
            "fields": ("selected_ai_provider", "saved_gemini_model", "saved_openrouter_model")
        }),
        ("Target Hardware Spec Search Sites (Authoritative Domains)", {
            "description": "Specify the exact sites to search generally whenever any device profile is created (e.g. gsmarena.com, devicespecifications.com, manufacturer websites). Enter one domain per line.",
            "fields": ("target_search_sites",)
        }),
        ("AI Model Prompt Template (Where AI Device Instructions Are Configured)", {
            "description": "Enter the exact system prompt instructing the AI model (Gemini or OpenRouter) how to build and validate mobile hardware fingerprints (brand, model, SoC, GPU, DPR, User-Agent).",
            "fields": ("ai_generation_prompt",)
        }),
        ("Mobile Fleet Concurrency & Mute Controls", {
            "fields": ("max_active_profiles", "force_global_mute", "default_video_resolution")
        }),
    )

    @admin.display(description="Global Prompt (Preview)")
    def prompt_preview(self, obj):
        if obj.ai_generation_prompt:
            return obj.ai_generation_prompt[:75] + "..."
        return "Default"


@admin.register(SavedProfile)
class SavedProfileAdmin(admin.ModelAdmin):
    list_display = ("name", "id", "brand", "model_name", "model_code", "soc", "proxy_type", "created_at")
    search_fields = ("name", "id", "brand", "model_name", "model_code")
    list_filter = ("brand", "proxy_type", "android_version")
    readonly_fields = ("id", "created_at", "updated_at")
    fieldsets = (
        ("Profile Identity & UUID", {
            "fields": ("id", "name", "brand", "model_name", "model_code")
        }),
        ("Hardware & Screen", {
            "fields": ("soc", "cpu_cores", "ram_gb", "screen_width", "screen_height", "dpr", "webgl_renderer", "webgl_vendor")
        }),
        ("Network & Proxy", {
            "fields": ("proxy_type", "proxy_host", "proxy_port", "proxy_username", "proxy_password", "user_agent", "os_fingerprint")
        }),
        ("Cookies & Session", {
            "fields": ("cookie_count", "cookies_data")
        }),
        ("Timestamps", {
            "fields": ("created_at", "updated_at"),
            "classes": ("collapse",)
        }),
    )


class LLMConfigAdminForm(forms.ModelForm):
    class Meta:
        model = LLMConfig
        fields = "__all__"
        widgets = {
            "system_prompt": forms.Textarea(attrs={
                "rows": 10,
                "cols": 95,
                "style": "font-family: Consolas, 'Courier New', monospace; font-size: 13px; line-height: 1.5; background: #fdfdfd;",
                "placeholder": "Optional provider override: leave blank to use the Global AI Prompt from Global Settings."
            }),
        }

@admin.register(LLMConfig)
class LLMConfigAdmin(admin.ModelAdmin):
    form = LLMConfigAdminForm
    list_display = ("provider", "model_name", "has_api_key", "has_custom_prompt", "is_active", "updated_at")
    list_editable = ("is_active",)
    list_filter = ("provider", "is_active")
    fieldsets = (
        ("Provider Configuration", {
            "fields": ("provider", "is_active", "api_key", "model_name")
        }),
        ("Provider-Specific AI Prompt Override (Optional)", {
            "description": "Enter a custom prompt specific to this provider. If left blank, the Global AI Prompt Template will be used.",
            "fields": ("system_prompt",)
        }),
    )

    @admin.display(boolean=True, description="API Key Set")
    def has_api_key(self, obj):
        return bool(obj.api_key.strip())

    @admin.display(boolean=True, description="Custom Prompt Set")
    def has_custom_prompt(self, obj):
        return bool(obj.system_prompt.strip())


@admin.register(Device)
class DeviceAdmin(admin.ModelAdmin):
    list_display = ("device_id", "owner", "platform", "brand", "model_name", "status", "last_seen", "created_at")
    list_filter = ("platform", "status", "created_at")
    search_fields = ("device_id", "device_sync_id", "brand", "model_name", "owner__username")
    readonly_fields = ("id", "created_at", "updated_at")
