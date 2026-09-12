import uuid
from django.db import models
from django.utils import timezone
from django.core.validators import MinValueValidator, MaxValueValidator
from devices.models import SavedProfile

class PlatformCategory(models.TextChoices):
    WARMING = "WARMING", "Cookie Warmer (General Web)"
    YOUTUBE = "YOUTUBE", "YouTube Operations"
    BLOG = "BLOG", "Blog / Content Reading"
    TWITTER = "TWITTER", "X / Twitter"
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
    name = models.CharField(max_length=150)
    category = models.CharField(
        max_length=20, 
        choices=PlatformCategory.choices, 
        default=PlatformCategory.YOUTUBE
    )
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
