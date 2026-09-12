import uuid
from django.db import models
from django.utils import timezone
from devices.models import SavedProfile


class ExecutionStatus(models.TextChoices):
    PENDING = "PENDING", "Pending Dispatch"
    DISPATCHED = "DISPATCHED", "Dispatched to Device"
    RUNNING = "RUNNING", "Running"
    SUCCESS = "SUCCESS", "Success"
    FAILED = "FAILED", "Failed"
    STALLED = "STALLED", "Stalled"


class Execution(models.Model):
    """
    Dedicated runtime execution instance ("What is happening now?").
    Tracks active DAG state machine progress, context variables, and lifecycle.
    """
    ExecutionStatus = ExecutionStatus

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    task = models.ForeignKey(
        "automation.AutomationTask",
        on_delete=models.CASCADE,
        related_name="executions"
    )
    profile = models.ForeignKey(
        SavedProfile,
        on_delete=models.CASCADE,
        related_name="executions"
    )
    status = models.CharField(
        max_length=20,
        choices=ExecutionStatus.choices,
        default=ExecutionStatus.PENDING,
        db_index=True
    )
    entry_state_id = models.CharField(max_length=100, default="start")
    current_state_id = models.CharField(max_length=100, default="start")
    compiled_dag = models.JSONField(default=dict)
    execution_context = models.JSONField(default=dict)
    logs = models.JSONField(default=list)
    error_message = models.TextField(blank=True, default="")
    started_at = models.DateTimeField(null=True, blank=True)
    completed_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]
        indexes = [
            models.Index(fields=["profile", "status"]),
            models.Index(fields=["task", "status"]),
        ]

    def __str__(self):
        return f"Execution {self.id} [{self.profile.name}] - {self.status}"

    @property
    def current_state(self) -> str:
        return self.current_state_id

    @current_state.setter
    def current_state(self, value: str):
        self.current_state_id = value

    @property
    def context(self) -> dict:
        return self.execution_context

    @context.setter
    def context(self, value: dict):
        self.execution_context = value

    @property
    def finished_at(self):
        return self.completed_at

    @finished_at.setter
    def finished_at(self, value):
        self.completed_at = value


class ExecutionLeaseStatus(models.TextChoices):
    ACTIVE = "ACTIVE", "Active"
    EXPIRED = "EXPIRED", "Expired"
    RELEASED = "RELEASED", "Released"


class ExecutionLease(models.Model):
    """
    Guarantees single-device execution exclusivity per browser profile and execution job.
    Prevents duplicate browser sessions and race conditions across distributed devices.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    profile = models.ForeignKey(
        SavedProfile,
        on_delete=models.CASCADE,
        related_name="leases"
    )
    execution = models.OneToOneField(
        Execution,
        null=True,
        blank=True,
        on_delete=models.CASCADE,
        related_name="lease"
    )
    registered_device = models.ForeignKey(
        "devices.Device",
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name="leases"
    )
    device_id = models.CharField(max_length=255, db_index=True)
    status = models.CharField(
        max_length=20,
        choices=ExecutionLeaseStatus.choices,
        default=ExecutionLeaseStatus.ACTIVE,
        db_index=True
    )
    acquired_at = models.DateTimeField(default=timezone.now)
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

    @property
    def device(self):
        return self.registered_device

    @device.setter
    def device(self, value):
        self.registered_device = value
        if value and hasattr(value, "device_id"):
            self.device_id = value.device_id

    def is_active_and_valid(self) -> bool:
        return self.status == ExecutionLeaseStatus.ACTIVE and timezone.now() < self.expires_at


class ExecutionEventType(models.TextChoices):
    RUN_STARTED = "RUN_STARTED", "Run Started"
    STATE_CHANGED = "STATE_CHANGED", "State Changed"
    HEARTBEAT = "HEARTBEAT", "Heartbeat"
    STALLED = "STALLED", "Execution Stalled"
    SUCCESS = "SUCCESS", "Execution Succeeded"
    FAILED = "FAILED", "Execution Failed"
    ABORTED = "ABORTED", "Execution Aborted"
    AI_DECISION = "AI_DECISION", "AI Decision"
    AI_RECOVERY = "AI_RECOVERY", "AI Recovery Executed"
    COOKIE_IMPORTED = "COOKIE_IMPORTED", "Cookie Imported"
    BUTTON_CLICKED = "BUTTON_CLICKED", "Button Clicked"


class ExecutionEvent(models.Model):
    """
    Structured time-series event log emitted during automation runs.
    Provides discrete, queryable audit trail for dashboards and telemetry.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    execution = models.ForeignKey(
        Execution,
        null=True,
        blank=True,
        on_delete=models.CASCADE,
        related_name="events"
    )
    event_type = models.CharField(
        max_length=50,
        choices=ExecutionEventType.choices,
        db_index=True
    )
    payload = models.JSONField(default=dict)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ["created_at"]
        indexes = [
            models.Index(fields=["execution", "created_at"]),
            models.Index(fields=["event_type", "created_at"]),
        ]

    def __str__(self):
        return f"Event [{self.event_type}] for execution {self.execution_id} at {self.created_at}"
