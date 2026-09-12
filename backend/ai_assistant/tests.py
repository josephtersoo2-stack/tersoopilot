import os
from unittest.mock import patch
from django.test import TestCase
from django.contrib.auth import get_user_model
from django.contrib import admin
from rest_framework.test import APITestCase
from rest_framework import status

from devices.security import SecretManager
from devices.models import LLMConfig, GlobalSetting
from automation.models import AIPromptConfig as LegacyAIPromptConfig

from .models import (
    AIProviderConfig,
    AIProviderChoices,
    PromptTemplate,
    PromptCategory,
    GlobalAISetting,
    AssistantSession,
    AssistantMessage,
    AIPromptConfig,
)
from .services import AIConfigService

User = get_user_model()


class AIConfigServiceTests(TestCase):
    def setUp(self):
        AIProviderConfig.objects.all().delete()
        PromptTemplate.objects.all().delete()
        LLMConfig.objects.all().delete()
        LegacyAIPromptConfig.objects.all().delete()

    def test_get_api_key_resolution_hierarchy(self):
        # 1. Environment variable fallback
        with patch.dict(os.environ, {"GEMINI_API_KEY": "env-gemini-key-123"}):
            self.assertEqual(AIConfigService.get_api_key("gemini"), "env-gemini-key-123")

        # 2. Legacy LLMConfig overrides environment
        LLMConfig.objects.create(
            provider="gemini",
            api_key="legacy-gemini-key-456",
            is_active=True
        )
        with patch.dict(os.environ, {"GEMINI_API_KEY": "env-gemini-key-123"}):
            self.assertEqual(AIConfigService.get_api_key("gemini"), "legacy-gemini-key-456")

        # 3. Canonical AIProviderConfig overrides legacy LLMConfig & env
        AIProviderConfig.objects.create(
            provider=AIProviderChoices.GEMINI,
            api_key="canonical-gemini-key-789",
            is_active=True
        )
        # Verify key is stored encrypted at rest
        stored = AIProviderConfig.objects.get(provider=AIProviderChoices.GEMINI)
        self.assertTrue(stored.api_key.startswith(SecretManager.PREFIX))
        self.assertEqual(stored.get_decrypted_api_key(), "canonical-gemini-key-789")

        with patch.dict(os.environ, {"GEMINI_API_KEY": "env-gemini-key-123"}):
            self.assertEqual(AIConfigService.get_api_key("gemini"), "canonical-gemini-key-789")

    def test_get_active_prompt_resolution_hierarchy(self):
        # 1. Default fallback
        prompt = AIConfigService.get_active_prompt(PromptCategory.DAG_RECOVERY)
        self.assertTrue(len(prompt) > 0)

        # 2. Legacy AIPromptConfig fallback for DAG_RECOVERY
        LegacyAIPromptConfig.objects.all().delete()
        LegacyAIPromptConfig.objects.create(
            name="Legacy Recovery",
            system_prompt="Custom Legacy Recovery Prompt",
            is_active=True
        )
        self.assertEqual(
            AIConfigService.get_active_prompt(PromptCategory.DAG_RECOVERY),
            "Custom Legacy Recovery Prompt"
        )

        # 3. Canonical PromptTemplate overrides legacy
        PromptTemplate.objects.create(
            category=PromptCategory.DAG_RECOVERY,
            name="Canonical Recovery Prompt",
            system_prompt="Canonical PromptTemplate Recovery Instruction",
            is_active=True
        )
        self.assertEqual(
            AIConfigService.get_active_prompt(PromptCategory.DAG_RECOVERY),
            "Canonical PromptTemplate Recovery Instruction"
        )

    def test_auto_provision_provider_config(self):
        self.assertFalse(AIProviderConfig.objects.filter(provider="openrouter").exists())
        cfg = AIConfigService.get_active_provider_config("openrouter")
        self.assertIsNotNone(cfg)
        self.assertEqual(cfg.provider, AIProviderChoices.OPENROUTER)
        self.assertTrue(cfg.is_active)


class AIAssistantAPITests(APITestCase):
    def setUp(self):
        self.admin_user = User.objects.create_superuser(
            username="ai_admin",
            password="adminpassword123",
            email="ai_admin@example.com"
        )
        self.client.force_authenticate(user=self.admin_user)

    def test_ai_provider_config_crud_and_encryption(self):
        create_resp = self.client.post(
            "/api/ai/providers/",
            {
                "name": "Test Anthropic Claude",
                "provider": "anthropic",
                "api_key": "sk-ant-testkey-9999",
                "model_name": "claude-3-5-haiku-20241022",
                "temperature": 0.2,
                "is_active": True,
            },
            format="json"
        )
        self.assertEqual(create_resp.status_code, status.HTTP_201_CREATED)
        self.assertTrue(create_resp.data["api_key_configured"])
        self.assertNotIn("sk-ant-testkey-9999", str(create_resp.data))

        # Check DB encryption
        created_id = create_resp.data["id"]
        instance = AIProviderConfig.objects.get(id=created_id)
        self.assertTrue(instance.api_key.startswith(SecretManager.PREFIX))
        self.assertEqual(instance.get_decrypted_api_key(), "sk-ant-testkey-9999")

    def test_prompt_template_crud(self):
        resp = self.client.post(
            "/api/ai/prompts/",
            {
                "name": "New Synthesis Prompt",
                "category": PromptCategory.DEVICE_SYNTHESIS,
                "system_prompt": "Synthesize authentic fingerprint JSON.",
                "is_active": True,
            },
            format="json"
        )
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(resp.data["category"], PromptCategory.DEVICE_SYNTHESIS)

    def test_global_ai_settings_endpoint(self):
        get_resp = self.client.get("/api/ai/settings/")
        self.assertEqual(get_resp.status_code, status.HTTP_200_OK)

        patch_resp = self.client.patch(
            "/api/ai/settings/",
            {"selected_ai_provider": "gemini", "max_active_profiles": 8},
            format="json"
        )
        self.assertEqual(patch_resp.status_code, status.HTTP_200_OK)
        self.assertEqual(patch_resp.data["selected_ai_provider"], "gemini")
        self.assertEqual(patch_resp.data["max_active_profiles"], 8)


class AIAssistantAdminRegistryTests(TestCase):
    def test_all_models_registered_in_admin(self):
        registered = admin.site._registry
        self.assertIn(AIProviderConfig, registered)
        self.assertIn(PromptTemplate, registered)
        self.assertIn(GlobalAISetting, registered)
        self.assertIn(AssistantSession, registered)
        self.assertIn(AssistantMessage, registered)
        self.assertIn(AIPromptConfig, registered)
