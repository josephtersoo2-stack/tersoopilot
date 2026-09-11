import uuid
from django.db import models
from django.utils import timezone
from devices.models import SavedProfile

class ExecutionLeaseStatus(models.TextChoices):
    ACTIVE = "ACTIVE", "Active"
    EXPIRED = "EXPIRED", "Expired"
    RELEASED = "RELEASED", "Released"

class ExecutionLease(models.Model):
    """
    Guarantees single-device execution exclusivity per browser profile.
    Prevents duplicate browser sessions and race conditions across distributed devices.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    profile = models.ForeignKey(
        SavedProfile,
        on_delete=models.CASCADE,
        related_name="leases"
    )
    device_id = models.CharField(max_length=255, db_index=True)
    status = models.CharField(
        max_length=20,
        choices=ExecutionLeaseStatus.choices,
        default=ExecutionLeaseStatus.ACTIVE,
        db_index=True
    )
    heartbeat_at = models.DateTimeField(default=timezone.now)
    expires_at = models.DateTimeField(db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]
        indexes = [
            models.Index(fields=["profile", "status"]),
            models.Index(fields=["device_id", "status"]),
        ]

    def __str__(self):
        return f"Lease {self.id} [{self.profile.name}] on {self.device_id} ({self.status})"

    def is_active_and_valid(self) -> bool:
        return self.status == ExecutionLeaseStatus.ACTIVE and timezone.now() < self.expires_at
