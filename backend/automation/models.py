import uuid
from django.conf import settings
from django.db import models
from django.utils import timezone
from django.core.validators import MinValueValidator, MaxValueValidator
from devices.models import SavedProfile

class PlatformCategory(models.TextChoices):
    WARMING = "WARMING", "Cookie Warmer (General Web)"
    YOUTUBE = "YOUTUBE", "YouTube Operations"
    WEBSITE = "WEBSITE", "Website / Direct Traffic"
    FACEBOOK = "FACEBOOK", "Facebook Operations"
    TWITTER = "TWITTER", "X / Twitter"
    TIKTOK = "TIKTOK", "TikTok Operations"
    REDDIT = "REDDIT", "Reddit Operations"
    BLOG = "BLOG", "Blog / Content Reading"
    CUSTOM = "CUSTOM", "Custom Automation"

class Niche(models.Model):
    """Stores the vocabulary, websites, and target channels for a persona domain."""
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=100, unique=True)
    description = models.TextField(blank=True, default="")
    seed_keywords = models.JSONField(
        default=list,
        help_text='List of search phrases, e.g. ["budget mechanical keyboards", "OLED monitor test"]'
    )
    seed_websites = models.JSONField(
        default=list,
        help_text='List of authority domains, e.g. ["rtings.com", "theverge.com"]'
    )
    target_youtube_channels = models.JSONField(
        default=list,
        help_text='Channels to engage with, e.g. ["@MKBHD", "@Dave2D"]'
    )
    blacklist_keywords = models.JSONField(
        default=list,
        help_text='Keywords to avoid, e.g. ["controversial", "nsfw"]'
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return self.name

class ProfilePersona(models.Model):
    """Behavioral quirks and trust metrics assigned to each browser profile."""
    class MaturationStage(models.TextChoices):
        INFANT = "INFANT", "Infant (Fresh Profile, 0-25)"
        SEEDING = "SEEDING", "Seeding (Basic Tracker Cookies, 26-50)"
        MATURING = "MATURING", "Maturing (Niche Browsing History, 51-75)"
        MATURE = "MATURE", "Mature / Battle-Tested (76-100)"

    profile = models.OneToOneField(
        SavedProfile, 
        on_delete=models.CASCADE, 
        related_name="persona"
    )
    trust_score = models.IntegerField(
        default=10, 
        validators=[MinValueValidator(0), MaxValueValidator(100)]
    )
    maturation_stage = models.CharField(
        max_length=20, 
        choices=MaturationStage.choices, 
        default=MaturationStage.INFANT
    )
    unique_domains_visited = models.IntegerField(default=0)
    patience_index = models.FloatField(
        default=0.6, 
        validators=[MinValueValidator(0.1), MaxValueValidator(1.0)]
    )
    engagement_rate = models.FloatField(
        default=0.15, 
        validators=[MinValueValidator(0.0), MaxValueValidator(0.5)]
    )
    typing_wpm = models.IntegerField(
        default=65, 
        validators=[MinValueValidator(30), MaxValueValidator(120)]
    )
    typo_probability = models.FloatField(
        default=0.03, 
        validators=[MinValueValidator(0.0), MaxValueValidator(0.15)]
    )
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"Persona: {self.profile.name} (Trust: {self.trust_score})"

class ProfileNicheAffiliation(models.Model):
    """Enables multi-niche association with percentage weighting."""
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    profile = models.ForeignKey(
        SavedProfile, 
        on_delete=models.CASCADE, 
        related_name="niche_affiliations"
    )
    niche = models.ForeignKey(
        Niche, 
        on_delete=models.CASCADE, 
        related_name="profile_affiliations"
    )
    weight_percentage = models.PositiveIntegerField(
        default=100, 
        validators=[MinValueValidator(1), MaxValueValidator(100)]
    )

    class Meta:
        unique_together = ("profile", "niche")

    def __str__(self):
        return f"{self.profile.name} -> {self.niche.name} ({self.weight_percentage}%)"

class AutomationTask(models.Model):
    """High-level task template configured via React Admin."""
    PlatformCategory = PlatformCategory

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    owner = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        null=True,
        blank=True,
        related_name="automation_tasks"
    )
    name = models.CharField(max_length=150)
    category = models.CharField(
        max_length=20, 
        choices=PlatformCategory.choices, 
        default=PlatformCategory.YOUTUBE
    )
    workflow_type = models.CharField(max_length=50, default="SEARCH_AND_DISCOVER")
    niche = models.ForeignKey(
        Niche, 
        on_delete=models.SET_NULL, 
        null=True, 
        blank=True
    )
    config = models.JSONField(default=dict)
    created_at = models.DateTimeField(auto_now_add=True)

    def __str__(self):
        return f"[{self.category}] {self.name}"


class ScheduleType(models.TextChoices):
    ONE_TIME = "ONE_TIME", "One Time"
    DAILY = "DAILY", "Daily"
    INTERVAL = "INTERVAL", "Interval"


