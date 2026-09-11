from django.contrib import admin
from .models import Execution, ExecutionLease, ExecutionEvent


@admin.register(Execution)
class ExecutionAdmin(admin.ModelAdmin):
    list_display = ("id", "task", "profile", "status", "current_state_id", "started_at", "completed_at")
    list_filter = ("status", "created_at")
    search_fields = ("id", "task__name", "profile__name")


@admin.register(ExecutionLease)
class ExecutionLeaseAdmin(admin.ModelAdmin):
    list_display = ("id", "profile", "execution", "device_id", "status", "expires_at")
    list_filter = ("status", "device_id")
    search_fields = ("id", "profile__name", "device_id")


@admin.register(ExecutionEvent)
class ExecutionEventAdmin(admin.ModelAdmin):
    list_display = ("id", "execution", "event_type", "created_at")
    list_filter = ("event_type", "created_at")
    search_fields = ("id", "execution__id")
