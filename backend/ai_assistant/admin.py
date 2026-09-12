from django.contrib import admin
from django import forms
from .models import (
    AssistantSession,
    AssistantMessage,
    AIPromptConfig,
    AIProviderConfig,
    PromptTemplate,
    GlobalAISetting,
)


class AIProviderConfigAdminForm(forms.ModelForm):
    api_key = forms.CharField(
        widget=forms.PasswordInput(render_value=True),
        required=False,
        help_text="API key will be encrypted at rest with AES-256 Fernet."
    )

    class Meta:
        model = AIProviderConfig
        fields = "__all__"


@admin.register(AIProviderConfig)
class AIProviderConfigAdmin(admin.ModelAdmin):
    form = AIProviderConfigAdminForm
    list_display = ("name", "provider", "model_name", "temperature", "is_active", "updated_at")
    list_filter = ("provider", "is_active")
    search_fields = ("name", "model_name", "provider")
    list_editable = ("is_active", "model_name", "temperature")
    readonly_fields = ("id", "created_at", "updated_at")


@admin.register(PromptTemplate)
class PromptTemplateAdmin(admin.ModelAdmin):
    list_display = ("name", "category", "is_active", "updated_at")
    list_filter = ("category", "is_active")
    search_fields = ("name", "system_prompt")
    list_editable = ("is_active",)
    readonly_fields = ("id", "created_at", "updated_at")


@admin.register(GlobalAISetting)
class GlobalAISettingAdmin(admin.ModelAdmin):
    list_display = ("__str__", "selected_ai_provider", "max_active_profiles", "force_global_mute", "updated_at")
    readonly_fields = ("singleton_id", "updated_at")

    def has_add_permission(self, request):
        return False

    def has_delete_permission(self, request, obj=None):
        return False


class AssistantMessageInline(admin.TabularInline):
    model = AssistantMessage
    fk_name = "session"
    extra = 0
    readonly_fields = ("id", "role", "content", "tool_calls", "tool_call_id", "created_at")
    ordering = ("created_at",)

    def has_add_permission(self, request, obj=None):
        return False

    def has_delete_permission(self, request, obj=None):
        return False


@admin.register(AssistantSession)
class AssistantSessionAdmin(admin.ModelAdmin):
    list_display = ("title", "id", "message_count", "created_at", "updated_at")
    search_fields = ("title", "id")
    readonly_fields = ("id", "created_at", "updated_at")
    inlines = [AssistantMessageInline]
    ordering = ("-updated_at",)

    @admin.display(description="Messages")
    def message_count(self, obj):
        return obj.messages.count()


@admin.register(AssistantMessage)
class AssistantMessageAdmin(admin.ModelAdmin):
    list_display = ("short_content", "role", "session", "tool_call_id", "created_at")
    list_filter = ("role",)
    search_fields = ("content", "session__title")
    readonly_fields = ("id", "created_at")
    ordering = ("-created_at",)

    @admin.display(description="Content")
    def short_content(self, obj):
        return obj.content[:80] + "..." if len(obj.content) > 80 else obj.content


@admin.register(AIPromptConfig)
class AIPromptConfigAdmin(admin.ModelAdmin):
    list_display = ("name", "provider", "model_name", "temperature", "is_active", "updated_at")
    list_filter = ("provider", "is_active")
    search_fields = ("name", "model_name", "system_prompt")
    list_editable = ("is_active", "model_name", "temperature")
    readonly_fields = ("id", "created_at", "updated_at")
    fieldsets = (
        ("General Information", {
            "fields": ("id", "name", "is_active")
        }),
        ("Model Configuration", {
            "fields": ("provider", "model_name", "temperature")
        }),
        ("System Prompt Template", {
            "fields": ("system_prompt",),
            "description": "Available placeholders: {task_name}, {task_category}, {current_state}, {execution_context}"
        }),
        ("Timestamps", {
            "fields": ("created_at", "updated_at"),
            "classes": ("collapse",)
        }),
    )
