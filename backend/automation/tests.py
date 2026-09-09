import uuid
import json
from unittest.mock import patch, MagicMock
from django.test import TestCase, override_settings
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
    AIPromptConfig
)
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



