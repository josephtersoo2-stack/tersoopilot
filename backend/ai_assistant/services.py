import os
import logging
from typing import Optional, Any, Dict

from .models import (
    AIProviderConfig,
    AIProviderChoices,
    PromptTemplate,
    PromptCategory,
    GlobalAISetting,
    DEFAULT_AI_PROMPT,
)

logger = logging.getLogger(__name__)


class AIConfigService:
    """
    Centralized authority for AI configuration, credentials resolution,
    prompt template retrieval, and multi-provider coordination.
    """

    @classmethod
    def normalize_provider(cls, provider: str) -> str:
        prov = (provider or "").strip().lower()
        if "gemini" in prov:
            return AIProviderChoices.GEMINI
        if "openrouter" in prov:
            return AIProviderChoices.OPENROUTER
        if "anthropic" in prov or "claude" in prov:
            return AIProviderChoices.ANTHROPIC
        return prov

    @classmethod
    def get_api_key(cls, provider: str) -> Optional[str]:
        """
        Resolves API credentials with high-precedence security fallback:
        1. Encrypted AIProviderConfig in database
        2. Legacy devices.LLMConfig
        3. Operating system environment variable
        """
        prov = cls.normalize_provider(provider)

        # 1. Canonical database config
        try:
            cfg = AIProviderConfig.objects.filter(provider=prov, is_active=True).first()
            if cfg:
                key = cfg.get_decrypted_api_key()
                if key:
                    return key
        except Exception as e:
            logger.debug(f"Error querying AIProviderConfig for {prov}: {e}")

        # 2. Legacy devices.LLMConfig fallback
        try:
            from devices.models import LLMConfig
            legacy_cfg = LLMConfig.objects.filter(provider=prov, is_active=True).first()
            if legacy_cfg and legacy_cfg.api_key:
                return legacy_cfg.api_key
        except Exception as e:
            logger.debug(f"Error querying legacy LLMConfig for {prov}: {e}")

        # 3. Environment variables
        env_map = {
            AIProviderChoices.GEMINI: ["GEMINI_API_KEY", "GOOGLE_API_KEY"],
            AIProviderChoices.OPENROUTER: ["OPENROUTER_API_KEY"],
            AIProviderChoices.ANTHROPIC: ["ANTHROPIC_API_KEY"],
        }
        for env_var in env_map.get(prov, [f"{prov.upper()}_API_KEY"]):
            val = os.environ.get(env_var)
            if val:
                return val.strip()

        return None

    @classmethod
    def get_active_prompt(cls, category: str, default: Optional[str] = None) -> str:
        """
        Retrieves the active prompt for a given category with fallback to legacy models.
        """
        try:
            template = PromptTemplate.objects.filter(category=category, is_active=True).first()
            if template and template.system_prompt:
                return template.system_prompt
        except Exception as e:
            logger.debug(f"Error querying PromptTemplate for {category}: {e}")

        # Legacy fallbacks based on category
        if category == PromptCategory.DAG_RECOVERY:
            try:
                from automation.models import AIPromptConfig
                auto_cfg = AIPromptConfig.objects.filter(is_active=True).order_by("-updated_at").first()
                if not auto_cfg:
                    auto_cfg = AIPromptConfig.get_active_config()
                if auto_cfg and auto_cfg.system_prompt:
                    return auto_cfg.system_prompt
            except Exception:
                pass

        if category == PromptCategory.DEVICE_SYNTHESIS:
            try:
                global_cfg = GlobalAISetting.load()
                if global_cfg.ai_generation_prompt:
                    return global_cfg.ai_generation_prompt
            except Exception:
                try:
                    from devices.models import GlobalSetting
                    dev_global = GlobalSetting.load()
                    if dev_global.ai_generation_prompt:
                        return dev_global.ai_generation_prompt
                except Exception:
                    pass

        return default or DEFAULT_AI_PROMPT

    @classmethod
    def get_active_provider_config(cls, provider: Optional[str] = None) -> AIProviderConfig:
        """
        Returns or auto-provisions an active provider configuration.
        """
        if not provider:
            settings = cls.get_global_settings()
            provider = settings.selected_ai_provider

        prov = cls.normalize_provider(provider)
        config = AIProviderConfig.objects.filter(provider=prov, is_active=True).first()
        if not config:
            # Seed default instance
            default_model = "gemini-2.5-flash" if prov == AIProviderChoices.GEMINI else "google/gemini-2.0-flash-001"
            config = AIProviderConfig.objects.create(
                provider=prov,
                name=f"Default {prov.capitalize()} Provider",
                model_name=default_model,
                temperature=0.1,
                is_active=True
            )
        return config

    @classmethod
    def get_global_settings(cls) -> GlobalAISetting:
        """
        Returns canonical GlobalAISetting singleton, synchronizing with legacy devices.GlobalSetting.
        """
        ai_setting = GlobalAISetting.load()
        try:
            from devices.models import GlobalSetting
            dev_setting = GlobalSetting.load()
            # If devices.GlobalSetting has customized values, sync onto ai_setting if unset
            if dev_setting.selected_ai_provider and dev_setting.selected_ai_provider != ai_setting.selected_ai_provider:
                ai_setting.selected_ai_provider = dev_setting.selected_ai_provider
                ai_setting.save(update_fields=["selected_ai_provider", "updated_at"])
        except Exception:
            pass
        return ai_setting
