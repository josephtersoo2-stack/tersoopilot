import uuid
import json
from django.db import models
from django.utils import timezone
from django.contrib.auth.models import User
from .security import SecretManager

class SavedProfile(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="profiles", null=True, blank=True)
    name = models.CharField(max_length=150)
    tag = models.CharField(max_length=50, blank=True, default="Default")
    brand = models.CharField(max_length=100)
    model_name = models.CharField(max_length=150)
    model_code = models.CharField(max_length=100)
    android_version = models.IntegerField(default=14)
    soc = models.CharField(max_length=150)
    webgl_vendor = models.CharField(max_length=100)
    webgl_renderer = models.CharField(max_length=150)
    ram_gb = models.IntegerField(default=8)
    cpu_cores = models.IntegerField(default=8)
    screen_width = models.IntegerField(default=384)
    screen_height = models.IntegerField(default=854)
    dpr = models.FloatField(default=2.8125)
    user_agent = models.TextField()
    
    # Proxy & Network configurations
    proxy_type = models.CharField(max_length=20, default="DIRECT")
    proxy_host = models.CharField(max_length=255, blank=True, default="")
    proxy_port = models.IntegerField(default=0)
    proxy_user = models.CharField(max_length=100, blank=True, default="")
    proxy_pass = models.CharField(max_length=255, blank=True, default="")
    web_rtc_mode = models.CharField(max_length=50, blank=True, default="Mdns")

    # Cloud Session Sync Data
    cookies_data = models.TextField(blank=True, default="[]")
    history_data = models.TextField(blank=True, default="[]")
    tabs_data = models.TextField(blank=True, default="[]")
    last_used_timestamp = models.BigIntegerField(default=0)
    cookie_count = models.IntegerField(default=0)
    device_sync_id = models.CharField(max_length=100, blank=True, default="")
    
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def save(self, *args, **kwargs):
        # Automatically encrypt proxy_pass if provided in plaintext
        if self.proxy_pass and not self.proxy_pass.startswith(SecretManager.PREFIX):
            self.proxy_pass = SecretManager.encrypt(self.proxy_pass)
        # Calculate cookie_count if not explicitly set and cookies_data is present
        if not self.cookie_count and self.cookies_data and self.cookies_data != "[]":
            try:
                decrypted = SecretManager.decrypt(self.cookies_data)
                parsed = json.loads(decrypted)
                if isinstance(parsed, list):
                    self.cookie_count = len(parsed)
            except Exception:
                pass
        # Automatically encrypt cookies_data if provided in plaintext
        if self.cookies_data and self.cookies_data != "[]" and not self.cookies_data.startswith(SecretManager.PREFIX):
            self.cookies_data = SecretManager.encrypt(self.cookies_data)
        super().save(*args, **kwargs)

        # Synchronize child domain models
        try:
            ProfileFingerprint.objects.update_or_create(
                profile=self,
                defaults={
                    "brand": self.brand,
                    "model_name": self.model_name,
                    "model_code": self.model_code,
                    "android_version": self.android_version,
                    "soc": self.soc,
                    "webgl_vendor": self.webgl_vendor,
                    "webgl_renderer": self.webgl_renderer,
                    "ram_gb": self.ram_gb,
                    "cpu_cores": self.cpu_cores,
                    "screen_width": self.screen_width,
                    "screen_height": self.screen_height,
                    "dpr": self.dpr,
                    "user_agent": self.user_agent,
                }
            )
            ProxyConfiguration.objects.update_or_create(
                profile=self,
                defaults={
                    "proxy_type": self.proxy_type,
                    "proxy_host": self.proxy_host,
                    "proxy_port": self.proxy_port,
                    "proxy_user": self.proxy_user,
                    "encrypted_password": self.proxy_pass,
                    "web_rtc_mode": self.web_rtc_mode,
                }
            )
            BrowserStorage.objects.update_or_create(
                profile=self,
                defaults={
                    "cookies_data": self.cookies_data,
                    "history_data": self.history_data,
                    "tabs_data": self.tabs_data,
                    "cookie_count": self.cookie_count,
                    "last_used_timestamp": self.last_used_timestamp,
                }
            )
        except Exception:
            pass

    def get_decrypted_proxy_pass(self) -> str:
        return SecretManager.decrypt(self.proxy_pass)

    def get_decrypted_cookies(self) -> str:
        return SecretManager.decrypt(self.cookies_data)

    @property
    def decrypted_cookies(self) -> str:
        return SecretManager.decrypt(self.cookies_data)

    def __str__(self):
        return f"{self.name} ({self.model_name})"

# Domain Alias
BrowserProfile = SavedProfile


class ProfileFingerprint(models.Model):
    """Encapsulates device hardware identity, GPU rendering, and OS fingerprint."""
    profile = models.OneToOneField(SavedProfile, on_delete=models.CASCADE, related_name="fingerprint")
    brand = models.CharField(max_length=100)
    model_name = models.CharField(max_length=150)
    model_code = models.CharField(max_length=100)
    android_version = models.IntegerField(default=14)
    soc = models.CharField(max_length=150)
    webgl_vendor = models.CharField(max_length=100)
    webgl_renderer = models.CharField(max_length=150)
    ram_gb = models.IntegerField(default=8)
    cpu_cores = models.IntegerField(default=8)
    screen_width = models.IntegerField(default=384)
    screen_height = models.IntegerField(default=854)
    dpr = models.FloatField(default=2.8125)
    user_agent = models.TextField()
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"Fingerprint [{self.brand} {self.model_name}]"