class SelectionMode(models.TextChoices):
    EXPLICIT_PROFILES = "EXPLICIT_PROFILES", "Explicit Profiles"
    NICHE = "NICHE", "Niche"
    ALL_ELIGIBLE = "ALL_ELIGIBLE", "All Eligible"


class Automation(models.Model):
    """
    First-class automation rule defining schedule, targeting, policies, and safeguards.
    Controls unattended batch execution across profiles.
    """
    ScheduleType = ScheduleType
    SelectionMode = SelectionMode

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=200)
    description = models.TextField(blank=True, default="")
    owner = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="automations",
        null=True,
        blank=True
    )
    task = models.ForeignKey(
        AutomationTask,
        on_delete=models.CASCADE,
        related_name="automations"
    )
    enabled = models.BooleanField(default=True, db_index=True)
    schedule_type = models.CharField(
        max_length=20,
        choices=ScheduleType.choices,
        default=ScheduleType.DAILY
    )
    schedule_config = models.JSONField(default=dict, blank=True)
    timezone = models.CharField(max_length=50, default="UTC")
    selection_mode = models.CharField(
        max_length=30,
        choices=SelectionMode.choices,
        default=SelectionMode.NICHE
    )
    target_niches = models.ManyToManyField(
        Niche,
        blank=True,
        related_name="automations"
    )
    target_profiles = models.ManyToManyField(
        SavedProfile,
        blank=True,
        related_name="automations"
    )
    concurrency_limit = models.PositiveIntegerField(default=5)
    cooldown_minutes = models.PositiveIntegerField(default=30)
    max_runtime_seconds = models.PositiveIntegerField(default=1800)
    max_retries = models.PositiveIntegerField(default=2)
    failure_threshold_percent = models.PositiveIntegerField(default=30)
    priority = models.IntegerField(default=10)
    active_from = models.DateTimeField(null=True, blank=True)
    active_until = models.DateTimeField(null=True, blank=True)
    last_run_at = models.DateTimeField(null=True, blank=True)
    next_run_at = models.DateTimeField(null=True, blank=True, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.name} [{self.schedule_type}]"


class AutomationRunStatus(models.TextChoices):
    SCHEDULED = "SCHEDULED", "Scheduled"
    RUNNING = "RUNNING", "Running"
    COMPLETED = "COMPLETED", "Completed"
    PARTIAL = "PARTIAL", "Partial"
    FAILED = "FAILED", "Failed"
    CANCELLED = "CANCELLED", "Cancelled"


class AutomationRun(models.Model):
    """
    Tracks an authoritative campaign execution cycle/batch of an Automation.
    Guarantees run idempotency across restarts with UNIQUE(automation, run_key).
    """
    Status = AutomationRunStatus

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    automation = models.ForeignKey(
        Automation,
        on_delete=models.CASCADE,
        related_name="runs"
    )
    run_key = models.CharField(max_length=255, db_index=True)
    scheduled_for = models.DateTimeField(default=timezone.now, db_index=True)
    status = models.CharField(
        max_length=20,
        choices=AutomationRunStatus.choices,
        default=AutomationRunStatus.SCHEDULED,
        db_index=True
    )
    total_target_profiles = models.PositiveIntegerField(default=0)
    queued_count = models.PositiveIntegerField(default=0)
    dispatched_count = models.PositiveIntegerField(default=0)
    running_count = models.PositiveIntegerField(default=0)
    success_count = models.PositiveIntegerField(default=0)
    failure_count = models.PositiveIntegerField(default=0)
    stalled_count = models.PositiveIntegerField(default=0)
    cancelled_count = models.PositiveIntegerField(default=0)
    skipped_count = models.PositiveIntegerField(default=0)
    started_at = models.DateTimeField(null=True, blank=True)
    completed_at = models.DateTimeField(null=True, blank=True)
    summary_metrics = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]
        unique_together = ("automation", "run_key")

    def __str__(self):
        return f"Run {self.run_key} [{self.automation.name}] - {self.status}"


from executions.models import Execution, ExecutionStatus


class TaskExecutionQueueQuerySet(models.QuerySet):
    def filter(self, *args, **kwargs):
        new_kwargs = {}
        for k, v in kwargs.items():
            if k == "status":
                new_kwargs["execution__status"] = v
            elif k.startswith("status__"):
                new_kwargs["execution__" + k] = v
            else:
                new_kwargs[k] = v
        return super().filter(*args, **new_kwargs)


