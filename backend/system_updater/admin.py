from django.contrib import admin, messages
from django.shortcuts import redirect, render
from django.urls import path
from django.utils.html import format_html
from django.utils.safestring import mark_safe
from .models import SystemPatch
from .engine import PatchEngine


@admin.register(SystemPatch)
class SystemPatchAdmin(admin.ModelAdmin):
    list_display = (
        "patch_id",
        "name",
        "version",
        "status_badge",
        "file_count_display",
        "applied_by",
        "applied_at",
        "rolled_back_at",
        "action_buttons",
    )
    list_filter = ("status", "applied_at")
    search_fields = ("patch_id", "name", "version", "description", "applied_by")
    readonly_fields = (
        "patch_id",
        "name",
        "version",
        "description",
        "author",
        "status",
        "applied_at",
        "rolled_back_at",
        "backup_dir",
        "files_modified",
        "manifest_data",
        "applied_by",
    )
    actions = ["action_rollback_selected"]

    def changelist_view(self, request, extra_context=None):
        try:
            return super().changelist_view(request, extra_context=extra_context)
        except Exception as e:
            err_msg = str(e).lower()
            if any(term in err_msg for term in ["doesn't exist", "no such table", "1146", "relation", "table"]):
                try:
                    from django.core.management import call_command
                    call_command("migrate", interactive=False)
                    messages.success(request, "Database tables were missing and have been automatically migrated successfully!")
                    return super().changelist_view(request, extra_context=extra_context)
                except Exception as mig_err:
                    messages.error(request, f"Database table missing. Please run 'python manage.py migrate' in cPanel terminal. Details: {mig_err}")
                    return redirect("admin:index")
            raise e

    def status_badge(self, obj):
        if obj.status == SystemPatch.STATUS_ACTIVE:
            return mark_safe('<span style="color: #10B981; font-weight: bold; background: #ECFDF5; padding: 3px 8px; border-radius: 4px;">ACTIVE</span>')
        elif obj.status == SystemPatch.STATUS_ROLLED_BACK:
            return mark_safe('<span style="color: #6B7280; font-weight: bold; background: #F3F4F6; padding: 3px 8px; border-radius: 4px;">ROLLED BACK</span>')
        return mark_safe('<span style="color: #EF4444; font-weight: bold; background: #FEF2F2; padding: 3px 8px; border-radius: 4px;">FAILED</span>')
    status_badge.short_description = "Status"

    def file_count_display(self, obj):
        return f"{obj.file_count} files"
    file_count_display.short_description = "Files"

    def action_buttons(self, obj):
        if obj.is_active:
            return format_html(
                '<a class="button" style="background: #EF4444; color: white;" href="rollback/{}/">Undo Update</a>',
                obj.patch_id
            )
        return mark_safe('<span style="color: #9CA3AF;">Reverted</span>')
    action_buttons.short_description = "Rollback"

    @admin.action(description="Rollback selected active patch")
    def action_rollback_selected(self, request, queryset):
        for patch in queryset:
            if patch.is_active:
                try:
                    result = PatchEngine.rollback_patch(patch_id=patch.patch_id)
                    messages.success(request, f"Successfully rolled back patch {patch.patch_id}.")
                except Exception as e:
                    messages.error(request, f"Failed to rollback {patch.patch_id}: {e}")
            else:
                messages.warning(request, f"Patch {patch.patch_id} is not active.")

    def get_urls(self):
        urls = super().get_urls()
        custom_urls = [
            path("upload-patch/", self.admin_site.admin_view(self.upload_patch_view), name="system_patch_upload"),
            path("rollback/<str:patch_id>/", self.admin_site.admin_view(self.rollback_patch_view), name="system_patch_rollback_single"),
        ]
        return custom_urls + urls

    def upload_patch_view(self, request):
        if request.method == "POST":
            uploaded_file = request.FILES.get("patch_file")
            if not uploaded_file:
                messages.error(request, "Please choose a .zip patch file to upload.")
                return redirect("..")
            try:
                patch_record, manifest = PatchEngine.apply_patch(
                    uploaded_file,
                    applied_by=request.user.username if request.user.is_authenticated else "admin"
                )
                messages.success(
                    request,
                    f"Hot-Patch '{patch_record.name}' (v{patch_record.version}) successfully deployed! "
                    f"{patch_record.file_count} files updated. Passenger reloaded."
                )
            except Exception as e:
                messages.error(request, f"Failed to apply patch: {e}")
            return redirect("..")

        return render(request, "admin/system_patch_upload.html", {"title": "Upload System Update (.zip)"})

    def rollback_patch_view(self, request, patch_id):
        try:
            result = PatchEngine.rollback_patch(patch_id=patch_id)
            messages.success(
                request,
                f"Patch '{patch_id}' has been undone. {result['restored_count']} files restored, "
                f"{result['deleted_count']} newly added files deleted."
            )
        except Exception as e:
            messages.error(request, f"Rollback failed: {e}")
        return redirect("../../")
