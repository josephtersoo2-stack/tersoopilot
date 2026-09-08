from django.contrib import admin
from .models import (
    Niche,
    ProfilePersona,
    ProfileNicheAffiliation,
    AutomationTask,
    TaskExecutionQueue,
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
    list_display = ("id", "task", "profile", "status", "started_at", "completed_at")
    list_filter = ("status",)
    search_fields = ("profile__name", "task__name", "id")

