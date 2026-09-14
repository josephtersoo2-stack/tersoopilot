import uuid
import json
import datetime
from unittest.mock import patch, MagicMock
from django.test import TestCase, override_settings
from django.utils import timezone
from django.contrib.auth.models import User
from rest_framework.test import APIClient
from rest_framework import status
from devices.models import SavedProfile
from .models import (
    Niche,
    ProfilePersona,
    ProfileNicheAffiliation,
    AutomationTask,
    TaskExecutionQueue,
    PlatformCategory,
    AIPromptConfig,
    Automation,
    AutomationRun,
    ScheduleType,
    SelectionMode,
    AutomationRunStatus,
)
from executions.models import Execution, ExecutionPlan
from .compiler import RecipeCompiler
from .decision_engine import (
    AgentRecoveryAction,
    GhostPilotDecisionEngine,
    LLMAdapterFactory,
    OpenRouterAdapter,
    GeminiAdapter
)


class AutomationEngineTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.operator, _ = User.objects.get_or_create(username="test-operator", defaults={"is_staff": True})
        self.client.force_authenticate(self.operator)
        self.profile = SavedProfile.objects.create(
            name="Alpha Device",
            brand="Samsung",
            model_name="Galaxy S24",
            model_code="SM-S921B",
            android_version=14,
            soc="Snapdragon 8 Gen 3",
            webgl_vendor="Qualcomm",
            webgl_renderer="Adreno (TM) 750",
            ram_gb=8,
            cpu_cores=8,
            screen_width=384,
            screen_height=854,
            dpr=2.8125,
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )

    def test_auto_persona_signal_generation(self):
        """Verify that creating a SavedProfile automatically triggers Persona creation with valid human bounds."""
        self.assertTrue(hasattr(self.profile, "persona"))
        persona = self.profile.persona
        self.assertEqual(persona.trust_score, 10)
        self.assertEqual(persona.maturation_stage, ProfilePersona.MaturationStage.INFANT)
        self.assertTrue(0.40 <= persona.patience_index <= 0.85)
        self.assertTrue(0.08 <= persona.engagement_rate <= 0.25)
        self.assertTrue(55 <= persona.typing_wpm <= 85)
        self.assertTrue(0.02 <= persona.typo_probability <= 0.05)

    def test_niche_crud_and_multi_niche_weighting(self):
        """Verify creating niches and assigning weighted affiliations to a profile."""
        tech_niche = Niche.objects.create(
            name="Tech & Hardware",
            description="Tech reviews, benchmarks, hardware testing",
            seed_keywords=["mechanical keyboards", "OLED monitor test"],
            seed_websites=["rtings.com", "theverge.com"],
            target_youtube_channels=["@MKBHD", "@Dave2D"],
            blacklist_keywords=["controversial", "nsfw"]
        )
        crypto_niche = Niche.objects.create(
            name="Crypto & Web3",
            description="Decentralized finance and blockchain",
            seed_keywords=["bitcoin halving", "ethereum l2"],
            seed_websites=["coindesk.com", "coinmarketcap.com"],
            target_youtube_channels=["@CoinBureau"],
            blacklist_keywords=["scam", "airdrop"]
        )

        # Assign multi-niche weighting via orchestration endpoint
        payload = {
            "niches": [
                {"niche_id": str(tech_niche.id), "weight": 70},
                {"niche_id": str(crypto_niche.id), "weight": 30}
            ]
        }
        url = f"/api/automation/profiles-orchestration/{self.profile.id}/set-niches/"
        response = self.client.post(url, payload, format="json")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(len(response.data), 2)

        # Check DB records
        affiliations = ProfileNicheAffiliation.objects.filter(profile=self.profile).order_by("-weight_percentage")
        self.assertEqual(affiliations.count(), 2)
        self.assertEqual(affiliations[0].niche, tech_niche)
        self.assertEqual(affiliations[0].weight_percentage, 70)
        self.assertEqual(affiliations[1].niche, crypto_niche)
        self.assertEqual(affiliations[1].weight_percentage, 30)

    def test_get_persona_endpoint(self):
        """Verify retrieving persona parameters for a profile."""
        url = f"/api/automation/profiles-orchestration/{self.profile.id}/get-persona/"
        response = self.client.get(url)
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data["trust_score"], 10)
        self.assertIn("typing_wpm", response.data)
        self.assertIn("patience_index", response.data)

    def test_set_persona_endpoint(self):
        """Verify updating persona parameters for a profile."""
        url = f"/api/automation/profiles-orchestration/{self.profile.id}/set-persona/"
        payload = {
            "trust_score": 85,
            "typing_wpm": 95,
            "typo_probability": 0.02,
            "patience_index": 0.8,
            "engagement_rate": 0.35,
        }
        response = self.client.post(url, payload, format="json")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data["trust_score"], 85)
        self.assertEqual(response.data["maturation_stage"], "MATURE")
        self.assertEqual(response.data["typing_wpm"], 95)
        self.assertEqual(response.data["patience_index"], 0.8)
        self.assertEqual(response.data["engagement_rate"], 0.35)

    def test_automation_task_and_execution_queue(self):
        """Verify automation task creation and execution queue DAG tracking."""
        task = AutomationTask.objects.create(
            name="YouTube Warming Session",
            category=PlatformCategory.YOUTUBE,
            config={"search_term": "OLED monitor test", "max_watch_duration_sec": 180}
        )
        queue_item = TaskExecutionQueue.objects.create(
            task=task,
            profile=self.profile,
            entry_state_id="search_query",
            compiled_dag={
                "search_query": {"action": "type_search", "next": "click_first_video"},
                "click_first_video": {"action": "click", "next": "watch_and_scroll"}
            },
            current_state_id="search_query",
            status=TaskExecutionQueue.ExecutionStatus.PENDING
        )
        self.assertEqual(queue_item.status, TaskExecutionQueue.ExecutionStatus.PENDING)
        self.assertEqual(queue_item.current_state_id, "search_query")


class DAGCompilerTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.operator, _ = User.objects.get_or_create(username="test-operator", defaults={"is_staff": True})
        self.client.force_authenticate(self.operator)
        self.profile = SavedProfile.objects.create(
            name="Compiler Test Device",
            brand="Google",
            model_name="Pixel 8 Pro",
            model_code="GC368",
            android_version=14,
            soc="Google Tensor G3",
            webgl_vendor="ARM",
            webgl_renderer="Mali-G715",
            ram_gb=12,
            cpu_cores=8,
            screen_width=412,
            screen_height=915,
            dpr=2.625,
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        self.niche = Niche.objects.create(
            name="Consumer Tech",
            seed_keywords=["Pixel 8 review", "Flagship battery test"],
            seed_websites=["theverge.com", "androidpolice.com"]
        )
        ProfileNicheAffiliation.objects.create(
            profile=self.profile,
            niche=self.niche,
            weight_percentage=100
        )

    def test_warmer_dag_compilation(self):
        task = AutomationTask.objects.create(
            name="Daily Warm",
            category="WARMING",
            niche=self.niche
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        self.assertIn("entry_state", dag)
        self.assertIn("states", dag)
        self.assertIn("phase1_nav", dag["states"])
        self.assertIn("phase2_google_nav", dag["states"])

    def test_youtube_dag_compilation(self):
        task = AutomationTask.objects.create(
            name="Tech Video Watch",
            category="YOUTUBE",
            niche=self.niche,
            config={"min_watch_seconds": 60, "max_watch_seconds": 120}
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        states = dag["states"]
        self.assertIn("yt_nav_home", states)
        self.assertIn("yt_type_query", states)
        self.assertIn("yt_monitor_playback", states)

        # Check persona typing speed was injected
        typing_params = states["yt_type_query"]["params"]
        self.assertEqual(typing_params["wpm"], self.profile.persona.typing_wpm)

    def test_dispatch_and_transition_flow(self):
        task = AutomationTask.objects.create(
            name="Dispatch Flow Test",
            category="YOUTUBE",
            niche=self.niche
        )
        # Dispatch
        resp = self.client.post(f"/api/automation/tasks/{task.id}/dispatch/", {"profile_ids": [str(self.profile.id)]}, format="json")
        self.assertEqual(resp.status_code, 201)
        queue_id = resp.data["queue_ids"][0]

        # Poll
        poll_resp = self.client.get(f"/api/automation/executions/poll/{self.profile.id}/")
        self.assertEqual(poll_resp.status_code, 200)
        self.assertTrue(poll_resp.data["work_available"])

        # Transition
        trans_resp = self.client.post(f"/api/automation/executions/{queue_id}/transition/", {
            "outcome": "SUCCESS",
            "context_update": {"test": "ok"}
        }, format="json")
        self.assertEqual(trans_resp.status_code, 200)
        self.assertEqual(trans_resp.data["status"], "ADVANCED")


class GhostPilotFleetControlTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.operator, _ = User.objects.get_or_create(username="test-operator", defaults={"is_staff": True})
        self.client.force_authenticate(self.operator)
        self.profile = SavedProfile.objects.create(
            name="Fleet Control Device",
            brand="Google",
            model_name="Pixel 8",
            android_version=14,
            ram_gb=8,
            cpu_cores=8,
            screen_width=412,
            screen_height=915,
            dpr=2.625,
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        self.niche = Niche.objects.create(
            name="Fleet Gaming",
            seed_keywords=["Gameplay 60fps"]
        )
        self.task = AutomationTask.objects.create(
            name="GhostPilot Test Task",
            category="YOUTUBE",
            niche=self.niche
        )
        self.dag = RecipeCompiler.compile_recipe(self.task, self.profile)
        self.job = TaskExecutionQueue.objects.create(
            task=self.task,
            profile=self.profile,
            status=TaskExecutionQueue.ExecutionStatus.PENDING,
            entry_state_id=self.dag["entry_state"],
            compiled_dag=self.dag,
            current_state_id=self.dag["entry_state"]
        )

    def test_ghostpilot_poll_both_routes(self):
        """Test polling via path parameter and query parameter."""
        # Query parameter route on /ghostpilot/poll/
        poll_resp = self.client.get(f"/api/automation/ghostpilot/poll/?profile_id={self.profile.id}")
        self.assertEqual(poll_resp.status_code, 200)
        self.assertTrue(poll_resp.data["work_available"])
        self.assertEqual(poll_resp.data["job_id"], str(self.job.id))

        # Job is now DISPATCHED, second poll should return no work
        poll_resp2 = self.client.get(f"/api/automation/ghostpilot/poll/{self.profile.id}/")
        self.assertEqual(poll_resp2.status_code, 200)
        self.assertFalse(poll_resp2.data["work_available"])

    def test_ghostpilot_poll_resilient_matching(self):
        """Test polling by profile name, alias, and cloud_sync_id."""
        # Reset job to PENDING
        self.job.status = TaskExecutionQueue.ExecutionStatus.PENDING
        self.job.save()

        # 1. Poll using random local UUID but passing profile_name query param
        local_uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        resp1 = self.client.get(f"/api/automation/ghostpilot/poll/{local_uuid}/?profile_name={self.profile.name}")
        self.assertEqual(resp1.status_code, 200)
        self.assertTrue(resp1.data["work_available"])
        self.assertEqual(resp1.data["job_id"], str(self.job.id))
        self.assertEqual(resp1.data["backend_profile_id"], str(self.profile.id))

        # Reset job
        self.job.status = TaskExecutionQueue.ExecutionStatus.PENDING
        self.job.save()

        # 2. Poll using cloud_sync_id
        resp2 = self.client.get(f"/api/automation/ghostpilot/poll/{local_uuid}/?cloud_sync_id={self.profile.id}")
        self.assertEqual(resp2.status_code, 200)
        self.assertTrue(resp2.data["work_available"])
        self.assertEqual(resp2.data["job_id"], str(self.job.id))

        # Reset job
        self.job.status = TaskExecutionQueue.ExecutionStatus.PENDING
        self.job.save()

        # 3. Poll directly using profile name in path
        resp3 = self.client.get(f"/api/automation/ghostpilot/poll/{self.profile.name}/")
        self.assertEqual(resp3.status_code, 200)
        self.assertTrue(resp3.data["work_available"])
        self.assertEqual(resp3.data["job_id"], str(self.job.id))

    def test_ghostpilot_heartbeat(self):
        """Test heartbeat touches job and reports alive."""
        self.job.status = TaskExecutionQueue.ExecutionStatus.DISPATCHED
        self.job.save()

        resp = self.client.post(f"/api/automation/ghostpilot/{self.job.id}/heartbeat/")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.data["status"], "ALIVE")
        self.assertEqual(resp.data["job_status"], TaskExecutionQueue.ExecutionStatus.RUNNING)

        self.job.refresh_from_db()
        self.assertEqual(self.job.status, TaskExecutionQueue.ExecutionStatus.RUNNING)
        self.assertIn("last_heartbeat", self.job.execution_context)

        # Test terminal job returns TERMINAL
        self.job.status = TaskExecutionQueue.ExecutionStatus.SUCCESS
        self.job.save()
        resp_term = self.client.post(f"/api/automation/ghostpilot/{self.job.id}/heartbeat/")
        self.assertEqual(resp_term.status_code, 200)
        self.assertEqual(resp_term.data["status"], "TERMINAL")

    def test_ghostpilot_abort(self):
        """Test operator abort immediately cancels job and marks FAILED."""
        resp = self.client.post(f"/api/automation/ghostpilot/{self.job.id}/abort/")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.data["status"], "ABORTED")

        self.job.refresh_from_db()
        self.assertEqual(self.job.status, TaskExecutionQueue.ExecutionStatus.FAILED)
        self.assertEqual(self.job.error_message, "Task manually aborted by operator.")
        self.assertIsNotNone(self.job.completed_at)
        self.assertTrue(any(log.get("type") == "ABORT" for log in self.job.logs))

    def test_ghostpilot_decision_no_key_fallback(self):
        """When GEMINI_API_KEY is not configured, engine resolves with fallback swipe."""
        with patch.dict("os.environ", {"GEMINI_API_KEY": "", "OPENROUTER_API_KEY": "", "AI_PROVIDER": ""}):
            resp = self.client.post(
                f"/api/automation/ghostpilot/{self.job.id}/decision/",
                {
                    "page_snapshot": {"elements": [{"tag": "button", "text": "Consent"}]},
                    "screenshot": None
                },
                format="json"
            )
            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.data["status"], "DECISION_RESOLVED")
            self.assertEqual(resp.data["action"], "BÉZIER_SWIPE")
            self.assertEqual(resp.data["swipe_direction"], "DOWN")

    @patch("google.genai.Client")
    def test_ghostpilot_decision_with_gemini(self, mock_client_cls):
        """Test structured AI decision resolution using mock Gemini 2.5 Flash."""
        mock_response = MagicMock()
        mock_response.text = json.dumps({
            "action": "TAP_COORDINATES",
            "target_x": 540,
            "target_y": 1200,
            "swipe_direction": None,
            "navigate_url": None,
            "wait_seconds": None,
            "next_state_override": "yt_monitor_playback",
            "reasoning": "Detected popup overlay; dismiss button tapped."
        })

        mock_instance = MagicMock()
        mock_instance.models.generate_content.return_value = mock_response
        mock_client_cls.return_value = mock_instance

        with patch.dict("os.environ", {"AI_PROVIDER": "GEMINI", "GEMINI_API_KEY": "test-key", "OPENROUTER_API_KEY": ""}):
            resp = self.client.post(
                f"/api/automation/ghostpilot/{self.job.id}/decision/",
                {
                    "page_snapshot": {"title": "YouTube - Consent Required"},
                    "screenshot": None
                },
                format="json"
            )
            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.data["status"], "DECISION_RESOLVED")
            self.assertEqual(resp.data["action"], "TAP_COORDINATES")
            self.assertEqual(resp.data["target_x"], 540)
            self.assertEqual(resp.data["target_y"], 1200)
            self.assertEqual(resp.data["next_state_override"], "yt_monitor_playback")

            self.job.refresh_from_db()
            self.assertEqual(self.job.current_state_id, "yt_monitor_playback")
            self.assertTrue(any(log.get("type") == "AI_DECISION" for log in self.job.logs))

    def test_ghostpilot_telemetry_stream(self):
        """Test SSE live log telemetry endpoint with once=true."""
        self.job.logs = [
            {"from_state": "start", "to_state": "yt_nav_home", "outcome": "SUCCESS"}
        ]
        self.job.save()

        resp = self.client.get(f"/api/automation/ghostpilot/{self.job.id}/stream/?once=true")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp["Content-Type"], "text/event-stream")

        content = b"".join(resp.streaming_content).decode("utf-8")
        self.assertIn("data: ", content)
        self.assertIn("yt_nav_home", content)


class GhostPilotDecisionEngineTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.operator, _ = User.objects.get_or_create(username="test-operator", defaults={"is_staff": True})
        self.client.force_authenticate(self.operator)
        self.profile = SavedProfile.objects.create(
            name="Decision Test Device",
            brand="Samsung",
            model_name="Galaxy S24",
            model_code="SM-S921B",
            android_version=14,
            soc="Snapdragon 8 Gen 3",
            webgl_vendor="Qualcomm",
            webgl_renderer="Adreno 750",
            ram_gb=8,
            cpu_cores=8,
            screen_width=384,
            screen_height=854,
            dpr=2.8125,
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        self.task = AutomationTask.objects.create(
            name="Decision Task",
            category="YOUTUBE",
            config={}
        )
        self.job = TaskExecutionQueue.objects.create(
            task=self.task,
            profile=self.profile,
            current_state_id="yt_monitor_playback",
            compiled_dag={"entry_state": "start", "states": {"start": {"command": "WAIT"}, "yt_monitor_playback": {"command": "WAIT_PLAYBACK"}}}
        )

    def test_decision_heuristic_fallback_when_no_keys(self):
        """Verifies that with no environment keys, the engine returns safe downward swipe."""
        with patch.dict("os.environ", {"GEMINI_API_KEY": "", "OPENROUTER_API_KEY": "", "AI_PROVIDER": ""}):
            resp = self.client.post(
                f"/api/automation/ghostpilot/{self.job.id}/decision/",
                {"page_snapshot": {"url": "https://m.youtube.com", "interactables": []}},
                format="json"
            )
            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.data["status"], "DECISION_RESOLVED")
            self.assertEqual(resp.data["action"], "BÉZIER_SWIPE")
            self.assertIn("No active API key found for provider", resp.data["reasoning"])

    @patch("openai.resources.chat.completions.Completions.create")
    def test_decision_via_openrouter(self, mock_create):
        """Verifies OpenRouter routing, model injection, and log auditing."""
        mock_choice = MagicMock()
        mock_choice.message.content = json.dumps({
            "action": "TAP_COORDINATES",
            "target_x": 192,
            "target_y": 420,
            "next_state_override": "yt_monitor_playback",
            "reasoning": "Tapping dismiss button via OpenRouter."
        })
        mock_response = MagicMock()
        mock_response.choices = [mock_choice]
        mock_create.return_value = mock_response

        env_vars = {
            "AI_PROVIDER": "OPENROUTER",
            "OPENROUTER_API_KEY": "sk-or-test-key",
            "AI_MODEL": "deepseek/deepseek-chat",
            "GEMINI_API_KEY": ""
        }

        with patch.dict("os.environ", env_vars):
            resp = self.client.post(
                f"/api/automation/ghostpilot/{self.job.id}/decision/",
                {"page_snapshot": {"url": "https://m.youtube.com"}},
                format="json"
            )
            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.data["action"], "TAP_COORDINATES")
            self.assertEqual(resp.data["target_x"], 192)
            self.assertEqual(resp.data["target_y"], 420)

            # Check execution queue logging
            self.job.refresh_from_db()
            ai_log = next(log for log in self.job.logs if log.get("type") == "AI_DECISION")
            self.assertEqual(ai_log["provider"], "OpenRouterAdapter")
            self.assertEqual(ai_log["model"], "deepseek/deepseek-chat")

    @patch("google.genai.Client")
    def test_decision_via_gemini(self, mock_genai_client_cls):
        """Verifies Gemini routing when chosen via task configuration."""
        mock_client = MagicMock()
        mock_genai_client_cls.return_value = mock_client

        mock_resp = MagicMock()
        mock_resp.text = json.dumps({
            "action": "WAIT",
            "wait_seconds": 5,
            "reasoning": "Ad skip countdown active."
        })
        mock_client.models.generate_content.return_value = mock_resp

        # Task config overrides global environment
        self.task.config = {
            "ai_provider": "GEMINI",
            "ai_model": "gemini-2.5-flash",
            "gemini_api_key": "test-gemini-key"
        }
        self.task.save()

        resp = self.client.post(
            f"/api/automation/ghostpilot/{self.job.id}/decision/",
            {"page_snapshot": {"url": "https://m.youtube.com"}},
            format="json"
        )
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.data["action"], "WAIT")
        self.assertEqual(resp.data["wait_seconds"], 5)

    @patch("openai.resources.chat.completions.Completions.create")
    def test_decision_via_openrouter_custom_model(self, mock_create):
        """Verifies OpenRouter routing with task-level custom model anthropic/claude-3.5-haiku."""
        mock_choice = MagicMock()
        mock_choice.message.content = json.dumps({
            "action": "NAVIGATE",
            "navigate_url": "https://m.youtube.com/feed/subscriptions",
            "reasoning": "Directing to subscriptions feed via Claude."
        })
        mock_response = MagicMock()
        mock_response.choices = [mock_choice]
        mock_create.return_value = mock_response

        self.task.config = {
            "ai_provider": "OPENROUTER",
            "ai_model": "anthropic/claude-3.5-haiku",
            "openrouter_api_key": "sk-or-custom-key"
        }
        self.task.save()

        resp = self.client.post(
            f"/api/automation/ghostpilot/{self.job.id}/decision/",
            {"page_snapshot": {"url": "https://m.youtube.com"}},
            format="json"
        )
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.data["action"], "NAVIGATE")
        self.assertEqual(resp.data["navigate_url"], "https://m.youtube.com/feed/subscriptions")

        # Verify model was passed to chat.completions.create
        self.assertEqual(mock_create.call_args.kwargs["model"], "anthropic/claude-3.5-haiku")

        self.job.refresh_from_db()
        ai_log = next(log for log in self.job.logs if log.get("type") == "AI_DECISION")
        self.assertEqual(ai_log["model"], "anthropic/claude-3.5-haiku")


class DynamicAIPromptConfigTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.operator, _ = User.objects.get_or_create(username="test-operator", defaults={"is_staff": True})
        self.client.force_authenticate(self.operator)
        self.profile = SavedProfile.objects.create(
            name="Config Test Device",
            brand="Samsung",
            model_name="Galaxy S24",
            model_code="SM-S921B",
            android_version=14,
            soc="Snapdragon 8 Gen 3",
            webgl_vendor="Qualcomm",
            webgl_renderer="Adreno 750",
            ram_gb=8,
            cpu_cores=8,
            screen_width=384,
            screen_height=854,
            dpr=2.8125,
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        self.task = AutomationTask.objects.create(
            name="Custom Prompt Task",
            category="YOUTUBE"
        )
        self.job = TaskExecutionQueue.objects.create(
            task=self.task,
            profile=self.profile,
            current_state_id="yt_monitor_playback",
            compiled_dag={"entry_state": "start", "states": {"start": {"command": "WAIT"}, "yt_monitor_playback": {"command": "WAIT_PLAYBACK"}}}
        )

    def test_auto_seed_default_config(self):
        AIPromptConfig.objects.all().delete()
        config = AIPromptConfig.get_active_config()
        self.assertIsNotNone(config)
        self.assertEqual(config.provider, "OPENROUTER")
        self.assertTrue(config.is_active)

    @patch("openai.resources.chat.completions.Completions.create")
    def test_dynamic_prompt_and_model_usage(self, mock_create):
        mock_choice = MagicMock()
        mock_choice.message.content = json.dumps({
            "action": "TAP_COORDINATES",
            "target_x": 100,
            "target_y": 200,
            "reasoning": "Test passed."
        })
        mock_response = MagicMock()
        mock_response.choices = [mock_choice]
        mock_create.return_value = mock_response

        # Update DB config to a custom model and custom prompt template
        config = AIPromptConfig.get_active_config()
        config.provider = "OPENROUTER"
        config.model_name = "anthropic/claude-3.5-haiku"
        config.system_prompt = "Custom instructions for {task_name} in state {current_state}."
        config.save()

        with patch.dict("os.environ", {"OPENROUTER_API_KEY": "sk-test", "GEMINI_API_KEY": ""}):
            resp = self.client.post(
                f"/api/automation/ghostpilot/{self.job.id}/decision/",
                {"page_snapshot": {"url": "https://m.youtube.com"}},
                format="json"
            )
            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.data["action"], "TAP_COORDINATES")

            # Verify model requested was claude-3.5-haiku
            call_kwargs = mock_create.call_args.kwargs
            self.assertEqual(call_kwargs["model"], "anthropic/claude-3.5-haiku")

            # Verify prompt formatting with dynamic parameters
            messages = call_kwargs["messages"]
            system_msg = next(m["content"] for m in messages if m["role"] == "system")
            self.assertIn("Custom instructions for Custom Prompt Task in state yt_monitor_playback.", system_msg)

    def test_ai_config_api_endpoints(self):
        """Test GET /api/automation/ai-config/active/ and POST /set-active/."""
        # Get active
        resp = self.client.get("/api/automation/ai-config/active/")
        self.assertEqual(resp.status_code, 200)
        self.assertIn("model_name", resp.data)
        self.assertIn("system_prompt", resp.data)

        # Create new config
        new_config = AIPromptConfig.objects.create(
            name="Gemini Config",
            provider=AIPromptConfig.ProviderChoices.GEMINI,
            model_name="gemini-2.5-flash",
            is_active=False
        )

        # Set active via API
        post_resp = self.client.post("/api/automation/ai-config/set-active/", {"config_id": str(new_config.id)}, format="json")
        self.assertEqual(post_resp.status_code, 200)
        self.assertEqual(post_resp.data["provider"], "GEMINI")
        self.assertEqual(post_resp.data["model_name"], "gemini-2.5-flash")

        # Verify previous is no longer active
        new_config.refresh_from_db()
        self.assertTrue(new_config.is_active)


