import logging
from rest_framework import status, views, permissions
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser, FormParser, JSONParser
from .engine import PatchEngine
from .models import SystemPatch

logger = logging.getLogger("system_updater")


class SystemUpdatesListView(views.APIView):
    """Lists all system updates and hot-patches."""
    permission_classes = [permissions.IsAdminUser]

    def get(self, request):
        patches = PatchEngine.list_patches()
        return Response({"success": True, "count": len(patches), "patches": patches})


class SystemUpdateUploadView(views.APIView):
    """
    WordPress-style package upload:
    Accepts a .zip / .patch.zip package, validates manifest,
    creates shadow snapshot, applies files, and triggers Passenger reload.
    """
    permission_classes = [permissions.IsAdminUser]
    parser_classes = [MultiPartParser, FormParser]

    def post(self, request):
        zip_file = request.FILES.get("package") or request.FILES.get("file")
        if not zip_file:
            return Response(
                {"error": "No update package uploaded. Provide file field 'package'.", "code": "NO_FILE"},
                status=status.HTTP_400_BAD_REQUEST
            )

        applied_by = request.user.username if request.user.is_authenticated else "admin"

        try:
            patch_record, manifest = PatchEngine.apply_patch(zip_file, applied_by=applied_by)
            return Response({
                "success": True,
                "message": f"Hot-Patch '{patch_record.name}' applied successfully.",
                "patch": {
                    "id": patch_record.id,
                    "patch_id": patch_record.patch_id,
                    "name": patch_record.name,
                    "version": patch_record.version,
                    "description": patch_record.description,
                    "status": patch_record.status,
                    "file_count": patch_record.file_count,
                    "applied_at": patch_record.applied_at.isoformat(),
                    "rollback_command": f"python manage.py rollback_update --id {patch_record.patch_id}",
                }
            }, status=status.HTTP_201_CREATED)
        except Exception as e:
            logger.error("Failed to apply patch: %s", e, exc_info=True)
            return Response(
                {"error": str(e), "code": "PATCH_APPLY_FAILED"},
                status=status.HTTP_400_BAD_REQUEST
            )


class SystemUpdateRollbackView(views.APIView):
    """
    Rolls back a patch cleanly from the Web UI / API:
    Restores original files from the shadow snapshot vault and deletes created files.
    """
    permission_classes = [permissions.IsAdminUser]
    parser_classes = [JSONParser, FormParser]

    def post(self, request):
        patch_id = request.data.get("patch_id")
        dry_run = bool(request.data.get("dry_run", False))

        try:
            result = PatchEngine.rollback_patch(patch_id=patch_id, dry_run=dry_run)
            return Response({
                "success": True,
                "message": f"Patch '{result['patch_id']}' rolled back successfully.",
                "result": result
            })
        except Exception as e:
            logger.error("Failed to rollback patch: %s", e, exc_info=True)
            return Response(
                {"error": str(e), "code": "ROLLBACK_FAILED"},
                status=status.HTTP_400_BAD_REQUEST
            )