class TaskExecutionQueue(models.Model):
    """
    Dedicated dispatch queue connecting tasks, profiles, and their canonical Execution (executions.Execution).
    
    Architecture Note:
    - This model functions as an operational dispatch queue and backward-compatibility adapter for legacy
      code, serializers, and routes that interact with TaskExecutionQueue.
    - The canonical entity for execution state, leasing, time-series events, and audit trails is
      `executions.models.Execution`.
    - Mutating properties and status checks delegate directly to the underlying `Execution` record,
      while `executions.models.ExecutionEvent` serves as the single source of truth for runtime events
      and SSE telemetry.
    """
    ExecutionStatus = ExecutionStatus

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    task = models.ForeignKey(
        AutomationTask, 
        on_delete=models.CASCADE, 
        related_name="queued_dispatches"
    )
    profile = models.ForeignKey(
        SavedProfile, 
        on_delete=models.CASCADE, 
        related_name="queued_dispatches"
    )
    execution = models.OneToOneField(
        "executions.Execution", 
        on_delete=models.CASCADE, 
        related_name="dispatch_entry", 
        null=True, 
        blank=True
    )
    created_at = models.DateTimeField(default=timezone.now)

    objects = TaskExecutionQueueQuerySet.as_manager()

    class Meta:
        ordering = ["-created_at"]
        indexes = [
            models.Index(fields=["profile", "created_at"]),
            models.Index(fields=["task", "created_at"]),
        ]

    def __init__(self, *args, **kwargs):
        legacy_keys = [
            "status", "entry_state_id", "current_state_id", "compiled_dag",
            "execution_context", "logs", "error_message", "started_at", "completed_at"
        ]
        self._legacy_attrs = {}
        for k in legacy_keys:
            if k in kwargs:
                self._legacy_attrs[k] = kwargs.pop(k)

        super().__init__(*args, **kwargs)

        for k, v in self._legacy_attrs.items():
            setattr(self, f"_{k}", v)

    def __str__(self):
        status_val = self.status if hasattr(self, "status") else "UNKNOWN"
        return f"Queue {self.id} [{self.profile.name}] - {status_val}"

    def save(self, *args, **kwargs):
        from executions.models import Execution
        if not self.execution_id:
            exec_obj = Execution.objects.create(
                id=self.id,
                task=self.task,
                profile=self.profile,
                status=getattr(self, "_status", Execution.ExecutionStatus.PENDING),
                entry_state_id=getattr(self, "_entry_state_id", "start"),
                current_state_id=getattr(self, "_current_state_id", "start"),
                compiled_dag=getattr(self, "_compiled_dag", {}),
                execution_context=getattr(self, "_execution_context", {}),
                logs=getattr(self, "_logs", []),
                error_message=getattr(self, "_error_message", ""),
                started_at=getattr(self, "_started_at", None),
                completed_at=getattr(self, "_completed_at", None),
            )
            self.execution = exec_obj
        else:
            if self.execution:
                if hasattr(self, "_status"):
                    self.execution.status = self._status
                if hasattr(self, "_entry_state_id"):
                    self.execution.entry_state_id = self._entry_state_id
                if hasattr(self, "_current_state_id"):
                    self.execution.current_state_id = self._current_state_id
                if hasattr(self, "_compiled_dag"):
                    self.execution.compiled_dag = self._compiled_dag
                if hasattr(self, "_execution_context"):
                    self.execution.execution_context = self._execution_context
                if hasattr(self, "_logs"):
                    self.execution.logs = self._logs
                if hasattr(self, "_error_message"):
                    self.execution.error_message = self._error_message
                if hasattr(self, "_started_at"):
                    self.execution.started_at = self._started_at
                if hasattr(self, "_completed_at"):
                    self.execution.completed_at = self._completed_at
                if hasattr(self, "_cancel_requested"):
                    self.execution.cancel_requested = self._cancel_requested
                self.execution.save()
        super().save(*args, **kwargs)

    def refresh_from_db(self, *args, **kwargs):
        super().refresh_from_db(*args, **kwargs)
        if self.execution:
            self.execution.refresh_from_db(*args, **kwargs)
            self._status = self.execution.status
            self._current_state_id = self.execution.current_state_id
            self._error_message = self.execution.error_message
            self._execution_context = self.execution.execution_context
            self._logs = self.execution.logs
            self._completed_at = self.execution.completed_at
            self._started_at = self.execution.started_at
            self._cancel_requested = self.execution.cancel_requested

    @property
    def cancel_requested(self):
        if self.execution:
            return self.execution.cancel_requested
        return getattr(self, "_cancel_requested", False)

    @cancel_requested.setter
    def cancel_requested(self, val):
        self._cancel_requested = val
        if self.execution:
            self.execution.cancel_requested = val

    @property
    def status(self):
        if self.execution:
            return self.execution.status
        return getattr(self, "_status", ExecutionStatus.PENDING)

    @status.setter
    def status(self, val):
        self._status = val
        if self.execution:
            self.execution.status = val

    @property
    def entry_state_id(self):
        if self.execution:
            return self.execution.entry_state_id
        return getattr(self, "_entry_state_id", "start")

    @entry_state_id.setter
    def entry_state_id(self, val):
        self._entry_state_id = val
        if self.execution:
            self.execution.entry_state_id = val

    @property
    def current_state_id(self):
        if self.execution:
            return self.execution.current_state_id
        return getattr(self, "_current_state_id", "start")

    @current_state_id.setter
    def current_state_id(self, val):
        self._current_state_id = val
        if self.execution:
            self.execution.current_state_id = val

    @property
    def current_state(self):
        return self.current_state_id

    @current_state.setter
    def current_state(self, val):
        self.current_state_id = val

    @property
    def compiled_dag(self):
        if self.execution:
            return self.execution.compiled_dag
        return getattr(self, "_compiled_dag", {})

    @compiled_dag.setter
    def compiled_dag(self, val):
        self._compiled_dag = val
        if self.execution:
            self.execution.compiled_dag = val

    @property
    def execution_context(self):
        if self.execution:
            return self.execution.execution_context
        return getattr(self, "_execution_context", {})

    @execution_context.setter
    def execution_context(self, val):
        self._execution_context = val
        if self.execution:
            self.execution.execution_context = val

    @property
    def context(self):
        return self.execution_context

    @context.setter
    def context(self, val):
        self.execution_context = val

    @property
    def logs(self):
        if self.execution:
            return self.execution.logs
        return getattr(self, "_logs", [])

    @logs.setter
    def logs(self, val):
        self._logs = val
        if self.execution:
            self.execution.logs = val

    @property
    def error_message(self):
        if self.execution:
            return self.execution.error_message
        return getattr(self, "_error_message", "")

    @error_message.setter
    def error_message(self, val):
        self._error_message = val
        if self.execution:
            self.execution.error_message = val

    @property
    def started_at(self):
        if self.execution:
            return self.execution.started_at
        return getattr(self, "_started_at", None)

    @started_at.setter
    def started_at(self, val):
        self._started_at = val
        if self.execution:
            self.execution.started_at = val

    @property
    def completed_at(self):
        if self.execution:
            return self.execution.completed_at
        return getattr(self, "_completed_at", None)

    @completed_at.setter
    def completed_at(self, val):
        self._completed_at = val
        if self.execution:
            self.execution.completed_at = val

    @property
    def finished_at(self):
        return self.completed_at

    @finished_at.setter
    def finished_at(self, val):
        self.completed_at = val


