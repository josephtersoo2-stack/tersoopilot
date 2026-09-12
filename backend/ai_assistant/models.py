import uuid
from django.db import models
from django.core.validators import MinValueValidator, MaxValueValidator
from django.utils import timezone
from devices.security import SecretManager

from automation.models import (
    AssistantSession as AutomationAssistantSession,
    AssistantMessage as AutomationAssistantMessage,
    AIPromptConfig as AutomationAIPromptConfig,
)


class AIProviderChoices(models.TextChoices):
    GEMINI = "gemini", "Google Gemini"
    OPENROUTER = "openrouter", "OpenRouter.ai"
    ANTHROPIC = "anthropic", "Anthropic Claude"


class PromptCategory(models.TextChoices):
    DEVICE_SYNTHESIS = "DEVICE_SYNTHESIS", "Device Specifications Synthesis"
    DAG_RECOVERY = "DAG_RECOVERY", "GhostPilot DAG Recovery"
    AGENT_CHAT = "AGENT_CHAT", "Assistant Operator Chat"
    CUSTOM = "CUSTOM", "Custom Prompt"


class AIProviderConfig(models.Model):
    """
    Canonical provider-level configuration and API credentials.
    Supports secure symmetric encryption at rest for API keys.
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=100, default="")
    provider = models.CharField(
        max_length=30,
        choices=AIProviderChoices.choices,
        default=AIProviderChoices.GEMINI,
        db_index=True,
    )
    api_key = models.CharField(
        max_length=255,
        blank=True,
        default="",
        help_text="Encrypted API key (leave blank to use system environment variable)"
    )
    model_name = models.CharField(
        max_length=150,
        blank=True,
        default="",
        help_text="Default model identifier, e.g. gemini-2.5-flash or google/gemini-2.0-flash-001"
    )
    temperature = models.FloatField(
        default=0.1,
        validators=[MinValueValidator(0.0), MaxValueValidator(1.0)]
    )
    is_active = models.BooleanField(default=True, db_index=True)
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        verbose_name = "AI Provider Configuration"
        verbose_name_plural = "AI Provider Configurations"
        ordering = ["provider", "-is_active", "-updated_at"]

    def save(self, *args, **kwargs):
        if not self.name:
            self.name = f"{self.get_provider_display()} Config"
        if self.api_key and not self.api_key.startswith(SecretManager.PREFIX):
            self.api_key = SecretManager.encrypt(self.api_key)
        super().save(*args, **kwargs)

    def get_decrypted_api_key(self) -> str:
        return SecretManager.decrypt(self.api_key) if self.api_key else ""

    @property
    def decrypted_api_key(self) -> str:
        return self.get_decrypted_api_key()

    def __str__(self):
        status = "Active" if self.is_active else "Inactive"
        return f"{self.name} [{self.provider}] ({status})"


class PromptTemplate(models.Model):
    """
    Categorized prompt templates for different system capabilities
    (Device Synthesis, GhostPilot DAG Recovery, Agent Operator Chat, etc.).
    """
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    category = models.CharField(
        max_length=40,
        choices=PromptCategory.choices,
        default=PromptCategory.CUSTOM,
        db_index=True
    )
    name = models.CharField(max_length=100)
    system_prompt = models.TextField()
    is_active = models.BooleanField(default=True, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        verbose_name = "Prompt Template"
        verbose_name_plural = "Prompt Templates"
        ordering = ["category", "-is_active", "-updated_at"]

    def __str__(self):
        status = "Active" if self.is_active else "Inactive"
        return f"[{self.category}] {self.name} ({status})"


DEFAULT_AI_PROMPT = (
    "You are an expert mobile hardware and anti-detect browser engineer.\n"
    "Your task is to generate verified hardware and browser fingerprint specifications for the requested mobile device.\n"
    "LIVE ONLINE SEARCH PRIORITY: The backend automatically performs a live web search for the device specifications and passes real-time ground truth snippets. You MUST prioritize the live web search findings over your internal model training data cutoff. Use the exact SoC (chipset), GPU renderer, Android version, RAM, and screen specs from the live search results.\n"
    "CRITICAL RULE FOR MODEL NAME: You must ALWAYS preserve the user's requested model in 'model_name' (e.g. if the user requests 'itel s26 ultra', output 'S26 Ultra', NEVER downgrade or rename it to an older model like S25). If the requested device is unreleased or concept, synthesize plausible, authentic next-generation hardware specifications consistent with the brand's hardware lineage.\n"
    "You must respond ONLY with a raw JSON object containing these exact fields:\n"
    "- brand (string, e.g. Samsung, Google, OnePlus, Xiaomi, itel)\n"
    "- model_name (string, matching the user's requested model without repeating brand)\n"
    "- model_code (string, authentic manufacturer code e.g. SM-S928B, CPH2581, S696LN)\n"
    "- android_version (integer, 13, 14 or 15)\n"
    "- soc (string, e.g. Snapdragon 8 Gen 3, Unisoc T7300, Dimensity 9300)\n"
    "- webgl_vendor (string: 'Qualcomm' for Adreno, 'ARM' for Mali)\n"
    "- webgl_renderer (string, e.g. 'Adreno (TM) 750', 'Mali-G57 MP2', 'Mali-G715')\n"
    "- ram_gb (integer, e.g. 8, 12, 16)\n"
    "- cpu_cores (integer, typically 8)\n"
    "- screen_width (integer, CSS viewport width e.g. 360, 384, 412)\n"
    "- screen_height (integer, CSS viewport height e.g. 800, 854, 915)\n"
    "- dpr (float, device pixel ratio e.g. 2.625, 2.75, 3.0)\n"
    "- user_agent (string, authentic Mobile Firefox: 'Mozilla/5.0 (Android {android_version}; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0')\n"
    "Output valid JSON only. No markdown formatting, no code blocks, no other text."
)

DEFAULT_TARGET_SEARCH_SITES = (
    "gsmarena.com\n"
    "devicespecifications.com\n"
    "phonearena.com\n"
    "kimovil.com\n"
    "nanoreview.net"
)


class GlobalAISetting(models.Model):
    """
    Authoritative singleton store for operator-level AI provider defaults,
    concurrency thresholds, search domains, and system prompt defaults.
    """
    singleton_id = models.IntegerField(primary_key=True, default=1)
    max_active_profiles = models.IntegerField(default=5)
    force_global_mute = models.BooleanField(default=True)
    default_video_resolution = models.CharField(max_length=20, default="240p")
    selected_ai_provider = models.CharField(max_length=50, default="openrouter")
    selected_ai_model = models.CharField(max_length=150, default="google/gemini-2.0-flash-001")
    saved_gemini_model = models.CharField(max_length=150, default="gemini-2.5-flash")
    saved_openrouter_model = models.CharField(max_length=150, default="google/gemini-2.0-flash-001")
    ai_generation_prompt = models.TextField(
        default=DEFAULT_AI_PROMPT,
        blank=True,
        help_text="Custom prompt/instructions provided to the AI model when generating device specs"
    )
    target_search_sites = models.TextField(
        default=DEFAULT_TARGET_SEARCH_SITES,
        blank=True,
        help_text="Authoritative phone spec websites to search (one domain per line)"
    )
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        verbose_name = "Global AI Setting"
        verbose_name_plural = "Global AI Settings"

    def save(self, *args, **kwargs):
        self.singleton_id = 1
        super().save(*args, **kwargs)

    @classmethod
    def load(cls):
        obj, _ = cls.objects.get_or_create(singleton_id=1)
        return obj

    def __str__(self):
        return f"Global AI Settings (Provider: {self.selected_ai_provider}, Gemini: {self.saved_gemini_model}, OpenRouter: {self.saved_openrouter_model})"


class AssistantSession(AutomationAssistantSession):
    class Meta:
        proxy = True
        app_label = "ai_assistant"
        verbose_name = "Assistant Session"
        verbose_name_plural = "Assistant Sessions"


class AssistantMessage(AutomationAssistantMessage):
    class Meta:
        proxy = True
        app_label = "ai_assistant"
        verbose_name = "Assistant Message"
        verbose_name_plural = "Assistant Messages"


class AIPromptConfig(AutomationAIPromptConfig):
    class Meta:
        proxy = True
        app_label = "ai_assistant"
        verbose_name = "AI Prompt Configuration"
        verbose_name_plural = "AI Prompt Configurations"