class ProxyConfiguration(models.Model):
    """Encapsulates proxy tunneling protocols, hosts, and encrypted credentials."""
    profile = models.OneToOneField(SavedProfile, on_delete=models.CASCADE, related_name="proxy_config")
    proxy_type = models.CharField(max_length=20, default="DIRECT")
    proxy_host = models.CharField(max_length=255, blank=True, default="")
    proxy_port = models.IntegerField(default=0)
    proxy_user = models.CharField(max_length=100, blank=True, default="")
    encrypted_password = models.CharField(max_length=255, blank=True, default="")
    web_rtc_mode = models.CharField(max_length=50, blank=True, default="Mdns")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    @property
    def proxy_pass(self) -> str:
        return SecretManager.decrypt(self.encrypted_password)

    @proxy_pass.setter
    def proxy_pass(self, value: str):
        self.encrypted_password = SecretManager.encrypt(value)

    def __str__(self):
        return f"Proxy [{self.proxy_type} {self.proxy_host}:{self.proxy_port}]"


class BrowserStorage(models.Model):
    """Encapsulates cookies, navigation history, and tab state snapshots."""
    profile = models.OneToOneField(SavedProfile, on_delete=models.CASCADE, related_name="storage")
    cookies_data = models.TextField(blank=True, default="[]")
    history_data = models.TextField(blank=True, default="[]")
    tabs_data = models.TextField(blank=True, default="[]")
    cookie_count = models.IntegerField(default=0)
    version = models.IntegerField(default=1)
    last_used_timestamp = models.BigIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def save(self, *args, **kwargs):
        if self.cookies_data and self.cookies_data != "[]" and not self.cookies_data.startswith(SecretManager.PREFIX):
            self.cookies_data = SecretManager.encrypt(self.cookies_data)
        super().save(*args, **kwargs)

    def get_decrypted_cookies(self) -> str:
        return SecretManager.decrypt(self.cookies_data)

    @property
    def decrypted_cookies(self) -> str:
        return SecretManager.decrypt(self.cookies_data)

    def __str__(self):
        return f"Storage [{self.profile.name}: {self.cookie_count} cookies]"


class DeviceStatus(models.TextChoices):
    ONLINE = "ONLINE", "Online"
    BUSY = "BUSY", "Busy"
    OFFLINE = "OFFLINE", "Offline"


class Device(models.Model):
    """
    Represents a physical or emulated mobile/desktop node executing automation jobs.
    Tracks hardware identity, runtime version, heartbeat, and operational status.
    """
    DeviceStatus = DeviceStatus

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="registered_devices", null=True, blank=True)
    device_id = models.CharField(max_length=255, unique=True, db_index=True)
    device_sync_id = models.CharField(max_length=100, blank=True, default="", db_index=True)
    platform = models.CharField(max_length=50, default="ANDROID")
    brand = models.CharField(max_length=100, blank=True, default="")
    model_name = models.CharField(max_length=150, blank=True, default="")
    app_version = models.CharField(max_length=50, blank=True, default="")
    status = models.CharField(max_length=30, choices=DeviceStatus.choices, default=DeviceStatus.ONLINE, db_index=True)
    last_seen = models.DateTimeField(default=timezone.now, db_index=True)
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-last_seen"]
        indexes = [
            models.Index(fields=["owner", "status"]),
            models.Index(fields=["device_id", "status"]),
        ]

    def __str__(self):
        return f"Device {self.device_id} [{self.platform}] - {self.status}"

    def is_online(self, timeout_seconds: int = 120) -> bool:
        return (timezone.now() - self.last_seen).total_seconds() < timeout_seconds


class LLMConfig(models.Model):
    PROVIDER_CHOICES = [
        ("gemini", "Google Gemini"),
        ("openrouter", "OpenRouter.ai"),
    ]

    provider = models.CharField(
        max_length=20,
        choices=PROVIDER_CHOICES,
        default="gemini",
        help_text="Select AI provider for device fingerprint generation"
    )
    api_key = models.CharField(
        max_length=255,
        blank=True,
        default="",
        help_text="API key (leave blank to read from environment variable GEMINI_API_KEY or OPENROUTER_API_KEY)"
    )
    model_name = models.CharField(
        max_length=150,
        blank=True,
        default="",
        help_text="Optional default model ID (leave blank to allow direct dynamic selection from the provider)"
    )
    system_prompt = models.TextField(
        blank=True,
        default="",
        help_text="Custom prompt/instructions for this AI provider (leave blank to use the Global AI Prompt)"
    )
    is_active = models.BooleanField(
        default=True,
        help_text="Whether this configuration is currently active"
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        verbose_name = "LLM & API Configuration"
        verbose_name_plural = "LLM & API Configurations"

    def __str__(self):
        status = "Active" if self.is_active else "Inactive"
        model_display = self.model_name if self.model_name else "Dynamic Models"
        return f"{self.get_provider_display()} - {model_display} ({status})"


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

class GlobalSetting(models.Model):
    singleton_id = models.IntegerField(primary_key=True, default=1)
    max_active_profiles = models.IntegerField(default=5)  # Range 1 to 10
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
        help_text="Authoritative phone spec websites to search (one domain per line, e.g. gsmarena.com, devicespecifications.com)"
    )
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        verbose_name = "AI Prompt & Global Setting"
        verbose_name_plural = "AI Prompt & Global Settings"

    def save(self, *args, **kwargs):
        self.singleton_id = 1
        super().save(*args, **kwargs)

    @classmethod
    def load(cls):
        obj, _ = cls.objects.get_or_create(singleton_id=1)
        return obj

    def __str__(self):
        return f"AI Prompt & Settings (Provider: {self.selected_ai_provider}, Gemini: {self.saved_gemini_model}, OpenRouter: {self.saved_openrouter_model})"