# Domain Model Canonical Export
Execution = Execution


class AIPromptConfig(models.Model):
    """Stores operator-controlled system prompts and model parameters."""
    class ProviderChoices(models.TextChoices):
        OPENROUTER = "OPENROUTER", "OpenRouter.ai"
        GEMINI = "GEMINI", "Google Gemini"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=100, default="Default Recovery Decision Engine")
    provider = models.CharField(
        max_length=30,
        choices=ProviderChoices.choices,
        default=ProviderChoices.OPENROUTER
    )
    model_name = models.CharField(
        max_length=150,
        default="deepseek/deepseek-chat",
        help_text="Model identifier, e.g. gemini-2.5-flash, deepseek/deepseek-chat, anthropic/claude-3.5-haiku"
    )
    temperature = models.FloatField(
        default=0.1,
        validators=[MinValueValidator(0.0), MaxValueValidator(1.0)]
    )
    system_prompt = models.TextField(
        default=(
            "You are GhostPilot, an autonomic browser recovery controller.\n"
            "Context:\n"
            "- Task Name: {task_name} ({task_category})\n"
            "- Stalled State ID: {current_state}\n"
            "- Execution Context: {execution_context}\n\n"
            "The mobile browser profile is currently stuck or received an unexpected page state.\n"
            "Analyze the provided stripped DOM representation and optional screenshot to deduce a concrete physical recovery action.\n\n"
            "Rules:\n"
            "1. If an unexpected popup, cookie banner, or consent wall is present, target its dismiss/accept button via TAP_COORDINATES using center X/Y bounds.\n"
            "2. If a video is paused or has an active ad skip button, target playback or skip controls.\n"
            "3. If the page is blank or scrolled to bottom, return BÉZIER_SWIPE with direction DOWN.\n"
            "4. If you can deduce which DAG node to resume, set next_state_override.\n"
            "5. Provide concise reasoning."
        )
    )
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        verbose_name = "AI Prompt Configuration"
        verbose_name_plural = "AI Prompt Configurations"

    def __str__(self):
        return f"{self.name} [{self.provider}: {self.model_name}]"

    @classmethod
    def get_active_config(cls):
        """Fetches the active configuration, auto-creating a default if none exists."""
        config = cls.objects.filter(is_active=True).first()
        if not config:
            config = cls.objects.create(
                name="Default Recovery Engine",
                provider=cls.ProviderChoices.OPENROUTER,
                model_name="deepseek/deepseek-chat",
                temperature=0.1,
                is_active=True
            )
        return config


class AssistantSession(models.Model):
    """Stores persistent conversational sessions with TersoAssistant."""
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    title = models.CharField(max_length=150, default="New Conversation")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"{self.title} ({self.id})"


