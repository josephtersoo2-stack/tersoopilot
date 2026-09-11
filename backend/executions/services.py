import datetime
import logging
from django.db import transaction
from django.utils import timezone
from devices.models import SavedProfile
from .models import ExecutionLease, ExecutionLeaseStatus

logger = logging.getLogger(__name__)

class LeaseService:
    DEFAULT_LEASE_DURATION_SECONDS = 60
    DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30

    @classmethod
    @transaction.atomic
    def acquire_lease(
        cls,
        user,
        profile_id,
        device_id: str,
        duration_seconds: int = DEFAULT_LEASE_DURATION_SECONDS
    ) -> tuple[ExecutionLease | None, str | None]:
        """
        Attempts to acquire an exclusive execution lease for the given profile and device.
        If another device holds an active, unexpired lease, returns an error (409 Conflict).
        """
        device_id = (device_id or "").strip()
        if not device_id:
            return None, "Device ID is required to acquire an execution lease."

        try:
            profile = SavedProfile.objects.select_for_update().get(id=profile_id)
        except (SavedProfile.DoesNotExist, ValueError):
            return None, "Profile not found."

        # Verify user ownership
        if profile.user and profile.user != user:
            return None, "You do not have permission to execute this profile."

        now = timezone.now()

        # Find existing active leases for this profile
        active_leases = ExecutionLease.objects.select_for_update().filter(
            profile=profile,
            status=ExecutionLeaseStatus.ACTIVE
        )

        for lease in active_leases:
            # Check if expired
            if now >= lease.expires_at:
                lease.status = ExecutionLeaseStatus.EXPIRED
                lease.save(update_fields=["status", "updated_at"])
                logger.info(f"Lease {lease.id} expired past timeout ({lease.expires_at})")
            elif lease.device_id == device_id:
                # Same device re-acquiring or renewing
                lease.heartbeat_at = now
                lease.expires_at = now + datetime.timedelta(seconds=duration_seconds)
                lease.save(update_fields=["heartbeat_at", "expires_at", "updated_at"])
                logger.info(f"Renewed execution lease {lease.id} for device {device_id}")
                return lease, None
            else:
                # Different device currently holds active lease!
                remaining = int((lease.expires_at - now).total_seconds())
                msg = (
                    f"Profile '{profile.name}' is already leased by device '{lease.device_id}' "
                    f"(lease expires in {remaining}s)."
                )
                logger.warning(f"Lease conflict on profile {profile.id}: {msg}")
                return None, msg

        # No active lease exists: create new lease
        new_lease = ExecutionLease.objects.create(
            profile=profile,
            device_id=device_id,
            status=ExecutionLeaseStatus.ACTIVE,
            heartbeat_at=now,
            expires_at=now + datetime.timedelta(seconds=duration_seconds)
        )
        logger.info(f"Granted new execution lease {new_lease.id} for profile {profile.name} to {device_id}")
        return new_lease, None

    @classmethod
    @transaction.atomic
    def heartbeat(
        cls,
        user,
        lease_id,
        device_id: str,
        extension_seconds: int = DEFAULT_LEASE_DURATION_SECONDS
    ) -> tuple[ExecutionLease | None, str | None]:
        """
        Refreshes heartbeat and extends expires_at for an active lease.
        """
        device_id = (device_id or "").strip()
        try:
            lease = ExecutionLease.objects.select_for_update().get(id=lease_id)
        except (ExecutionLease.DoesNotExist, ValueError):
            return None, "Execution lease not found."

        if lease.profile.user and lease.profile.user != user:
            return None, "Unauthorized: Lease profile belongs to another user."

        if lease.device_id != device_id:
            return None, f"Device mismatch: Lease was issued to '{lease.device_id}', not '{device_id}'."

        now = timezone.now()

        if lease.status != ExecutionLeaseStatus.ACTIVE:
            return None, f"Cannot renew lease: Current status is '{lease.status}'."

        if now >= lease.expires_at:
            lease.status = ExecutionLeaseStatus.EXPIRED
            lease.save(update_fields=["status", "updated_at"])
            return None, "Execution lease has expired. Please acquire a new lease."

        lease.heartbeat_at = now
        lease.expires_at = now + datetime.timedelta(seconds=extension_seconds)
        lease.save(update_fields=["heartbeat_at", "expires_at", "updated_at"])
        return lease, None

    @classmethod
    @transaction.atomic
    def release_lease(
        cls,
        user,
        lease_id,
        device_id: str
    ) -> tuple[bool, str | None]:
        """
        Releases an active execution lease immediately when automation or browser session stops.
        """
        device_id = (device_id or "").strip()
        try:
            lease = ExecutionLease.objects.select_for_update().get(id=lease_id)
        except (ExecutionLease.DoesNotExist, ValueError):
            return False, "Execution lease not found."

        if lease.profile.user and lease.profile.user != user:
            return False, "Unauthorized: Lease profile belongs to another user."

        if lease.device_id != device_id:
            return False, f"Device mismatch: Lease was issued to '{lease.device_id}', not '{device_id}'."

        lease.status = ExecutionLeaseStatus.RELEASED
        lease.save(update_fields=["status", "updated_at"])
        logger.info(f"Released execution lease {lease.id} for device {device_id}")
        return True, None

    @classmethod
    def get_profile_lease_status(cls, user, profile_id) -> dict:
        try:
            profile = SavedProfile.objects.get(id=profile_id)
        except (SavedProfile.DoesNotExist, ValueError):
            return {"status": "NOT_FOUND", "is_leased": False}

        if profile.user and profile.user != user:
            return {"status": "UNAUTHORIZED", "is_leased": False}

        now = timezone.now()
        active_lease = ExecutionLease.objects.filter(
            profile=profile,
            status=ExecutionLeaseStatus.ACTIVE,
            expires_at__gt=now
        ).first()

        if active_lease:
            return {
                "is_leased": True,
                "lease_id": str(active_lease.id),
                "device_id": active_lease.device_id,
                "status": active_lease.status,
                "expires_at": active_lease.expires_at.isoformat(),
                "remaining_seconds": max(0, int((active_lease.expires_at - now).total_seconds()))
            }
        return {"is_leased": False, "status": "AVAILABLE"}
