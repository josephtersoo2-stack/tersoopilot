import uuid
from django.db import models
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

class TaskExecutionQueue(models.Model):
    """State-machine DAG compiled job dispatched to Android GhostPilot."""
    class ExecutionStatus(models.TextChoices):
        PENDING = "PENDING", "Pending Dispatch"
        DISPATCHED = "DISPATCHED", "Dispatched to Device"
        RUNNING = "RUNNING", "Running"
        SUCCESS = "SUCCESS", "Success"
        FAILED = "FAILED", "Failed"
        STALLED = "STALLED", "Stalled"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    task = models.ForeignKey(
        AutomationTask, 
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
        default=ExecutionStatus.PENDING
    )
    entry_state_id = models.CharField(max_length=100, default="start")
    compiled_dag = models.JSONField(default=dict)
    current_state_id = models.CharField(max_length=100, default="start")
    execution_context = models.JSONField(default=dict)
    logs = models.JSONField(default=list)
    error_message = models.TextField(blank=True, default="")
    started_at = models.DateTimeField(null=True, blank=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    def __str__(self):
        return f"Queue {self.id} [{self.profile.name}] - {self.status}"


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
