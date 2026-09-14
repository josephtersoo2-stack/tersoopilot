from django.contrib import admin
from .models import (
    Niche,
    ProfilePersona,
    ProfileNicheAffiliation,
    AutomationTask,
    TaskExecutionQueue,
    Automation,
    AutomationRun,
)

@admin.register(Niche)
class NicheAdmin(admin.ModelAdmin):
    list_display = ("name", "created_at", "updated_at")
    search_fields = ("name", "description")

@admin.register(ProfilePersona)
class ProfilePersonaAdmin(admin.ModelAdmin):
    list_display = ("profile", "trust_score", "maturation_stage", "patience_index", "typing_wpm", "updated_at")
    list_filter = ("maturation_stage",)
    search_fields = ("profile__name",)

@admin.register(ProfileNicheAffiliation)
class ProfileNicheAffiliationAdmin(admin.ModelAdmin):
    list_display = ("profile", "niche", "weight_percentage")
    list_filter = ("niche",)
    search_fields = ("profile__name", "niche__name")

@admin.register(AutomationTask)
class AutomationTaskAdmin(admin.ModelAdmin):
    list_display = ("name", "category", "niche", "created_at")
    list_filter = ("category", "niche")
    search_fields = ("name",)

@admin.register(TaskExecutionQueue)
class TaskExecutionQueueAdmin(admin.ModelAdmin):
    list_display = ("id", "task", "profile", "created_at")
    list_filter = ("execution__status", "created_at")
    search_fields = ("profile__name", "task__name", "id")

@admin.register(Automation)
class AutomationAdmin(admin.ModelAdmin):
    list_display = ("name", "task", "schedule_type", "enabled", "concurrency_limit", "cooldown_minutes", "next_run_at", "created_at")
    list_filter = ("enabled", "schedule_type", "selection_mode")
    search_fields = ("name", "description")

@admin.register(AutomationRun)
class AutomationRunAdmin(admin.ModelAdmin):
    list_display = ("id", "automation", "run_key", "status", "scheduled_for", "total_target_profiles", "success_count", "failure_count", "created_at")
    list_filter = ("status", "scheduled_for")
    search_fields = ("automation__name", "run_key", "id")