class AssistantMessage(models.Model):
    """Individual messages, tool calls, and tool responses within a session."""
    class RoleChoices(models.TextChoices):
        USER = "user", "User"
        ASSISTANT = "assistant", "Assistant"
        TOOL = "tool", "Tool Output"
        SYSTEM = "system", "System"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    session = models.ForeignKey(
        AssistantSession,
        on_delete=models.CASCADE,
        related_name="messages"
    )
    role = models.CharField(max_length=20, choices=RoleChoices.choices)
    content = models.TextField(blank=True, default="")
    tool_calls = models.JSONField(
        null=True, blank=True,
        help_text="Stored tool invocations requested by the LLM"
    )
    tool_call_id = models.CharField(
        max_length=100, blank=True, default="",
        help_text="For OpenRouter tool responses, maps to the original tool_call.id"
    )
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["created_at"]

    def __str__(self):
        return f"[{self.role}] {self.content[:60]}..."


class PlatformCalibration(models.Model):
    """
    Stores visual coordinate calibrations and natural scroll configuration
    for specific platforms (e.g. YOUTUBE) and device viewport scales.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    platform = models.CharField(max_length=50, default="YOUTUBE", db_index=True)
    device_model = models.CharField(max_length=100, default="default", blank=True)
    viewport_width = models.IntegerField(default=1080)
    viewport_height = models.IntegerField(default=2400)
    anchors = models.JSONField(default=dict, blank=True)
    settings = models.JSONField(default=dict, blank=True)
    screenshot_base64 = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-updated_at"]

    def __str__(self):
        return f"Calibration [{self.platform}] - {len(self.anchors)} anchors ({self.viewport_width}x{self.viewport_height})"


class CustomWorkflow(models.Model):
    """
    User-defined interactive visual workflow containing ordered journeys,
    deterministic step actions (Go to URL, Click Link, Click Element, Type Text,
    Type & Enter, Click Ad/iFrame, AI Type), and calibrated dwell times.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=255, default="Untitled Workflow")
    platform = models.CharField(max_length=50, default="YOUTUBE", db_index=True)
    description = models.TextField(blank=True, default="")
    journeys = models.JSONField(default=list, blank=True, help_text="List of journeys and their sequential action steps")
    settings = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-updated_at"]

    def __str__(self):
        return f"{self.name} ({len(self.journeys)} journeys)"

    def compile_dag(self, profile=None, overrides=None) -> dict:
        """
        Compiles the visual workflow into an executable DAG dictionary
        that GhostPilot on the mobile device will execute in strict sequence.
        Supports overrides passed during campaign launch (e.g. video URL, keywords, watch duration).
        """
        import re
        overrides = overrides or {}
        states = {}
        first_state = None
        prev_state = None
        step_idx = 0

        override_video_url = overrides.get("video_url")
        override_video_id = None
        if override_video_url:
            m = re.search(r"(?:v=|/v/|embed/|shorts/|youtu\.be/|/e/|watch\?v=|&v=)([a-zA-Z0-9_-]{11})", override_video_url)
            if m:
                override_video_id = m.group(1)

        override_keywords = overrides.get("keywords") or overrides.get("keyword")
        override_watch_min = overrides.get("min_watch_seconds") or overrides.get("watch_min")
        override_watch_max = overrides.get("max_watch_seconds") or overrides.get("watch_max")

        # Load profile behavioral persona settings
        persona = getattr(profile, "persona", None)
        if persona is None and profile:
            try:
                persona = ProfilePersona.objects.filter(profile=profile).first()
            except Exception:
                persona = None

        persona_wpm = getattr(persona, "typing_wpm", 65) if persona else 65
        persona_typo = getattr(persona, "typo_probability", 0.03) if persona else 0.03
        persona_patience = getattr(persona, "patience_index", 0.6) if persona else 0.6

        inherited_video_url = None
        inherited_video_id = None
        inherited_keyword = None

        # Retrieve all calibrated spatial anchors (including active addon anchors)
        calibrated_anchors = {}
        try:
            from automation.services.calibration_service import InterfaceCalibrationService
            calibrated_anchors = InterfaceCalibrationService.get_all_anchors_with_addons(self.platform or "YOUTUBE")
        except Exception:
            pass

        # Iterate over all journeys and their steps
        for j_idx, journey in enumerate(self.journeys or []):
            j_name = journey.get("name") or f"journey_{j_idx+1}"
            referrer = overrides.get("referrer") or journey.get("referrer") or ""
            steps = journey.get("steps") or []

            for s_idx, step in enumerate(steps):
                step_idx += 1
                state_id = f"step_{step_idx}_{step.get('type', 'action').lower()}"
                if first_state is None:
                    first_state = state_id

                if prev_state and prev_state in states:
                    states[prev_state]["transitions"]["SUCCESS"] = state_id

                step_type = (step.get("type") or "START").upper()

                # Build clean params by stripping workflow-builder metadata.
                # Canvas node coordinates (x, y), node IDs, and display labels must
                # never reach the Android runner as action parameters.
                METADATA_KEYS = {"type", "id", "x", "y", "position", "label", "title",
                                  "description", "step_index", "canvas_x", "canvas_y"}
                params = {k: v for k, v in step.items() if k not in METADATA_KEYS}

                if referrer:
                    params["referrer"] = referrer

                # Check for runtime overrides
                if override_video_url and ("video_url" in params or "target_url" in params or step_type in ["SEARCH_TARGET_VIDEO", "VIDEO_SEARCH", "YT_ORGANIC_TARGET_SEARCH", "SCROLL_TARGET_VIDEO", "SCROLL_TO_TARGET", "WAIT_PLAYBACK", "WATCH_VIDEO"]):
                    params["target_video_url"] = override_video_url
                    params["video_url"] = override_video_url
                    if override_video_id:
                        params["target_video_id"] = override_video_id
                        params["video_id"] = override_video_id

                if override_keywords and ("keywords" in params or "keyword" in params or "text" in params or step_type in ["SEARCH_TARGET_VIDEO", "VIDEO_SEARCH", "YT_ORGANIC_TARGET_SEARCH", "TYPE_TEXT", "TYPE_AND_ENTER"]):
                    if isinstance(override_keywords, list):
                        params["candidate_keywords"] = override_keywords
                        params["target_keyword"] = override_keywords[0] if override_keywords else ""
                    else:
                        kws = [k.strip() for k in str(override_keywords).split(",") if k.strip()]
                        params["candidate_keywords"] = kws
                        params["target_keyword"] = kws[0] if kws else str(override_keywords)

                if override_watch_min is not None:
                    params["dwell_min"] = int(override_watch_min)
                    params["min_watch_seconds"] = int(override_watch_min)
                if override_watch_max is not None:
                    params["dwell_max"] = int(override_watch_max)
                    params["max_watch_seconds"] = int(override_watch_max)
                    params["duration_seconds"] = int(override_watch_max)

                # Extract video ID if video_url is present
                v_url = params.get("video_url") or params.get("target_video_url")
                if v_url and not params.get("target_video_id"):
                    m = re.search(r"(?:v=|/v/|embed/|shorts/|youtu\.be/|/e/|watch\?v=|&v=)([a-zA-Z0-9_-]{11})", v_url)
                    if m:
                        params["target_video_id"] = m.group(1)
                        params["video_id"] = m.group(1)

                if "video_url" in params and "target_video_url" not in params:
                    params["target_video_url"] = params["video_url"]
                if "keyword" in params and "target_keyword" not in params:
                    params["target_keyword"] = params["keyword"]

                # Inject behavioral persona parameters from profile
                params.setdefault("wpm", persona_wpm)
                params.setdefault("typo_probability", persona_typo)
                params.setdefault("patience_index", persona_patience)

                # 1. Check active workflow addons for dynamic step definitions
                addon_step_def = None
                try:
                    for ad in WorkflowAddon.objects.filter(is_active=True):
                        for s_def in (ad.manifest.get("steps") or []):
                            if s_def.get("type") == step_type:
                                addon_step_def = s_def
                                break
                        if addon_step_def:
                            break
                except Exception:
                    pass

                # Map visual workflow step to exact deterministic command
                command = "NAVIGATE"
                if addon_step_def:
                    compiler_spec = addon_step_def.get("compiler") or {}
                    command = compiler_spec.get("command") or step_type
                    for c_k, c_v in compiler_spec.items():
                        if c_k == "command":
                            continue
                        if isinstance(c_v, str) and c_v.startswith("{{") and c_v.endswith("}}"):
                            var_name = c_v[2:-2].strip()
                            if var_name in params:
                                params[c_k] = params[var_name]
                        elif c_k not in params:
                            params[c_k] = c_v
                elif step_type in ["START", "GO_TO_URL", "NAVIGATE"]:
                    command = "NAVIGATE"
                    target_nav_url = params.get("url") or params.get("starting_url") or params.get("target_url") or "https://m.youtube.com"
                    if "youtube.com" in target_nav_url and "m.youtube.com" not in target_nav_url:
                        target_nav_url = target_nav_url.replace("https://www.youtube.com", "https://m.youtube.com").replace("https://youtube.com", "https://m.youtube.com")
                    params["url"] = target_nav_url
                elif step_type in ["SEARCH_TARGET_VIDEO", "VIDEO_SEARCH", "YT_ORGANIC_TARGET_SEARCH"]:
                    command = "YT_ORGANIC_TARGET_SEARCH"
                    if params.get("step_name") == "Search & Click Target Video":
                        params["step_name"] = "Search Target Video"
                    params["click_video"] = True
                    kw = params.get("keyword") or params.get("keywords") or params.get("query")
                    fb1 = params.get("fallback_keyword_1")
                    fb2 = params.get("fallback_keyword_2")
                    c_kws = []
                    for k in [kw, fb1, fb2]:
                        if k and isinstance(k, str) and k.strip():
                            clean_k = k.strip()
                            if clean_k not in c_kws:
                                c_kws.append(clean_k)
                    if c_kws:
                        params["target_keyword"] = c_kws[0]
                        params["candidate_keywords"] = c_kws
                    elif kw:
                        params["target_keyword"] = kw
                        if "candidate_keywords" not in params:
                            params["candidate_keywords"] = [kw] if isinstance(kw, str) else kw

                    # Retain search retry mode provision: CLEAR_SEARCH_BAR vs RETURN_TO_HOME
                    params["search_retry_mode"] = params.get("search_retry_mode", "CLEAR_SEARCH_BAR")

                    # YT_ORGANIC_TARGET_SEARCH is a multi-step routine; clear static single-click spatial anchors to prevent false taps
                    params.pop("spatial_anchor", None)
                    params.pop("search_anchor", None)
                    params.pop("normalized_x", None)
                    params.pop("normalized_y", None)
                    inherited_video_url = params.get("video_url") or params.get("target_video_url")
                    inherited_video_id = params.get("target_video_id") or params.get("video_id")
                    inherited_keyword = params.get("target_keyword") or params.get("keyword")
                elif step_type in ["SCROLL_TARGET_VIDEO", "SCROLL_TO_TARGET", "SCROLL_TO_TARGET_VIDEO", "YT_SCROLL_TO_TARGET"]:
                    command = "YT_SCROLL_TO_TARGET"
                    if inherited_video_url:
                        params.setdefault("video_url", inherited_video_url)
                        params.setdefault("target_video_url", inherited_video_url)
                    if inherited_video_id:
                        params.setdefault("target_video_id", inherited_video_id)
                        params.setdefault("video_id", inherited_video_id)
                    if inherited_keyword:
                        params.setdefault("target_keyword", inherited_keyword)
                    params.setdefault("scroll_past_and_return", True)
                    params.setdefault("max_scroll_batches", 10)
                    params.pop("spatial_anchor", None)
                    params.pop("search_anchor", None)
                    params.pop("normalized_x", None)
                    params.pop("normalized_y", None)
                elif step_type in ["YT_TAP_SEARCH_BAR", "TAP_SEARCH_BAR", "TAP_SEARCH", "CLICK_SEARCH_ICON"]:
                    command = "YT_TAP_SEARCH_BAR"
                    params.setdefault("spatial_anchor", "SEARCH_BUTTON_HOME")
                elif step_type in ["YT_SUBMIT_SEARCH", "SUBMIT_SEARCH"]:
                    command = "YT_SUBMIT_SEARCH"
                    params.setdefault("spatial_anchor", "SEARCH_SUBMIT")
                elif step_type in ["LIKE_VIDEO", "YT_LIKE_VIDEO", "LIKE"]:
                    command = "SPATIAL_ANCHOR_CLICK"
                    params.setdefault("spatial_anchor", "LIKE_BUTTON")
                elif step_type in ["DISLIKE_VIDEO", "YT_DISLIKE_VIDEO"]:
                    command = "SPATIAL_ANCHOR_CLICK"
                    params.setdefault("spatial_anchor", "DISLIKE_BUTTON")
                elif step_type in ["SHARE_VIDEO", "YT_SHARE_VIDEO"]:
                    command = "SPATIAL_ANCHOR_CLICK"
                    params.setdefault("spatial_anchor", "SHARE_BUTTON")
                elif step_type in ["SUBSCRIBE", "SUBSCRIBE_CHANNEL", "YT_SUBSCRIBE_CHANNEL"]:
                    command = "SPATIAL_ANCHOR_CLICK"
                    params.setdefault("spatial_anchor", "SUBSCRIBE_BUTTON")
                elif step_type in ["POST_COMMENT", "COMMENT", "YT_POST_COMMENT", "ADD_COMMENT"]:
                    command = "YT_POST_COMMENT"
                    params["comment_text"] = params.get("comment_text") or params.get("text") or params.get("comment") or params.get("prompt") or ""
                elif step_type in ["SCROLL_TO_COMMENTS", "YT_SCROLL_TO_COMMENTS"]:
                    command = "SPATIAL_ANCHOR_CLICK"
                    params.setdefault("spatial_anchor", "COMMENTS_SECTION")
                elif step_type in ["DWELL_ON_COMMENTS", "YT_DWELL_ON_COMMENTS"]:
                    command = "YT_DWELL_ON_COMMENTS"
                elif step_type in ["EXPAND_DESCRIPTION", "YT_EXPAND_DESCRIPTION"]:
                    command = "SPATIAL_ANCHOR_CLICK"
                    params.setdefault("spatial_anchor", "DESCRIPTION_EXPAND")
                elif step_type in ["WAIT_PLAYBACK", "WATCH_VIDEO", "WATCH"]:
                    command = "WAIT_PLAYBACK"
                    dur = params.get("duration_seconds") or params.get("dwell_max") or params.get("dwell_min") or 90
                    params["duration_seconds"] = int(dur)
                    if not params.get("video_url") and inherited_video_url:
                        params["video_url"] = inherited_video_url
                        params["target_video_url"] = inherited_video_url
                    if not params.get("target_video_id") and inherited_video_id:
                        params["target_video_id"] = inherited_video_id
                        params["video_id"] = inherited_video_id
                    if not params.get("target_keyword") and inherited_keyword:
                        params["target_keyword"] = inherited_keyword
                    params["auto_click_target"] = True
                elif step_type in ["DWELL", "WAIT", "PAGE_DWELL"]:
                    command = "PAGE_DWELL"
                elif step_type in ["SHORTS_SWIPE", "YT_SHORTS_SWIPE"]:
                    command = "YT_SHORTS_SWIPE"
                elif step_type in ["SCRUB_TIMELINE", "YT_SCRUB_TIMELINE"]:
                    command = "YT_SCRUB_TIMELINE"
                elif step_type in ["SPATIAL_ANCHOR_CLICK"]:
                    command = "SPATIAL_ANCHOR_CLICK"
                elif step_type in ["CLICK_LINK"]:
                    command = "CLICK_LINK"
                elif step_type in ["CLICK_ELEMENT", "CLICK"]:
                    command = "CLICK_ELEMENT"
                elif step_type in ["TYPE_TEXT"]:
                    command = "TYPE_TEXT"
                elif step_type in ["TYPE_AND_ENTER"]:
                    command = "TYPE_AND_ENTER"
                elif step_type in ["CLICK_AD_IFRAME", "CLICK_IFRAME"]:
                    command = "CLICK_AD_IFRAME"
                elif step_type in ["AI_TYPE"]:
                    command = "AI_TYPE"
                elif step_type in ["BÉZIER_SWIPE", "SWIPE_VERTICAL", "SWIPE"]:
                    command = "SWIPE_VERTICAL"
                elif step_type in ["DYNAMIC_ACTION", "ADDON_STEP", "EXECUTE_SCRIPT"]:
                    command = "DYNAMIC_ACTION"
                elif step_type in ["TERMINATE", "COMPLETE"]:
                    command = "TERMINATE"

                # Synchronize calibrated spatial anchor coordinates directly into parameters (for single anchor actions)
                if command not in ["YT_ORGANIC_TARGET_SEARCH", "YT_SCROLL_TO_TARGET"]:
                    anchor_target = params.get("spatial_anchor") or params.get("anchor")
                    if anchor_target and calibrated_anchors:
                        resolved_anchor = calibrated_anchors.get(anchor_target) or calibrated_anchors.get(str(anchor_target).upper())
                        if resolved_anchor:
                            if resolved_anchor.get("x") is not None and "normalized_x" not in params:
                                params["normalized_x"] = int(resolved_anchor["x"])
                            if resolved_anchor.get("y") is not None and "normalized_y" not in params:
                                params["normalized_y"] = int(resolved_anchor["y"])

                states[state_id] = {
                    "command": command,
                    "params": params,
                    "transitions": {
                        "SUCCESS": "exit",
                        "FAILURE": "exit"
                    }
                }
                prev_state = state_id

        if not states:
            states["empty_start"] = {
                "command": "NAVIGATE",
                "params": {"url": "https://m.youtube.com"},
                "transitions": {
                    "SUCCESS": "exit",
                    "FAILURE": "exit"
                }
            }
            first_state = "empty_start"

        states["exit"] = {
            "command": "TERMINATE",
            "params": {},
            "transitions": {}
        }

        return {
            "version": 2,
            "entry_state": first_state,
            "timeout_seconds": 1800,
            "max_steps": max(50, step_idx + 10),
            "states": states
        }


