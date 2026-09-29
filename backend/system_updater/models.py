import os
from django.db import models
from django.utils import timezone


class SystemPatch(models.Model):
    """
    Tracks applied system hot-patches and updates with versioning,
    file manifests, shadow snapshot locations, and rollback history.
    """
    STATUS_ACTIVE = "ACTIVE"
    STATUS_ROLLED_BACK = "ROLLED_BACK"
    STATUS_FAILED = "FAILED"

    STATUS_CHOICES = [
        (STATUS_ACTIVE, "Active / Applied"),
        (STATUS_ROLLED_BACK, "Rolled Back"),
        (STATUS_FAILED, "Failed Application"),
    ]

    patch_id = models.CharField(max_length=120, unique=True, db_index=True)
    name = models.CharField(max_length=255)
    version = models.CharField(max_length=50, default="1.0.0")
    description = models.TextField(blank=True, default="")
    author = models.CharField(max_length=100, blank=True, default="System")
    status = models.CharField(
        max_length=30,
        choices=STATUS_CHOICES,
        default=STATUS_ACTIVE,
        db_index=True
    )
    applied_at = models.DateTimeField(auto_now_add=True)
    rolled_back_at = models.DateTimeField(null=True, blank=True)
    backup_dir = models.CharField(max_length=500, blank=True, default="")
    files_modified = models.JSONField(default=list)
    manifest_data = models.JSONField(default=dict)
    applied_by = models.CharField(max_length=100, default="system")

    class Meta:
        verbose_name = "System Hot-Patch"
        verbose_name_plural = "System Hot-Patches"
        ordering = ["-applied_at"]

    def __str__(self):
        return f"[{self.status}] {self.name} (v{self.version}) - {self.patch_id}"

    @property
    def file_count(self) -> int:
        return len(self.files_modified) if isinstance(self.files_modified, list) else 0

    @property
    def is_active(self) -> bool:
        return self.status == self.STATUS_ACTIVE
