from django.contrib import admin
from .models import AssistantSession, AssistantMessage, AIPromptConfig


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