class WorkflowAddon(models.Model):
    """
    Self-contained workflow extension package (like a WordPress plugin/addon).
    Can be zipped, uploaded to the backend, activated, deactivated, or uninstalled completely.
    Supplies custom workflow steps, execution scripts, schema/parameter definitions, and pre-built templates.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    slug = models.SlugField(max_length=100, unique=True, db_index=True)
    name = models.CharField(max_length=255)
    version = models.CharField(max_length=50, default="1.0.0")
    category = models.CharField(max_length=50, default="workflow", db_index=True)
    platform = models.CharField(max_length=50, default="YOUTUBE", db_index=True)
    author = models.CharField(max_length=255, blank=True, default="Antidetect Team")
    description = models.TextField(blank=True, default="")
    icon = models.CharField(max_length=100, default="Puzzle")
    is_active = models.BooleanField(default=True, db_index=True)
    manifest = models.JSONField(default=dict, blank=True)
    package_file = models.FileField(upload_to="addons_packages/", null=True, blank=True)
    installed_dir = models.CharField(max_length=500, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["name"]

    def __str__(self):
        return f"{self.name} v{self.version} ({'Active' if self.is_active else 'Disabled'})"

    @property
    def steps(self):
        return self.manifest.get("steps", []) if isinstance(self.manifest, dict) else []

    @property
    def templates(self):
        return self.manifest.get("templates", []) if isinstance(self.manifest, dict) else []