@override_settings(ASSISTANT_ALLOW_WRITES=True)
class TersoAssistantEngineTests(TestCase):
    """Tests for the TersoAssistant tool-calling conversational engine."""

    def setUp(self):
        self.client = APIClient()
        self.operator, _ = User.objects.get_or_create(username="test-operator", defaults={"is_staff": True})
        self.client.force_authenticate(self.operator)
        self.profile = SavedProfile.objects.create(
            name="Assistant Test Device",
            brand="Samsung",
            model_name="Galaxy A54",
            model_code="SM-A546B",
            android_version=14,
            soc="Exynos 1380",
            webgl_vendor="ARM",
            webgl_renderer="Mali-G68",
            ram_gb=6,
            cpu_cores=8,
            screen_width=384,
            screen_height=854,
            dpr=2.34,
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        self.niche = Niche.objects.create(
            name="Assistant Gaming Niche",
            seed_keywords=["best gaming laptops 2026"]
        )
        self.task = AutomationTask.objects.create(
            name="Assistant Test Task",
            category="YOUTUBE",
            niche=self.niche
        )
        self.job = TaskExecutionQueue.objects.create(
            task=self.task,
            profile=self.profile,
            status=TaskExecutionQueue.ExecutionStatus.RUNNING,
            entry_state_id="start",
            compiled_dag={"entry_state": "start", "states": {"start": {"command": "WAIT"}, "yt_monitor_playback": {"command": "WAIT_PLAYBACK"}}},
            current_state_id="yt_monitor_playback",
            logs=[{"type": "INIT", "msg": "Job started"}]
        )

    def test_local_tool_execution_fleet_status(self):
        """Validate execute_tool('get_fleet_status') returns expected summary keys."""
        from automation.assistant_tools import execute_tool
        result = execute_tool("get_fleet_status", {})
        self.assertIn("total_profiles", result)
        self.assertIn("active_running_jobs", result)
        self.assertIn("pending_queued_jobs", result)
        self.assertIn("failed_jobs", result)
        self.assertIn("maturation_distribution", result)
        # We have at least 1 profile from setUp
        self.assertGreaterEqual(result["total_profiles"], 1)

    def test_create_niche_tool(self):
        """Validate execute_tool('create_niche') creates a DB entry."""
        from automation.assistant_tools import execute_tool
        result = execute_tool("create_niche", {
            "name": "TersoAssistant Test Niche",
            "description": "Created by assistant tool test",
            "seed_keywords": ["AI agents", "LLM tools"],
            "seed_websites": ["arxiv.org"],
            "target_youtube_channels": ["@TwoMinutePapers"]
        })
        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["name"], "TersoAssistant Test Niche")
        # Verify DB entry
        self.assertTrue(Niche.objects.filter(name="TersoAssistant Test Niche").exists())

    def test_abort_job_tool(self):
        """Validate execute_tool('abort_job') marks job as FAILED with reason."""
        from automation.assistant_tools import execute_tool
        result = execute_tool("abort_job", {
            "job_id": str(self.job.id),
            "reason": "Operator requested emergency halt"
        })
        self.assertEqual(result["status"], "ABORTED")
        self.job.refresh_from_db()
        self.assertEqual(self.job.status, TaskExecutionQueue.ExecutionStatus.FAILED)
        self.assertIn("emergency halt", self.job.error_message)

    def test_get_job_telemetry_tool(self):
        """Validate execute_tool('get_job_telemetry') returns correct job metadata."""
        from automation.assistant_tools import execute_tool
        result = execute_tool("get_job_telemetry", {"job_id": str(self.job.id)})
        self.assertEqual(result["job_id"], str(self.job.id))
        self.assertEqual(result["profile"], "Assistant Test Device")
        self.assertEqual(result["task"], "Assistant Test Task")
        self.assertEqual(result["status"], "RUNNING")
        self.assertEqual(result["current_state_id"], "yt_monitor_playback")
        self.assertEqual(result["logs_count"], 1)

    def test_unrecognized_tool_returns_error(self):
        """Validate execute_tool with unknown tool name returns error dict."""
        from automation.assistant_tools import execute_tool
        result = execute_tool("nonexistent_tool", {})
        self.assertIn("error", result)
        self.assertIn("not recognized", result["error"])

    def test_assistant_session_creation_api(self):
        """Validate POST /api/automation/assistant/new-session/ creates a session."""
        resp = self.client.post(
            "/api/automation/assistant/new-session/",
            {"title": "Fleet Ops Chat"},
            format="json"
        )
        self.assertEqual(resp.status_code, 201)
        self.assertEqual(resp.data["title"], "Fleet Ops Chat")
        self.assertIn("id", resp.data)
        self.assertIn("messages", resp.data)
        self.assertEqual(len(resp.data["messages"]), 0)

    def test_assistant_session_list_and_retrieve(self):
        """Validate listing and retrieving sessions with messages."""
        from automation.models import AssistantSession, AssistantMessage
        session = AssistantSession.objects.create(title="Test Session")
        AssistantMessage.objects.create(
            session=session,
            role=AssistantMessage.RoleChoices.USER,
            content="Hello TersoAssistant"
        )
        AssistantMessage.objects.create(
            session=session,
            role=AssistantMessage.RoleChoices.ASSISTANT,
            content="Fleet is healthy."
        )

        # List
        list_resp = self.client.get("/api/automation/assistant/")
        self.assertEqual(list_resp.status_code, 200)
        self.assertGreaterEqual(len(list_resp.data), 1)

        # Retrieve
        detail_resp = self.client.get(f"/api/automation/assistant/{session.id}/")
        self.assertEqual(detail_resp.status_code, 200)
        self.assertEqual(len(detail_resp.data["messages"]), 2)
        self.assertEqual(detail_resp.data["messages"][0]["role"], "user")
        self.assertEqual(detail_resp.data["messages"][1]["role"], "assistant")

    def test_chat_endpoint_rejects_empty_message(self):
        """Validate POST /chat/ with empty message returns 400."""
        from automation.models import AssistantSession
        session = AssistantSession.objects.create(title="Empty Test")
        resp = self.client.post(
            f"/api/automation/assistant/{session.id}/chat/",
            {"message": "   "},
            format="json"
        )
        self.assertEqual(resp.status_code, 400)
        self.assertIn("error", resp.data)

    @patch("openai.resources.chat.completions.Completions.create")
    def test_openrouter_tool_call_loop(self, mock_create):
        """
        Full integration test: user asks fleet status → LLM calls get_fleet_status →
        backend executes tool → result fed back → LLM returns final summary.
        Verifies 4 messages logged: user → assistant(tool_call) → tool(result) → assistant(final).
        """
        from automation.models import AssistantSession

        # 1st API call: LLM requests get_fleet_status tool
        tool_call_mock = MagicMock()
        tool_call_mock.id = "call_terso_001"
        tool_call_mock.type = "function"
        tool_call_mock.function.name = "get_fleet_status"
        tool_call_mock.function.arguments = "{}"

        first_resp = MagicMock()
        first_resp.choices = [MagicMock(
            message=MagicMock(content=None, tool_calls=[tool_call_mock])
        )]

        # 2nd API call: LLM returns final conversational summary
        second_resp = MagicMock()
        second_resp.choices = [MagicMock(
            message=MagicMock(
                content="Fleet is currently healthy with 1 running job across 1 profile.",
                tool_calls=None
            )
        )]

        mock_create.side_effect = [first_resp, second_resp]

        session = AssistantSession.objects.create(title="Tool Loop Test")

        with patch.dict("os.environ", {"OPENROUTER_API_KEY": "sk-test-key"}):
            resp = self.client.post(
                f"/api/automation/assistant/{session.id}/chat/",
                {"message": "What is the status of the fleet?"},
                format="json"
            )

        self.assertEqual(resp.status_code, 200)
        self.assertIn("Fleet is currently healthy", resp.data["reply"])

        # Verify DB message log: User → Assistant(tool_call) → Tool(result) → Assistant(final)
        messages = session.messages.all().order_by("created_at")
        self.assertEqual(messages.count(), 4)
        self.assertEqual(messages[0].role, "user")
        self.assertEqual(messages[0].content, "What is the status of the fleet?")
        self.assertEqual(messages[1].role, "assistant")
        self.assertIsNotNone(messages[1].tool_calls)
        self.assertEqual(messages[1].tool_calls[0]["function"]["name"], "get_fleet_status")
        self.assertEqual(messages[2].role, "tool")
        self.assertEqual(messages[2].tool_call_id, "call_terso_001")
        # Tool result should contain fleet data
        tool_result = json.loads(messages[2].content)
        self.assertIn("total_profiles", tool_result)
        self.assertEqual(messages[3].role, "assistant")
        self.assertIn("Fleet is currently healthy", messages[3].content)

    def test_tool_execute_task_on_profiles(self):
        """Validate execute_task_on_profiles tool directly compiles and dispatches a task to target profiles."""
        from automation.assistant_tools import execute_tool
        result = execute_tool("execute_task_on_profiles", {
            "profile_ids": [str(self.profile.id)],
            "task_category": "WARMING",
            "task_name": "Selected Warming Task"
        })
        self.assertEqual(result["status"], "DISPATCHED")
        self.assertEqual(result["dispatched_count"], 1)
        self.assertTrue(TaskExecutionQueue.objects.filter(profile=self.profile, task__name="Selected Warming Task").exists())

    @patch("openai.resources.chat.completions.Completions.create")
    def test_chat_with_page_context(self, mock_create):
        """Validate POST /chat/ accepts page_context and incorporates it into the LLM system prompt."""
        from automation.models import AssistantSession
        resp_mock = MagicMock()
        resp_mock.choices = [MagicMock(
            message=MagicMock(
                content="I see you have 1 profile selected on the Profiles screen.",
                tool_calls=None
            )
        )]
        mock_create.return_value = resp_mock

        session = AssistantSession.objects.create(title="Context Test")
        page_ctx = {
            "active_tab": "PROFILES",
            "page_name": "Profiles & Personas",
            "selected_items": [
                {"id": str(self.profile.id), "name": self.profile.name, "details": "Google Pixel 7"}
            ],
            "summary": "1 profile selected"
        }

        with patch.dict("os.environ", {"OPENROUTER_API_KEY": "sk-test-key"}):
            resp = self.client.post(
                f"/api/automation/assistant/{session.id}/chat/",
                {
                    "message": "Warm up the selected profiles.",
                    "page_context": page_ctx
                },
                format="json"
            )

        self.assertEqual(resp.status_code, 200)
        # Verify OpenAI client was called with system message containing page context
        call_args = mock_create.call_args[1]
        messages_sent = call_args["messages"]
        system_msg = next(m["content"] for m in messages_sent if m["role"] == "system")
        self.assertIn("Profiles & Personas", system_msg)
        self.assertIn(str(self.profile.id), system_msg)

    def test_ai_assistant_admin_category_registration(self):
        """Verify AssistantSession, AssistantMessage, and AIPromptConfig are registered under AI Assistant app."""
        from django.contrib import admin
        from ai_assistant.models import AssistantSession, AssistantMessage, AIPromptConfig

        self.assertIn(AssistantSession, admin.site._registry)
        self.assertIn(AssistantMessage, admin.site._registry)
        self.assertIn(AIPromptConfig, admin.site._registry)

        session_admin = admin.site._registry[AssistantSession]
        self.assertEqual(session_admin.model._meta.app_config.verbose_name, "AI Assistant")

    def test_assign_niche_to_profile_by_name(self):
        """Validate assign_niche_to_profile resolves human-readable names and assigns niche."""
        from automation.assistant_tools import execute_tool
        res = execute_tool("assign_niche_to_profile", {
            "profile": self.profile.name,
            "niche": self.niche.name,
            "weight": 100
        })
        self.assertEqual(res["status"], "SUCCESS")
        self.assertEqual(res["profile_name"], self.profile.name)
        self.assertTrue(ProfileNicheAffiliation.objects.filter(profile=self.profile, niche=self.niche).exists())

    def test_youtube_compiler_video_format_long_form(self):
        """Verify that YouTubeCompiler embeds video_format='long_form' into yt_select_video params."""
        task = AutomationTask.objects.create(
            name="YouTube Long Form Test",
            category=PlatformCategory.YOUTUBE,
            config={"video_format": "long_form", "target_keyword": "test long form"}
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        self.assertIn("yt_select_video", dag["states"])
        node = dag["states"]["yt_select_video"]
        self.assertEqual(node["command"], "YT_CLICK_VIDEO_CARD")
        self.assertEqual(node["params"].get("video_format"), "long_form")

    def test_youtube_compiler_video_format_shorts(self):
        """Verify that YouTubeCompiler embeds video_format='shorts' into yt_select_video params."""
        task = AutomationTask.objects.create(
            name="YouTube Shorts Test",
            category=PlatformCategory.YOUTUBE,
            config={"video_format": "shorts", "target_keyword": "funny shorts"}
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        node = dag["states"]["yt_select_video"]
        self.assertEqual(node["params"].get("video_format"), "shorts")

    def test_youtube_compiler_video_format_both(self):
        """Verify that YouTubeCompiler embeds video_format='both' into yt_select_video params."""
        task = AutomationTask.objects.create(
            name="YouTube Mixed Test",
            category=PlatformCategory.YOUTUBE,
            config={"video_format": "both"}
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        node = dag["states"]["yt_select_video"]
        self.assertEqual(node["params"].get("video_format"), "both")

    def test_youtube_compiler_video_format_default(self):
        """Verify that YouTubeCompiler defaults to 'long_form' when unspecified or invalid."""
        task = AutomationTask.objects.create(
            name="YouTube Default Test",
            category=PlatformCategory.YOUTUBE,
            config={}
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        node = dag["states"]["yt_select_video"]
        self.assertEqual(node["params"].get("video_format"), "long_form")


class YouTubeStrategyTests(TestCase):
    def setUp(self):
        self.profile = SavedProfile.objects.create(
            name="Strategy Test Device",
            brand="Samsung",
            model_name="Galaxy S24",
            model_code="SM-S921B",
            android_version=14,
            soc="Snapdragon 8 Gen 3",
            webgl_vendor="Qualcomm",
            webgl_renderer="Adreno 750",
            ram_gb=8,
            cpu_cores=8,
            screen_width=384,
            screen_height=854,
            dpr=2.8125,
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )

    def test_shorts_surfing_compilation(self):
        task = AutomationTask.objects.create(
            name="Shorts Run",
            category=PlatformCategory.YOUTUBE,
            config={"strategy": "SHORTS_SURF", "shorts_count": 5}
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        states = dag["states"]
        self.assertIn("yt_nav_shorts", states)
        self.assertIn("short_loop_1", states)
        self.assertIn("short_swipe_1", states)
        self.assertEqual(states["short_swipe_1"]["command"], "YT_SHORTS_SWIPE")

    def test_rabbit_hole_compilation(self):
        task = AutomationTask.objects.create(
            name="Rabbit Hole Binge",
            category=PlatformCategory.YOUTUBE,
            config={"strategy": "RABBIT_HOLE", "rabbit_hole_depth": 3}
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        states = dag["states"]
        self.assertIn("rabbit_watch_1", states)
        self.assertIn("rabbit_click_next_1", states)
        self.assertEqual(states["rabbit_click_next_1"]["command"], "YT_CLICK_UP_NEXT")

    def test_channel_binge_compilation(self):
        task = AutomationTask.objects.create(
            name="Channel Binge",
            category=PlatformCategory.YOUTUBE,
            config={"strategy": "CHANNEL_BINGE", "target_channel": "@Dave2D"}
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        states = dag["states"]
        self.assertIn("yt_nav_channel", states)
        self.assertIn("yt_select_channel_video", states)
        self.assertEqual(states["yt_nav_channel"]["params"]["url"], "https://m.youtube.com/@Dave2D/videos")

    def test_direct_watch_compilation(self):
        task = AutomationTask.objects.create(
            name="Direct Watch",
            category=PlatformCategory.YOUTUBE,
            config={"strategy": "DIRECT_URL", "target_url": "https://m.youtube.com/watch?v=dQw4w9WgXcQ"}
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        states = dag["states"]
        self.assertIn("yt_nav_direct", states)
        self.assertIn("yt_watch_direct", states)
        self.assertEqual(states["yt_nav_direct"]["params"]["url"], "https://m.youtube.com/watch?v=dQw4w9WgXcQ")

    def test_multi_keyword_and_video_link_compilation(self):
        """Test candidate keywords parsing, video ID extraction, and scroll depth in SEARCH_TARGET."""
        from automation.compiler import extract_youtube_video_id, parse_candidate_keywords

        # Test video ID extraction
        self.assertEqual(extract_youtube_video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        self.assertEqual(extract_youtube_video_id("https://youtu.be/dQw4w9WgXcQ?si=123"), "dQw4w9WgXcQ")
        self.assertEqual(extract_youtube_video_id("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"), "dQw4w9WgXcQ")
        self.assertEqual(extract_youtube_video_id("https://youtube.com/shorts/dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        self.assertEqual(extract_youtube_video_id("dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        self.assertEqual(extract_youtube_video_id("invalid-link"), "")

        # Test candidate keywords parsing
        kw_list = parse_candidate_keywords({"target_keywords": ["keyword 1", "keyword 2"]})
        self.assertEqual(kw_list, ["keyword 1", "keyword 2"])

        kw_comma = parse_candidate_keywords({"target_keywords": "mechanical keyboard, budget thock, custom board"})
        self.assertEqual(kw_comma, ["mechanical keyboard", "budget thock", "custom board"])

        kw_newline = parse_candidate_keywords({"target_keywords": "mechanical keyboard\nbudget thock\ncustom board"})
        self.assertEqual(kw_newline, ["mechanical keyboard", "budget thock", "custom board"])

        # Test DAG compilation with multi-keywords and video link
        task = AutomationTask.objects.create(
            name="Multi Keyword Target Search",
            category=PlatformCategory.YOUTUBE,
            config={
                "strategy": "SEARCH_TARGET",
                "target_keywords": ["build mechanical keyboard", "budget thock board"],
                "target_video_url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "target_channel": "@KeybEnthusiast",
                "max_search_scroll_depth": 15,
                "video_format": "long_form"
            }
        )
        dag = RecipeCompiler.compile_recipe(task, self.profile)
        states = dag["states"]
        self.assertIn("yt_type_query", states)
        # Should only type the FIRST keyword initially
        self.assertEqual(states["yt_type_query"]["params"]["text"], "build mechanical keyboard")

        # yt_select_video node should contain full candidate list, video ID, and max scroll depth
        select_node = states["yt_select_video"]
        self.assertEqual(select_node["params"]["candidate_keywords"], ["build mechanical keyboard", "budget thock board"])
        self.assertEqual(select_node["params"]["target_video_id"], "dQw4w9WgXcQ")
        self.assertEqual(select_node["params"]["max_scroll_depth"], 15)
        self.assertEqual(select_node["params"]["target_channel"], "@KeybEnthusiast")
        self.assertEqual(select_node["params"]["video_format"], "long_form")


class AutomationV2DomainModelTests(TestCase):
    """
    Verification suite for Phase 1: Domain Models, Run Idempotency, and Plan Versioning.
    """
    def setUp(self):
        self.profile = SavedProfile.objects.create(
            name="Pixel 8 Automation Worker",
            brand="Google",
            model_name="Pixel 8 Pro",
            model_code="GC3VE",
            android_version=14,
            screen_width=412,
            screen_height=915,
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        self.niche = Niche.objects.create(
            name="Mechanical Keyboards",
            description="Testing niche targeting"
        )
        self.task = AutomationTask.objects.create(
            name="YouTube Warmup Task",
            category=PlatformCategory.YOUTUBE,
            niche=self.niche,
            config={"min_watch_seconds": 60, "max_watch_seconds": 180}
        )

    def test_automation_creation_and_defaults(self):
        """Verify Automation model field defaults and relationships."""
        automation = Automation.objects.create(
            name="Daily Morning Warmup",
            task=self.task,
            schedule_type=ScheduleType.DAILY,
            schedule_config={"time": "09:30"},
            timezone="Africa/Lagos",
            selection_mode=SelectionMode.NICHE,
            concurrency_limit=3,
            cooldown_minutes=45
        )
        automation.target_niches.add(self.niche)
        automation.target_profiles.add(self.profile)

        self.assertEqual(automation.name, "Daily Morning Warmup")
        self.assertTrue(automation.enabled)
        self.assertEqual(automation.schedule_type, ScheduleType.DAILY)
        self.assertEqual(automation.timezone, "Africa/Lagos")
        self.assertIn(self.niche, automation.target_niches.all())
        self.assertIn(self.profile, automation.target_profiles.all())
        self.assertEqual(str(automation), "Daily Morning Warmup [DAILY]")

    def test_automation_run_idempotency(self):
        """Verify UNIQUE(automation, run_key) prevents duplicate campaign runs."""
        from django.db import IntegrityError

        automation = Automation.objects.create(
            name="Idempotent Test",
            task=self.task,
            schedule_type=ScheduleType.ONE_TIME
        )
        run_key = "test_run_2026_09_13_1000"

        run1 = AutomationRun.objects.create(
            automation=automation,
            run_key=run_key,
            total_target_profiles=1
        )
        self.assertEqual(run1.status, AutomationRunStatus.SCHEDULED)

        # Attempting to create duplicate run with same run_key must raise IntegrityError
        from django.db import transaction
        with transaction.atomic():
            with self.assertRaises(IntegrityError):
                AutomationRun.objects.create(
                    automation=automation,
                    run_key=run_key,
                    total_target_profiles=1
                )

    def test_execution_plan_versioning_and_immutability(self):
        """Verify versioned ExecutionPlan guarantees historical DAG immutability."""
        from django.db import IntegrityError, transaction

        # Plan v1
        plan_v1 = ExecutionPlan.objects.create(
            task=self.task,
            version=1,
            compiled_dag={"entry_state": "step_1", "states": {"step_1": {"cmd": "GOTO"}}},
            config_snapshot=self.task.config
        )
        self.assertEqual(plan_v1.version, 1)

        # Duplicate version for same task must fail
        with transaction.atomic():
            with self.assertRaises(IntegrityError):
                ExecutionPlan.objects.create(
                    task=self.task,
                    version=1,
                    compiled_dag={"entry_state": "different"}
                )

        # Execution linked to Plan v1
        automation = Automation.objects.create(
            name="Plan Test Auto",
            task=self.task
        )
        run = AutomationRun.objects.create(
            automation=automation,
            run_key="run_v1"
        )
        exec_record = Execution.objects.create(
            task=self.task,
            profile=self.profile,
            automation_run=run,
            plan=plan_v1,
            plan_version="1",
            recovery_status=Execution.RecoveryStatus.NONE,
            compiled_dag=plan_v1.compiled_dag
        )

        # Now mutate task configuration and generate Plan v2
        self.task.config = {"min_watch_seconds": 300, "max_watch_seconds": 600}
        self.task.save()

        plan_v2 = ExecutionPlan.objects.create(
            task=self.task,
            version=2,
            compiled_dag={"entry_state": "step_v2", "states": {"step_v2": {"cmd": "SEARCH"}}},
            config_snapshot=self.task.config
        )

        # Historical execution must remain unchanged and pointing to Plan v1
        exec_record.refresh_from_db()
        self.assertEqual(exec_record.plan.version, 1)
        self.assertEqual(exec_record.compiled_dag["entry_state"], "step_1")
        self.assertEqual(plan_v2.version, 2)


class AutomationV2SchedulerTests(TestCase):
    """
    Validates Phase 2: Deterministic Scheduler, Policies, Eligibility Engine,
    Job Dispatcher, and Worker Claim API.
    """

    def setUp(self):
        self.user = User.objects.create_superuser(
            username="scheduler_admin",
            password="adminpassword123",
            email="admin@terso.test"
        )
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        self.niche = Niche.objects.create(
            name="Tech Reviewers",
            seed_keywords=["oled monitor", "keychron keyboard"],
            seed_websites=["https://rtings.com"]
        )

        self.profile1 = SavedProfile.objects.create(
            user=self.user,
            name="Profile Tech 1",
            brand="Samsung",
            model_name="S24 Ultra",
            model_code="SM-S928B",
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        ProfileNicheAffiliation.objects.create(
            profile=self.profile1,
            niche=self.niche,
            weight_percentage=100
        )

        self.profile2 = SavedProfile.objects.create(
            user=self.user,
            name="Profile Tech 2",
            brand="Google",
            model_name="Pixel 8 Pro",
            model_code="GC3VE",
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        ProfileNicheAffiliation.objects.create(
            profile=self.profile2,
            niche=self.niche,
            weight_percentage=100
        )

        self.task = AutomationTask.objects.create(
            name="Daily Niche Warmup",
            category=PlatformCategory.WARMING,
            config={"max_actions": 5}
        )

    def test_schedule_policy_evaluation(self):
        """Tests schedule window determination for ONE_TIME, DAILY, and INTERVAL schedules."""
        from django.utils import timezone
        import datetime
        from automation.scheduler.policies import is_automation_due

        now = timezone.now()

        # 1. ONE_TIME schedule past due
        auto_onetime = Automation.objects.create(
            name="One-Time Task",
            task=self.task,
            schedule_type=ScheduleType.ONE_TIME,
            schedule_config={"run_at": (now - datetime.timedelta(minutes=5)).isoformat()}
        )
        is_due, run_key, _ = is_automation_due(auto_onetime, now_dt=now)
        self.assertTrue(is_due)
        self.assertEqual(run_key, f"{auto_onetime.id}_onetime")

        # After run created, should no longer be due
        AutomationRun.objects.create(automation=auto_onetime, run_key=run_key)
        is_due, _, _ = is_automation_due(auto_onetime, now_dt=now)
        self.assertFalse(is_due)

        # 2. INTERVAL schedule
        auto_interval = Automation.objects.create(
            name="Interval Task",
            task=self.task,
            schedule_type=ScheduleType.INTERVAL,
            schedule_config={"interval_minutes": 30},
            last_run_at=now - datetime.timedelta(minutes=45)
        )
        is_due, run_key, _ = is_automation_due(auto_interval, now_dt=now)
        self.assertTrue(is_due)
        self.assertTrue(run_key.startswith(str(auto_interval.id)))

        # Update last_run_at to recent, should NOT be due
        auto_interval.last_run_at = now - datetime.timedelta(minutes=10)
        auto_interval.save()
        is_due, _, _ = is_automation_due(auto_interval, now_dt=now)
        self.assertFalse(is_due)

    def test_eligibility_engine_filtering(self):
        """Validates all filtering rules: targeting, active lease conflict, cooldown, already queued."""
        from django.utils import timezone
        import datetime
        from automation.scheduler.planner import EligibilityEngine, EligibilityReason
        from executions.models import ExecutionLease, ExecutionLeaseStatus, Execution, ExecutionStatus

        now = timezone.now()

        automation = Automation.objects.create(
            name="Targeted Auto",
            task=self.task,
            selection_mode=SelectionMode.NICHE,
            cooldown_minutes=60
        )
        automation.target_niches.add(self.niche)

        # Profile 1 has active lease on another device
        ExecutionLease.objects.create(
            profile=self.profile1,
            device_id="device_conflict",
            status=ExecutionLeaseStatus.ACTIVE,
            expires_at=now + datetime.timedelta(minutes=5)
        )

        results = EligibilityEngine.calculate_eligible_profiles(automation, now_dt=now)
        eval_map = {r["profile_id"]: r for r in results}

        # Profile 1 must be excluded due to ACTIVE_LEASE_CONFLICT
        self.assertFalse(eval_map[str(self.profile1.id)]["eligible"])
        self.assertEqual(eval_map[str(self.profile1.id)]["reason"], EligibilityReason.ACTIVE_LEASE_CONFLICT)

        # Profile 2 has no conflicts -> eligible
        self.assertTrue(eval_map[str(self.profile2.id)]["eligible"])
        self.assertEqual(eval_map[str(self.profile2.id)]["reason"], EligibilityReason.OK)

        # Now test Cooldown on Profile 2
        Execution.objects.create(
            task=self.task,
            profile=self.profile2,
            status=ExecutionStatus.SUCCESS,
            updated_at=now - datetime.timedelta(minutes=15)
        )
        results2 = EligibilityEngine.calculate_eligible_profiles(automation, now_dt=now)
        eval_map2 = {r["profile_id"]: r for r in results2}
        self.assertFalse(eval_map2[str(self.profile2.id)]["eligible"])
        self.assertEqual(eval_map2[str(self.profile2.id)]["reason"], EligibilityReason.COOLDOWN_ACTIVE)

    def test_scheduler_service_mint_and_idempotency(self):
        """Validates run minting, execution queuing, and run key idempotency."""
        from django.utils import timezone
        import datetime
        from automation.scheduler.service import SchedulerService
        from executions.models import Execution, ExecutionStatus

        now = timezone.now()
        automation = Automation.objects.create(
            name="Batch Run Auto",
            task=self.task,
            schedule_type=ScheduleType.ONE_TIME,
            schedule_config={"run_at": (now - datetime.timedelta(minutes=1)).isoformat()},
            selection_mode=SelectionMode.NICHE
        )
        automation.target_niches.add(self.niche)

        # Evaluate due automations
        due_runs = SchedulerService.evaluate_due_automations(now_dt=now)
        self.assertEqual(len(due_runs), 1)

        run = due_runs[0]
        self.assertEqual(run.status, AutomationRunStatus.SCHEDULED)
        self.assertEqual(run.total_target_profiles, 2)
        self.assertEqual(run.queued_count, 2)

        # Executions were created in PENDING state
        pending_execs = Execution.objects.filter(automation_run=run)
        self.assertEqual(pending_execs.count(), 2)
        self.assertTrue(all(e.status == ExecutionStatus.PENDING for e in pending_execs))

        # Re-running evaluate_due_automations must NOT mint duplicate runs or executions
        due_runs_again = SchedulerService.evaluate_due_automations(now_dt=now)
        self.assertEqual(len(due_runs_again), 0)
        self.assertEqual(AutomationRun.objects.filter(automation=automation).count(), 1)
        self.assertEqual(Execution.objects.filter(automation_run=run).count(), 2)

    def test_job_dispatcher_allocation_and_locking(self):
        """Validates atomic job allocation, lease acquisition, and event emission."""
        from django.utils import timezone
        import datetime
        from automation.scheduler.service import SchedulerService
        from automation.scheduler.dispatcher import JobDispatcher
        from executions.models import Execution, ExecutionStatus, ExecutionLease, ExecutionEvent, ExecutionEventType

        now = timezone.now()
        automation = Automation.objects.create(
            name="Dispatch Test Auto",
            task=self.task,
            schedule_type=ScheduleType.ONE_TIME,
            schedule_config={"run_at": (now - datetime.timedelta(seconds=10)).isoformat()},
            selection_mode=SelectionMode.NICHE,
            concurrency_limit=5
        )
        automation.target_niches.add(self.niche)

        SchedulerService.evaluate_due_automations(now_dt=now)

        # Device A claims next job
        job_payload = JobDispatcher.allocate_next_execution(device_id="android_device_alpha", user=self.user)
        self.assertIsNotNone(job_payload)
        self.assertEqual(job_payload["task_id"], str(self.task.id))
        self.assertIn("lease_id", job_payload)

        # Verify execution record transitioned to DISPATCHED
        exec_record = Execution.objects.get(id=job_payload["execution_id"])
        self.assertEqual(exec_record.status, ExecutionStatus.DISPATCHED)

        # Verify lease was created and active
        lease = ExecutionLease.objects.get(id=job_payload["lease_id"])
        self.assertEqual(lease.device_id, "android_device_alpha")
        self.assertEqual(lease.profile, exec_record.profile)

        # Verify RUN_STARTED event was recorded
        event = ExecutionEvent.objects.filter(execution=exec_record, event_type=ExecutionEventType.RUN_STARTED).first()
        self.assertIsNotNone(event)
        self.assertEqual(event.payload["device_id"], "android_device_alpha")

    def test_claim_next_api_endpoint(self):
        """Validates POST /api/automation/ghostpilot/claim-next/ HTTP endpoint."""
        from django.utils import timezone
        import datetime
        from automation.scheduler.service import SchedulerService
        from rest_framework import status

        now = timezone.now()
        automation = Automation.objects.create(
            name="API Claim Auto",
            task=self.task,
            schedule_type=ScheduleType.ONE_TIME,
            schedule_config={"run_at": (now - datetime.timedelta(seconds=10)).isoformat()},
            selection_mode=SelectionMode.NICHE
        )
        automation.target_niches.add(self.niche)
        SchedulerService.evaluate_due_automations(now_dt=now)

        # Post without device_id -> 400 Bad Request
        res_bad = self.client.post("/api/automation/ghostpilot/claim-next/", {}, format="json")
        self.assertEqual(res_bad.status_code, status.HTTP_400_BAD_REQUEST)

        # Post with device_id -> 200 OK and receives job
        res = self.client.post("/api/automation/ghostpilot/claim-next/", {"device_id": "phone_pixel_9"}, format="json")
        self.assertEqual(res.status_code, status.HTTP_200_OK)
        self.assertTrue(res.data.get("has_work"))
        self.assertIsNotNone(res.data.get("job"))
        self.assertEqual(res.data["job"]["task_id"], str(self.task.id))


class AutomationV2RestApiTests(TestCase):
    """
    Validates Phase 3: Automation and Run CRUD endpoints, manual trigger,
    pause/resume, run cancellation, and execution listings.
    """

    def setUp(self):
        self.admin = User.objects.create_superuser(
            username="rest_admin",
            password="adminpassword123",
            email="rest@terso.test"
        )
        self.client = APIClient()
        self.client.force_authenticate(user=self.admin)

        self.task = AutomationTask.objects.create(
            name="API Test Task",
            category=PlatformCategory.WARMING,
            config={"actions": 3}
        )

        self.profile = SavedProfile.objects.create(
            user=self.admin,
            name="API Profile",
            brand="Google",
            model_name="Pixel 9",
            model_code="G1234",
            user_agent="Mozilla/5.0"
        )

    def test_automation_crud_and_lifecycle_actions(self):
        """Tests CRUD on /api/automation/automations/, pause, resume, and manual trigger."""
        # 1. Create Automation
        create_payload = {
            "name": "Live Niche Automation",
            "task": str(self.task.id),
            "schedule_type": "DAILY",
            "schedule_config": {"time": "12:00"},
            "selection_mode": "EXPLICIT_PROFILES",
            "target_profiles": [str(self.profile.id)],
            "concurrency_limit": 3,
            "cooldown_minutes": 15
        }
        res_create = self.client.post("/api/automation/automations/", create_payload, format="json")
        self.assertEqual(res_create.status_code, status.HTTP_201_CREATED)
        auto_id = res_create.data["id"]

        # 2. List Automations
        res_list = self.client.get("/api/automation/automations/")
        self.assertEqual(res_list.status_code, status.HTTP_200_OK)
        self.assertGreaterEqual(len(res_list.data), 1)

        # 3. Pause
        res_pause = self.client.post(f"/api/automation/automations/{auto_id}/pause/")
        self.assertEqual(res_pause.status_code, status.HTTP_200_OK)
        self.assertFalse(res_pause.data["enabled"])

        # 4. Resume
        res_resume = self.client.post(f"/api/automation/automations/{auto_id}/resume/")
        self.assertEqual(res_resume.status_code, status.HTTP_200_OK)
        self.assertTrue(res_resume.data["enabled"])

        # 5. Trigger run immediately
        res_trigger = self.client.post(f"/api/automation/automations/{auto_id}/trigger/")
        self.assertEqual(res_trigger.status_code, status.HTTP_201_CREATED)
        self.assertIn("run_key", res_trigger.data)
        run_id = res_trigger.data["id"]

        # 6. View Run executions
        res_execs = self.client.get(f"/api/automation/runs/{run_id}/executions/")
        self.assertEqual(res_execs.status_code, status.HTTP_200_OK)
        self.assertEqual(len(res_execs.data), 1)

        # 7. Cancel Run
        res_cancel = self.client.post(f"/api/automation/runs/{run_id}/cancel/")
        self.assertEqual(res_cancel.status_code, status.HTTP_200_OK)
        self.assertEqual(res_cancel.data["status"], "CANCELLED")

        # Verify pending execution marked FAILED upon run cancellation
        res_execs_after = self.client.get(f"/api/automation/runs/{run_id}/executions/")
        self.assertEqual(res_execs_after.data[0]["status"], "FAILED")


class AutomationV2WatchdogTests(TestCase):
    """
    Phase 5 Tests: Watchdog, Lease Reaper, Stalled Execution Reconciliation,
    Device Health Supervision, and Failure Threshold Circuit Breaker.
    """

    def setUp(self):
        from io import StringIO
        from django.core.management import call_command
        from devices.models import Device, DeviceStatus
        from executions.models import (
            ExecutionStatus,
            ExecutionLease,
            ExecutionLeaseStatus,
            ExecutionEvent,
            ExecutionEventType,
            RecoveryStatus,
        )
        self.operator = User.objects.create_user(username="watchdog-op", password="password123")
        self.profile1 = SavedProfile.objects.create(
            user=self.operator,
            name="Watchdog Profile 1",
            brand="Samsung",
            model_name="Galaxy S24",
            model_code="SM-S921B",
            android_version=14,
            soc="Snapdragon 8 Gen 3",
            webgl_vendor="Qualcomm",
            webgl_renderer="Adreno 750",
            ram_gb=8,
            cpu_cores=8,
            screen_width=384,
            screen_height=854,
            dpr=2.8,
            user_agent="Mozilla/5.0"
        )
        self.profile2 = SavedProfile.objects.create(
            user=self.operator,
            name="Watchdog Profile 2",
            brand="Google",
            model_name="Pixel 8",
            model_code="GP8",
            android_version=14,
            soc="Tensor G3",
            webgl_vendor="ARM",
            webgl_renderer="Mali-G715",
            ram_gb=8,
            cpu_cores=8,
            screen_width=412,
            screen_height=915,
            dpr=2.6,
            user_agent="Mozilla/5.0"
        )
        self.task = AutomationTask.objects.create(
            name="Watchdog Test Task",
            category=PlatformCategory.WARMING,
            config={"required_capabilities": []}
        )
        self.automation = Automation.objects.create(
            name="Watchdog Guarded Automation",
            task=self.task,
            schedule_type=ScheduleType.DAILY,
            selection_mode=SelectionMode.EXPLICIT_PROFILES,
            max_retries=2,
            failure_threshold_percent=30,
            concurrency_limit=5
        )
        self.automation.target_profiles.add(self.profile1, self.profile2)

    def test_reap_expired_leases(self):
        """Tests that the watchdog expires stale leases and frees device locks."""
        from devices.models import Device, DeviceStatus
        from executions.models import ExecutionLease, ExecutionLeaseStatus, ExecutionStatus
        from automation.scheduler.watchdog import AutomationWatchdogService

        now = timezone.now()
        device = Device.objects.create(
            device_id="dev-watchdog-01",
            owner=self.operator,
            status=DeviceStatus.BUSY,
            last_seen=now,
            last_heartbeat=now
        )
        exec_item = Execution.objects.create(
            task=self.task,
            profile=self.profile1,
            status=ExecutionStatus.RUNNING
        )
        device.current_execution = exec_item
        device.save()

        # 1. Create an expired lease
        expired_lease = ExecutionLease.objects.create(
            profile=self.profile1,
            registered_device=device,
            device_id=device.device_id,
            execution=exec_item,
            status=ExecutionLeaseStatus.ACTIVE,
            acquired_at=now - datetime.timedelta(seconds=120),
            expires_at=now - datetime.timedelta(seconds=10)
        )

        # 2. Create a fresh, valid lease on another profile
        valid_lease = ExecutionLease.objects.create(
            profile=self.profile2,
            device_id="dev-other",
            status=ExecutionLeaseStatus.ACTIVE,
            acquired_at=now,
            expires_at=now + datetime.timedelta(seconds=60)
        )

        reaped = AutomationWatchdogService.reap_expired_leases(now_dt=now)
        self.assertEqual(len(reaped), 1)
        self.assertEqual(reaped[0].id, expired_lease.id)

        expired_lease.refresh_from_db()
        self.assertEqual(expired_lease.status, ExecutionLeaseStatus.EXPIRED)

        valid_lease.refresh_from_db()
        self.assertEqual(valid_lease.status, ExecutionLeaseStatus.ACTIVE)

        # Device should now be ONLINE and current_execution cleared
        device.refresh_from_db()
        self.assertEqual(device.status, DeviceStatus.ONLINE)
        self.assertIsNone(device.current_execution)

    def test_reconcile_stalled_executions_pending_retry(self):
        """Tests that stalled executions with retries remaining transition to STALLED / PENDING."""
        from executions.models import Execution, ExecutionStatus, ExecutionLease, ExecutionLeaseStatus, RecoveryStatus
        from automation.scheduler.watchdog import AutomationWatchdogService

        now = timezone.now()
        exec_item = Execution.objects.create(
            task=self.task,
            profile=self.profile1,
            status=ExecutionStatus.RUNNING,
            retry_count=0,
            max_retries=2
        )
        lease = ExecutionLease.objects.create(
            profile=self.profile1,
            execution=exec_item,
            device_id="dev-worker-stalled",
            status=ExecutionLeaseStatus.EXPIRED,
            expires_at=now - datetime.timedelta(seconds=30)
        )

        reconciled = AutomationWatchdogService.reconcile_stalled_executions(now_dt=now)
        self.assertEqual(len(reconciled), 1)

        exec_item.refresh_from_db()
        self.assertEqual(exec_item.status, ExecutionStatus.STALLED)
        self.assertEqual(exec_item.recovery_status, RecoveryStatus.PENDING)
        self.assertEqual(exec_item.retry_count, 1)

    def test_reconcile_stalled_executions_retries_exhausted(self):
        """Tests that stalled executions that have reached max_retries transition to FAILED / EXHAUSTED."""
        from executions.models import Execution, ExecutionStatus, ExecutionLease, ExecutionLeaseStatus, RecoveryStatus
        from automation.scheduler.watchdog import AutomationWatchdogService

        now = timezone.now()
        exec_item = Execution.objects.create(
            task=self.task,
            profile=self.profile1,
            status=ExecutionStatus.RUNNING,
            retry_count=2,
            max_retries=2
        )
        lease = ExecutionLease.objects.create(
            profile=self.profile1,
            execution=exec_item,
            device_id="dev-worker-exhausted",
            status=ExecutionLeaseStatus.EXPIRED,
            expires_at=now - datetime.timedelta(seconds=30)
        )

        reconciled = AutomationWatchdogService.reconcile_stalled_executions(now_dt=now)
        self.assertEqual(len(reconciled), 1)

        exec_item.refresh_from_db()
        self.assertEqual(exec_item.status, ExecutionStatus.FAILED)
        self.assertEqual(exec_item.recovery_status, RecoveryStatus.EXHAUSTED)
        self.assertIsNotNone(exec_item.completed_at)

    def test_stalled_execution_claimed_by_device_for_recovery(self):
        """Tests that an eligible device can claim a STALLED (recovery_status=PENDING) execution."""
        from executions.models import Execution, ExecutionStatus, RecoveryStatus
        from automation.scheduler.dispatcher import JobDispatcher

        stalled_exec = Execution.objects.create(
            task=self.task,
            profile=self.profile1,
            status=ExecutionStatus.STALLED,
            recovery_status=RecoveryStatus.PENDING,
            checkpoint_version=3,
            last_confirmed_state="nav_complete",
            entry_state_id="nav_complete"
        )

        allocated = JobDispatcher.allocate_next_execution(
            device_id="dev-healer-01",
            user=self.operator
        )

        self.assertIsNotNone(allocated)
        self.assertEqual(allocated["execution_id"], str(stalled_exec.id))
        self.assertEqual(allocated["recovery_status"], RecoveryStatus.RUNNING)
        self.assertEqual(allocated["checkpoint_version"], 3)
        self.assertEqual(allocated["last_confirmed_state"], "nav_complete")

        stalled_exec.refresh_from_db()
        self.assertEqual(stalled_exec.status, ExecutionStatus.DISPATCHED)
        self.assertEqual(stalled_exec.recovery_status, RecoveryStatus.RUNNING)

    def test_check_device_health_marks_offline(self):
        """Tests that devices without heartbeats past the threshold are marked OFFLINE."""
        from devices.models import Device, DeviceStatus
        from automation.scheduler.watchdog import AutomationWatchdogService

        now = timezone.now()
        dead_device = Device.objects.create(
            device_id="dev-ghost",
            owner=self.operator,
            status=DeviceStatus.ONLINE,
            last_seen=now - datetime.timedelta(seconds=200),
            last_heartbeat=now - datetime.timedelta(seconds=200)
        )
        live_device = Device.objects.create(
            device_id="dev-active",
            owner=self.operator,
            status=DeviceStatus.ONLINE,
            last_seen=now - datetime.timedelta(seconds=15),
            last_heartbeat=now - datetime.timedelta(seconds=15)
        )

        offline = AutomationWatchdogService.check_device_health(offline_threshold_seconds=120, now_dt=now)
        self.assertEqual(len(offline), 1)
        self.assertEqual(offline[0].device_id, "dev-ghost")

        dead_device.refresh_from_db()
        self.assertEqual(dead_device.status, DeviceStatus.OFFLINE)

        live_device.refresh_from_db()
        self.assertEqual(live_device.status, DeviceStatus.ONLINE)

    def test_evaluate_failure_thresholds_circuit_breaker(self):
        """Tests Section 26: Automatic pausing when failure threshold percentage is exceeded."""
        from automation.models import AutomationRun, AutomationRunStatus
        from executions.models import Execution, ExecutionStatus
        from automation.scheduler.watchdog import AutomationWatchdogService

        now = timezone.now()
        run = AutomationRun.objects.create(
            automation=self.automation,
            run_key="test_run_breaker_01",
            status=AutomationRunStatus.RUNNING,
            total_target_profiles=4,
            queued_count=2,
            failure_count=0
        )

        # 2 failures out of 4 (50% failure rate >= 30% threshold)
        Execution.objects.create(
            automation_run=run,
            task=self.task,
            profile=self.profile1,
            status=ExecutionStatus.FAILED
        )
        Execution.objects.create(
            automation_run=run,
            task=self.task,
            profile=self.profile2,
            status=ExecutionStatus.FAILED
        )
        pending1 = Execution.objects.create(
            automation_run=run,
            task=self.task,
            profile=self.profile1,
            status=ExecutionStatus.PENDING
        )
        pending2 = Execution.objects.create(
            automation_run=run,
            task=self.task,
            profile=self.profile2,
            status=ExecutionStatus.PENDING
        )

        evaluated = AutomationWatchdogService.evaluate_failure_thresholds(now_dt=now)
        self.assertEqual(len(evaluated), 1)

        run.refresh_from_db()
        self.assertEqual(run.status, AutomationRunStatus.PARTIAL)
        self.assertTrue(run.summary_metrics.get("failure_threshold_exceeded"))
        self.assertEqual(run.failure_count, 2)
        self.assertEqual(run.queued_count, 0)
        self.assertEqual(run.cancelled_count, 2)

        pending1.refresh_from_db()
        self.assertEqual(pending1.status, ExecutionStatus.CANCELLED)
        pending2.refresh_from_db()
        self.assertEqual(pending2.status, ExecutionStatus.CANCELLED)

    def test_run_automation_watchdog_command_once(self):
        """Tests that the management command runs a single sweep cleanly and exits."""
        from io import StringIO
        from django.core.management import call_command

        out = StringIO()
        call_command("run_automation_watchdog", once=True, stdout=out)
        output = out.getvalue()
        self.assertIn("TersoPilot Automation V2 Watchdog started", output)
        self.assertIn("Watchdog shut down cleanly", output)


class AssistantOperationalToolsTests(TestCase):
    def setUp(self):
        from devices.models import SavedProfile, Device, DeviceStatus
        from executions.models import Execution, ExecutionEvent, ExecutionStatus
        from automation.models import (
            AutomationTask,
            Automation,
            AutomationRun,
            ScheduleType,
            SelectionMode,
            AutomationRunStatus,
        )

        self.profile = SavedProfile.objects.create(
            name="Assistant Test Profile",
            brand="Samsung",
            model_name="Galaxy S24",
            model_code="SM-S921B",
            android_version=14,
            soc="Snapdragon 8 Gen 3",
            webgl_vendor="Qualcomm",
            webgl_renderer="Adreno (TM) 750",
            ram_gb=8,
            cpu_cores=8,
            screen_width=384,
            screen_height=854,
            dpr=2.8125,
            user_agent="Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        self.device = Device.objects.create(
            device_id="dev-ai-worker-01",
            brand="Google",
            model_name="Pixel 8",
            android_version=14,
            battery_percent=88,
            status=DeviceStatus.ONLINE,
            last_heartbeat=timezone.now()
        )
        self.task = AutomationTask.objects.create(
            name="Assistant Target Task",
            category="WARMING",
            config={"target_url": "https://example.com"}
        )
        self.automation = Automation.objects.create(
            name="Warmup Fleet AI Schedule",
            task=self.task,
            enabled=True,
            schedule_type=ScheduleType.DAILY,
            schedule_config={"hour": 9, "minute": 30},
            timezone="UTC",
            selection_mode=SelectionMode.EXPLICIT_PROFILES,
            concurrency_limit=2,
            cooldown_minutes=60,
            max_retries=3,
            failure_threshold_percent=30
        )
        self.automation.target_profiles.add(self.profile)

    def test_tool_list_automations(self):
        from automation.assistant_tools import tool_list_automations
        from automation.models import Automation, ScheduleType, SelectionMode

        # Create disabled automation
        Automation.objects.create(
            name="Disabled Backup Automation",
            task=self.task,
            enabled=False,
            schedule_type=ScheduleType.INTERVAL,
            schedule_config={"minutes": 30},
            selection_mode=SelectionMode.ALL_ELIGIBLE
        )

        all_autos = tool_list_automations(enabled_only=False)
        self.assertGreaterEqual(len(all_autos), 2)

        enabled_autos = tool_list_automations(enabled_only=True)
        names = [a["name"] for a in enabled_autos]
        self.assertIn("Warmup Fleet AI Schedule", names)
        self.assertNotIn("Disabled Backup Automation", names)

    def test_tool_get_automation(self):
        from automation.assistant_tools import tool_get_automation

        # Lookup by exact name
        res = tool_get_automation("Warmup Fleet AI Schedule")
        self.assertEqual(res["name"], "Warmup Fleet AI Schedule")
        self.assertEqual(res["schedule_type"], "DAILY")
        self.assertEqual(res["concurrency_limit"], 2)
        self.assertEqual(res["task_name"], "Assistant Target Task")

        # Lookup by UUID
        res_by_id = tool_get_automation(str(self.automation.id))
        self.assertEqual(res_by_id["name"], "Warmup Fleet AI Schedule")

        # Unknown automation
        err = tool_get_automation("NonExistentAutomation_99")
        self.assertIn("error", err)

    def test_tool_get_automation_run(self):
        from automation.assistant_tools import tool_get_automation_run
        from automation.models import AutomationRun, AutomationRunStatus

        run = AutomationRun.objects.create(
            automation=self.automation,
            run_key="test_assistant_run_key_123",
            status=AutomationRunStatus.RUNNING,
            total_target_profiles=5,
            queued_count=3,
            running_count=1,
            success_count=1,
            failure_count=0
        )

        # Lookup by run_key
        res = tool_get_automation_run("test_assistant_run_key_123")
        self.assertEqual(res["run_key"], "test_assistant_run_key_123")
        self.assertEqual(res["status"], AutomationRunStatus.RUNNING)
        self.assertEqual(res["total_target_profiles"], 5)

        # Lookup by UUID
        res_uuid = tool_get_automation_run(str(run.id))
        self.assertEqual(res_uuid["run_key"], "test_assistant_run_key_123")

        # Unknown run
        err = tool_get_automation_run("non_existent_run_404")
        self.assertIn("error", err)

    def test_tool_run_automation_now_guarded(self):
        from automation.assistant_tools import tool_run_automation_now

        # In read-only mode, write should be blocked
        with override_settings(ASSISTANT_ALLOW_WRITES=False):
            res_blocked = tool_run_automation_now("Warmup Fleet AI Schedule")
            self.assertIn("error", res_blocked)
            self.assertIn("read-only mode", res_blocked["error"])

        # With writes allowed, run should be minted
        with override_settings(ASSISTANT_ALLOW_WRITES=True):
            res = tool_run_automation_now("Warmup Fleet AI Schedule")
            self.assertEqual(res["status"], "RUNNING")
            self.assertIn("run_id", res)
            self.assertEqual(res["automation"], "Warmup Fleet AI Schedule")

    def test_tool_pause_and_resume_automation_guarded(self):
        from automation.assistant_tools import tool_pause_automation, tool_resume_automation

        with override_settings(ASSISTANT_ALLOW_WRITES=False):
            res_p = tool_pause_automation("Warmup Fleet AI Schedule")
            self.assertIn("read-only mode", res_p["error"])
            res_r = tool_resume_automation("Warmup Fleet AI Schedule")
            self.assertIn("read-only mode", res_r["error"])

        with override_settings(ASSISTANT_ALLOW_WRITES=True):
            p_res = tool_pause_automation("Warmup Fleet AI Schedule")
            self.assertEqual(p_res["status"], "PAUSED")
            self.automation.refresh_from_db()
            self.assertFalse(self.automation.enabled)

            r_res = tool_resume_automation("Warmup Fleet AI Schedule")
            self.assertEqual(r_res["status"], "RESUMED")
            self.automation.refresh_from_db()
            self.assertTrue(self.automation.enabled)

    def test_tool_diagnose_fleet_and_get_device_status(self):
        from automation.assistant_tools import tool_diagnose_fleet, tool_get_device_status
        from devices.models import Device, DeviceStatus

        # Create low battery device
        Device.objects.create(
            device_id="dev-low-batt",
            brand="Xiaomi",
            model_name="Redmi 12",
            android_version=13,
            battery_percent=12,
            status=DeviceStatus.ONLINE,
            last_heartbeat=timezone.now()
        )
        # Create stale offline device
        stale_time = timezone.now() - datetime.timedelta(seconds=200)
        Device.objects.create(
            device_id="dev-stale",
            brand="Transsion",
            model_name="Infinix Hot 40",
            android_version=13,
            battery_percent=75,
            status=DeviceStatus.OFFLINE,
            last_heartbeat=stale_time
        )

        diag = tool_diagnose_fleet()
        self.assertGreaterEqual(diag["total_nodes"], 3)
        self.assertIn("dev-low-batt", diag["low_battery_alerts"])
        self.assertIn("dev-stale", diag["stale_heartbeat_alerts"])

        # Single device lookup
        single = tool_get_device_status("dev-ai-worker-01")
        self.assertEqual(single["device_id"], "dev-ai-worker-01")
        self.assertEqual(single["battery_percent"], 88)

        # Omitted device_id delegates to fleet diagnosis
        delegated = tool_get_device_status(None)
        self.assertIn("total_nodes", delegated)

        # Non-existent device
        err = tool_get_device_status("dev-does-not-exist")
        self.assertIn("error", err)

    def test_tool_explain_recovery(self):
        from automation.assistant_tools import tool_explain_recovery
        from executions.models import Execution, ExecutionEvent, ExecutionStatus

        execution = Execution.objects.create(
            task=self.task,
            profile=self.profile,
            status=ExecutionStatus.STALLED,
            recovery_status="PENDING",
            retry_count=1,
            max_retries=3,
            checkpoint_version=2,
            last_confirmed_state="warmup_step_2"
        )
        ExecutionEvent.objects.create(
            execution=execution,
            event_type="STALLED",
            payload={"reason": "Heartbeat expired after 120s"}
        )

        res = tool_explain_recovery(str(execution.id))
        self.assertEqual(res["execution_id"], str(execution.id))
        self.assertEqual(res["status"], "STALLED")
        self.assertEqual(res["checkpoint_version"], 2)
        self.assertIn("warmup_step_2", res["diagnosis_explanation"])
        self.assertEqual(len(res["recent_audit_events"]), 1)

        err = tool_explain_recovery(str(uuid.uuid4()))
        self.assertIn("error", err)

    def test_execute_tool_dispatcher(self):
        from automation.assistant_tools import execute_tool

        res = execute_tool("list_automations", {"enabled_only": True})
        self.assertIsInstance(res, list)

        err = execute_tool("unknown_tool_function_12345", {})
        self.assertIn("error", err)

    def test_openrouter_tools_schema_integrity(self):
        from automation.assistant_tools import TOOL_MAP, OPENROUTER_TOOLS

        declared_names = {t["function"]["name"] for t in OPENROUTER_TOOLS}
        for tool_name in TOOL_MAP:
            self.assertIn(
                tool_name,
                declared_names,
                f"Tool '{tool_name}' in TOOL_MAP must have a corresponding schema in OPENROUTER_TOOLS"
            )


class AutomationStatusManagementCommandTests(TestCase):
    def setUp(self):
        from devices.models import SavedProfile, Device, DeviceStatus
        from automation.models import Automation, AutomationTask, AutomationRun, AutomationRunStatus, ScheduleType, SelectionMode

        self.profile = SavedProfile.objects.create(
            name="Status Profile",
            brand="Samsung",
            model_name="Galaxy S24",
            user_agent="Mozilla/5.0 Android"
        )
        self.device = Device.objects.create(
            device_id="status-dev-01",
            brand="Google",
            model_name="Pixel 8",
            battery_percent=15,  # low battery alert
            status=DeviceStatus.ONLINE,
            last_heartbeat=timezone.now()
        )
        self.task = AutomationTask.objects.create(
            name="Status Task",
            category="WARMING"
        )
        self.automation = Automation.objects.create(
            name="Status Automation",
            task=self.task,
            enabled=True,
            schedule_type=ScheduleType.DAILY,
            selection_mode=SelectionMode.ALL_ELIGIBLE
        )
        self.run = AutomationRun.objects.create(
            automation=self.automation,
            run_key="status_run_01",
            status=AutomationRunStatus.RUNNING,
            total_target_profiles=1,
            queued_count=1
        )

    def test_automation_status_text_output(self):
        from io import StringIO
        from django.core.management import call_command

        out = StringIO()
        call_command("automation_status", stdout=out)
        output = out.getvalue()
        self.assertIn("TersoPilot Automation V2 System Status", output)
        self.assertIn("[1] Automation Rules & Schedules", output)
        self.assertIn("[2] Execution Queue Depth", output)
        self.assertIn("[3] Mobile Worker Fleet & Leases", output)
        self.assertIn("Low Battery Warnings", output)
        self.assertIn("status-dev-01", output)

    def test_automation_status_json_output(self):
        import json
        from io import StringIO
        from django.core.management import call_command

        out = StringIO()
        call_command("automation_status", json=True, stdout=out)
        data = json.loads(out.getvalue())
        self.assertIn("timestamp", data)
        self.assertIn("automations", data)
        self.assertIn("queue", data)
        self.assertIn("fleet", data)
        self.assertEqual(data["automations"]["total"], 1)
        self.assertEqual(data["fleet"]["total_devices"], 1)
        self.assertEqual(len(data["fleet"]["low_battery_alerts"]), 1)


class AutomationReconcileManagementCommandTests(TestCase):
    def setUp(self):
        from devices.models import SavedProfile, Device, DeviceStatus
        from automation.models import Automation, AutomationTask, AutomationRun, AutomationRunStatus, ScheduleType, SelectionMode
        from executions.models import Execution, ExecutionStatus, ExecutionLease, ExecutionPlan

        self.profile = SavedProfile.objects.create(
            name="Reconcile Profile",
            brand="Samsung",
            model_name="Galaxy S24",
            user_agent="Mozilla/5.0 Android"
        )
        self.task = AutomationTask.objects.create(
            name="Reconcile Task",
            category="WARMING"
        )
        self.plan = ExecutionPlan.objects.create(
            task=self.task,
            version=1,
            compiled_dag={"entry_state": "start", "states": {}}
        )
        self.automation = Automation.objects.create(
            name="Reconcile Automation",
            task=self.task,
            enabled=True,
            schedule_type=ScheduleType.DAILY,
            selection_mode=SelectionMode.ALL_ELIGIBLE
        )
        # 1. Terminated run with orphan PENDING execution
        self.completed_run = AutomationRun.objects.create(
            automation=self.automation,
            run_key="reconcile_completed_run",
            status=AutomationRunStatus.COMPLETED,
            total_target_profiles=1
        )
        self.orphan_exec = Execution.objects.create(
            automation_run=self.completed_run,
            task=self.task,
            profile=self.profile,
            status=ExecutionStatus.PENDING
        )
        # 2. Expired active lease
        expired_time = timezone.now() - datetime.timedelta(seconds=60)
        self.expired_lease = ExecutionLease.objects.create(
            profile=self.profile,
            device_id="reconcile-dev-01",
            status="ACTIVE",
            acquired_at=expired_time - datetime.timedelta(seconds=60),
            expires_at=expired_time
        )
        # 3. Execution missing plan
        self.missing_plan_exec = Execution.objects.create(
            task=self.task,
            profile=self.profile,
            status=ExecutionStatus.PENDING,
            plan=None
        )
        # 4. Stale device assignment
        self.stale_device = Device.objects.create(
            device_id="reconcile-stale-dev",
            brand="Google",
            model_name="Pixel 8",
            status=DeviceStatus.BUSY,
            current_execution=Execution.objects.create(
                task=self.task,
                profile=self.profile,
                status=ExecutionStatus.FAILED
            )
        )

    def test_automation_reconcile_dry_run(self):
        from io import StringIO
        from django.core.management import call_command
        from executions.models import Execution, ExecutionStatus, ExecutionLease
        from devices.models import Device, DeviceStatus

        out = StringIO()
        call_command("automation_reconcile", stdout=out)
        output = out.getvalue()
        self.assertIn("AUDIT_ONLY MODE", output)
        self.assertIn("Total Inconsistencies Detected:", output)
        self.assertIn("Total Safe Repairs Performed: 0", output)

        # In dry run, records must remain unmodified
        self.orphan_exec.refresh_from_db()
        self.assertEqual(self.orphan_exec.status, ExecutionStatus.PENDING)

        self.expired_lease.refresh_from_db()
        self.assertEqual(self.expired_lease.status, "ACTIVE")

        self.stale_device.refresh_from_db()
        self.assertEqual(self.stale_device.status, DeviceStatus.BUSY)

    def test_automation_reconcile_repair_mode(self):
        from io import StringIO
        from django.core.management import call_command
        from executions.models import Execution, ExecutionStatus, ExecutionLease
        from devices.models import Device, DeviceStatus

        out = StringIO()
        call_command("automation_reconcile", fix=True, stdout=out)
        output = out.getvalue()
        self.assertIn("REPAIR MODE", output)
        self.assertIn("Total Safe Repairs Performed:", output)

        # In repair mode, orphan execution should be cancelled
        self.orphan_exec.refresh_from_db()
        self.assertEqual(self.orphan_exec.status, ExecutionStatus.CANCELLED)
        self.assertIn("Cancelled by automation_reconcile", self.orphan_exec.error_message)

        # Expired active lease should be marked EXPIRED
        self.expired_lease.refresh_from_db()
        self.assertEqual(self.expired_lease.status, "EXPIRED")

        # Execution missing plan should be linked to canonical plan
        self.missing_plan_exec.refresh_from_db()
        self.assertIsNotNone(self.missing_plan_exec.plan)
        self.assertEqual(self.missing_plan_exec.plan, self.plan)

        # Stale device should be set to ONLINE and current_execution cleared
        self.stale_device.refresh_from_db()
        self.assertEqual(self.stale_device.status, DeviceStatus.ONLINE)
        self.assertIsNone(self.stale_device.current_execution)


class AutomationFleetApiTests(TestCase):
    def setUp(self):
        from devices.models import Device, DeviceStatus
        self.client = APIClient()
        self.admin = User.objects.create_superuser(username="fleet-admin", password="password123")
        self.client.force_authenticate(self.admin)
        self.device = Device.objects.create(
            device_id="node-fleet-test-01",
            brand="Samsung",
            model_name="Galaxy S23",
            android_version=14,
            battery_percent=88,
            status=DeviceStatus.ONLINE
        )

    def test_get_automation_fleet(self):
        """Verify GET /api/automation/fleet/ returns device list per Section 31."""
        resp = self.client.get("/api/automation/fleet/")
        self.assertEqual(resp.status_code, 200)
        self.assertTrue(any(d["device_id"] == "node-fleet-test-01" for d in resp.data))

    def test_automation_fleet_disable_and_enable(self):
        """Verify POST /api/automation/fleet/{id}/disable/ and enable/ endpoints."""
        resp_dis = self.client.post(f"/api/automation/fleet/{self.device.id}/disable/")
        self.assertEqual(resp_dis.status_code, 200)
        self.device.refresh_from_db()
        self.assertEqual(self.device.status, "DISABLED")

        resp_en = self.client.post(f"/api/automation/fleet/{self.device.id}/enable/")
        self.assertEqual(resp_en.status_code, 200)
        self.device.refresh_from_db()
        self.assertEqual(self.device.status, "ONLINE")

